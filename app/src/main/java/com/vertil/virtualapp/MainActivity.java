package com.vertil.virtualapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.remote.InstalledAppInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Panel único de Fase B (directiva del propietario):
 *  1. Apps instaladas en el teléfono + botón "Clonar" (sourceDir + splits → motor).
 *  3. Apps clonadas + botón "Lanzar" + estado de procesos :p0..:p49.
 *  4. Captura de logcat filtrada por paquete del juego + marcadores VERTIL.
 * Mantenimiento pulsado sobre un clon = fijarlo como paquete objetivo del logcat.
 */
public class MainActivity extends Activity {

    private static final int ROW_HEADER = 0;
    private static final int ROW_PHONE = 1;
    private static final int ROW_CLONED = 2;
    private static final int ROW_PROC = 3;

    private ListView list;
    private TextView status;
    private LinearLayout root;
    private RowsAdapter adapter;
    private String logTargetPkg;

    private static class Row {
        final int type;
        final String title;
        final String subtitle;
        final int pid;

        Row(int type, String title, String subtitle, int pid) {
            this.type = type;
            this.title = title;
            this.subtitle = subtitle;
            this.pid = pid;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        Throwable startupError = VApp.getStartupError();
        if (startupError != null) {
            TextView err = new TextView(this);
            err.setTextColor(Color.RED);
            err.setTextSize(13);
            err.setText("FALLO VirtualCore.startup(): " + startupError);
            root.addView(err);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = mkButton("Refrescar");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshAll();
            }
        });
        Button logcat = mkButton("Capturar logcat");
        logcat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureLogcat();
            }
        });
        buttons.addView(refresh, lp(0));
        buttons.addView(logcat, lp(0));
        root.addView(buttons);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setText("Clonar = copiar APK+splits al motor · Lanzar = ejecutar clon · "
                + "Mantén pulsado un clon para fijar el paquete del logcat");
        root.addView(hint);

        list = new ListView(this);
        adapter = new RowsAdapter(new ArrayList<Row>());
        list.setAdapter(adapter);
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Row r = adapter.getItem(position);
                if (r != null && r.type == ROW_CLONED) {
                    logTargetPkg = r.subtitle;
                    setStatus("Paquete objetivo del logcat: " + logTargetPkg);
                    return true;
                }
                return false;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Color.DKGRAY);
        root.addView(status);

        setContentView(root);
        refreshAll();
    }

    // ---------- acciones ----------

    private void refreshAll() {
        setStatus("Cargando…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> rows = buildRows();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adapter = new RowsAdapter(rows);
                        list.setAdapter(adapter);
                        setStatus("Listo · objetivo logcat: "
                                + (logTargetPkg != null ? logTargetPkg : "(mantén pulsado un clon)"));
                    }
                });
            }
        }).start();
    }

    private void cloneApp(final ApplicationInfo info) {
        setStatus("Clonando " + info.packageName + " (base + "
                + (info.splitSourceDirs != null ? info.splitSourceDirs.length : 0) + " splits)…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = AppInstaller.cloneToDevice(MainActivity.this, info);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error == null) {
                            Toast.makeText(MainActivity.this, "Clonado en el espacio virtual",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            // Regla del propietario: causa exacta, sin disimular.
                            Toast.makeText(MainActivity.this, "FALLO: " + error, Toast.LENGTH_LONG).show();
                        }
                        refreshAll();
                    }
                });
            }
        }).start();
    }

    private void launchApp(final String pkg) {
        try {
            Intent intent = VirtualCore.get().getLaunchIntent(pkg, 0);
            if (intent == null) {
                setStatus("getLaunchIntent devolvió null para " + pkg
                        + " (¿sin activity MAIN/LAUNCHER en el clon?)");
                return;
            }
            startActivity(intent);
            setStatus("Lanzado " + pkg + " — revisa PROCESOS en Refrescar");
        } catch (Throwable t) {
            setStatus("FALLO al lanzar " + pkg + ": " + t);
        }
    }

    private void captureLogcat() {
        final String target = logTargetPkg;
        setStatus("Capturando logcat" + (target != null ? " (filtro: " + target + " + VERTIL)" : " (solo VERTIL)") + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final int[] count = new int[1];
                    final File f = LogcatCapture.capture(MainActivity.this, target, count);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Log guardado (" + count[0] + " líneas): " + f.getAbsolutePath()
                                    + "\nRecupéralo con: adb pull " + f.getAbsolutePath());
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("FALLO capturando logcat: " + t);
                        }
                    });
                }
            }
        }).start();
    }

    // ---------- datos ----------

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<Row>();
        rows.add(new Row(ROW_HEADER, "APPS EN EL TELÉFONO (fuente)", null, 0));
        try {
            PackageManager pm = getPackageManager();
            List<PackageInfo> installed = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
            Collections.sort(installed, new Comparator<PackageInfo>() {
                @Override
                public int compare(PackageInfo a, PackageInfo b) {
                    return String.valueOf(a.packageName).compareTo(String.valueOf(b.packageName));
                }
            });
            for (PackageInfo pi : installed) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null) {
                    continue;
                }
                String label;
                try {
                    CharSequence cs = ai.loadLabel(pm);
                    label = cs != null ? cs.toString() : pi.packageName;
                } catch (Throwable t) {
                    label = pi.packageName;
                }
                int splits = ai.splitSourceDirs != null ? ai.splitSourceDirs.length : 0;
                rows.add(new Row(ROW_PHONE, label + (splits > 0 ? "  (+" + splits + " splits)" : ""),
                        pi.packageName, 0));
            }
        } catch (Throwable t) {
            rows.add(new Row(ROW_HEADER, "FALLO listando apps: " + t, null, 0));
        }

        rows.add(new Row(ROW_HEADER, "APPS CLONADAS (motor)", null, 0));
        try {
            List<InstalledAppInfo> clones = VirtualCore.get().getInstalledApps(0);
            for (InstalledAppInfo info : clones) {
                rows.add(new Row(ROW_CLONED, info.packageName + (info.splitCodePaths != null
                        ? "  (+" + info.splitCodePaths.length + " splits)" : ""), info.packageName, 0));
            }
        } catch (Throwable t) {
            rows.add(new Row(ROW_HEADER, "FALLO leyendo clones (¿startup falló?): " + t, null, 0));
        }

        rows.add(new Row(ROW_HEADER, "PROCESOS VIRTUALES :p0..:p49", null, 0));
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            int found = 0;
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.processName != null && p.processName.startsWith(getPackageName() + ":p")) {
                        rows.add(new Row(ROW_PROC, "pid " + p.pid + " · " + p.processName, null, p.pid));
                        found++;
                    }
                }
            }
            if (found == 0) {
                rows.add(new Row(ROW_PROC, "(ningún proceso :p activo)", null, 0));
            }
        } catch (Throwable t) {
            rows.add(new Row(ROW_HEADER, "FALLO leyendo procesos: " + t, null, 0));
        }
        return rows;
    }

    // ---------- UI helpers ----------

    private class RowsAdapter extends BaseAdapter {
        private final List<Row> rows;

        RowsAdapter(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type == ROW_HEADER ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Row row = rows.get(position);
            if (row.type == ROW_HEADER) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                tv.setText(row.title);
                tv.setTextSize(13);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setPadding(0, dp(10), 0, dp(4));
                return tv;
            }
            LinearLayout cell = null;
            TextView text = null;
            Button action = null;
            if (convertView instanceof LinearLayout) {
                cell = (LinearLayout) convertView;
                text = (TextView) cell.getChildAt(0);
                action = (Button) cell.getChildAt(1);
            }
            if (cell == null) {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                text = new TextView(MainActivity.this);
                text.setTextSize(14);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                cell.addView(text, tp);
                action = mkButton("…");
                cell.addView(action, lp(1));
            }
            text.setText(row.title + "\n" + (row.subtitle != null ? row.subtitle : ""));
            if (row.type == ROW_PHONE) {
                action.setText("Clonar");
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ApplicationInfo ai = findPhoneApp(row.subtitle);
                        if (ai != null) {
                            cloneApp(ai);
                        } else {
                            setStatus("No se encontró ApplicationInfo de " + row.subtitle);
                        }
                    }
                });
            } else if (row.type == ROW_CLONED) {
                action.setText("Lanzar");
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchApp(row.subtitle);
                    }
                });
            } else {
                action.setText("Ver");
                action.setVisibility(row.pid > 0 ? View.VISIBLE : View.INVISIBLE);
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setStatus("Proceso activo pid=" + row.pid);
                    }
                });
            }
            return cell;
        }
    }

    private ApplicationInfo findPhoneApp(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(s);
            }
        });
    }

    private Button mkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        return b;
    }

    private LinearLayout.LayoutParams lp(int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(marginDp), 0, 0, 0);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
