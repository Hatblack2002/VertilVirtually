package com.vertil.controlapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/**
 * App de control: verifica dentro del espacio virtual que el motor sirve
 * Application/Activity/almacenamiento/Intent básicos. Nada de juegos, nada de splits.
 */
public class ControlActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("VERTIL CONTROL — OK");
        title.setTextSize(20);
        root.addView(title);

        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setText("host= " + getPackageName()
                + "\ndispositivo= " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAPI= " + Build.VERSION.SDK_INT);
        root.addView(info);

        Button write = new Button(this);
        write.setText("Escribir fichero de prueba");
        write.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    File base = getExternalFilesDir(null);
                    if (base == null) {
                        base = getFilesDir();
                    }
                    File f = new File(base, "control-" + System.currentTimeMillis() + ".txt");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write("control-ok".getBytes("UTF-8"));
                    fos.close();
                    Toast.makeText(ControlActivity.this, "Escrito: " + f.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(ControlActivity.this, "FALLO: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(write, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }
}
