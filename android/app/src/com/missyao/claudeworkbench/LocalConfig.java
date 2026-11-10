package com.missyao.claudeworkbench;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 手机本机模式的配置：DeepSeek 地址/Key/模型、工作区、存储权限。
 * Key 只存在 App 私有目录和 proot 的 /root/.claude/settings.json 里。
 */
public final class LocalConfig {
    private static final String PREFS = "local";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic";
    public static final String DEFAULT_MODEL = "deepseek-flash[1m]";
    public static final String DEFAULT_EFFORT = "low";

    private LocalConfig() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String apiKey(Context c) {
        return prefs(c).getString("api_key", "").trim();
    }

    public static String baseUrl(Context c) {
        String v = prefs(c).getString("base_url", DEFAULT_BASE_URL).trim();
        return v.isEmpty() ? DEFAULT_BASE_URL : v;
    }

    public static String model(Context c) {
        String v = prefs(c).getString("model", DEFAULT_MODEL).trim();
        return v.isEmpty() ? DEFAULT_MODEL : v;
    }

    public static String effort(Context c) {
        return prefs(c).getString("effort", DEFAULT_EFFORT).trim();
    }

    /** 2.3.0 起手机端默认低档位；老用户从 max 迁移一次。 */
    public static void ensureDefaultEffort(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("effort_default_low_v230", false)) {
            p.edit()
                .putString("effort", DEFAULT_EFFORT)
                .putBoolean("effort_default_low_v230", true)
                .apply();
        }
    }

    public static boolean isConfigured(Context c) {
        return !apiKey(c).isEmpty();
    }

    public static void save(Context c, String apiKey, String baseUrl, String model, String effort) {
        prefs(c).edit()
            .putString("api_key", apiKey == null ? "" : apiKey.trim())
            .putString("base_url", baseUrl == null ? DEFAULT_BASE_URL : baseUrl.trim())
            .putString("model", model == null ? DEFAULT_MODEL : model.trim())
            .putString("effort", effort == null ? "" : effort.trim())
            .apply();
    }

    public static boolean tsEnabled(Context c) {
        return prefs(c).getBoolean("ts_enabled", false);
    }

    public static void setTsEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean("ts_enabled", enabled).apply();
    }

    public static String tsAuthKey(Context c) {
        return prefs(c).getString("ts_auth_key", "").trim();
    }

    public static String tsPcUrl(Context c) {
        return prefs(c).getString("ts_pc_url", "").trim();
    }

    public static void saveTs(Context c, String authKey, String pcUrl) {
        prefs(c).edit()
            .putString("ts_auth_key", authKey == null ? "" : authKey.trim())
            .putString("ts_pc_url", pcUrl == null ? "" : pcUrl.trim())
            .apply();
    }

    /** 内置代理要连的电脑 origin，例如 http://100.x.x.x:4711 或 https://xxx.ts.net */
    public static String tsTargetUrl(Context c) {
        try {
            Uri u = Uri.parse(tsPcUrl(c));
            if (u.getScheme() == null || u.getHost() == null) return "";
            int port = u.getPort();
            return u.getScheme() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return "";
        }
    }

    /** WebView 实际加载的本地代理地址，路径/查询/电脑 token 原样保留。 */
    public static String tsLocalUrl(Context c) {
        try {
            Uri u = Uri.parse(tsPcUrl(c));
            if (u.getHost() == null) return "";
            String path = u.getEncodedPath();
            if (path == null || path.isEmpty() || "/".equals(path)) path = "/m/index.html";
            String query = u.getEncodedQuery();
            return "http://127.0.0.1:" + LocalEngine.TS_PROXY_PORT + path
                + (query == null || query.isEmpty() ? "" : "?" + query);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasAllFilesAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** proot 里 /sdcard 对应哪里：有全盘权限就用公共存储，否则用 App 专属外部目录。 */
    public static File externalRoot(Context c) {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            return Environment.getExternalStorageDirectory();
        }
        File ext = c.getExternalFilesDir(null);
        return ext != null ? ext : new File(c.getFilesDir(), "linux/workspace");
    }

    private static JSONObject readJson(File f) {
        if (!f.isFile()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 1024 * 1024)];
            int n = in.read(buf);
            if (n <= 0) return new JSONObject();
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void writeJson(File f, JSONObject obj) {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        try {
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Exception ignored) {
        }
    }

    private static String haikuModel(String model) {
        String m = model == null ? "" : model.trim();
        int i = m.indexOf('[');
        if (i > 0) m = m.substring(0, i).trim();
        return m.isEmpty() ? "deepseek-flash" : m;
    }

    /** 写入 proot 里的 ~/.claude/settings.json，Claude Code 和本地服务端都读这一份。 */
    public static void writeClaudeSettings(Context c, File alpineDir) {
        File file = new File(new File(alpineDir, "root/.claude"), "settings.json");
        JSONObject root = readJson(file);
        JSONObject env;
        try {
            env = root.optJSONObject("env");
        } catch (Exception e) {
            env = null;
        }
        if (env == null) {
            try {
                env = new JSONObject();
                root.put("env", env);
            } catch (Exception e) {
                env = new JSONObject();
            }
        }
        String model = model(c);
        try {
            env.put("ANTHROPIC_BASE_URL", baseUrl(c));
            env.put("ANTHROPIC_AUTH_TOKEN", apiKey(c));
            env.put("ANTHROPIC_MODEL", model);
            env.put("ANTHROPIC_DEFAULT_OPUS_MODEL", model);
            env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", model);
            env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", haikuModel(model));
            env.put("ANTHROPIC_SMALL_FAST_MODEL", haikuModel(model));
            env.put("CLAUDE_CODE_SUBAGENT_MODEL", haikuModel(model));
            env.remove("ANTHROPIC_API_KEY");
            env.put("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "900000");
            env.put("DISABLE_AUTOUPDATER", "1");
            env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            // Alpine 用系统 ripgrep（musl），避免官方内置 ripgrep 的 glibc 依赖
            env.put("USE_BUILTIN_RIPGREP", "0");
            String effort = effort(c);
            if (effort.isEmpty()) env.remove("CLAUDE_CODE_EFFORT_LEVEL");
            else env.put("CLAUDE_CODE_EFFORT_LEVEL", effort);
        } catch (Exception ignored) {
        }
        writeJson(file, root);
    }

    /** 预置首次启动引导，避免 headless 模式卡在欢迎/信任界面。 */
    public static void writeClaudeJson(Context c, File alpineDir) {
        File file = new File(alpineDir, "root/.claude.json");
        JSONObject root = readJson(file);
        try {
            root.put("hasCompletedOnboarding", true);
            root.put("hasSeenTasksHint", true);
            if (!root.has("theme")) root.put("theme", "dark");
            if (!root.has("numStartups")) root.put("numStartups", 1);
            JSONObject projects = root.optJSONObject("projects");
            if (projects == null) {
                projects = new JSONObject();
                root.put("projects", projects);
            }
            for (String dir : new String[]{"/workspace", "/sdcard"}) {
                JSONObject p = projects.optJSONObject(dir);
                if (p == null) {
                    p = new JSONObject();
                    projects.put(dir, p);
                }
                p.put("hasTrustDialogAccepted", true);
                p.put("hasCompletedProjectOnboarding", true);
            }
        } catch (Exception ignored) {
        }
        writeJson(file, root);
    }
}
