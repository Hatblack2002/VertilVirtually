package com.vertil.virtualapp;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.stub.VASettings;

import java.io.File;
import java.io.FileOutputStream;

/**
 * VERTIL host Application — patrón RECOVERED de XApp (VirtualXposed) sin Xposed.
 * VirtualCore.startup() debe correr en el main thread (attachBaseContext lo es).
 *
 * Captura de crashes (regla del propietario: causa exacta antes de corregir):
 * este Application corre en TODOS los procesos del host, incluido :x (el proceso
 * servidor del motor, donde vive BinderProvider). Si :x muere sin publicar el
 * provider, la app principal ve "ContentProviderClient.call(...) on a null object
 * reference" — el diagnóstico real queda aquí escrito en logs/engine-crash-*.txt,
 * compartible desde la UI con LogFileProvider, sin adb.
 */
public class VApp extends Application {

    private static volatile Throwable startupError;

    public static Throwable getStartupError() {
        return startupError;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        installCrashCapture();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NativeEngine.disableJit(Build.VERSION.SDK_INT);
        }
        VASettings.ENABLE_IO_REDIRECT = true;
        VASettings.ENABLE_INNER_SHORTCUT = false;
        try {
            VirtualCore.get().startup(base);
        } catch (Throwable e) {
            // El error se muestra en la UI principal (regla del propietario: causa exacta, no ocultar).
            e.printStackTrace();
            startupError = e;
        }
    }

    /**
     * Instala el handler ANTES de startup() para cubrir: startup en :x, onCreate de
     * BinderProvider y cualquier excepción no capturada del proceso servidor.
     */
    private void installCrashCapture() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    File dir = LogFileProvider.logDir(VApp.this);
                    if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                        File f = new File(dir, "engine-crash-" + System.currentTimeMillis() + ".txt");
                        StringBuilder sb = new StringBuilder();
                        sb.append("proceso: ").append(currentProcessName()).append("\n");
                        sb.append("hilo: ").append(t.getName()).append("\n");
                        sb.append("paquete host: ").append(getPackageName()).append("\n\n");
                        sb.append(android.util.Log.getStackTraceString(e));
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(sb.toString().getBytes("UTF-8"));
                        fos.close();
                    }
                } catch (Throwable ignored) {
                    // no podemos hacer nada si falla el propio volcado
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    private static String currentProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Application.getProcessName();
            }
        } catch (Throwable ignored) {
        }
        try {
            int pid = Process.myPid();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/" + pid + "/cmdline"));
            String n = r.readLine();
            r.close();
            return n != null ? n.replace("\0", "").trim() : "desconocido";
        } catch (Throwable t) {
            return "desconocido";
        }
    }
}
