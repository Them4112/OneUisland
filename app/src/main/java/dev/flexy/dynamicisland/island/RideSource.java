package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import dev.flexy.dynamicisland.xposed.XLog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ride-hailing apps (Uber, Bolt, FreeNow) -> island item built from their ongoing notification.
 * No app hook: SystemUI already forwards notifications to IslandController.onNotifications().
 */
final class RideSource {
    private static final class App {
        final String name;
        final int color;

        App(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    private static final Map<String, App> APPS = new HashMap<>();

    static {
        APPS.put("com.ubercab", new App("Uber", 0xFFFFFFFF));
        APPS.put("ee.mtakso.client", new App("Bolt", 0xFF34D186));
        APPS.put("taxi.android.client", new App("FreeNow", 0xFFFF5A5F));
    }

    private static final Pattern ETA = Pattern.compile("(?i)(\\d{1,3})\\s*(?:min\\b|mins\\b|minutes?\\b|minut\\w*)");
    private static final int MAX_ACTIONS = 3;

    private final Context ctx;
    private final Runnable onChanged;
    private IslandItem current;
    private String lastSig;

    RideSource(Context ctx, Runnable onChanged) {
        this.ctx = ctx;
        this.onChanged = onChanged;
    }

    void onNotifications(List<StatusBarNotification> all) {
        StatusBarNotification found = null;
        if (IslandSettings.alertRide()) {
            for (StatusBarNotification sbn : all) {
                if (APPS.containsKey(sbn.getPackageName()) && isTrip(sbn.getNotification())) {
                    found = sbn;
                    break;
                }
            }
        }
        if (found == null) {
            if (current != null) {
                current = null;
                lastSig = null;
                onChanged.run();
            }
            return;
        }
        try {
            String sig = signature(found);
            if (current == null || !sig.equals(lastSig)) {
                current = build(found);
                lastSig = sig;
                onChanged.run();
            }
        } catch (Throwable t) {
            XLog.e("ride update failed", t);
        }
    }

    void collect(List<IslandItem> out, String foregroundPackage) {
        if (current == null || current.packageName.equals(foregroundPackage)) return;
        for (IslandItem it : out) {
            if (current.packageName.equals(it.packageName)) return;
        }
        out.add(current);
    }

    private static boolean isTrip(Notification n) {
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        String cat = n.category;
        if (Notification.CATEGORY_PROMO.equals(cat) || Notification.CATEGORY_SOCIAL.equals(cat)
                || Notification.CATEGORY_RECOMMENDATION.equals(cat)) return false;
        return (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
    }

    private static String signature(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        StringBuilder sb = new StringBuilder();
        sb.append(sbn.getPackageName()).append('|').append(e.getCharSequence(Notification.EXTRA_TITLE))
                .append('|').append(e.getCharSequence(Notification.EXTRA_TEXT))
                .append('|').append(e.getCharSequence(Notification.EXTRA_SUB_TEXT))
                .append('|').append(e.getInt(Notification.EXTRA_PROGRESS, -1));
        if (n.actions != null) {
            for (Notification.Action a : n.actions) sb.append('|').append(a == null ? null : a.title);
        }
        return sb.toString();
    }

    private IslandItem build(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        App app = APPS.get(sbn.getPackageName());
        IslandItem it = new IslandItem("ride:" + sbn.getPackageName());
        it.packageName = sbn.getPackageName();
        it.kind = IslandItem.Kind.LIVE;
        it.postTime = sbn.getPostTime();
        it.contentIntent = n.contentIntent;
        it.appName = app.name;
        it.accent = app.color;

        CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = e.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT);
        it.title = title != null && title.length() > 0 ? title : app.name;
        it.subtitle = text != null && text.length() > 0 ? text : sub;

        Matcher m = null;
        for (CharSequence cs : new CharSequence[]{title, text, sub}) {
            if (cs == null) continue;
            Matcher x = ETA.matcher(cs);
            if (x.find()) {
                m = x;
                break;
            }
        }
        if (m != null) {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = m.group(1) + " min";
        }
        int max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        int prog = e.getInt(Notification.EXTRA_PROGRESS, -1);
        if (max > 0 && prog >= 0) {
            it.trailing = IslandItem.Trailing.PROGRESS_RING;
            it.progress = Math.min(1f, prog / (float) max);
            if (m != null) it.detail = m.group(1) + " min";
        }

        it.icon = loadIcon(n.getSmallIcon());
        it.iconIsImage = false;
        if (it.icon == null) {
            try {
                it.icon = ctx.getPackageManager().getApplicationIcon(sbn.getPackageName());
                it.iconIsImage = true;
            } catch (Throwable ignored) {
            }
        }
        it.cardIcon = loadIcon(n.getLargeIcon());

        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null || a.title == null) continue;
                it.actions.add(new IslandItem.Action(a.title, loadIcon(a.getIcon()), a.actionIntent, null, false));
                if (it.actions.size() == MAX_ACTIONS) break;
            }
        }
        return it;
    }

    private Drawable loadIcon(Icon icon) {
        if (icon == null) return null;
        try {
            return icon.loadDrawable(ctx);
        } catch (Throwable t) {
            return null;
        }
    }
}
