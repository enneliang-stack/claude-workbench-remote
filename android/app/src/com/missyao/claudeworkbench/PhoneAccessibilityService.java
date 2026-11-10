package com.missyao.claudeworkbench;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：给手机端 Claude 提供读屏、点按、输入、打开 App 的能力。
 * 只有用户在系统设置里手动开启，并且 App 里的「手机控制」开关打开时才生效。
 */
public class PhoneAccessibilityService extends AccessibilityService {
    private static volatile PhoneAccessibilityService instance;

    public static PhoneAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        PhoneControlServer.onAccessibilityConnected(this);
        FloatingControl.attach(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 控制接口按需读屏，不订阅事件内容。
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        FloatingControl.detach();
        PhoneControlServer.onAccessibilityDisconnected();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        FloatingControl.detach();
        PhoneControlServer.onAccessibilityDisconnected();
        super.onDestroy();
    }
}
