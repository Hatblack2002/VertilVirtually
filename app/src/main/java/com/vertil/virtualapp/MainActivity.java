package com.vertil.virtualapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.InstalledAppInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Panel único de Fase B (directiva del propietario).
 * Regla de esta revisión: la app NUNCA debe quedar como "pantalla de texto".
 * ESPACIO VIRTUAL PRIMERO (queja del propietario 11-06: los clones quedaban
 * sepultados tras 200+ apps del teléfono y solo se veía el nombre del paquete):
 *  - Primera sección = launcher del espacio virtual: icono + nombre + versión
 *    de cada clon, toque en la fila o en "Lanzar" para iniciarla.
 *  - Pulsación larga sobre un clon = menú: iniciar / añadir a la pantalla de
 *    inicio (icono del clon en el home de Android) / fijar logcat / eliminar.
 *  - Estado vacío con guía explícita (clonar o importar).
 *  - Barra de estado del motor (verde/roja): si el motor falla, al tocarla se
 *    copia/comparte la causa completa SIN adb (LogFileProvider + ACTION_SEND).
 *  - "Importar APK…": instala en el espacio virtual un APK suelto elegido con el
 *    selector de archivos (además de clonar apps ya instaladas).
 *  - Listado del teléfono con reintento sin GET_ACTIVITIES; los ya clonados se
 *    marcan "· ya en el espacio".
 */
public class MainActivity extends Activity {

    private static final int ROW_HEADER = 0;
    private static final int ROW_PHONE = 1;
    private static final int ROW_CLONED = 2;
    private static final int ROW_PROC = 3;
    private static final int ROW_EMPTY = 4;
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
    private int lastCloneCount;

    private static class Row {
        final int type;
        final String title;
        final String subtitle;
        final int pid;
        final String pkg;
        final Drawable icon;

        Row(int type, String title, String subtitle, int pid) {
            this(type, title, subtitle, pid, null, null);
        }

        Row(int type, String title, String subtitle, int pid, String pkg, Drawable icon) {
            this.type = type;
            this.title = title;
            this.subtitle = subtitle;
            this.pid = pid;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    /** Datos visuales de un clon para el launcher (cargados en hilo de fondo). */
    private static class CloneEntry {
        String pkg;
        String label;
        String version;
        int splits;
        Drawable icon;
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
        hint.setText("Espacio virtual (primera lista): toca una app para INICIARLA\n"
                + "· mantén pulsada para opciones (iniciar / logcat / eliminar)\n"
                + "Añade apps con 'Clonar' (teléfono) o 'Importar APK…'\n"
                + "Si algo falla: Capturar logcat → Compartir log (sin adb)");
        root.addView(hint);

        list = new ListView(this);
        adapter = new RowsAdapter(new ArrayList<Row>());
        list.setAdapter(adapter);
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Row r = adapter.getItem(position);
                if (r != null && r.type == ROW_CLONED) {
                    showCloneMenu(r);
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
            banner.setText(lastCloneCount > 0
                    ? "MOTOR VIRTUAL: ACTIVO — " + lastCloneCount
                        + (lastCloneCount == 1 ? " app en el espacio virtual" : " apps en el espacio virtual")
                    : "MOTOR VIRTUAL: ACTIVO — añade tu primera app (Clonar o Importar APK…)");
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
                int n = 0;
                for (Row r : rows) {
                    if (r.type == ROW_CLONED) {
                        n++;
                    }
                }
                final int cloneCount = n;
                final File crash = findLatestEngineCrash();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        engineCrashFile = crash;
                        lastCloneCount = cloneCount;
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

    private void launchApp(final String pkg, final String label) {
        final String name = label != null ? label : pkg;
        try {
            Intent intent = VirtualCore.get().getLaunchIntent(pkg, 0);
            if (intent == null) {
                setStatus("getLaunchIntent devolvió null para " + pkg
                        + " (¿sin activity MAIN/LAUNCHER en el clon?)");
                return;
            }
            // REGLA DEL LANZAMIENTO (queja del propietario 11-06: el clon abría con los
            // datos de la app original): el intent virtual NUNCA va directo a
            // Activity.startActivity() — sin hooks de binder en el proceso principal,
            // Android lo resolvería contra los apps REALES y abriría el original con
            // sus datos. El camino correcto del motor es el binder virtual: servidor :x
            // → ActivityStack.startActivityInNewTaskLocked → stub en :p con el directorio
            // de datos VIRTUAL (primera vez = vacío, como Parallel Space).
            int res = VActivityManager.get().startActivity(intent, 0);
            if (res == 0) {
                setStatus("Iniciando " + name + " (" + pkg + ") — proceso virtual :p…");
            } else {
                setStatus("FALLO al iniciar " + name + ": código " + res
                        + " (¿motor activo? ¿activity MAIN/LAUNCHER en el clon?)");
            }
        } catch (Throwable t) {
            setStatus("FALLO al lanzar " + name + ": " + t);
        }
    }

    // ---------- espacio virtual: menú y eliminación ----------

    private void showCloneMenu(final Row r) {
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(new CharSequence[]{
                        "Iniciar " + r.title,
                        "Añadir a la pantalla de inicio…",
                        "Fijar logcat en " + r.pkg,
                        "Eliminar del espacio virtual…"
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            launchApp(r.pkg, r.title);
                        } else if (which == 1) {
                            createHomeShortcut(r);
                        } else if (which == 2) {
                            logTargetPkg = r.pkg;
                            setStatus("Paquete objetivo del logcat: " + logTargetPkg);
                        } else {
                            confirmUninstall(r);
                        }
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    /**
     * Fija un icono del clon en la pantalla de inicio de Android (petición del
     * propietario: ver el espacio virtual como apps independientes desde el
     * Android normal). El icono usa la identidad del clon (nombre + icono real);
     * al tocarlo, ShortcutTrampolineActivity abre SU ventana directamente.
     * Android 8+: ShortcutManager.requestPinShortcut (diálogo del sistema).
     * Android 7: broadcast legacy INSTALL_SHORTCUT.
     */
    private void createHomeShortcut(final Row r) {
        try {
            Bitmap bmp = null;
            if (r.icon != null) {
                try {
                    bmp = com.lody.virtual.helper.utils.DrawableUtils.drawableToBitMap(r.icon);
                } catch (Throwable ignored) {
                }
            }
            Intent launch = new Intent(this, ShortcutTrampolineActivity.class);
            launch.setAction(Intent.ACTION_VIEW);
            launch.putExtra(ShortcutTrampolineActivity.EXTRA_PACKAGE, r.pkg);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String name = r.title != null ? r.title : r.pkg;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ShortcutManager sm = getSystemService(ShortcutManager.class);
                if (sm == null || !sm.isRequestPinShortcutSupported()) {
                    setStatus("El launcher actual no permite fijar accesos directos "
                            + "(requestPinShortcut no soportado). El clon sigue disponible en el espacio virtual.");
                    return;
                }
                ShortcutInfo.Builder b = new ShortcutInfo.Builder(this, "vertil_clone_" + r.pkg)
                        .setShortLabel(name)
                        .setLongLabel(name)
                        .setIntent(launch);
                if (bmp != null) {
                    b.setIcon(Icon.createWithBitmap(bmp));
                }
                sm.requestPinShortcut(b.build(), null);
                setStatus("Acceso directo de '" + name
                        + "' propuesto: confirma el diálogo del sistema para fijarlo en tu pantalla de inicio");
            } else {
                Intent legacy = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
                legacy.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
                if (bmp != null) {
                    legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON, bmp);
                }
                legacy.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
                sendBroadcast(legacy);
                setStatus("Acceso directo de '" + name + "' creado en la pantalla de inicio");
            }
        } catch (Throwable t) {
            setStatus("FALLO creando acceso directo: " + t);
        }
    }

    private void confirmUninstall(final Row r) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar " + r.title)
                .setMessage("Se borrarán los datos del clon de " + r.pkg
                        + " dentro del espacio virtual. La app original del teléfono no se toca.")
                .setPositiveButton("Eliminar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setStatus("Eliminando " + r.pkg + " del espacio virtual…");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                boolean ok = false;
                                Throwable err = null;
                                try {
                                    ok = VirtualCore.get().uninstallPackage(r.pkg);
                                } catch (Throwable t) {
                                    err = t;
                                }
                                final boolean fOk = ok;
                                final Throwable fErr = err;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (fOk) {
                                            Toast.makeText(MainActivity.this,
                                                    r.title + " eliminada del espacio virtual",
                                                    Toast.LENGTH_SHORT).show();
                                        } else {
                                            setStatus("FALLO eliminando " + r.pkg
                                                    + (fErr != null ? ": " + fErr : " (uninstallPackage=false)"));
                                        }
                                        refreshAll();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
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

        // ---- ESPACIO VIRTUAL (launcher, primera sección — queja del propietario) ----
        List<CloneEntry> clones = new ArrayList<CloneEntry>();
        String clonesError = null;
        try {
            List<InstalledAppInfo> infos = VirtualCore.get().getInstalledApps(0);
            PackageManager realPm = VirtualCore.get().getUnHookPackageManager();
            PackageManager hookedPm = getPackageManager();
            for (InstalledAppInfo info : infos) {
                CloneEntry e = new CloneEntry();
                e.pkg = info.packageName;
                e.label = info.packageName;
                e.splits = info.splitCodePaths != null ? info.splitCodePaths.length : 0;
                try {
                    ApplicationInfo ai = info.getApplicationInfo(0);
                    if (ai != null) {
                        // Icono/etiqueta del APK del clon (vive en los datos privados del host);
                        // primero con el PM real (sin hooks), luego con el hookeado.
                        String l = safeLabel(ai, realPm, null);
                        e.label = l != null ? l : safeLabel(ai, hookedPm, e.label);
                        try {
                            e.icon = ai.loadIcon(realPm);
                        } catch (Throwable ignored) {
                            try {
                                e.icon = ai.loadIcon(hookedPm);
                            } catch (Throwable ignored2) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    PackageInfo pi = info.getPackageInfo(0);
                    if (pi != null && !TextUtils.isEmpty(pi.versionName)) {
                        e.version = pi.versionName;
                    }
                } catch (Throwable ignored) {
                }
                clones.add(e);
            }
            Collections.sort(clones, new Comparator<CloneEntry>() {
                @Override
                public int compare(CloneEntry a, CloneEntry b) {
                    return String.valueOf(a.label).compareToIgnoreCase(String.valueOf(b.label));
                }
            });
        } catch (Throwable t) {
            clonesError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        rows.add(new Row(ROW_HEADER, "ESPACIO VIRTUAL · "
                + (clonesError != null ? "?" : String.valueOf(clones.size()))
                + (clones.size() == 1 ? " app" : " apps"), null, 0));
        if (clonesError != null) {
            rows.add(new Row(ROW_EMPTY, "No se pudo leer el espacio virtual",
                    clonesError + "\nSi el motor falló, toca la barra superior para ver y compartir la causa.", 0));
        } else if (clones.isEmpty()) {
            rows.add(new Row(ROW_EMPTY, "Tu espacio virtual está vacío",
                    "Añade tu primera app: botón 'Clonar' en la lista de apps del teléfono (abajo)"
                            + " o 'Importar APK…'.\n"
                            + "Las apps del espacio virtual aparecerán aquí con su icono y nombre:"
                            + " toca una para iniciarla.", 0));
        } else {
            for (CloneEntry e : clones) {
                String sub = e.pkg + (e.version != null ? " · v" + e.version : "")
                        + (e.splits > 0 ? " · +" + e.splits + " splits" : "");
                rows.add(new Row(ROW_CLONED, e.label, sub, 0, e.pkg, e.icon));
            }
        }

        // ---- AÑADIR AL ESPACIO (fuente: teléfono) ----
        Set<String> clonedPkgs = new HashSet<String>();
        for (CloneEntry e : clones) {
            clonedPkgs.add(e.pkg);
        }
        List<PackageInfo> installed = listPhoneApps();
        rows.add(new Row(ROW_HEADER, "AÑADIR AL ESPACIO · apps del teléfono ("
                + (installed == null ? "?" : String.valueOf(installed.size())) + ")", null, 0));
        if (installed != null) {
            for (PackageInfo pi : installed) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || getPackageName().equals(pi.packageName)) {
                    continue; // clonar VERTIL dentro de VERTIL no tiene sentido
                }
                String label = safeLabel(ai, getPackageManager(), pi.packageName);
                int splits = ai.splitSourceDirs != null ? ai.splitSourceDirs.length : 0;
                boolean already = clonedPkgs.contains(pi.packageName);
                rows.add(new Row(ROW_PHONE,
                        label + (already ? "  · ya en el espacio" : "")
                                + (splits > 0 ? "  (" + "+" + splits + " splits)" : ""),
                        pi.packageName, 0));
            }
        }

        // ---- DIAGNÓSTICO ----
        rows.add(new Row(ROW_HEADER, "DIAGNÓSTICO · procesos virtuales :p0..:p49", null, 0));
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

    /** Etiqueta segura de un ApplicationInfo; nunca lanza. */
    private String safeLabel(ApplicationInfo ai, PackageManager pm, String fallback) {
        try {
            CharSequence cs = ai.loadLabel(pm);
            if (!TextUtils.isEmpty(cs)) {
                return cs.toString();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
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
            int t = rows.get(position).type;
            return (t == ROW_HEADER || t == ROW_EMPTY) ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Row row = rows.get(position);
            if (row.type == ROW_HEADER || row.type == ROW_EMPTY) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                if (row.type == ROW_HEADER) {
                    tv.setText(row.title);
                    tv.setTextSize(13);
                    tv.setTypeface(null, android.graphics.Typeface.BOLD);
                    tv.setPadding(0, dp(10), 0, dp(4));
                    // El espacio virtual encabeza la pantalla en verde; el resto en gris azulado.
                    tv.setTextColor(row.title != null && row.title.startsWith("ESPACIO VIRTUAL")
                            ? 0xFF1B5E20 : 0xFF37474F);
                } else {
                    tv.setText(row.title + "\n\n" + (row.subtitle != null ? row.subtitle : ""));
                    tv.setTextSize(14);
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL);
                    tv.setPadding(dp(4), dp(8), dp(4), dp(8));
                    tv.setTextColor(0xFF546E7A);
                }
                return tv;
            }
            // Filas de app/proceso: [icono][textos][botón] — reciclable entre tipos.
            LinearLayout cell;
            ImageView icon;
            TextView text;
            TextView sub;
            Button action;
            if (convertView instanceof LinearLayout) {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                LinearLayout texts = (LinearLayout) cell.getChildAt(1);
                text = (TextView) texts.getChildAt(0);
                sub = (TextView) texts.getChildAt(1);
                action = (Button) cell.getChildAt(2);
            } else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(0, dp(4), 0, dp(4));
                icon = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(46), dp(46));
                ip.setMargins(0, 0, dp(10), 0);
                cell.addView(icon, ip);
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                text = new TextView(MainActivity.this);
                text.setTextSize(15);
                text.setTypeface(null, android.graphics.Typeface.BOLD);
                sub = new TextView(MainActivity.this);
                sub.setTextSize(11);
                sub.setTextColor(0xFF607D8B);
                texts.addView(text, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                texts.addView(sub, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                action = mkButton("…");
                cell.addView(action, lp(1));
            }
            boolean isClone = row.type == ROW_CLONED;
            if (isClone) {
                if (row.icon != null) {
                    icon.setImageDrawable(row.icon);
                } else {
                    icon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }
            text.setText(row.title);
            if (TextUtils.isEmpty(row.subtitle)) {
                sub.setVisibility(View.GONE);
            } else {
                sub.setText(row.subtitle);
                sub.setVisibility(View.VISIBLE);
            }
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
                        launchApp(row.pkg, row.title);
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
            // La fila completa del espacio virtual también inicia la app (launcher).
            if (isClone) {
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchApp(row.pkg, row.title);
                    }
                });
            } else {
                cell.setOnClickListener(null);
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
