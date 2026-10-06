package com.reed.fcmguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import com.reed.fcmguard.core.ProtectionEngine;

public class GuardService extends Service {
    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation.
    private static final String CHANNEL_ID = "fcm_guard_persistent_v2";
    private static final int NOTIFICATION_ID = 426;
    private static final long FALLBACK_INTERVAL_MS = 30L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;
    private boolean foreground;
    private static volatile boolean running;
    private volatile boolean active;
    private final java.util.concurrent.atomic.AtomicBoolean checking =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean pending =
            new java.util.concurrent.atomic.AtomicBoolean();
    private BroadcastReceiver packageReceiver;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;

    public static boolean isRunning() {
        return running;
    }

    private final Runnable fallbackCheck =
            new Runnable() {
                @Override
                public void run() {
                    repair(false);
                    handler.postDelayed(this, FALLBACK_INTERVAL_MS);
                }
            };

    private final Runnable repairDebounced =
            new Runnable() {
                @Override
                public void run() {
                    repair(true);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        active = true;
        applyExecutionMode();
        registerObserver();
        SettingsGuard.rememberIfUseful(this);
        packageReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        handler.removeCallbacks(repairDebounced);
                        handler.postDelayed(repairDebounced, 400L);
                    }
                };
        IntentFilter changes = new IntentFilter();
        changes.addAction(Intent.ACTION_PACKAGE_ADDED);
        changes.addAction(Intent.ACTION_PACKAGE_REMOVED);
        changes.addAction(Intent.ACTION_PACKAGE_CHANGED);
        changes.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(packageReceiver, changes, Context.RECEIVER_EXPORTED);
        else registerReceiver(packageReceiver, changes);

        if (Build.VERSION.SDK_INT >= 24)
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                networkCallback =
                        new android.net.ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(android.net.Network network) {
                                schedule();
                            }

                            @Override
                            public void onCapabilitiesChanged(
                                    android.net.Network network,
                                    android.net.NetworkCapabilities caps) {
                                if (caps.hasCapability(
                                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                                    schedule();
                            }

                            private void schedule() {
                                handler.post(
                                        () -> {
                                            if (active) {
                                                handler.removeCallbacks(repairDebounced);
                                                handler.postDelayed(repairDebounced, 400L);
                                            }
                                        });
                            }
                        };
                if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
            } catch (RuntimeException unavailable) {
                networkCallback = null;
            }
        handler.post(fallbackCheck);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SettingsGuard.setProtectionEnabled(this, true);
        registerObserver();
        applyExecutionMode();
        handler.removeCallbacks(fallbackCheck);
        handler.post(fallbackCheck);
        return START_STICKY;
    }

    private void repair(boolean notifyFailure) {
        if (!active || !SettingsGuard.isProtectionEnabled(this)) return;
        if (!checking.compareAndSet(false, true)) {
            pending.set(true);
            return;
        }
        ProtectionEngine engine = ProtectionEngine.get(this);
        engine.worker.execute(
                () -> {
                    if (!active || !SettingsGuard.isProtectionEnabled(this)) {
                        checking.set(false);
                        return;
                    }
                    String message;
                    try {
                        ProtectionEngine.Snapshot snapshot = engine.check(true, false);
                        message =
                                snapshot.lastResult == null
                                        ? getString(R.string.notification_active)
                                        : snapshot.lastResult.message;
                    } catch (RuntimeException failure) {
                        message = "Protection check unavailable";
                    }
                    final String text = message;
                    handler.post(
                            () -> {
                                if (active && foreground) refreshNotification(text);
                                checking.set(false);
                                if (active && pending.getAndSet(false)) repair(false);
                            });
                });
    }

    private void registerObserver() {
        ContentResolver resolver = getContentResolver();
        try {
            if (observer != null) resolver.unregisterContentObserver(observer);
        } catch (Throwable ignored) {
        }

        observer =
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        handler.removeCallbacks(repairDebounced);
                        handler.postDelayed(repairDebounced, 400L);
                    }
                };
        resolver.registerContentObserver(
                Settings.System.getUriFor(SettingsGuard.getConfiguredKey(this)), false, observer);
    }

    private void applyExecutionMode() {
        if (SettingsGuard.usePersistentNotification(this)) {
            ensureNotificationChannel(this);
            startForeground(
                    NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
            foreground = true;
        } else {
            if (foreground) stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            foreground = false;
        }
    }

    /**
     * Creates the visible-but-silent foreground-service channel. Returns true only when this call
     * created the channel for the first time.
     */
    public static boolean ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        boolean created = nm.getNotificationChannel(CHANNEL_ID) == null;
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(channel);
        return created;
    }

    /** True when Android will actually place the foreground notification in the shade. */
    public static boolean canShowPersistentNotification(Context context) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            return channel != null
                    && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override
    public void onDestroy() {
        active = false;
        running = false;
        if (networkCallback != null)
            try {
                ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
        if (packageReceiver != null)
            try {
                unregisterReceiver(packageReceiver);
            } catch (RuntimeException ignored) {
            }

        handler.removeCallbacksAndMessages(null);
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refreshNotification(String text) {
        if (!foreground) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, com.reed.fcmguard.ui.DashboardActivity.class);
        PendingIntent pi =
                PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        return builder.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
