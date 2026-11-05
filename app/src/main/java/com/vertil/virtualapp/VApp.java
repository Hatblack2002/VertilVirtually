package com.vertil.virtualapp;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.stub.VASettings;

/**
 * VERTIL host Application — patrón RECOVERED de XApp (VirtualXposed) sin Xposed.
 * VirtualCore.startup() debe correr en el main thread (attachBaseContext lo es).
 */
public class VApp extends Application {

    private static volatile Throwable startupError;

    public static Throwable getStartupError() {
        return startupError;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
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
}
