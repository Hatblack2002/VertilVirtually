package com.vertil.virtualapp;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

/**
 * Captura de logcat para diagnóstico (directiva Fase B, requisito 4).
 * Filtra por el paquete objetivo (el juego clonado) y por los marcadores del motor VERTIL.
 * Escribe el resultado en getExternalFilesDir("logs") — recuperable con adb pull.
 */
public final class LogcatCapture {

    private LogcatCapture() {
    }

    private static final String[] ENGINE_MARKERS = {
            "vertil", "VERTIL", "VirtualCore", "VClient", "VActivityManager", "VPackageManager",
            "lody.virtual", "VASettings", "BinderProvider", "StubActivity", "StubCP",
            "IOUniformer", "ServiceManagerNative", "InvocationStubManager", "VAppManagerService",
            // Cabeceras de crash: sin estos marcadores se perdía la línea con el tipo y
            // mensaje de la excepción (java.lang.SecurityException: ...) y los frames
            // android.*/java.* del stack, dejando el log sin la causa real.
            "AndroidRuntime", "System.err", "FATAL EXCEPTION", "uncaught",
            // (VERTIL 0.1.12) Bloque COMPLETO del tombstone nativo. El clon se renombra
            // con setArgV0 (p.ej. "m.catsbit.oxide") y sus líneas de crash NO contienen
            // "vertil", así que el filtro perdía justo lo que hay que leer para
            // diagnosticar un SIGSEGV: señal, código, fault addr, ABI, causa,
            // "Abort message", registros y la línea "pid/name >>> paquete <<<".
            "F/DEBUG", "Fatal signal", "fault addr", "Abort message", "Cause:",
            "SIGSEGV", "SIGABRT", "SIGBUS", "SIGILL", "SIGFPE", "SIGTRAP", "SIGSYS"
    };

    public static File capture(Context ctx, String targetPkg, int[] outCount) throws IOException {
        String baseDir = ctx.getExternalFilesDir(null) != null
                ? ctx.getExternalFilesDir(null).getAbsolutePath()
                : ctx.getFilesDir().getAbsolutePath();
        File logsDir = new File(baseDir, "logs");
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            throw new IOException("No se pudo crear " + logsDir);
        }
        String ts = String.valueOf(System.currentTimeMillis());
        File out = new File(logsDir, "logcat-" + (targetPkg != null ? targetPkg : "vertil") + "-" + ts + ".txt");

        Pattern pkgPattern = null;
        if (targetPkg != null && targetPkg.length() > 0) {
            pkgPattern = Pattern.compile(Pattern.quote(targetPkg), Pattern.CASE_INSENSITIVE);
        }
        Pattern enginePattern = buildEnginePattern();

        Process process = null;
        BufferedReader reader = null;
        FileOutputStream fos = null;
        int lines = 0;
        try {
            process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()), 1 << 16);
            fos = new FileOutputStream(out);
            String line;
            while ((line = reader.readLine()) != null) {
                if (matches(line, pkgPattern, enginePattern)) {
                    fos.write((line + "\n").getBytes("UTF-8"));
                    lines++;
                }
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) { }
            }
            if (process != null) {
                process.destroy();
            }
        }
        if (outCount != null && outCount.length > 0) {
            outCount[0] = lines;
        }
        return out;
    }

    private static boolean matches(String line, Pattern pkgPattern, Pattern enginePattern) {
        if (pkgPattern != null && pkgPattern.matcher(line).find()) {
            return true;
        }
        return enginePattern.matcher(line).find();
    }

    private static Pattern buildEnginePattern() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ENGINE_MARKERS.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(Pattern.quote(ENGINE_MARKERS[i]));
        }
        // procesos del pool del motor: <host>:p0..:p49
        sb.append("|:p\\d+");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
}
