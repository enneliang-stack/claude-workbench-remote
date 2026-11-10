package com.missyao.claudeworkbench;

import org.json.JSONObject;

/**
 * 手机 App 的任务状态：页面 JS 桥上报当前对话是否在跑，本机 /api/phone/current
 * 提供本机运行中的任务数。通知栏和悬浮窗共用这一份状态。
 */
public final class TaskStatus {
    private static volatile boolean pageRunning = false;
    private static volatile boolean pageRemote = false;
    private static volatile String pageTitle = "";
    private static volatile String pageConv = "";
    private static volatile int pageCompleted = 0;
    private static volatile int localRunning = 0;
    private static volatile long pageRunningAt = 0;
    private static volatile long lastPageCompletedAt = 0;

    private TaskStatus() {
    }

    public static synchronized void onPageState(JSONObject o) {
        if (o == null) return;
        boolean running = o.optBoolean("running", false);
        String title = o.optString("title", "");
        String conv = o.optString("id", "");
        boolean remote = o.optBoolean("remote", false);
        if (running) {
            pageRunning = true;
            pageRunningAt = System.currentTimeMillis();
            pageTitle = title;
            pageConv = conv;
            pageRemote = remote;
        } else if (pageRunning) {
            pageRunning = false;
            pageTitle = title;
            pageConv = conv;
            pageRemote = remote;
            pageCompleted++;
            lastPageCompletedAt = System.currentTimeMillis();
        } else {
            pageTitle = title;
            pageConv = conv;
            pageRemote = remote;
        }
    }

    /** 本机 /api/phone/current 的运行中数量；只用于“运行中”状态，完成数由页面桥上报。 */
    public static synchronized void updateLocalRunning(int count) {
        int prev = localRunning;
        localRunning = Math.max(0, count);
        if (localRunning > 0 && pageRunning) pageRunningAt = System.currentTimeMillis();
        if (localRunning < prev) {
            // 本机任务完成：页面在本机对话上时由页面 run_end 计数；
            // 页面不在该对话上时，用本机计数补上，并避开刚由页面记过的 5 秒。
            boolean pageOwnsTask = pageRunning && !pageRemote;
            boolean justCountedByPage = System.currentTimeMillis() - lastPageCompletedAt < 5000;
            if (!pageOwnsTask && !justCountedByPage) pageCompleted += prev - localRunning;
        }
        // 页面切到别的对话后可能收不到旧任务的 run_end；本机计数已归零且
        // 页面超过 5 秒没有再次上报运行时，把旧任务按完成处理。
        if (localRunning == 0 && pageRunning && pageRunningAt > 0
                && System.currentTimeMillis() - pageRunningAt > 5000) {
            pageRunning = false;
            pageRunningAt = 0;
            pageCompleted++;
            pageTitle = "";
            pageConv = "";
            pageRemote = false;
        }
    }

    public static synchronized void clearCompleted() {
        pageCompleted = 0;
    }

    public static synchronized boolean isRunning() {
        return pageRunning || localRunning > 0;
    }

    public static synchronized boolean isPageTaskRunning() {
        return pageRunning;
    }

    public static synchronized boolean isPageTaskRemote() {
        return pageRemote;
    }

    public static synchronized boolean isCompletionPending() {
        return pageCompleted > 0 && !isRunning();
    }

    public static synchronized String completionConv() {
        return pageConv;
    }

    public static synchronized String pageTitle() {
        return pageTitle;
    }

    public static synchronized String notificationText() {
        int running;
        if (pageRunning) {
            // 本机页面和 localRunning 会指向同一个任务，取较大值避免重复计数；
            // 远程电脑页任务和本机任务是两件事，要相加。
            running = pageRemote ? 1 + localRunning : Math.max(1, localRunning);
        } else {
            running = localRunning;
        }
        if (running > 0) {
            String s = running == 1 ? "任务运行中" : running + " 个任务运行中";
            if (pageCompleted > 0) s += " · " + pageCompleted + " 个已完成";
            return s;
        }
        if (pageCompleted > 0) return pageCompleted + " 个任务已完成";
        return "空闲中";
    }

    public static synchronized String overlayText() {
        if (pageRunning) {
            return pageTitle.isEmpty() ? "任务进行中…" : pageTitle + " · 任务进行中";
        }
        if (localRunning > 0) return "任务进行中…";
        if (pageCompleted > 0) return "任务已完成";
        return "";
    }
}
