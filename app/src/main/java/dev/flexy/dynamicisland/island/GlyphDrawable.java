package dev.flexy.dynamicisland.island;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

final class GlyphDrawable extends Drawable {
    static final int UNLOCK = 0;
    static final int HOTSPOT = 1;
    static final int WIFI = 2;
    static final int BLUETOOTH = 3;

    private final int type;
    private boolean pulse;
    private final long born = SystemClock.uptimeMillis();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private int tint = 0xFFFFFFFF;
    private int alpha = 255;

    GlyphDrawable(int type, boolean pulse) {
        this.type = type;
        this.pulse = pulse;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setPulse(boolean v) {
        pulse = v;
    }

    @Override
    public void draw(Canvas c) {
        p.setColor(tint);
        p.setAlpha(alpha);
        if (type == UNLOCK) drawUnlock(c);
        else if (type == WIFI) drawWifi(c);
        else if (type == BLUETOOTH) drawBluetooth(c);
        else drawHotspot(c);
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private void drawUnlock(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        float x = (SystemClock.uptimeMillis() - born) / 1300f;
        float cf = ease((SystemClock.uptimeMillis() - born - 250) / 450f);
        int mixed = 0xFF000000
                | (Math.round(255 + (((tint >> 16) & 255) - 255) * cf) << 16)
                | (Math.round(255 + (((tint >> 8) & 255) - 255) * cf) << 8)
                | Math.round(255 + ((tint & 255) - 255) * cf);
        p.setColor(mixed);
        p.setAlpha(alpha);
        float lift = ease(x / 0.4f);
        float sw = Math.max(0f, Math.min(1f, (x - 0.3f) / 0.7f));
        float back = sw - 1f;
        float swing = 1f + 2.2f * back * back * back + 1.2f * back * back;
        float bw = w * 0.56f, bh = w * 0.40f;
        float bodyTop = cy - bh * 0.15f;
        p.setStyle(Paint.Style.FILL);
        r.set(cx - bw / 2f, bodyTop, cx + bw / 2f, bodyTop + bh);
        c.drawRoundRect(r, w * 0.08f, w * 0.08f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.085f);
        float sr = bw * 0.30f;
        float lx = cx - sr;
        int save = c.save();
        c.translate(0, -lift * w * 0.08f);
        c.rotate(-58f * swing, lx, bodyTop);
        path.rewind();
        path.moveTo(lx, bodyTop);
        path.lineTo(lx, bodyTop - sr);
        r.set(cx - sr, bodyTop - sr * 2f, cx + sr, bodyTop);
        path.arcTo(r, 180, 180);
        path.lineTo(cx + sr, bodyTop - sr * 0.55f * lift);
        c.drawPath(path, p);
        c.restoreToCount(save);
    }

    private void drawWifi(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float by = getBounds().exactCenterY() + w * 0.28f;
        float t = ease((SystemClock.uptimeMillis() - born) / 700f);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, by, w * 0.07f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.08f);
        for (int k = 1; k <= 3; k++) {
            float rad = w * 0.19f * k;
            float lit = Math.max(0f, Math.min(1f, t * 3.2f - (k - 1)));
            p.setAlpha(Math.round(alpha * lit));
            r.set(cx - rad, by - rad, cx + rad, by + rad);
            c.drawArc(r, 225, 90, false, p);
        }
    }

    private void drawBluetooth(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        float t = ease((SystemClock.uptimeMillis() - born) / 500f);
        float u = w * 0.27f * (0.6f + 0.4f * t);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.08f);
        p.setAlpha(Math.round(alpha * (0.4f + 0.6f * t)));
        path.rewind();
        path.moveTo(cx - u, cy - u * 0.9f);
        path.lineTo(cx + u, cy + u * 0.9f);
        path.lineTo(cx, cy + u * 1.8f);
        path.lineTo(cx, cy - u * 1.8f);
        path.lineTo(cx + u, cy - u * 0.9f);
        path.lineTo(cx - u, cy + u * 0.9f);
        c.drawPath(path, p);
    }

    private void drawHotspot(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        float ph = pulse ? (SystemClock.uptimeMillis() % 1300) / 1300f : -1f;
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, w * 0.075f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.08f);
        for (int k = 1; k <= 2; k++) {
            float rad = w * 0.20f * k;
            float lit = 1f;
            if (ph >= 0f) {
                float d = Math.abs(ph * 3f - k);
                d = Math.min(d, 3f - d);
                lit = 0.3f + 0.7f * Math.max(0f, 1f - d / 1.1f);
            }
            p.setAlpha(Math.round(alpha * lit));
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(r, -45, 90, false, p);
            c.drawArc(r, 135, 90, false, p);
        }
    }

    @Override
    public void setTint(int color) {
        tint = color;
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
    }

    @Override
    public void setColorFilter(ColorFilter f) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
