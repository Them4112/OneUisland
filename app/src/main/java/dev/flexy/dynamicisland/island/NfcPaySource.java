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
import java.util.Locale;

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
    private final Drawable wave = new WaveDrawable();

    NfcPaySource(Context ctx, Handler main, Runnable onChanged) {
        this.ctx = ctx;
        this.main = main;
        this.onChanged = onChanged;
    }

    void start() {
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!IslandSettings.alertNfcPay()) return;
                String s = i.getStringExtra("state");
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
        boolean pl = "pl".equals(Locale.getDefault().getLanguage());
        IslandItem it = new IslandItem("nfc:pay");
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = "com.android.nfc";
        it.accent = GREEN;
        it.icon = wave;
        it.iconColor = true;
        it.iconTint = GREEN;
        it.title = active ? (pl ? "Płatność" : "Payment") : (pl ? "Gotowe" : "Done");
        it.appName = pl ? "Płatność zbliżeniowa" : "Contactless payment";
        it.pulsingDot = false;
        it.noExpand = true;
        it.postTime = Long.MAX_VALUE - 2;
        out.add(it);
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
            float cx = getBounds().left + w * 0.30f;
            float cy = getBounds().top + h / 2f;
            p.setStrokeWidth(w * 0.09f);
            for (int k = 1; k <= 3; k++) {
                float rad = w * 0.17f * k;
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                p.setAlpha(255 - (k - 1) * 55);
                c.drawArc(r, -42, 84, false, p);
            }
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(ColorFilter f) {
            p.setColorFilter(f);
            p.setColor(0xFFFFFFFF);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
