package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import dev.flexy.dynamicisland.xposed.XLog;
import java.util.List;

/** Ongoing determinate-progress notifications (downloads, uploads, installs) -> ring + bar on the island. */
final class DownloadSource {
    private static final int MAX_ACTIONS = 3;

    private final Context ctx;
    private final Runnable onChanged;
    private IslandItem current;
    private String lastSig;

    DownloadSource(Context ctx, Runnable onChanged) {
        this.ctx = ctx;
        this.onChanged = onChanged;
    }

    void onNotifications(List<StatusBarNotification> all) {
        StatusBarNotification best = null;
        int count = 0;
        if (IslandSettings.alertDownload()) {
            for (StatusBarNotification sbn : all) {
                if (!isDownload(sbn)) continue;
                count++;
                if (best == null || sbn.getPostTime() > best.getPostTime()) best = sbn;
            }
        }
        if (best == null) {
            if (current != null) {
                current = null;
                lastSig = null;
                onChanged.run();
            }
            return;
        }
        try {
            Bundle e = best.getNotification().extras;
            String sig = best.getKey() + '|' + e.getCharSequence(Notification.EXTRA_TITLE) + '|'
                    + e.getInt(Notification.EXTRA_PROGRESS) + '|' + count;
            if (current == null || !sig.equals(lastSig)) {
                current = build(best, count);
                lastSig = sig;
                onChanged.run();
            }
        } catch (Throwable t) {
            XLog.e("download update failed", t);
        }
    }

    void collect(List<IslandItem> out, String foregroundPackage) {
        if (current == null || current.packageName.equals(foregroundPackage)) return;
        for (IslandItem it : out) {
            if (current.packageName.equals(it.packageName) || it.kind == IslandItem.Kind.CALL) return;
        }
        out.add(current);
    }

    private static boolean isDownload(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) == 0) return false;
        String pkg = sbn.getPackageName();
        if ("com.hevy".equals(pkg) || "com.ubercab".equals(pkg) || "ee.mtakso.client".equals(pkg)
                || "taxi.android.client".equals(pkg)) return false;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_CALL.equals(n.category)
                || Notification.CATEGORY_NAVIGATION.equals(n.category)
                || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false;
        Bundle e = n.extras;
        return !e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                && e.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
                && e.getInt(Notification.EXTRA_PROGRESS, -1) >= 0;
    }

    private IslandItem build(StatusBarNotification sbn, int count) {
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        IslandItem it = new IslandItem("download:" + sbn.getPackageName());
        it.packageName = sbn.getPackageName();
        it.kind = IslandItem.Kind.LIVE;
        it.postTime = sbn.getPostTime();
        it.contentIntent = n.contentIntent;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            it.appName = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (Throwable ignored) {
        }
        it.accent = n.color == 0 ? 0xFF0A84FF : Colors.readableOnBlack(n.color);

        CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = e.getCharSequence(Notification.EXTRA_TEXT);
        it.title = title != null && title.length() > 0 ? title : it.appName;
        String sub = text == null ? "" : text.toString();
        if (count > 1) sub = (sub.isEmpty() ? "" : sub + "  ·  ") + "+" + (count - 1) + " more";
        it.subtitle = sub;

        float p = e.getInt(Notification.EXTRA_PROGRESS) / (float) e.getInt(Notification.EXTRA_PROGRESS_MAX);
        it.trailing = IslandItem.Trailing.PROGRESS_RING;
        it.progress = Math.max(0f, Math.min(1f, p));
        it.detail = Math.round(it.progress * 100f) + "%";

        it.icon = loadIcon(n.getSmallIcon());
        it.iconIsImage = false;
        if (it.icon == null) {
            try {
                it.icon = ctx.getPackageManager().getApplicationIcon(sbn.getPackageName());
                it.iconIsImage = true;
            } catch (Throwable ignored) {
            }
        }
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
