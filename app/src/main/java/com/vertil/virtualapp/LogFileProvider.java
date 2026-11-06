package com.vertil.virtualapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Proveedor mínimo (sin androidx) para compartir archivos de diagnóstico de VERTIL
 * (logs de logcat y crashes del proceso :x) con apps de mensajería/nube vía ACTION_SEND,
 * sin necesidad de adb. Solo lectura, solo del directorio logs/, solo por nombre simple.
 */
public class LogFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File dir = logDir(getContext());
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("nombre inválido");
        }
        File f = new File(dir, name);
        if (!f.exists()) {
            throw new FileNotFoundException(f.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public static File logDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File d = new File(base, "logs");
        if (!d.exists() && !d.mkdirs()) {
            // si falla mkdirs devolvemos el dir igualmente: openFile dará FileNotFoundException clara
        }
        return d;
    }

    public static Uri uriFor(Context context, File f) {
        return Uri.parse("content://" + context.getPackageName() + ".logfiles/" + f.getName());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    // update se implementa en ambas aridades y SIN @Override: el android.jar que ve
    // javac en el runner declara abstract update(Uri,ContentValues,String,String[])
    // (4 parámetros, run 42134909698) mientras el framework clásico declara
    // update(...,String[] selectionArgs, String sortOrder). Cubriendo ambas, la clase
    // compila contra cualquiera de las dos formas del supertipo.
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs, String sortOrder) {
        return 0;
    }
}
