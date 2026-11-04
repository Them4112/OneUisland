package dev.flexy.dynamicisland.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;

import java.util.List;

import dev.flexy.dynamicisland.xposed.XLog;

final class NfcPaySource {
    static final String ACTION = "dev.flexy.dynamicisland.NFC_PAYMENT";
    private static final int GREEN = 0xFF30D158;
    private static final long DONE_MS = 2500;
    private static final long MAX_ACTIVE_MS = 20000;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChanged;
    private boolean active;
    private long startedAt;
    private long doneAt;
    private final WaveDrawable wave = new WaveDrawable();

    NfcPaySource(Context ctx, Handler main, Runnable onChanged) {
        this.ctx = ctx;
        this.main = main;
        this.onChanged = onChanged;
    }

    void start() {
        XLog.i("nfc pay receiver registering");
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String s = i.getStringExtra("state");
                XLog.i("nfc pay broadcast state=" + s + " enabled=" + IslandSettings.alertNfcPay());
                if (!IslandSettings.alertNfcPay()) return;
                long now = SystemClock.uptimeMillis();
                synchronized (NfcPaySource.this) {
                    if ("start".equals(s)) {
                        active = true;
                        startedAt = now;
                        doneAt = 0;
                    } else if ("end".equals(s) && active) {
                        active = false;
                        doneAt = now;
                    } else {
                        return;
                    }
                }
                onChanged.run();
            }
        }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    synchronized boolean collect(List<IslandItem> out) {
        long now = SystemClock.uptimeMillis();
        if (active && now - startedAt > MAX_ACTIVE_MS) {
            active = false;
            doneAt = 0;
        }
        boolean done = !active && doneAt > 0 && now - doneAt < DONE_MS;
        if (!active && !done) return false;
        IslandItem it = new IslandItem("nfc:pay");
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = "com.android.nfc";
        it.accent = GREEN;
        it.icon = wave;
        it.iconColor = true;
        it.iconTint = GREEN;
        it.title = "";
        it.pulsingDot = false;
        it.animatedIcon = active;
        wave.animate = active;
        it.noExpand = true;
        it.postTime = Long.MAX_VALUE - 2;
        out.add(it);
        XLog.i("nfc pay item added active=" + active);
        if (active) main.postDelayed(onChanged, Math.max(16, startedAt + MAX_ACTIVE_MS - now + 20));
        if (done) main.postDelayed(onChanged, Math.max(16, doneAt + DONE_MS - now + 20));
        return true;
    }

    private static final class WaveDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        WaveDrawable() {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width();
            float h = getBounds().height();
            float cx = getBounds().left + w * 0.22f;
            float cy = getBounds().top + h / 2f;
            p.setStrokeWidth(w * 0.09f);
            float ph = (SystemClock.uptimeMillis() % 1300) / 1300f;
            if (!animate) ph = -1f;
            for (int k = 1; k <= 3; k++) {
                float rad = w * 0.17f * k;
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                p.setColor(tint);
                float lit = 1f;
                if (ph >= 0f) {
                    float d = Math.abs(ph * 4f - k);
                    d = Math.min(d, 4f - d);
                    lit = 0.25f + 0.75f * Math.max(0f, 1f - d / 1.2f);
                }
                p.setAlpha(Math.round(alpha * lit));
                c.drawArc(r, -42, 84, false, p);
            }
        }

        boolean animate;
        private int tint = 0xFFFFFFFF;
        private int alpha = 255;

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
            p.setColorFilter(f);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
