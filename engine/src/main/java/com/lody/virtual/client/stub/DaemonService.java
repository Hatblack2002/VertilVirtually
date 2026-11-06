package com.lody.virtual.client.stub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;

import java.io.File;


/**
 * @author Lody
 *
 */
public class DaemonService extends Service {

    private static final int NOTIFY_ID = 1001;

    private static final String CHANNEL_ID = "vertil_daemon";

        static boolean showNotification = true;

    /**
     * Android 8+ (API 26) rechaza startForeground con una notificación sin canal ni
     * icono pequeño: "Bad notification for startForeground" -> RemoteServiceException
     * mata el proceso :x (crash del S20, Android 14, tras superar el permiso
     * FOREGROUND_SERVICE). El código original usaba `new Notification()` vacío.
     * Se construye una notificación válida: canal IMPORTANCE_LOW (sin sonido) y
     * pequeño icono del framework, resoluble desde cualquier proceso (la app no
     * tiene mipmap propio; usa @android:drawable/sym_def_app_icon). Con targetSdk 28
     * no se exige foregroundServiceType (solo aplica a apps target 34+).
     */
    private static Notification buildDaemonNotification(Service service) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = service.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "VERTIL", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(service, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(service);
        }
        return builder
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("VERTIL")
                .setOngoing(true)
                .build();
    }

        public static void startup(Context context) {
                File flagFile = context.getFileStreamPath(Constants.NO_NOTIFICATION_FLAG);
                if (Build.VERSION.SDK_INT >= 25 && flagFile.exists()) {
                        showNotification = false;
                }

                context.startService(new Intent(context, DaemonService.class));
                if (VirtualCore.get().isServerProcess()) {
                        // PrivilegeAppOptimizer.notifyBootFinish();
                        DaemonJobService.scheduleJob(context);
                }
        }

        @Override
        public void onDestroy() {
                super.onDestroy();
                startup(this);
        }

        @Override
        public IBinder onBind(Intent intent) {
                return null;
        }

        @Override
        public void onCreate() {
                super.onCreate();
                if (!showNotification) {
                        return;
                }
        startService(new Intent(this, InnerService.class));
        startForeground(NOTIFY_ID, buildDaemonNotification(this));
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
                return START_STICKY;
        }

        public static final class InnerService extends Service {

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForeground(NOTIFY_ID, buildDaemonNotification(this));
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

                @Override
                public IBinder onBind(Intent intent) {
                        return null;
                }
        }


}
