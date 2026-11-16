package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.flexy.dynamicisland.xposed.XLog;

/**
 * Kod weryfikacyjny z nowego powiadomienia (SMS, bank, mail) -> karta z kodem i przyciskiem Copy.
 * Powiadomienie starsze niż 30 s albo już widziane nie wraca; karta znika sama po 12 s (25 s po skopiowaniu).
 */
final class OtpSource {
    private static final long SHOW_MS = 12_000;
    private static final long COPIED_MS = 25_000;
    private static final long FRESH_MS = 30_000;
    private static final int REMEMBER = 64;
    private static final int ACCENT = 0xFF30D158;

    private final Context ctx;
    private final Runnable onChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::onExpire;
    private final Set<String> seen = new HashSet<>();
    private final ArrayDeque<String> seenOrder = new ArrayDeque<>();
    private boolean primed;

    private IslandItem current;
    private long until;

    OtpSource(Context ctx, Runnable onChanged) {
        this.ctx = ctx;
        this.onChanged = onChanged;
    }

    void onNotifications(List<StatusBarNotification> all) {
        // pierwsza lista to to, co już wisi: nie pokazuj starych kodów po restarcie SystemUI
        if (!primed) {
            for (StatusBarNotification sbn : all) remember(sbn.getKey());
            primed = true;
            return;
        }
        if (!IslandSettings.alertOtp()) return;
        long now = System.currentTimeMillis();
        for (StatusBarNotification sbn : all) {
            if (seen.contains(sbn.getKey())) continue;
            remember(sbn.getKey());
            Notification n = sbn.getNotification();
            if ((n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0) continue;
            if (now - sbn.getPostTime() > FRESH_MS) continue;
            if (ctx.getPackageName().equals(sbn.getPackageName())) continue;
            try {
                OtpDetector.Match m = OtpDetector.detect(otpText(n));
                if (m == null) continue;
                show(build(sbn, m, false), SHOW_MS);
                XLog.i("otp from " + sbn.getPackageName());
                return;
            } catch (Throwable t) {
                XLog.e("otp detect failed", t);
            }
        }
    }

    void collect(List<IslandItem> out) {
        if (current == null) return;
        if (SystemClock.uptimeMillis() >= until) {
            current = null;
            return;
        }
        for (IslandItem it : out) if (it.kind == IslandItem.Kind.CALL) return;
        out.add(current);
    }

    private void show(IslandItem item, long ms) {
        current = item;
        until = SystemClock.uptimeMillis() + ms;
        main.removeCallbacks(expire);
        main.postDelayed(expire, ms + 20);
        onChanged.run();
    }

    private void onExpire() {
        if (current != null && SystemClock.uptimeMillis() >= until) {
            current = null;
            onChanged.run();
        }
    }

    private void remember(String key) {
        if (!seen.add(key)) return;
        seenOrder.addLast(key);
        while (seenOrder.size() > REMEMBER) seen.remove(seenOrder.pollFirst());
    }

    /** Ostatnia wiadomość w MessagingStyle, inaczej wszystkie teksty powiadomienia. */
    private static String otpText(Notification n) {
        Bundle e = n.extras;
        try {
            Parcelable[] raw = e.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (raw != null && raw.length > 0) {
                List<Notification.MessagingStyle.Message> messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw);
                if (!messages.isEmpty()) {
                    CharSequence last = messages.get(messages.size() - 1).getText();
                    CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
                    return (title == null ? "" : title + " ") + (last == null ? "" : last);
                }
            }
        } catch (Throwable ignored) {
        }
        return TextProgress.joined(n);
    }

    private IslandItem build(StatusBarNotification sbn, OtpDetector.Match otp, boolean copied) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        IslandItem it = new IslandItem("otp:" + sbn.getKey());
        it.packageName = pkg;
        it.kind = IslandItem.Kind.LIVE;
        it.postTime = Long.MAX_VALUE;
        it.contentIntent = n.contentIntent;
        Drawable appIcon = null;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            it.appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            appIcon = pm.getApplicationIcon(pkg);
        } catch (Throwable ignored) {
        }
        CharSequence sender = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        String from = sender != null && sender.toString().trim().length() > 0 ? sender.toString().trim() : String.valueOf(it.appName);
        it.accent = copied ? 0xFFFFFFFF : ACCENT;
        it.icon = new GlyphDrawable(GlyphDrawable.KEY, false);
        it.iconColor = true;
        it.iconTint = it.accent;
        it.cardIcon = appIcon;
        it.title = otp.display();
        it.subtitle = from + "  ·  " + (copied ? "Copied" : "Verification code");
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = otp.display();
        it.trailingColor = it.accent;
        final String code = otp.code;
        IslandItem.Action copy = new IslandItem.Action(copied ? "Copied" : "Copy", null, null, () -> {
            copy(code);
            show(build(sbn, otp, true), COPIED_MS);
        }, false);
        copy.glyph = copied ? Glyphs.CHECK : Glyphs.COPY;
        copy.active = copied;
        it.actions.add(copy);
        return it;
    }

    private void copy(String code) {
        try {
            ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
            if (cm == null) return;
            ClipData clip = ClipData.newPlainText("Verification code", code);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            cm.setPrimaryClip(clip);
        } catch (Throwable t) {
            XLog.e("otp copy failed", t);
        }
    }
}
