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
    static final int CAMERA = 4;
    static final int MIC = 5;
    static final int LOCATION = 6;
    static final int KEY = 7;

    private final int type;
    private boolean pulse;
    private long born = SystemClock.uptimeMillis();
    private boolean frozen;
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

    GlyphDrawable frozen() {
        frozen = true;
        return this;
    }

    void play() {
        frozen = false;
        born = SystemClock.uptimeMillis();
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
        else if (type == CAMERA) drawCamera(c);
        else if (type == MIC) drawMic(c);
        else if (type == LOCATION) drawLocation(c);
        else if (type == KEY) drawKey(c);
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
        long el = frozen ? 0 : SystemClock.uptimeMillis() - born;
        float x = el / 1300f;
        float cf = ease((el - 250) / 450f);
        int mixed = 0xFF000000
                | (Math.round(255 + (((tint >> 16) & 255) - 255) * cf) << 16)
                | (Math.round(255 + (((tint >> 8) & 255) - 255) * cf) << 8)
                | Math.round(255 + ((tint & 255) - 255) * cf);
        p.setColor(mixed);
        p.setAlpha(alpha);
        float lift = ease(x / 0.4f);
        float sw = Math.max(0f, Math.min(1f, (x - 0.3f) / 0.7f));
        float turn = sw * sw * (3f - 2f * sw);
        float sx = (float) Math.cos(Math.PI * turn);
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
        c.translate(0, -lift * w * 0.045f);
        c.scale(sx, 1f, lx, 0f);
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

    /** Puls 0..1 (sinus) dla trybu pulse, inaczej 1. */
    private float breath() {
        if (!pulse) return 1f;
        float ph = (SystemClock.uptimeMillis() % 1600) / 1600f;
        return 0.65f + 0.35f * (float) (0.5 + 0.5 * Math.sin(ph * Math.PI * 2));
    }

    private void drawCamera(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        p.setAlpha(Math.round(alpha * breath()));
        p.setStyle(Paint.Style.FILL);
        r.set(cx - w * 0.42f, cy - w * 0.26f, cx + w * 0.42f, cy + w * 0.30f);
        c.drawRoundRect(r, w * 0.10f, w * 0.10f, p);
        r.set(cx - w * 0.16f, cy - w * 0.38f, cx + w * 0.16f, cy - w * 0.20f);
        c.drawRoundRect(r, w * 0.05f, w * 0.05f, p);
        p.setColor(0xFF000000);
        p.setAlpha(Math.round(alpha * 0.85f));
        c.drawCircle(cx, cy + w * 0.03f, w * 0.17f, p);
        p.setColor(tint);
        p.setAlpha(Math.round(alpha * breath()));
        c.drawCircle(cx, cy + w * 0.03f, w * 0.09f, p);
    }

    private void drawMic(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        p.setAlpha(Math.round(alpha * breath()));
        p.setStyle(Paint.Style.FILL);
        r.set(cx - w * 0.16f, cy - w * 0.46f, cx + w * 0.16f, cy + w * 0.12f);
        c.drawRoundRect(r, w * 0.16f, w * 0.16f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.085f);
        r.set(cx - w * 0.30f, cy - w * 0.24f, cx + w * 0.30f, cy + w * 0.27f);
        c.drawArc(r, 0f, 180f, false, p);
        c.drawLine(cx, cy + w * 0.27f, cx, cy + w * 0.42f, p);
        c.drawLine(cx - w * 0.15f, cy + w * 0.44f, cx + w * 0.15f, cy + w * 0.44f, p);
    }

    private void drawLocation(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        p.setAlpha(Math.round(alpha * breath()));
        p.setStyle(Paint.Style.FILL);
        float rad = w * 0.30f;
        float top = cy - w * 0.42f;
        path.rewind();
        r.set(cx - rad, top, cx + rad, top + rad * 2f);
        path.arcTo(r, 150f, 240f);
        path.lineTo(cx, cy + w * 0.46f);
        path.close();
        c.drawPath(path, p);
        p.setColor(0xFF000000);
        p.setAlpha(Math.round(alpha * 0.85f));
        c.drawCircle(cx, top + rad, rad * 0.42f, p);
        p.setColor(tint);
    }

    private void drawKey(Canvas c) {
        float w = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.09f);
        float hr = w * 0.17f;
        float hx = cx - w * 0.22f;
        c.drawCircle(hx, cy, hr, p);
        float sx = hx + hr;
        float ex = cx + w * 0.44f;
        c.drawLine(sx, cy, ex, cy, p);
        c.drawLine(ex, cy, ex, cy + w * 0.18f, p);
        c.drawLine(ex - w * 0.16f, cy, ex - w * 0.16f, cy + w * 0.13f, p);
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
