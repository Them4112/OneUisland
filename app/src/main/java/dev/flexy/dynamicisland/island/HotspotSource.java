package dev.flexy.dynamicisland.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;

import java.util.List;

import dev.flexy.dynamicisland.xposed.XLog;

final class HotspotSource {
    private static final int BLUE = 0xFF0A84FF;
    private static final long CYCLE_MS = 30000;
    private static final long BURST_MS = 4000;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChanged;
    private boolean on;
    private long onSince;
    private boolean scheduled;
    private final GlyphDrawable icon = new GlyphDrawable(GlyphDrawable.HOTSPOT, true);

    HotspotSource(Context ctx, Handler main, Runnable onChanged) {
        this.ctx = ctx;
        this.main = main;
        this.onChanged = onChanged;
    }

    void start() {
        try {
            WifiManager wm = ctx.getSystemService(WifiManager.class);
            Object s = WifiManager.class.getMethod("getWifiApState").invoke(wm);
            on = s instanceof Integer && ((Integer) s == 13 || (Integer) s == 12);
            if (on) onSince = SystemClock.uptimeMillis();
        } catch (Throwable t) {
            XLog.e("hotspot initial state failed", t);
        }
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int s = i.getIntExtra("wifi_state", -1);
                    boolean now;
                    if (s == 13 || s == 12) now = true;
                    else if (s == 11 || s == 10) now = false;
                    else return;
                    XLog.i("hotspot state=" + s);
                    synchronized (HotspotSource.this) {
                        if (now == on) return;
                        on = now;
                        if (now) onSince = SystemClock.uptimeMillis();
                    }
                    onChanged.run();
                }
            }, new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("hotspot receiver failed", t);
        }
    }

    private void turnOff() {
        try {
            Object tm = ctx.getSystemService("tethering");
            tm.getClass().getMethod("stopTethering", int.class).invoke(tm, 0);
            XLog.i("hotspot stopTethering called");
        } catch (Throwable t) {
            XLog.e("hotspot turn off failed", t);
        }
    }

    synchronized boolean collect(List<IslandItem> out) {
        if (!IslandSettings.alertHotspot() || !on) return false;
        long phase = (SystemClock.uptimeMillis() - onSince) % CYCLE_MS;
        boolean burst = phase < BURST_MS;
        IslandItem it = new IslandItem("hotspot");
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = ctx.getPackageName();
        icon.setPulse(burst);
        it.icon = icon;
        it.iconColor = true;
        it.iconTint = BLUE;
        it.accent = BLUE;
        it.animatedIcon = burst;
        it.title = "Hotspot";
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = "On";
        it.trailingColor = BLUE;
        it.iconTap = this::turnOff;
        it.toggle = 1;
        it.postTime = Long.MAX_VALUE - 5;
        out.add(it);
        long next = burst ? BURST_MS - phase : CYCLE_MS - phase;
        if (!scheduled) {
            scheduled = true;
            main.postDelayed(() -> {
                scheduled = false;
                onChanged.run();
            }, next + 20);
        }
        return true;
    }
}
