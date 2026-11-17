package com.missyao.claudeworkbench;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 手机控制悬浮窗。使用无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY，
 * 所以不需要额外的「悬浮窗」权限，也能显示在其它 App 上面。
 */
public final class FloatingControl {
    private static FloatingControl instance;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 完成/输入用的可点击面板
    private LinearLayout panel;
    private LinearLayout inputRow;
    private ImageView logo;
    private TextView status;
    private EditText input;
    private ImageButton messageBtn;
    private ImageButton doneBtn;
    private Button sendBtn;
    private Button cancelBtn;
    private WindowManager.LayoutParams params;
    private boolean attached;

    // 跟随 App 的浅色/深色主题（设置里切换后 1 秒内同步到悬浮窗）
    private static final String PREFS = "remote";
    private static final String PREF_THEME = "app_theme";
    private boolean lightTheme;
    private boolean paletteReady;
    private int colPanel, colStroke, colText, colMuted, colFaint;
    private int colAccent, colGreen, colBlue, colRed;
    private int colChip, colInputBg, colInk, colStatusBg, colEdgeBg;
    private int colMsgBg, colDoneBg, colStopBg, colRipple;

    // 运行中的细状态条：不可点击，不拦截任何触摸
    private LinearLayout statusBar;
    private ProcessStarView statusLogo;
    private TextView statusText;
    private WindowManager.LayoutParams statusParams;
    private boolean statusAttached;

    // 屏幕边缘的停止/插话小按钮
    private LinearLayout edge;
    private TextView grip;
    private Button stopBtn;
    private Button interjectBtn;
    private Button resumeBtn;
    private WindowManager.LayoutParams edgeParams;
    private boolean edgeAttached;
    private boolean edgeTouchPaused;
    private final Runnable restoreEdgeTouch = () -> setEdgeTouchPaused(false);
    private float dragDownRawX, dragDownRawY;
    private int dragStartX, dragStartY;
    private boolean draggingEdge;

    private boolean inputMode;
    private boolean messageMode;

    private FloatingControl(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    public static void attach(AccessibilityService service) {
        if (instance != null) return;
        instance = new FloatingControl(service);
        instance.start();
    }

    public static void detach() {
        if (instance == null) return;
        instance.stop();
        instance = null;
    }

    private void start() {
        build();
        handler.post(tick);
    }

    private void stop() {
        handler.removeCallbacksAndMessages(null);
        hideAll();
        instance = null;
    }

    private boolean canFocusOverlay() {
        return Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(service);
    }

    private int dp(float v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private Drawable pressable(int fill, int ripple, int radius) {
        GradientDrawable bg = rounded(fill, 0x00000000, radius);
        return new RippleDrawable(ColorStateList.valueOf(ripple), bg, null);
    }

    private Button button(String text) {
        Button b = new Button(service);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        b.setStateListAnimator(null);
        return b;
    }

    private ImageButton iconButton(int drawable, String desc) {
        ImageButton b = new ImageButton(service);
        b.setImageResource(drawable);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setContentDescription(desc);
        b.setStateListAnimator(null);
        return b;
    }

    private boolean readLightTheme() {
        return !"dark".equals(service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_THEME, "light"));
    }

    /** 读主题色；App 设置里切换浅色/深色后，悬浮窗也跟着换。 */
    private void syncTheme() {
        boolean light = readLightTheme();
        if (paletteReady && light == lightTheme) return;
        lightTheme = light;
        paletteReady = true;
        if (light) {
            colPanel = 0xFAFFFDFA;
            colStroke = 0xFFDED8CE;
            colText = 0xFF201E1B;
            colMuted = 0xFF4C4741;
            colFaint = 0xFF9A9186;
            colAccent = 0xFFC46443;
            colGreen = 0xFF3F8F68;
            colBlue = 0xFF4F7FAE;
            colRed = 0xFFC45A5A;
            colChip = 0xFFECE7DF;
            colInputBg = 0xFFF3F0EA;
            colInk = 0xFFFFFDFA;
            colStatusBg = 0xF2FFFDFA;
            colEdgeBg = 0xF2FFFDFA;
            colMsgBg = 0xFFE9EFF6;
            colDoneBg = 0xFFE5F1EA;
            colStopBg = 0xFFF6E9E7;
            colRipple = 0x244C4741;
        } else {
            colPanel = 0xF2151A21;
            colStroke = 0xFF2A323C;
            colText = 0xFFE8E6E3;
            colMuted = 0xFF9AA3AD;
            colFaint = 0xFF6E7680;
            colAccent = 0xFFF0A35E;
            colGreen = 0xFF5DD39E;
            colBlue = 0xFF8FB8FF;
            colRed = 0xFFE58A80;
            colChip = 0xFF232B35;
            colInputBg = 0xFF0F1318;
            colInk = 0xFF17202B;
            colStatusBg = 0xE6151A21;
            colEdgeBg = 0xE6151A21;
            colMsgBg = 0xFF1B2530;
            colDoneBg = 0xFF14261F;
            colStopBg = 0xFF2A1717;
            colRipple = 0x33FFFFFF;
        }
        styleViews();
    }

    /** 主题切换时直接改现有控件的颜色，不重建悬浮窗。 */
    private void styleViews() {
        if (panel == null) return;
        panel.setBackground(rounded(colPanel, colStroke, 22));
        logo.setImageTintList(ColorStateList.valueOf(colAccent));
        status.setTextColor(colText);

        messageBtn.setImageTintList(ColorStateList.valueOf(colBlue));
        messageBtn.setBackground(pressable(colMsgBg, colRipple, 14));
        doneBtn.setImageTintList(ColorStateList.valueOf(colGreen));
        doneBtn.setBackground(pressable(colDoneBg, colRipple, 14));

        input.setTextColor(colText);
        input.setHintTextColor(colFaint);
        input.setBackground(rounded(colInputBg, colStroke, 10));
        styleTextButton(sendBtn, colInk, colAccent);
        styleTextButton(cancelBtn, colMuted, colChip);

        statusLogo.setColor(colAccent);
        statusText.setTextColor(colText);
        statusBar.setBackground(rounded(colStatusBg, colStroke, 10));

        if (grip != null) {
            grip.setTextColor((colMuted & 0x00FFFFFF) | 0x99000000);
        }
        edge.setBackground(rounded(colEdgeBg, colStroke, 18));
        styleEdgeButton(resumeBtn, colBlue, colMsgBg);
        styleEdgeButton(interjectBtn, colAccent, colChip);
        styleEdgeButton(stopBtn, colRed, colStopBg);
    }

    private void styleTextButton(Button b, int textColor, int bg) {
        b.setTextColor(textColor);
        b.setBackground(rounded(bg, colStroke, 10));
    }

    private void styleEdgeButton(Button b, int textColor, int bg) {
        b.setTextColor(textColor);
        b.setBackground(pressable(bg, colRipple, 15));
    }

    private void build() {
        syncTheme();
        buildPanel();
        buildStatusBar();
        buildEdge();
        styleViews();
    }

    /** 只在「任务已完成」和输入插话/发消息时出现的可点击面板。 */
    private void buildPanel() {
        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(6), dp(8), dp(6));
        panel.setElevation(dp(6));
        panel.setOnClickListener(v -> {
            if (!inputMode && PhoneControlServer.isCompletionPending()) {
                // 先移除悬浮窗，再清完成状态/打开 App，避免窗口在移除前重排一下。
                String conv = PhoneControlServer.isPageTaskRemote()
                    ? "" : PhoneControlServer.completionConvId();
                hideAll();
                PhoneControlServer.dismissCompletion();
                openAppAtConversation(conv);
            }
        });

        LinearLayout row = new LinearLayout(service);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // App logo 只取星形本身，不加任何底色或方框。
        logo = new ImageView(service);
        logo.setImageResource(R.drawable.ic_notification);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
        lp.rightMargin = dp(7);
        row.addView(logo, lp);

        status = new TextView(service);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setMaxLines(2);
        status.setMaxWidth(dp(190));
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(status, sp);

        // 完成状态：图标按钮只占 28dp，给状态文字留出更多宽度。
        messageBtn = iconButton(R.drawable.ic_overlay_message, "发消息");
        messageBtn.setVisibility(View.GONE);
        messageBtn.setOnClickListener(v -> showInput(true));
        LinearLayout.LayoutParams mbp = new LinearLayout.LayoutParams(dp(28), dp(28));
        mbp.leftMargin = dp(7);
        row.addView(messageBtn, mbp);

        doneBtn = iconButton(R.drawable.ic_overlay_done, "完成，收起提示");
        doneBtn.setVisibility(View.GONE);
        doneBtn.setOnClickListener(v -> {
            // 先移除窗口再清状态：不再先 setText("")，避免面板在移除前
            // 先缩成只有 logo 和两个图标的一条，看起来像“点了没反应”。
            hideAll();
            PhoneControlServer.dismissCompletion();
            if (inputMode) finishInput(true);
        });
        LinearLayout.LayoutParams dbp = new LinearLayout.LayoutParams(dp(28), dp(28));
        dbp.leftMargin = dp(6);
        row.addView(doneBtn, dbp);
        panel.addView(row);

        inputRow = new LinearLayout(service);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(8);
        input = new EditText(service);
        input.setHint("输入插话内容…");
        input.setTextSize(13);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(10), dp(7), dp(10), dp(7));
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = button("发送");
        sendBtn.setOnClickListener(v -> sendInput());
        cancelBtn = button("取消");
        cancelBtn.setOnClickListener(v -> finishInput(true));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sendLp.leftMargin = dp(7);
        inputRow.addView(sendBtn, sendLp);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelLp.leftMargin = dp(6);
        inputRow.addView(cancelBtn, cancelLp);
        panel.addView(inputRow, ip);

        int type = canFocusOverlay()
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(8);
        params.x = 0;
        params.horizontalMargin = dp(8);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    /**
     * 运行中的细状态条（星形 logo + 状态文字）。FLAG_NOT_TOUCHABLE 是关键：
     * 所有触摸都会穿透到下面的 App，Claude 点它覆盖的位置也不会被接住。
     */
    private void buildStatusBar() {
        statusBar = new LinearLayout(service);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(11), dp(4), dp(12), dp(4));

        // 星形 logo 和网页「过程」头像使用同一套逐根缩短 + 加宽 + 呼吸动画。
        statusLogo = new ProcessStarView(service);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        logoLp.rightMargin = dp(6);
        statusBar.addView(statusLogo, logoLp);

        statusText = new TextView(service);
        statusText.setTextSize(11);
        statusText.setMaxLines(1);
        statusText.setMaxWidth(dp(240));
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setGravity(Gravity.CENTER);
        statusBar.addView(statusText);

        statusParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        statusParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        statusParams.y = dp(4);
        statusParams.horizontalMargin = dp(8);
    }

    private Button edgeButton(String glyph, String desc) {
        Button b = new Button(service);
        b.setText(glyph);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setContentDescription(desc);
        b.setStateListAnimator(null);
        return b;
    }

    /** 边缘小胶囊：继续/插话/停止，可拖动并吸附到左右边缘。 */
    private void buildEdge() {
        edge = new LinearLayout(service);
        edge.setOrientation(LinearLayout.VERTICAL);
        edge.setGravity(Gravity.CENTER_HORIZONTAL);
        edge.setPadding(dp(3), dp(3), dp(3), dp(3));

        grip = new TextView(service);
        grip.setText("⋮");
        grip.setTextSize(11);
        grip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(30), dp(12));
        gp.bottomMargin = dp(2);
        edge.addView(grip, gp);
        makeDraggable(grip);

        resumeBtn = edgeButton("▶", "继续手机操作");
        resumeBtn.setVisibility(View.GONE);
        resumeBtn.setOnClickListener(v -> {
            PhoneControlServer.clearPause();
            updateStatus();
        });
        makeDraggable(resumeBtn);

        interjectBtn = edgeButton("✎", "插话");
        interjectBtn.setOnClickListener(v -> showInput(false));
        makeDraggable(interjectBtn);

        stopBtn = edgeButton("■", "停止手机操作");
        stopBtn.setOnClickListener(v -> {
            PhoneControlServer.stopCurrent();
            if (statusText != null) statusText.setText("正在停止…");
            if (inputMode) finishInput(false);
        });
        makeDraggable(stopBtn);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(30), dp(30));
        bp.bottomMargin = dp(2);
        edge.addView(resumeBtn, bp);
        edge.addView(interjectBtn, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout.LayoutParams last = new LinearLayout.LayoutParams(dp(30), dp(30));
        last.topMargin = dp(2);
        edge.addView(stopBtn, last);

        edgeParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        edgeParams.gravity = Gravity.TOP | Gravity.START;
        applyEdgeTouchFlag();
    }

    private int screenW() {
        return service.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return service.getResources().getDisplayMetrics().heightPixels;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 按住胶囊（或按钮）拖动；松手吸附到最近的左右边缘。 */
    private void makeDraggable(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragDownRawX = event.getRawX();
                    dragDownRawY = event.getRawY();
                    dragStartX = edgeParams.x;
                    dragStartY = edgeParams.y;
                    draggingEdge = false;
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - dragDownRawX;
                    float dy = event.getRawY() - dragDownRawY;
                    if (!draggingEdge
                            && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                        draggingEdge = true;
                    }
                    if (draggingEdge && edgeAttached) {
                        int maxX = Math.max(0, screenW() - edge.getWidth());
                        int maxY = Math.max(0, screenH() - edge.getHeight());
                        edgeParams.x = clamp(dragStartX + Math.round(dx), 0, maxX);
                        edgeParams.y = clamp(dragStartY + Math.round(dy), 0, maxY);
                        try {
                            wm.updateViewLayout(edge, edgeParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    if (draggingEdge) {
                        draggingEdge = false;
                        snapEdgeToSide();
                        saveEdgePosition();
                    } else {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    draggingEdge = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    private void snapEdgeToSide() {
        if (!edgeAttached) return;
        int w = edge.getWidth();
        int h = edge.getHeight();
        if (w <= 0 || h <= 0) return;
        int sw = screenW();
        int sh = screenH();
        int margin = dp(5);
        int centerX = edgeParams.x + w / 2;
        edgeParams.x = centerX < sw / 2
            ? margin
            : Math.max(margin, sw - w - margin);
        edgeParams.y = clamp(edgeParams.y, 0, Math.max(0, sh - h));
        try {
            wm.updateViewLayout(edge, edgeParams);
        } catch (Exception ignored) {
        }
    }

    private void saveEdgePosition() {
        service.getSharedPreferences("floating", Context.MODE_PRIVATE)
            .edit()
            .putInt("edge_x", edgeParams.x)
            .putInt("edge_y", edgeParams.y)
            .apply();
    }

    /** 首次显示时恢复上次拖到的位置；没有记录就默认左侧居中。 */
    private void applyEdgePosition() {
        if (!edgeAttached) return;
        int w = edge.getWidth();
        int h = edge.getHeight();
        if (w <= 0 || h <= 0) return;
        int sw = screenW();
        int sh = screenH();
        int x = service.getSharedPreferences("floating", Context.MODE_PRIVATE)
            .getInt("edge_x", -1);
        int y = service.getSharedPreferences("floating", Context.MODE_PRIVATE)
            .getInt("edge_y", -1);
        if (x < 0 || y < 0) {
            // 默认靠左、竖直居中；用户拖动过则继续恢复上次保存的位置。
            x = dp(5);
            y = Math.max(0, (sh - h) / 2);
        }
        edgeParams.x = clamp(x, 0, Math.max(0, sw - w));
        edgeParams.y = clamp(y, 0, Math.max(0, sh - h));
        try {
            wm.updateViewLayout(edge, edgeParams);
        } catch (Exception ignored) {
        }
    }

    private void refreshEdgeLayout() {
        if (!edgeAttached) return;
        int maxX = Math.max(0, screenW() - edge.getWidth());
        int maxY = Math.max(0, screenH() - edge.getHeight());
        edgeParams.x = clamp(edgeParams.x, 0, maxX);
        edgeParams.y = clamp(edgeParams.y, 0, maxY);
        try {
            wm.updateViewLayout(edge, edgeParams);
        } catch (Exception ignored) {
        }
    }

    /**
     * AI 要用坐标点按/滑动时调用：如果落点压在插话/停止小浮窗上，
     * 临时让浮窗不接收触摸，让手势穿透到下面的 App，避免 Claude 把自己点停。
     * 返回是否临时关了触摸，调用方在手势结束后要调用 endAiGesture()。
     */
    public static boolean beginAiGesture(int x, int y) {
        FloatingControl f = instance;
        return f != null && f.beginGesturePassThrough(x, y);
    }

    public static void endAiGesture() {
        FloatingControl f = instance;
        if (f == null) return;
        f.handler.post(() -> f.setEdgeTouchPaused(false));
    }

    private boolean beginGesturePassThrough(int x, int y) {
        if (!edgeAttached || edge == null || edgeParams == null) return false;
        int w = edge.getWidth();
        int h = edge.getHeight();
        if (w <= 0) w = dp(36);
        if (h <= 0) h = dp(120);
        int margin = dp(8);
        int left = edgeParams.x - margin;
        int top = edgeParams.y - margin;
        int right = edgeParams.x + w + margin;
        int bottom = edgeParams.y + h + margin;
        if (x < left || x > right || y < top || y > bottom) return false;
        setEdgeTouchPaused(true);
        return true;
    }

    private void setEdgeTouchPaused(boolean paused) {
        if (edgeTouchPaused == paused) {
            if (paused) {
                handler.removeCallbacks(restoreEdgeTouch);
                handler.postDelayed(restoreEdgeTouch, 3500);
            }
            return;
        }
        edgeTouchPaused = paused;
        handler.removeCallbacks(restoreEdgeTouch);
        if (paused) handler.postDelayed(restoreEdgeTouch, 3500);
        applyEdgeTouchFlag();
    }

    private void applyEdgeTouchFlag() {
        if (edgeParams == null) return;
        if (edgeTouchPaused) {
            edgeParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            edgeParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (edgeAttached) {
            try {
                wm.updateViewLayout(edge, edgeParams);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean shouldRun() {
        return PhoneControlServer.isEnabled(service)
            && PhoneControlServer.isAccessibilityConnected()
            && PhoneControlServer.isRunning()
            && PhoneControlServer.shouldShowOverlay(service);
    }

    private void show() {
        if (attached) return;
        panel.setVisibility(View.VISIBLE);
        try {
            wm.addView(panel, params);
            attached = true;
        } catch (Exception e) {
            // 悬浮窗权限中途被收回时，退回到无障碍浮层，至少不崩。
            if (params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
                params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                try {
                    wm.addView(panel, params);
                    attached = true;
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void hide() {
        if (!attached) return;
        // 先把 View 压成不可见，再移除窗口。窗口移除是异步的，
        // 这一步能保证点击 ✓ 后不会再多画一帧旧面板。
        panel.setVisibility(View.GONE);
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
        }
        attached = false;
    }

    private void showStatusBar() {
        if (statusAttached) return;
        try {
            wm.addView(statusBar, statusParams);
            statusAttached = true;
            startStatusPulse();
        } catch (Exception ignored) {
        }
    }

    private void hideStatusBar() {
        if (!statusAttached) return;
        try {
            wm.removeView(statusBar);
        } catch (Exception ignored) {
        }
        statusAttached = false;
        stopStatusPulse();
    }

    /** 星形逐根纯缩短 + 1.96s 呼吸（70%–100%），和网页过程头像一致。 */
    private void startStatusPulse() {
        if (statusLogo != null) statusLogo.start();
    }

    private void stopStatusPulse() {
        if (statusLogo != null) statusLogo.stop();
    }

    private void showEdge() {
        if (edgeAttached) return;
        // 如果 AI 手势还没结束，新挂上的浮窗也要保持触摸穿透。
        applyEdgeTouchFlag();
        try {
            wm.addView(edge, edgeParams);
            edgeAttached = true;
            edge.post(this::applyEdgePosition);
        } catch (Exception ignored) {
        }
    }

    private void hideEdge() {
        if (!edgeAttached) return;
        try {
            wm.removeView(edge);
        } catch (Exception ignored) {
        }
        edgeAttached = false;
    }

    private void hideAll() {
        hide();
        hideStatusBar();
        hideEdge();
    }

    /** 切换可聚焦/不可聚焦时重新挂载，确保输入法能正常弹出。 */
    private void reattach() {
        hide();
        show();
    }

    private void updateStatus() {
        if (inputMode) return;
        boolean done = PhoneControlServer.isCompletionPending()
            && !PhoneControlServer.isClaudeRunning();

        if (done) {
            // 任务完成用可点击面板，用户要点「发消息」或绿色 ✓。
            hideStatusBar();
            hideEdge();
            // 远程电脑页任务不能通过本机 /api/phone/send 发消息，只保留 ✓。
            messageBtn.setVisibility(PhoneControlServer.isPageTaskRemote() ? View.GONE : View.VISIBLE);
            doneBtn.setVisibility(View.VISIBLE);
            String text = PhoneControlServer.overlayStatus();
            status.setText(text.isEmpty() ? "任务已完成" : text);
            show();
            return;
        }

        // 运行中不再使用面板和按钮：只有不可点击的细状态条 + 边缘小按钮。
        hide();
        messageBtn.setVisibility(View.GONE);
        doneBtn.setVisibility(View.GONE);
        String text = PhoneControlServer.overlayStatus();
        if (text.isEmpty()) {
            hideStatusBar();
            hideEdge();
            return;
        }
        statusText.setText(text);
        showStatusBar();

        boolean active = PhoneControlServer.isClaudeRunning()
            || PhoneControlServer.isPaused()
            || PhoneControlServer.isToolBusy();
        if (PhoneControlServer.isPageTaskRemote()) {
            // 远程电脑页任务只显示状态，不给停止/插话边缘按钮（那些只作用于本机引擎）。
            active = PhoneControlServer.isPaused() || PhoneControlServer.isToolBusy();
        }
        if (active) {
            resumeBtn.setVisibility(PhoneControlServer.isPaused() ? View.VISIBLE : View.GONE);
            showEdge();
            refreshEdgeLayout();
            edge.post(this::refreshEdgeLayout);
        } else {
            hideEdge();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                syncTheme();
                boolean alive = PhoneControlServer.isEnabled(service)
                    && PhoneControlServer.isAccessibilityConnected()
                    && PhoneControlServer.isRunning();
                if (!alive) {
                    if (inputMode) finishInput(false);
                    hideAll();
                } else if (!inputMode && PhoneControlServer.isPhoneChatVisible()) {
                    // 用户已经停在手机端的对话页上：任务完成的回复就在页面里，
                    // 不再弹完成悬浮窗；正看着同一个对话时把完成状态一并清掉。
                    PhoneControlServer.refreshRunState();
                    if (PhoneControlServer.isCompletionPending()) {
                        String doneConv = PhoneControlServer.completionConvId();
                        String viewing = PhoneControlServer.phoneChatConvId();
                        if (doneConv.isEmpty() || doneConv.equals(viewing)) {
                            PhoneControlServer.dismissCompletion();
                        }
                    }
                    hideAll();
                } else {
                    // 即使悬浮窗暂时隐藏也要轮询，才能知道 Claude 是否还在跑。
                    PhoneControlServer.refreshRunState();
                    if (inputMode) {
                        // 输入面板自己保持显示，期间不显示状态条和边缘按钮。
                    } else if (shouldRun()) {
                        updateStatus();
                    } else {
                        hideAll();
                    }
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 900);
        }
    };

    private void showInput(boolean forMessage) {
        if (inputMode) return;
        if (!canFocusOverlay()) {
            Toast.makeText(service,
                forMessage
                    ? "先开启「显示在其他应用上层」权限，才能直接在操作页面发消息"
                    : "先开启「显示在其他应用上层」权限，才能直接在操作页面插话",
                Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + service.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(i);
            } catch (Exception e) {
                status.setText("无法打开悬浮窗权限设置：" + e.getMessage());
            }
            return;
        }
        messageMode = forMessage;
        PhoneControlServer.setPaused(true);
        inputMode = true;
        hideStatusBar();
        hideEdge();
        messageBtn.setVisibility(View.GONE);
        doneBtn.setVisibility(View.GONE);
        show();
        if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        inputRow.setVisibility(View.VISIBLE);
        status.setText(forMessage
            ? "输入新消息，发送后继续这个任务…"
            : "已暂停手机操作，输入插话…");
        input.setHint(forMessage ? "给这个对话发新消息…" : "输入插话内容…");
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
        reattach();
        input.setText("");
        input.requestFocus();
        handler.postDelayed(() -> {
            try {
                InputMethodManager imm = (InputMethodManager)
                    service.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception ignored) {
            }
        }, 120);
    }

    /** 点击“任务已完成”悬浮窗，打开 App 并定位到对应对话。 */
    private void openAppAtConversation(String convId) {
        try {
            Intent i = new Intent(service, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (convId != null && !convId.isEmpty()) {
                i.putExtra(MainActivity.EXTRA_CONV_ID, convId);
            }
            service.startActivity(i);
        } catch (Exception e) {
            status.setText("无法打开 App：" + e.getMessage());
        }
    }

    private void finishInput(boolean clearPause) {
        if (!inputMode) return;
        inputMode = false;
        messageMode = false;
        inputRow.setVisibility(View.GONE);
        hideKeyboard();
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        reattach();
        sendBtn.setEnabled(true);
        cancelBtn.setEnabled(true);
        if (clearPause) PhoneControlServer.clearPause();
        updateStatus();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager)
                service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && input.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void sendInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        final boolean wasMessage = messageMode;
        sendBtn.setEnabled(false);
        cancelBtn.setEnabled(false);
        status.setText(wasMessage ? "正在发送消息…" : "正在发送插话…");
        PhoneControlServer.ResultCallback cb = (ok, message) -> handler.post(() -> {
            sendBtn.setEnabled(true);
            cancelBtn.setEnabled(true);
            if (ok) {
                finishInput(true);
                status.setText(wasMessage ? "已发送，任务继续" : "已插话，继续操作");
                Toast.makeText(service,
                    wasMessage ? "消息已发送，任务继续" : "已插话，手机操作继续",
                    Toast.LENGTH_SHORT).show();
            } else {
                status.setText(message == null
                    ? (wasMessage ? "发送失败" : "插话失败") : message);
            }
        });
        if (wasMessage) {
            PhoneControlServer.sendMessage(text, cb);
        } else {
            PhoneControlServer.steer(text, cb);
        }
    }
}
