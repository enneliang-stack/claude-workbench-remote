package com.missyao.claudeworkbench;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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
    private TextView status;
    private EditText input;
    private Button messageBtn;
    private Button doneBtn;
    private Button sendBtn;
    private Button cancelBtn;
    private WindowManager.LayoutParams params;
    private boolean attached;

    // 运行中的细状态条：不可点击，不拦截任何触摸
    private TextView statusBar;
    private WindowManager.LayoutParams statusParams;
    private boolean statusAttached;

    // 屏幕右侧的停止/插话小按钮
    private LinearLayout edge;
    private Button stopBtn;
    private Button interjectBtn;
    private Button resumeBtn;
    private WindowManager.LayoutParams edgeParams;
    private boolean edgeAttached;
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

    private Button button(String text, int color, int bg) {
        Button b = new Button(service);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(color);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setBackground(rounded(bg, 0xFF2A323C, 9));
        return b;
    }

    private void build() {
        buildPanel();
        buildStatusBar();
        buildEdge();
    }

    /** 只在「任务已完成」和输入插话/发消息时出现的可点击面板。 */
    private void buildPanel() {
        panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setMinimumWidth(dp(200));
        panel.setBackground(rounded(0xF2151A21, 0xFF2A323C, 14));
        panel.setOnClickListener(v -> {
            if (!inputMode && PhoneControlServer.isCompletionPending()) {
                // 远程电脑页的任务不在本机对话列表里，打开 App 首页即可。
                String conv = PhoneControlServer.isPageTaskRemote()
                    ? "" : PhoneControlServer.completionConvId();
                openAppAtConversation(conv);
                PhoneControlServer.dismissCompletion();
                hideAll();
            }
        });

        LinearLayout row = new LinearLayout(service);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        status = new TextView(service);
        status.setTextColor(0xFFE8E6E3);
        status.setTextSize(12);
        status.setMaxLines(2);
        status.setMaxWidth(dp(190));
        status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(status, sp);

        // 任务完成状态：可以直接给这个对话发新消息，或点绿色 ✓ 收起提示。
        messageBtn = button("发消息", 0xFF6EA8FE, 0xFF17202B);
        messageBtn.setVisibility(View.GONE);
        messageBtn.setOnClickListener(v -> showInput(true));
        row.addView(messageBtn);

        doneBtn = button("✓", 0xFF5DD39E, 0xFF14261F);
        doneBtn.setTextSize(15);
        doneBtn.setVisibility(View.GONE);
        doneBtn.setOnClickListener(v -> {
            PhoneControlServer.dismissCompletion();
            if (inputMode) finishInput(true);
            status.setText("");
            hideAll();
        });
        row.addView(doneBtn);
        panel.addView(row);

        inputRow = new LinearLayout(service);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(7);
        input = new EditText(service);
        input.setHint("输入插话内容…");
        input.setHintTextColor(0xFF6E7680);
        input.setTextColor(0xFFE8E6E3);
        input.setTextSize(13);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(rounded(0xFF0F1318, 0xFF2A323C, 9));
        input.setPadding(dp(9), dp(6), dp(9), dp(6));
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = button("发送", 0xFFF0A35E, 0xFF2A2118);
        sendBtn.setOnClickListener(v -> sendInput());
        cancelBtn = button("取消", 0xFF9AA3AD, 0xFF151A21);
        cancelBtn.setOnClickListener(v -> finishInput(true));
        inputRow.addView(sendBtn);
        inputRow.addView(cancelBtn);
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
     * 运行中的细状态条。FLAG_NOT_TOUCHABLE 是关键：它只显示文字，
     * 所有触摸都会穿透到下面的 App，Claude 点它覆盖的位置也不会被接住。
     */
    private void buildStatusBar() {
        statusBar = new TextView(service);
        statusBar.setTextColor(0xFFE8E6E3);
        statusBar.setTextSize(11);
        statusBar.setMaxLines(1);
        statusBar.setMaxWidth(dp(240));
        statusBar.setEllipsize(TextUtils.TruncateAt.END);
        statusBar.setGravity(Gravity.CENTER);
        statusBar.setPadding(dp(10), dp(3), dp(10), dp(3));
        statusBar.setBackground(rounded(0xCC151A21, 0xFF2A323C, 9));

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

    private Button edgeButton(String glyph, int color, int bg, String desc) {
        Button b = new Button(service);
        b.setText(glyph);
        b.setTextSize(13);
        b.setTextColor(color);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rounded(bg, 0x00000000, 15));
        b.setContentDescription(desc);
        return b;
    }

    /** 右侧小胶囊：继续/插话/停止，可拖动并吸附到左右边缘。 */
    private void buildEdge() {
        edge = new LinearLayout(service);
        edge.setOrientation(LinearLayout.VERTICAL);
        edge.setGravity(Gravity.CENTER_HORIZONTAL);
        edge.setPadding(dp(3), dp(3), dp(3), dp(3));
        edge.setBackground(rounded(0xD9151A21, 0xFF2A323C, 18));

        TextView grip = new TextView(service);
        grip.setText("⋮");
        grip.setTextSize(11);
        grip.setTextColor(0x99E8E6E3);
        grip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(30), dp(12));
        gp.bottomMargin = dp(2);
        edge.addView(grip, gp);
        makeDraggable(grip);

        resumeBtn = edgeButton("▶", 0xFF8FB8FF, 0xFF1B2530, "继续手机操作");
        resumeBtn.setVisibility(View.GONE);
        resumeBtn.setOnClickListener(v -> {
            PhoneControlServer.clearPause();
            updateStatus();
        });
        makeDraggable(resumeBtn);

        interjectBtn = edgeButton("✎", 0xFFF0B46A, 0xFF2A2118, "插话");
        interjectBtn.setOnClickListener(v -> showInput(false));
        makeDraggable(interjectBtn);

        stopBtn = edgeButton("■", 0xFFE58A80, 0xFF2A1717, "停止手机操作");
        stopBtn.setOnClickListener(v -> {
            PhoneControlServer.stopCurrent();
            if (statusBar != null) statusBar.setText("正在停止…");
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

    /** 首次显示时恢复上次拖到的位置；没有记录就默认右侧居中。 */
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
            x = Math.max(0, sw - w - dp(5));
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

    private boolean shouldRun() {
        return PhoneControlServer.isEnabled(service)
            && PhoneControlServer.isAccessibilityConnected()
            && PhoneControlServer.isRunning()
            && PhoneControlServer.shouldShowOverlay(service);
    }

    /** 当前前台是不是工作台 App 自己（手机页/电脑端页）；是的话不需要悬浮窗。 */
    private boolean isForegroundApp() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pkg = root.getPackageName();
            return pkg != null && service.getPackageName().contentEquals(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    private void show() {
        if (attached) return;
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
    }

    private void showEdge() {
        if (edgeAttached) return;
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

        // 运行中不再使用面板和按钮：只有不可点击的细状态条 + 右侧小按钮。
        hide();
        messageBtn.setVisibility(View.GONE);
        doneBtn.setVisibility(View.GONE);
        String text = PhoneControlServer.overlayStatus();
        if (text.isEmpty()) {
            hideStatusBar();
            hideEdge();
            return;
        }
        statusBar.setText(text);
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
                boolean alive = PhoneControlServer.isEnabled(service)
                    && PhoneControlServer.isAccessibilityConnected()
                    && PhoneControlServer.isRunning();
                if (!alive) {
                    if (inputMode) finishInput(false);
                    hideAll();
                } else if (!inputMode && PhoneControlServer.isPhoneChatVisible()
                        && !PhoneControlServer.isCompletionPending()) {
                    // 用户已经停在一个对话上（任务结束后的完成提示会由 JS 桥接清除）。
                    // 任务仍在跑时不遮挡聊天页；完成待确认时保留悬浮窗，直到用户打开对应对话。
                    PhoneControlServer.refreshRunState();
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
