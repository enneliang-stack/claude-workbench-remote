package com.missyao.claudeworkbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * 悬浮状态条里的星形 logo。
 *
 * 和手机页 / PC 过程头像保持同一套动画：
 * - 每根射线独立周期、独立相位；
 * - 沿射线方向缩短，同时在最短时加宽 15%（宽度与长度同一节奏）；
 * - 整颗星 1.96s 呼吸，透明度 70%–100%。
 *
 * 这里直接按 SVG viewBox 28 28 200 200 的几何自绘，避免用静态 PNG + 整颗缩放
 * 导致缩短时宽度一起变细。
 */
public final class ProcessStarView extends View {
    private static final float VB_MIN = 28f;
    private static final float VB_SIZE = 200f;
    private static final float CX = 128f;
    private static final float CY = 128f;
    private static final float BREATH_SECONDS = 1.96f;
    private static final float BREATH_MIN_ALPHA = 0.70f;
    private static final float WIDTH_GAIN = 0.15f;

    private static final PathInterpolator EASE =
            new PathInterpolator(0.42f, 0f, 0.58f, 1f);

    private static final class Ray {
        final boolean line;
        final float x2, y2, strokeWidth;
        final Path path;
        final float angle, dur, delay, min;

        Ray(boolean line, float x2, float y2, float strokeWidth,
            Path path, float angle, float dur, float delay, float min) {
            this.line = line;
            this.x2 = x2;
            this.y2 = y2;
            this.strokeWidth = strokeWidth;
            this.path = path;
            this.angle = angle;
            this.dur = dur;
            this.delay = delay;
            this.min = min;
        }
    }

    // 和 m.js PROCESS_STAR_SVG / app.js avatarSpark 的十根参数一一对应。
    private static final Ray[] RAYS = new Ray[]{
            new Ray(true, 178.54f, 59.19f, 15.74f, null, -53.70f, 2.700f, -1.003f, 0.8647f),
            new Ray(true, 204.97f, 103.06f, 12.83f, null, -17.95f, 3.020f, -2.231f, 0.8557f),
            new Ray(true, 177.20f, 196.55f, 17.46f, null, 54.33f, 3.300f, -1.138f, 0.8700f),
            new Ray(true, 127.73f, 206.38f, 11.24f, null, 90.20f, 2.420f, -1.885f, 0.8508f),
            new Ray(true, 52.67f, 105.29f, 16.54f, null, -163.22f, 2.000f, -1.520f, 0.8672f),
            new Ray(false, 0, 0, 0, petal6(), -89.07f, 2.880f, -2.421f, 0.8592f),
            new Ray(false, 0, 0, 0, petal7(), 17.76f, 2.180f, -1.022f, 0.8380f),
            new Ray(false, 0, 0, 0, petal8(), 125.69f, 3.150f, -2.048f, 0.8585f),
            new Ray(false, 0, 0, 0, petal9(), 161.85f, 2.480f, -0.032f, 0.8381f),
            new Ray(false, 0, 0, 0, petal10(), -125.92f, 2.340f, -0.477f, 0.8447f)
    };

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean running;
    private long startUptime;
    private int color = 0xFFE05A3F;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postOnAnimation(this);
        }
    };

    public ProcessStarView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    public void setColor(int c) {
        color = c;
        strokePaint.setColor(c);
        fillPaint.setColor(c);
        invalidate();
    }

    public void start() {
        if (running) return;
        running = true;
        startUptime = SystemClock.uptimeMillis();
        postOnAnimation(frame);
        invalidate();
    }

    public void stop() {
        if (!running) return;
        running = false;
        removeCallbacks(frame);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0f) return;

        float t = running ? (SystemClock.uptimeMillis() - startUptime) / 1000f : 0f;

        // 整颗星 1.96s 呼吸，和网页 sparkBreath 的 70%–100% 一致。
        float bf = (t % BREATH_SECONDS) / BREATH_SECONDS;
        float bp = bf < 0.5f ? bf * 2f : (1f - bf) * 2f;
        int alpha = Math.round(255f * (1f - (1f - BREATH_MIN_ALPHA) * EASE.getInterpolation(bp)));
        strokePaint.setAlpha(alpha);
        fillPaint.setAlpha(alpha);

        canvas.save();
        float scale = size / VB_SIZE;
        canvas.scale(scale, scale);
        canvas.translate(-VB_MIN, -VB_MIN);
        // SVG 外层 group 的 rotate(5 128 128)
        canvas.rotate(5f, CX, CY);

        for (Ray r : RAYS) {
            float local = t - r.delay;
            local = ((local % r.dur) + r.dur) % r.dur;
            float p = local / r.dur;
            float prog = p < 0.5f ? p * 2f : (1f - p) * 2f;
            float ease = EASE.getInterpolation(prog);
            float s = 1f - (1f - r.min) * ease;
            float w = 1f + WIDTH_GAIN * ease;

            canvas.save();
            // 沿径向缩长度，同时把宽度放大：rotate(angle) scale(s, w) rotate(-angle)。
            canvas.translate(CX, CY);
            canvas.rotate(r.angle);
            canvas.scale(s, w);
            canvas.rotate(-r.angle);
            canvas.translate(-CX, -CY);

            if (r.line) {
                strokePaint.setStrokeWidth(r.strokeWidth);
                canvas.drawLine(CX, CY, r.x2, r.y2, strokePaint);
            } else {
                canvas.drawPath(r.path, fillPaint);
            }
            canvas.restore();
        }

        // 中心圆不参与逐根收缩，但跟随整颗星呼吸。
        canvas.drawCircle(CX, CY, 10f, fillPaint);
        canvas.restore();
    }

    private static Path petal6() {
        Path p = new Path();
        p.moveTo(136.96f, 128.15f);
        p.lineTo(136.95f, 51.84f);
        p.quadTo(129.42f, 40.14f, 121.52f, 51.59f);
        p.lineTo(119.04f, 127.85f);
        p.close();
        return p;
    }

    private static Path petal7() {
        Path p = new Path();
        p.moveTo(127.22f, 130.43f);
        p.lineTo(204.75f, 157.89f);
        p.quadTo(213.51f, 155.39f, 207.83f, 148.27f);
        p.lineTo(128.78f, 125.57f);
        p.close();
        return p;
    }

    private static Path petal8() {
        Path p = new Path();
        p.moveTo(120.97f, 122.95f);
        p.lineTo(71.98f, 193.29f);
        p.quadTo(71.52f, 206.62f, 84.01f, 201.93f);
        p.lineTo(135.03f, 133.05f);
        p.close();
        return p;
    }

    private static Path petal9() {
        Path p = new Path();
        p.moveTo(127.20f, 125.56f);
        p.lineTo(48.20f, 148.82f);
        p.quadTo(42.55f, 156.00f, 51.36f, 158.45f);
        p.lineTo(128.80f, 130.44f);
        p.close();
        return p;
    }

    private static Path petal10() {
        Path p = new Path();
        p.moveTo(131.08f, 125.77f);
        p.lineTo(82.16f, 53.96f);
        p.quadTo(71.50f, 50.00f, 71.94f, 61.36f);
        p.lineTo(124.92f, 130.23f);
        p.close();
        return p;
    }
}
