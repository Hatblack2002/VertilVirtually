package com.vertil.virtualapp;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.remote.InstallResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Clonación al espacio virtual (directiva Fase B, requisito 1).
 *
 * El motor ya soporta splits: VAppManagerService.installPackage recibe un DIRECTORIO,
 * el PackageParser de la plataforma (vía PackageParserCompat, parse de clúster) localiza
 * base.apk + splits, y el servicio copia cada split con sus librerías nativas
 * (engine/src/main/java/com/lody/virtual/server/pm/VAppManagerService.java L196-234).
 *
 * Flujo: copiar sourceDir + splitSourceDirs a un directorio de staging propio del host
 * (siempre escribible, sin permisos de almacenamiento) e instalar ese directorio.
 */
public final class AppInstaller {

    private AppInstaller() {
    }

    /** @return null si la clonación fue exitosa; si no, el mensaje de fallo exacto. */
    public static String cloneToDevice(Context ctx, ApplicationInfo info) {
        File staging = stagingDir(ctx, info.packageName);
        try {
            deleteRecursively(staging);
            if (!staging.mkdirs() && !staging.isDirectory()) {
                return "No se pudo crear el directorio de staging: " + staging;
            }
            if (info.sourceDir == null) {
                return "La app no informa sourceDir (¿APK normalizado?)";
            }
            copyFile(new File(info.sourceDir), new File(staging, "base.apk"));
            if (info.splitSourceDirs != null) {
                for (String split : info.splitSourceDirs) {
                    File src = new File(split);
                    copyFile(src, new File(staging, src.getName()));
                }
            }
            InstallResult res = VirtualCore.get()
                    .installPackage(staging.getAbsolutePath(), InstallStrategy.TERMINATE_IF_EXIST);
            if (res == null) {
                return "installPackage devolvió null";
            }
            if (!res.isSuccess) {
                // Regla del propietario: causa exacta antes de corregir.
                return "installPackage falló: " + res.msg;
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            deleteRecursively(staging);
        }
    }

    public static File stagingDir(Context ctx, String pkg) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getFilesDir();
        }
        return new File(new File(base, "clone_staging"), pkg);
    }

    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        // Ignoramos el resultado: el fallo de borrado no es fatal.
        f.delete();
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new IOException("copiando " + src.getAbsolutePath() + " -> " + dst.getAbsolutePath()
                    + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
