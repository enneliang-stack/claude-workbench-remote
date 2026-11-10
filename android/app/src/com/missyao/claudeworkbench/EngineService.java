package com.missyao.claudeworkbench;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * 手机本机服务的前台保活：proot 里跑 Node，任务可能持续很久，
 * 没有前台服务会被 OriginOS 直接杀掉。
 */
public class EngineService extends Service {
    private static final String TAG = "ClaudeEngine";
    private static final String CHANNEL = "phone_engine";
    private static final int NOTIFY_ID = 4712;
    private static final int FGS_TYPE_SPECIAL_USE = 0x40000000;

    public static final String ACTION_START = "com.missyao.claudeworkbench.engine.START";
    public static final String ACTION_STOP = "com.missyao.claudeworkbench.engine.STOP";
    public static final String ACTION_INSTALL = "com.missyao.claudeworkbench.engine.INSTALL";
    private static final String PREFS = "engine";
    private static final String PREF_ENABLED = "enabled";

    private PowerManager.WakeLock wakeLock;

    public static void start(Context c) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, true).apply();
        Intent i = new Intent(c, EngineService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void install(Context c) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, true).apply();
        Intent i = new Intent(c, EngineService.class).setAction(ACTION_INSTALL);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
        Intent i = new Intent(c, EngineService.class).setAction(ACTION_STOP);
        c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClaudeWorkbench:engine");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            LocalEngine.stopServer(this);
            LocalEngine.stopTailscaleProxy(this);
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        final boolean installFirst = ACTION_INSTALL.equals(action);
        startInForeground(installFirst ? "正在安装手机端…" : "正在启动手机端…");
        acquireWakeLock();
        new Thread(() -> {
            try {
                if (installFirst || !LocalEngine.isInstalled(this)) {
                    LocalEngine.install(this, line -> {
                        Log.i(TAG, line);
                        updateNotification(line);
                    });
                }
                int port = LocalEngine.startServer(this, line -> Log.i(TAG, line));
                updateNotification("手机端运行中 · 端口 " + port);
            } catch (Exception e) {
                LocalEngine.log(this, "服务启动失败：" + Log.getStackTraceString(e));
                String reason = shortText(e.getMessage(), 90);
                String prefix = LocalEngine.isInstalled(this) ? "启动失败：" : "安装失败：";
                updateNotification(prefix + reason);
            }
        }, installFirst ? "phone-engine-install" : "phone-engine-start").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (!LocalEngine.isInstalling()) {
            LocalEngine.stopServer(this);
            LocalEngine.stopTailscaleProxy(this);
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "手机端引擎", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("在本机运行 Claude Code 时保持后台进程");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startInForeground(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, FGS_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 点通知正文直接回到 App；只有「查看日志」动作才打开日志页
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent log = new Intent(this, MainActivity.class);
        log.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        log.putExtra(MainActivity.EXTRA_SHOW_LOG, true);
        PendingIntent logPi = PendingIntent.getActivity(this, 3, log,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent stop = new Intent(this, EngineService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return b.setContentTitle("Claude 工作台 · 手机端")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(0, "查看日志", logPi)
                .addAction(0, "停止", stopPi)
                .build();
    }

    private static String shortText(String text, int max) {
        if (text == null) return "未知错误";
        String s = text.replace('\r', ' ').replace('\n', ' ').trim();
        while (s.contains("  ")) s = s.replace("  ", " ");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }
}
