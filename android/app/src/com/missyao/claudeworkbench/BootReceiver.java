package com.missyao.claudeworkbench;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机后如果系统把无障碍服务收回了，用一次 ADB 授权的 WRITE_SECURE_SETTINGS
 * 自动写回去。没有授权时这里不会有任何动作。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
            || "android.intent.action.QUICKBOOT_POWERON".equals(action)
            || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!boot) return;
        if (PhoneControlServer.isEnabled(context)) {
            PhoneControlServer.ensureAccessibilityEnabled(context);
        }
    }
}
