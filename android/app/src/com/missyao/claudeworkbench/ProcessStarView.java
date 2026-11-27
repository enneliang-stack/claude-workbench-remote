package com.missyao.claudeworkbench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * 悬浮状态条里的星形 logo。
 *
 * 和手机页 / PC 过程头像保持同一套动画：
 * - 每根射线独立周期、独立相位；
 * - 沿射线方向缩短，同时在最短时加宽 15%（宽度与长度同一节奏）；
 * - 整颗星 4.5s 呼吸，透明度 75%–100%；整体逆时针 35 秒一圈。
 *
 * 这里直接按 SVG viewBox 28 28 200 200 的几何自绘，避免用静态 PNG + 整颗缩放
 * 导致缩短时宽度一起变细。
 */
public final class ProcessStarView extends View {
    private static final float VB_MIN = 28f;
    private static final float VB_SIZE = 200f;
    private static final float CX = 128f;
    private static final float CY = 128f;
    private static final float BREATH_SECONDS = 4.5f;
    private static final float BREATH_MIN_ALPHA = 0.75f;
    private static final float BREATH_SCALE_DROP = 0.05f;
    private static final float SPIN_SECONDS = 35f;

    private static final PathInterpolator EASE =
            new PathInterpolator(0.42f, 0f, 0.58f, 1f);

    private static final class Ray {
        final boolean line;
        final float x2, y2, strokeWidth;
        final Path path;
        final float angle, dur, delay, min, gain;

        Ray(boolean line, float x2, float y2, float strokeWidth,
            Path path, float angle, float dur, float delay, float min, float gain) {
            this.line = line;
            this.x2 = x2;
            this.y2 = y2;
            this.strokeWidth = strokeWidth;
            this.path = path;
            this.angle = angle;
            this.dur = dur;
            this.delay = delay;
            this.min = min;
            this.gain = gain;
        }
    }

    // 和 m.js PROCESS_STAR_SVG / app.js avatarSpark 的十二根参数一一对应。
    private static final Ray[] RAYS = new Ray[]{
            new Ray(true, 188.43f, 151.19f, 12.83f, null, 21.00f, 2.916f, -0.222f, 1.2080f, 0.15f),
            new Ray(true, 127.72f, 203.44f, 11.24f, null, 90.21f, 3.332f, -1.711f, 1.0780f, 0.07f),
            new Ray(true, 69.54f, 75.34f, 16.54f, null, 222.02f, 2.001f, -0.521f, 0.9090f, 0.07f),
            new Ray(false, 0, 0, 0, taperL1(), -35.62f, 3.407f, -2.229f, 0.8241f, 0.15f),
            new Ray(false, 0, 0, 0, taperL3(), 52.93f, 3.420f, -2.530f, 0.8310f, 0.15f),
            new Ray(false, 0, 0, 0, taperL6(), 173.74f, 3.416f, -3.368f, 1.0780f, 0.07f),
            new Ray(false, 0, 0, 0, petal6(), -74.22f, 2.546f, -2.086f, 1.0260f, 0.07f),
            new Ray(false, 0, 0, 0, petal7(), 1.50f, 2.217f, -0.717f, 1.1000f, 0.07f),
            new Ray(false, 0, 0, 0, petal8(), 122.69f, 3.233f, -1.337f, 0.8960f, 0.07f),
            new Ray(false, 0, 0, 0, petal9(), 149.85f, 4.123f, -0.663f, 0.7895f, 0.15f),
            new Ray(false, 0, 0, 0, petal10(), 255.56f, 2.669f, -2.480f, 0.8000f, 0.07f),
            new Ray(false, 0, 0, 0, petal11(), 191.24f, 3.416f, -3.368f, 0.7660f, 0.12f),
    };

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(0xFFDD7F5F);
        // 中心亮渐变，和客户端/任务栏图标一致
        Shader sparkShader = new RadialGradient(128f, 128f, 108f,
                new int[]{0xFFEA9876, 0xFFDD7F5F, 0xFFCF6D4B},
                new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP);
        strokePaint.setShader(sparkShader);
        fillPaint.setShader(sparkShader);
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

        // 整颗星 4.5s 呼吸，和网页 sparkBreath 的 75%–100% 一致；最低点同时缩到 0.90。
        float bf = (t % BREATH_SECONDS) / BREATH_SECONDS;
        float bp = bf < 0.5f ? bf * 2f : (1f - bf) * 2f;
        float breathEase = EASE.getInterpolation(bp);
        float breathScale = 1f - BREATH_SCALE_DROP * breathEase;
        int alpha = Math.round(255f * (1f - (1f - BREATH_MIN_ALPHA) * breathEase));
        strokePaint.setAlpha(alpha);
        fillPaint.setAlpha(alpha);
        centerPaint.setAlpha(alpha);

        canvas.save();
        float scale = size / VB_SIZE;
        canvas.scale(scale, scale);
        canvas.translate(-VB_MIN, -VB_MIN);
        canvas.translate(CX, CY);
        canvas.scale(breathScale, breathScale);
        canvas.translate(-CX, -CY);
        // 整体逆时针旋转：35 秒一圈
        canvas.rotate(-(t % SPIN_SECONDS) / SPIN_SECONDS * 360f, CX, CY);
        // SVG 外层 group 的 rotate(5 128 128)
        canvas.rotate(5f, CX, CY);

        for (Ray r : RAYS) {
            float local = t - r.delay;
            local = ((local % r.dur) + r.dur) % r.dur;
            float p = local / r.dur;
            float prog = p < 0.5f ? p * 2f : (1f - p) * 2f;
            float ease = EASE.getInterpolation(prog);
            float s = 1f - (1f - r.min) * ease;
            float w = 1f + r.gain * ease;

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
        // 用实心中间色，避免小尺寸下渐变中心看起来像小白点。
        canvas.drawCircle(CX, CY, 10f, centerPaint);
        canvas.restore();
    }

    private static Path taperL1() {
        Path p = new Path();
        p.moveTo(134.42f, 136.96f);
        p.lineTo(201.99f, 84.67f);
        p.quadTo(203.80f, 73.69f, 192.82f, 71.88f);
        p.lineTo(121.58f, 119.04f);
        p.close();
        return p;
    }

    private static Path taperL3() {
        Path p = new Path();
        p.moveTo(119.64f, 134.32f);
        p.lineTo(171.90f, 200.59f);
        p.quadTo(184.12f, 202.29f, 185.83f, 190.06f);
        p.lineTo(136.36f, 121.68f);
        p.close();
        return p;
    }

    private static Path taperL6() {
        Path p = new Path();
        p.moveTo(127.08f, 119.62f);
        p.lineTo(44.02f, 131.56f);
        p.quadTo(38.65f, 137.80f, 45.25f, 142.73f);
        p.lineTo(128.92f, 136.38f);
        p.close();
        return p;
    }

    private static Path petal6() {
        Path p = new Path();
        p.moveTo(138.78f, 131.05f);
        p.lineTo(154.43f, 62.80f);
        p.quadTo(149.89f, 50.48f, 139.58f, 58.60f);
        p.lineTo(117.22f, 124.95f);
        p.close();
        return p;
    }

    private static Path petal7() {
        Path p = new Path();
        p.moveTo(127.88f, 133.05f);
        p.lineTo(214.16f, 135.31f);
        p.quadTo(222.24f, 130.47f, 214.41f, 125.21f);
        p.lineTo(128.12f, 122.95f);
        p.close();
        return p;
    }

    private static Path petal8() {
        Path p = new Path();
        p.moveTo(118.90f, 122.16f);
        p.lineTo(75.48f, 196.13f);
        p.quadTo(75.71f, 209.48f, 87.94f, 204.13f);
        p.lineTo(137.10f, 133.84f);
        p.close();
        return p;
    }

    private static Path petal9() {
        Path p = new Path();
        p.moveTo(126.71f, 125.79f);
        p.lineTo(54.28f, 164.95f);
        p.quadTo(50.24f, 173.15f, 59.37f, 173.72f);
        p.lineTo(129.29f, 130.21f);
        p.close();
        return p;
    }

    private static Path petal10() {
        Path p = new Path();
        p.moveTo(131.67f, 127.05f);
        p.lineTo(112.46f, 42.32f);
        p.quadTo(103.98f, 34.73f, 100.24f, 45.46f);
        p.lineTo(124.33f, 128.95f);
        p.close();
        return p;
    }

    private static Path petal11() {
        Path p = new Path();
        p.moveTo(128.40f, 125.98f);
        p.lineTo(68.24f, 111.98f);
        p.quadTo(61.85f, 114.84f, 66.66f, 119.93f);
        p.lineTo(127.60f, 130.02f);
        p.close();
        return p;
    }
}
