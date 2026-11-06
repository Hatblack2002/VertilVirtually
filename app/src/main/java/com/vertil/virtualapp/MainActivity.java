package com.vertil.virtualapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
 * Panel único de Fase B (directiva del propietario).
 * Regla de esta revisión: la app NUNCA debe quedar como "pantalla de texto".
 *  - Barra de estado del motor (verde/roja): si el motor falla, al tocarla se
 *    copia/comparte la causa completa SIN adb (LogFileProvider + ACTION_SEND).
 *  - "Importar APK…": instala en el espacio virtual un APK suelto elegido con el
 *    selector de archivos (además de clonar apps ya instaladas).
 *  - Listado con reintento sin GET_ACTIVITIES y diagnóstico cuando el listado
 *    del teléfono llega vacío.
 * Mantenimiento pulsado sobre un clon = fijarlo como paquete objetivo del logcat.
 */
public class MainActivity extends Activity {

    private static final int ROW_HEADER = 0;
    private static final int ROW_PHONE = 1;
    private static final int ROW_CLONED = 2;
    private static final int ROW_PROC = 3;
    private static final int REQ_IMPORT = 41;

    private ListView list;
    private TextView status;
    private TextView banner;
    private LinearLayout root;
    private RowsAdapter adapter;
    private String logTargetPkg;
    private File lastLog;
    private File engineCrashFile;
    private long appStartTs;

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
        appStartTs = System.currentTimeMillis();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        banner = new TextView(this);
        banner.setTextSize(15);
        banner.setTypeface(null, android.graphics.Typeface.BOLD);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(10), dp(14), dp(10), dp(14));
        root.addView(banner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paintBanner();

        LinearLayout buttons1 = new LinearLayout(this);
        buttons1.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = mkButton("Refrescar");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshAll();
            }
        });
        Button importApk = mkButton("Importar APK…");
        importApk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        });
        buttons1.addView(refresh, lpRow(0));
        buttons1.addView(importApk, lpRow(4));
        root.addView(buttons1);

        LinearLayout buttons2 = new LinearLayout(this);
        buttons2.setOrientation(LinearLayout.HORIZONTAL);
        Button logcat = mkButton("Capturar logcat");
        logcat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureLogcat();
            }
        });
        Button share = mkButton("Compartir log");
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLastLog();
            }
        });
        buttons2.addView(logcat, lpRow(0));
        buttons2.addView(share, lpRow(4));
        root.addView(buttons2);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setText("1) Pulsa Clonar en una app del teléfono (o Importar APK…)\n"
                + "2) Pulsa Lanzar en APPS CLONADAS\n"
                + "3) Si algo falla: Capturar logcat → Compartir log (WhatsApp/Drive, sin adb)\n"
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

    // ---------- barra de estado del motor ----------

    private void paintBanner() {
        Throwable err = VApp.getStartupError();
        if (err != null) {
            banner.setBackgroundColor(0xFFB00020);
            banner.setTextColor(Color.WHITE);
            banner.setText("MOTOR VIRTUAL: FALLÓ — toca aquí para ver y compartir la causa");
            banner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showStartupErrorDialog();
                }
            });
        } else if (engineCrashFile != null) {
            banner.setBackgroundColor(0xFFE65100);
            banner.setTextColor(Color.WHITE);
            banner.setText("MOTOR: el proceso servidor (:x) falló — toca aquí para ver y compartir la causa");
            banner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEngineCrashDialog();
                }
            });
        } else {
            banner.setBackgroundColor(0xFF1B5E20);
            banner.setTextColor(Color.WHITE);
            banner.setText("MOTOR VIRTUAL: ACTIVO — elige una app del teléfono y pulsa Clonar");
            banner.setOnClickListener(null);
        }
    }

    /** Último engine-crash-*.txt escrito DESPUÉS de abrir la app (los viejos no cuentan). */
    private File findLatestEngineCrash() {
        File dir = LogFileProvider.logDir(this);
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File latest = null;
        long latestTs = 0;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith("engine-crash-") || !n.endsWith(".txt")) {
                continue;
            }
            try {
                long ts = Long.parseLong(n.substring("engine-crash-".length(), n.length() - 4));
                if (ts >= appStartTs && ts >= latestTs) {
                    latest = f;
                    latestTs = ts;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return latest;
    }

    private void showEngineCrashDialog() {
        if (engineCrashFile == null || !engineCrashFile.exists()) {
            return;
        }
        String content;
        try {
            byte[] data = readAll(engineCrashFile);
            content = new String(data, "UTF-8");
        } catch (Throwable t) {
            content = "No se pudo leer " + engineCrashFile.getName() + ": " + t;
        }
        final String body = content;
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setText(engineCrashFile.getName() + "\n\n" + body);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Crash del proceso servidor del motor")
                .setView(dialogRoot)
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Compartir", null)
                .setNegativeButton("Copiar", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareFile(engineCrashFile, "Crash del proceso :x de VERTIL");
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard("VERTIL-crash", body);
                Toast.makeText(MainActivity.this, "Causa copiada al portapapeles", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return buf;
        } finally {
            fis.close();
        }
    }

    private void showStartupErrorDialog() {
        final Throwable err = VApp.getStartupError();
        if (err == null) {
            return;
        }
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setText("Motor: VirtualCore.startup()\n\n" + android.util.Log.getStackTraceString(err));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Causa exacta del fallo (regla del propietario)")
                .setView(dialogRoot)
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Compartir", null)
                .setNegativeButton("Copiar", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File f = saveStartupError(err);
                if (f != null) {
                    shareFile(f, "Causa del fallo de VERTIL");
                } else {
                    Toast.makeText(MainActivity.this, "No se pudo guardar el archivo", Toast.LENGTH_SHORT).show();
                }
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard("VERTIL-fallo", android.util.Log.getStackTraceString(err));
                Toast.makeText(MainActivity.this, "Causa copiada al portapapeles", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private File saveStartupError(Throwable err) {
        try {
            File f = new File(LogFileProvider.logDir(this), "startup-error-" + System.currentTimeMillis() + ".txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(android.util.Log.getStackTraceString(err).getBytes("UTF-8"));
            fos.close();
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- acciones ----------

    private void refreshAll() {
        setStatus("Cargando…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> rows = buildRows();
                final File crash = findLatestEngineCrash();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        engineCrashFile = crash;
                        paintBanner();
                        adapter = new RowsAdapter(rows);
                        list.setAdapter(adapter);
                        setStatus("Listo · objetivo logcat: "
                                + (logTargetPkg != null ? logTargetPkg : "(mantén pulsado un clon)"));
                    }
                });
            }
        }).start();
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            setStatus("El selector de archivos falló: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) {
            return;
        }
        final List<Uri> uris = new ArrayList<Uri>();
        if (data.getData() != null) {
            uris.add(data.getData());
        }
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !uris.contains(u)) {
                    uris.add(u);
                }
            }
        }
        if (uris.isEmpty()) {
            setStatus("No se seleccionó ningún archivo");
            return;
        }
        final int n = uris.size();
        setStatus("Importando " + n + " archivo(s) al espacio virtual…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = AppInstaller.installFromUris(MainActivity.this, uris);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error == null) {
                            Toast.makeText(MainActivity.this,
                                    "Importado: " + n + " archivo(s)", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "FALLO: " + error, Toast.LENGTH_LONG).show();
                            setStatus("FALLO importando: " + error);
                        }
                        refreshAll();
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
                            setStatus("FALLO clonando " + info.packageName + ": " + error
                                    + "\nSi el error menciona ContentProviderClient, toca la barra superior");
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
                    lastLog = f;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Log guardado (" + count[0] + " líneas): " + f.getName()
                                    + "\nPulsa 'Compartir log' para enviarlo (sin adb)");
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

    private void shareLastLog() {
        if (lastLog == null || !lastLog.exists()) {
            setStatus("Aún no hay log: pulsa primero 'Capturar logcat'");
            return;
        }
        shareFile(lastLog, "Log filtrado de VERTIL");
    }

    // ---------- datos ----------

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<Row>();
        List<PackageInfo> installed = listPhoneApps();
        rows.add(new Row(ROW_HEADER, "APPS EN EL TELÉFONO (fuente) — "
                + (installed == null ? "?" : installed.size()) + " apps", null, 0));
        if (installed != null) {
            for (PackageInfo pi : installed) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null) {
                    continue;
                }
                String label;
                try {
                    CharSequence cs = ai.loadLabel(getPackageManager());
                    label = cs != null ? cs.toString() : pi.packageName;
                } catch (Throwable t) {
                    label = pi.packageName;
                }
                int splits = ai.splitSourceDirs != null ? ai.splitSourceDirs.length : 0;
                rows.add(new Row(ROW_PHONE, label + (splits > 0 ? "  (+" + splits + " splits)" : ""),
                        pi.packageName, 0));
            }
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

    /**
     * Listado del teléfono con reintento: GET_ACTIVITIES puede fallar o llegar vacío
     * según el filtrado de visibilidad del sistema; el segundo intento pide solo lo básico.
     */
    private List<PackageInfo> listPhoneApps() {
        PackageManager pm = getPackageManager();
        try {
            List<PackageInfo> l = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
            if (l != null && !l.isEmpty()) {
                return sortApps(l);
            }
        } catch (Throwable ignored) {
        }
        try {
            List<PackageInfo> l = pm.getInstalledPackages(0);
            if (l != null && !l.isEmpty()) {
                return sortApps(l);
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<PackageInfo>();
    }

    private List<PackageInfo> sortApps(List<PackageInfo> l) {
        Collections.sort(l, new Comparator<PackageInfo>() {
            @Override
            public int compare(PackageInfo a, PackageInfo b) {
                return String.valueOf(a.packageName).compareTo(String.valueOf(b.packageName));
            }
        });
        return l;
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
                action.setVisibility(View.VISIBLE);
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
                action.setVisibility(View.VISIBLE);
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

    // ---------- compartición (sin adb) ----------

    private void shareFile(File f, String subject) {
        try {
            Uri uri = LogFileProvider.uriFor(this, f);
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_SUBJECT, subject);
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Enviar " + f.getName()));
        } catch (Throwable t) {
            setStatus("FALLO compartiendo: " + t + " — el archivo está en " + f.getAbsolutePath());
        }
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Throwable ignored) {
        }
    }

    // ---------- utils ----------

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
                status.setText(TextUtils.isEmpty(s) ? "" : s);
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

    private LinearLayout.LayoutParams lpRow(int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(marginDp), 0, 0, 0);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
