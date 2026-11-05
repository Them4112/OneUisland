package dev.flexy.dynamicisland.island;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import dev.flexy.dynamicisland.Settings;
import dev.flexy.dynamicisland.xposed.XLog;

final class IslandSettings {
    private static volatile Bundle values = new Bundle();

    private IslandSettings() {
    }

    static int maxChars() {
        int v = values.getInt(Settings.MAX_CHARS, Settings.DEF_MAX_CHARS);
        return Math.max(Settings.MIN_MAX_CHARS, Math.min(Settings.NO_MAX_CHARS, v));
    }

    static boolean previewLength() {
        return values.getBoolean(Settings.PREVIEW_LENGTH, false);
    }

    static boolean alertBattery() {
        return values.getBoolean(Settings.ALERT_BATTERY, Settings.DEF_ALERT_BATTERY);
    }

    static boolean alertHotspot() {
        return values.getBoolean(Settings.ALERT_HOTSPOT, Settings.DEF_ALERT_HOTSPOT);
    }

    static boolean alertConnect() {
        return values.getBoolean(Settings.ALERT_CONNECT, Settings.DEF_ALERT_CONNECT);
    }

    static boolean alertDownload() {
        return values.getBoolean(Settings.ALERT_DOWNLOAD, Settings.DEF_ALERT_DOWNLOAD);
    }

    private static String orderCache;
    private static java.util.List<String> orderList = java.util.Collections.emptyList();

    static int priorityOf(IslandItem.Kind kind) {
        String s = values.getString(Settings.PRIORITY_ORDER, Settings.DEF_PRIORITY_ORDER);
        if (!s.equals(orderCache)) {
            orderList = java.util.Arrays.asList(s.split(","));
            orderCache = s;
        }
        int i = orderList.indexOf(kind.name());
        return i < 0 ? kind.priority : 100 - i * 10;
    }

    static boolean alertRide() {
        return values.getBoolean(Settings.ALERT_RIDE, Settings.DEF_ALERT_RIDE);
    }

    static boolean outline() {
        return values.getBoolean(Settings.OUTLINE, Settings.DEF_OUTLINE);
    }

    static boolean alertUnlock() {
        return values.getBoolean(Settings.ALERT_UNLOCK, Settings.DEF_ALERT_UNLOCK);
    }

    static boolean alertNfcPay() {
        return values.getBoolean(Settings.ALERT_NFC_PAY, Settings.DEF_ALERT_NFC_PAY);
    }

    static boolean alertPods() {
        return values.getBoolean(Settings.ALERT_PODS, Settings.DEF_ALERT_PODS);
    }

    static boolean podsEveryUnlock() {
        return values.getBoolean(Settings.PODS_EVERY_UNLOCK, Settings.DEF_PODS_EVERY_UNLOCK);
    }

    static boolean alertDnd() {
        return values.getBoolean(Settings.ALERT_DND, Settings.DEF_ALERT_DND);
    }

    static boolean alertRinger() {
        return values.getBoolean(Settings.ALERT_RINGER, Settings.DEF_ALERT_RINGER);
    }

    static int position(boolean large) {
        int v = values.getInt(large ? Settings.POSITION : Settings.POSITION_PHONE, Settings.DEF_POSITION);
        int max = large ? Settings.POS_CAMERA : Settings.POS_LEFT;
        return v >= Settings.POS_CENTER && v <= max ? v : Settings.DEF_POSITION;
    }

    static boolean glassPill(boolean large) {
        return values.getBoolean(large ? Settings.GLASS_PILL : Settings.GLASS_PILL_PHONE, Settings.DEF_GLASS_PILL);
    }

    static boolean detailedBubble() {
        return values.getBoolean(Settings.DETAILED_BUBBLE, Settings.DEF_DETAILED_BUBBLE);
    }

    static boolean frostedCards() {
        return values.getBoolean(Settings.FROSTED_CARDS, Settings.DEF_FROSTED_CARDS);
    }

    static boolean spotifyRecents() {
        return values.getBoolean(Settings.SPOTIFY_RECENTS, Settings.DEF_SPOTIFY_RECENTS);
    }

    static void watch(Context ctx, Runnable onChanged) {
        try {
            HandlerThread thread = new HandlerThread("IslandSettings");
            thread.start();
            Handler bg = new Handler(thread.getLooper());
            Handler main = new Handler(Looper.getMainLooper());
            ContentResolver resolver = ctx.getContentResolver();
            Runnable fetch = () -> {
                try {
                    Bundle b = resolver.call(Settings.URI, Settings.METHOD_GET, null, null);
                    values = b != null ? b : new Bundle();
                    main.post(onChanged);
                } catch (Throwable t) {
                    XLog.e("island settings unavailable", t);
                }
            };
            resolver.registerContentObserver(Settings.URI, false, new android.database.ContentObserver(bg) {
                @Override
                public void onChange(boolean selfChange) {
                    fetch.run();
                }
            });
            bg.post(fetch);
        } catch (Throwable t) {
            XLog.e("island settings watch", t);
        }
    }
}
