package com.missyao.claudeworkbench;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 手机本机网页与两个后台轮询共用任务状态，按服务进程与事件序号去重。 */
public final class TaskStatus {
    private static int localRunning;
    private static boolean legacyPageRunning;
    private static String legacyPageId = "";
    private static long epoch, revision = -1;
    private static boolean awaitBaselineLiveEnd;
    private static int completed, failed, stopped;
    private static String lastOutcome = "", lastConv = "";

    private TaskStatus() {}

    public static synchronized void onPageState(JSONObject o) {
        if (o == null || o.optBoolean("remote")) return;
        JSONObject snapshot = o.optJSONObject("snapshot");
        if (snapshot != null) {
            applySnapshot(snapshot, !o.optBoolean("running"));
            return;
        }
        if (epoch != 0) return;
        String id = o.optString("id", "");
        if (o.optBoolean("running")) {
            legacyPageRunning = true;
            legacyPageId = id;
        } else if (!id.isEmpty() && legacyPageId.equals(id)) {
            legacyPageRunning = false;
            // 老服务只有明确的网页结束事件带有结果，计数降到零无法证明成功。
            String outcome = o.optString("outcome", "");
            if (!outcome.isEmpty()) {
                recordEnd(id, outcome);
                legacyPageId = "";
            }
        }
    }

    public static synchronized void onLocalSnapshot(JSONObject snapshot) {
        applySnapshot(snapshot, false);
    }

    private static void applySnapshot(JSONObject snapshot, boolean liveEnd) {
        if (snapshot == null || !snapshot.optBoolean("ok")) return;
        long incomingEpoch = snapshot.optLong("epoch");
        long incomingRevision = snapshot.optLong("revision", -1);
        if (incomingEpoch == 0) {
            if (epoch == 0) {
                localRunning = Math.max(0, snapshot.optInt("count", snapshot.optBoolean("running") ? 1 : 0));
                if (localRunning == 0) legacyPageRunning = false;
            }
            return;
        }
        if (incomingRevision < 0 || incomingEpoch < epoch) return;
        long floor = revision;
        if (incomingEpoch != epoch) {
            // 首次轮询仅建立基线。若此序号的实时结束随后才送达，仍可接收一次。
            clearCompleted();
            epoch = incomingEpoch;
            awaitBaselineLiveEnd = !liveEnd;
            floor = liveEnd ? incomingRevision - 1 : incomingRevision;
        } else if (incomingRevision == revision && liveEnd && awaitBaselineLiveEnd) {
            floor = revision - 1;
            awaitBaselineLiveEnd = false;
        } else {
            if (incomingRevision <= revision) return;
            awaitBaselineLiveEnd = false;
        }
        legacyPageRunning = false;
        localRunning = Math.max(0, snapshot.optInt("count"));
        JSONArray ends = snapshot.optJSONArray("ends");
        for (int i = 0; ends != null && i < ends.length(); i++) {
            JSONObject end = ends.optJSONObject(i);
            if (end != null && end.optLong("revision") > floor && end.optLong("revision") <= incomingRevision) {
                recordEnd(end.optString("id"), end.optString("outcome"));
            }
        }
        revision = incomingRevision;
    }

    private static void recordEnd(String id, String outcome) {
        switch (outcome) {
            case "failed": failed++; break;
            case "stopped": stopped++; break;
            case "completed": completed++; break;
            default: return;
        }
        lastOutcome = outcome;
        lastConv = id;
    }

    public static synchronized void clearCompleted() {
        awaitBaselineLiveEnd = false;
        completed = failed = stopped = 0;
        lastOutcome = lastConv = "";
    }

    public static synchronized boolean isRunning() {
        return localRunning > 0 || legacyPageRunning;
    }

    public static synchronized boolean isCompletionPending() {
        return !lastOutcome.isEmpty() && !isRunning();
    }

    public static synchronized String completionText() {
        if ("failed".equals(lastOutcome)) return "任务出错";
        if ("stopped".equals(lastOutcome)) return "任务已停止";
        return "任务已完成";
    }

    public static synchronized String completionConv() {
        return lastConv;
    }

    public static synchronized String notificationText() {
        int running = Math.max(localRunning, legacyPageRunning ? 1 : 0);
        List<String> parts = new ArrayList<>();
        if (running > 0) parts.add(running == 1 ? "任务运行中" : running + " 个任务运行中");
        if (completed > 0) parts.add(completed + " 个任务已完成");
        if (failed > 0) parts.add(failed + " 个任务出错");
        if (stopped > 0) parts.add(stopped + " 个任务已停止");
        return parts.isEmpty() ? "空闲中" : String.join(" · ", parts);
    }
}
