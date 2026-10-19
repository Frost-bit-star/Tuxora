package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;

/**
 * Foreground service that keeps the first-run bootstrap alive while the app is
 * backgrounded: shows a progress notification and holds a partial wake lock so
 * the download/extract loop is not suspended by the OS.
 */
public final class SetupService extends Service {

    private static final String CHANNEL_ID = "tuxora_setup";
    private static final int NOTIF_ID = 41;

    private static volatile SetupService instance;

    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, SetupService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            // Background-start restrictions - setup still proceeds without the service.
            android.util.Log.w("SetupService", "Could not start setup service", e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, SetupService.class));
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    /** Pushes progress to the notification; safe to call from any thread. */
    public static void notifyProgress(int percent, String message) {
        SetupService service = instance;
        if (service != null) service.push(percent, message);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "First-run setup", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notifications.createNotificationChannel(channel);
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tuxora:setup");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        push(0, "Setting up Tuxora\u2026");
        startAsForeground(buildNotification(0, "Setting up Tuxora\u2026"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The work runs on TuxoraSetup's own threads; this service only keeps
        // the process important while they run.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("SetupService", "Could not promote to foreground", e);
        }
    }

    private void push(int percent, String message) {
        if (notifications != null) {
            notifications.notify(NOTIF_ID, buildNotification(percent, message));
        }
    }

    private Notification buildNotification(int percent, String message) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
            .setContentTitle("Setting up Tuxora")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, Math.max(0, Math.min(100, percent)), percent <= 0)
            .build();
    }
}
