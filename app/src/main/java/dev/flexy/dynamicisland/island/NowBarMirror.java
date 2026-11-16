package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.flexy.dynamicisland.xposed.XLog;

/**
 * Lustro do Now Bara Samsunga (pill na dole ekranu blokady + chip w pasku stanu): dla treningu Hevy,
 * przejazdu i latarki wysyła z SystemUI powiadomienie z extras ongoingActivityNoti, tak jak AssistantApp.
 * Element wyspy jest źródłem: tytuł, drugi wiersz, postęp, akcje i ikony idą stąd.
 */
public final class NowBarMirror {
    static final String TAG = "dynamic_island_mirror";
    private static final String CHANNEL = "dynamic_island_mirror_nowbar";
    private static final String ACTION = "dev.flexy.dynamicisland.MIRROR_ACTION";
    private static final String OA = "android.ongoingActivityNoti.";
    private static final int SEM_ONGOING_ACTIVITY = 0x8000;
    private static final int BASE_ID = 0x0D00;
    private static final int ICON_PX = 128;

    private final Context ctx;
    private final Map<String, String> posted = new HashMap<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<Integer, Runnable> runnables = new HashMap<>();
    private Icon torchIcon;
    private static final java.util.Set<String> HIDDEN_SOURCES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, String> sources = new HashMap<>();

    /** Czy to powiadomienie źródłowe ma zniknąć z panelu, bo jego lustro jest w Now Barze. */
    public static boolean hidesSource(String notificationKey) {
        return notificationKey != null && HIDDEN_SOURCES.contains(notificationKey);
    }

    private void source(String slot, String key) {
        String before = key == null ? sources.remove(slot) : sources.put(slot, key);
        if (java.util.Objects.equals(before, key)) return;
        if (before != null) HIDDEN_SOURCES.remove(before);
        if (key != null) HIDDEN_SOURCES.add(key);
        dev.flexy.dynamicisland.xposed.HideDuplicated.rebuild();
    }

    NowBarMirror(Context ctx) {
        this.ctx = ctx;
        ids.put("hevy", BASE_ID + 1);
        ids.put("ride", BASE_ID + 2);
        ids.put("torch", BASE_ID + 3);
    }

    void register() {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            for (Map.Entry<String, Integer> e : ids.entrySet()) nm.cancel(TAG + "_" + e.getKey(), e.getValue());
        } catch (Throwable ignored) {
        }
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    Runnable r = runnables.get(i.getIntExtra("slot", -1));
                    if (r != null) {
                        try {
                            r.run();
                        } catch (Throwable t) {
                            XLog.e("mirror action failed", t);
                        }
                    }
                }
            }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("mirror receiver failed", t);
        }
    }

    /** Wywoływane przy każdym odświeżeniu: null = nic do pokazania w danym miejscu. */
    void sync(IslandItem hevy, IslandItem ride, IslandItem torch) {
        boolean on = IslandSettings.nowBarMirror();
        put("hevy", on ? hevy : null);
        put("ride", on ? ride : null);
        put("torch", on && torch != null && torch.torchOn ? torch : null);
    }

    private void put(String slot, IslandItem it) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            String tag = TAG + "_" + slot;
            int id = ids.get(slot);
            if (it == null) {
                source(slot, null);
                if (posted.remove(slot) != null) nm.cancel(tag, id);
                return;
            }
            String sig = signature(it);
            if (sig.equals(posted.get(slot))) return;
            ensureChannel(nm);
            nm.notify(tag, id, build(slot, id, it));
            posted.put(slot, sig);
            source(slot, it.sourceKey);
        } catch (Throwable t) {
            XLog.e("mirror " + slot, t);
        }
    }

    private static String signature(IslandItem it) {
        StringBuilder sb = new StringBuilder();
        sb.append(it.key).append('|').append(it.title).append('|').append(it.subtitle).append('|').append(it.detail)
                .append('|').append(it.progress < 0 ? -1 : Math.round(it.progress * 50f))
                .append('|').append(it.torchOn).append('|').append(it.torchLevel);
        for (IslandItem.Action a : it.actions) sb.append('|').append(a.title);
        return sb.toString();
    }

    private void ensureChannel(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Live activities (Now Bar)", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static final class Btn {
        final String title;
        final PendingIntent intent;

        Btn(String title, PendingIntent intent) {
            this.title = title;
            this.intent = intent;
        }
    }

    private PendingIntent broadcast(int slotId, Runnable r) {
        runnables.put(slotId, r);
        Intent in = new Intent(ACTION).setPackage(ctx.getPackageName()).putExtra("slot", slotId);
        return PendingIntent.getBroadcast(ctx, slotId, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private List<Btn> buttons(int id, IslandItem it) {
        List<Btn> out = new ArrayList<>();
        int base = id * 8;
        if (it.torch) {
            if (it.torchSet != null && it.torchMax > 1) {
                final int down = Math.max(1, it.torchLevel - 1);
                final int up = Math.min(it.torchMax, it.torchLevel + 1);
                out.add(new Btn("\u2212", broadcast(base + 1, () -> it.torchSet.accept(down))));
                out.add(new Btn("+", broadcast(base + 2, () -> it.torchSet.accept(up))));
            }
            if (it.torchToggle != null) out.add(new Btn("Turn off", broadcast(base, it.torchToggle)));
            return out;
        }
        for (int i = 0; i < it.actions.size() && out.size() < 3; i++) {
            IslandItem.Action a = it.actions.get(i);
            if (a.title == null) continue;
            PendingIntent pi = a.intent;
            if (pi == null && a.runnable != null) pi = broadcast(base + i, a.runnable);
            if (pi != null) out.add(new Btn(a.title.toString(), pi));
        }
        return out;
    }

    private Notification build(String slot, int id, IslandItem it) {
        boolean torch = it.torch;
        String title = torch ? "Flashlight" : str(it.title, it.appName);
        String sub = torch ? null : str(it.subtitle, null);
        String detail;
        if (torch) {
            detail = it.torchMax > 1 ? "On  \u00b7  Level " + it.torchLevel + "/" + it.torchMax : "On";
        } else {
            detail = str(it.detail, null);
        }
        String secondary = detail != null && sub != null ? detail + "  \u00b7  " + sub : detail != null ? detail : sub != null ? sub : str(it.appName, "");
        Icon small = torch ? torchIcon() : toIcon(it.icon, !it.iconIsImage);
        if (small == null) small = Icon.createWithResource(ctx, android.R.drawable.stat_notify_sync);
        Icon large = torch ? null : toIcon(it.cardIcon != null ? it.cardIcon : it.cardImage != null ? it.cardImage : it.icon, false);
        int accent = it.accent == 0 ? 0xFFFFFFFF : it.accent;
        int percent = torch || it.progress < 0 ? -1 : Math.round(Math.max(0f, Math.min(1f, it.progress)) * 100f);
        List<Btn> buttons = buttons(id, it);

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(small)
                .setContentTitle(title)
                .setContentText(secondary)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(accent)
                .setColorized(false)
                .setCustomBigContentView(card(title, sub, detail, percent, large, buttons, accent, false))
                .setStyle(new Notification.DecoratedCustomViewStyle());
        if (large != null) b.setLargeIcon(large);
        if (it.contentIntent != null) b.setContentIntent(it.contentIntent);
        if (percent >= 0) b.setProgress(100, percent, false);
        for (Btn btn : buttons) b.addAction(new Notification.Action.Builder(small, btn.title, btn.intent).build());

        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(b, true);
        } catch (Throwable ignored) {
        }
        Bundle extras = new Bundle();
        extras.putString("android.substName", str(it.appName, title));
        extras.putInt(OA + "style", 1);
        extras.putCharSequence(OA + "primaryInfo", title);
        extras.putCharSequence(OA + "secondaryInfo", secondary);
        extras.putCharSequence(OA + "chipExpandedText", title);
        extras.putCharSequence(OA + "nowbarPrimaryInfo", title);
        extras.putCharSequence(OA + "nowbarSecondaryInfo", secondary);
        extras.putInt(OA + "chipBgColor", 0xFF000000);
        extras.putBoolean("android.showSmallIcon", true);
        extras.putParcelable(OA + "chipIcon", small);
        extras.putParcelable(OA + "nowbarIcon", large != null ? large : small);
        extras.putParcelable(OA + "firstIcon", small);
        extras.putParcelable(OA + "secondaryInfoIcon", small);
        android.widget.RemoteViews nowBarCard = card(title, sub, detail, percent, large, buttons, accent, true);
        extras.putParcelable(OA + "nowbarExpandRemoteView", nowBarCard);
        extras.putParcelable(OA + "expandedRemoteView", nowBarCard);
        b.addExtras(extras);
        Notification n = b.build();
        try {
            java.lang.reflect.Field f = Notification.class.getDeclaredField("semFlags");
            f.setAccessible(true);
            f.setInt(n, f.getInt(n) | SEM_ONGOING_ACTIVITY);
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** Rozwinięta karta: tytuł, drugi wiersz, odliczanie, pasek, obrazek, do 3 przycisków. nowBar = biały tekst na ciemnym. */
    private android.widget.RemoteViews card(String title, String sub, String detail, int percent, Icon image,
                                            List<Btn> buttons, int accent, boolean nowBar) {
        android.widget.RemoteViews rv = new android.widget.RemoteViews("dev.flexy.dynamicisland", dev.flexy.dynamicisland.R.layout.mirror_expanded);
        int primary = nowBar ? 0xFFFFFFFF : 0;
        int secondaryColor = nowBar ? 0xB3FFFFFF : 0;
        if (nowBar) {
            int pad = Math.round(20 * ctx.getResources().getDisplayMetrics().density);
            rv.setViewPadding(dev.flexy.dynamicisland.R.id.mirror_root, pad, pad, pad, pad);
        }
        rv.setTextViewText(dev.flexy.dynamicisland.R.id.mirror_title, title == null ? "" : title);
        if (primary != 0) rv.setTextColor(dev.flexy.dynamicisland.R.id.mirror_title, primary);
        if (sub != null) {
            rv.setTextViewText(dev.flexy.dynamicisland.R.id.mirror_text, sub);
            if (secondaryColor != 0) rv.setTextColor(dev.flexy.dynamicisland.R.id.mirror_text, secondaryColor);
        } else {
            rv.setViewVisibility(dev.flexy.dynamicisland.R.id.mirror_text, android.view.View.GONE);
        }
        if (detail != null) {
            rv.setTextViewText(dev.flexy.dynamicisland.R.id.mirror_detail, detail);
            rv.setTextColor(dev.flexy.dynamicisland.R.id.mirror_detail, nowBar ? 0xFFFFFFFF : accent);
        } else {
            rv.setViewVisibility(dev.flexy.dynamicisland.R.id.mirror_detail, android.view.View.GONE);
        }
        if (image != null) rv.setImageViewIcon(dev.flexy.dynamicisland.R.id.mirror_image, image);
        else rv.setViewVisibility(dev.flexy.dynamicisland.R.id.mirror_image, android.view.View.GONE);
        if (percent >= 0) {
            rv.setProgressBar(dev.flexy.dynamicisland.R.id.mirror_progress, 100, percent, false);
            try {
                rv.setColorStateList(dev.flexy.dynamicisland.R.id.mirror_progress, "setProgressTintList",
                        android.content.res.ColorStateList.valueOf(accent));
                rv.setColorStateList(dev.flexy.dynamicisland.R.id.mirror_progress, "setProgressBackgroundTintList",
                        android.content.res.ColorStateList.valueOf(nowBar ? 0x40FFFFFF : 0x40808080));
            } catch (Throwable ignored) {
            }
        } else {
            rv.setViewVisibility(dev.flexy.dynamicisland.R.id.mirror_progress, android.view.View.GONE);
        }
        int[] ids = {dev.flexy.dynamicisland.R.id.mirror_btn0, dev.flexy.dynamicisland.R.id.mirror_btn1, dev.flexy.dynamicisland.R.id.mirror_btn2};
        if (buttons.isEmpty()) {
            rv.setViewVisibility(dev.flexy.dynamicisland.R.id.mirror_buttons, android.view.View.GONE);
        } else {
            for (int i = 0; i < ids.length; i++) {
                if (i >= buttons.size()) {
                    rv.setViewVisibility(ids[i], android.view.View.GONE);
                    continue;
                }
                rv.setTextViewText(ids[i], buttons.get(i).title);
                if (primary != 0) rv.setTextColor(ids[i], primary);
                rv.setOnClickPendingIntent(ids[i], buttons.get(i).intent);
            }
        }
        return rv;
    }

    private static String str(CharSequence cs, CharSequence fallback) {
        if (cs != null && cs.toString().trim().length() > 0) return cs.toString().trim();
        return fallback == null ? null : fallback.toString();
    }

    private Icon toIcon(Drawable d, boolean white) {
        if (d == null) return null;
        try {
            Drawable m = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            m.setBounds(0, 0, ICON_PX, ICON_PX);
            if (white) m.setTint(0xFFFFFFFF);
            m.draw(c);
            return Icon.createWithBitmap(bmp);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Biała latarka: korpus, szyjka i snop światła — SystemUI nie ma stabilnej nazwy zasobu. */
    private Icon torchIcon() {
        if (torchIcon != null) return torchIcon;
        int s = ICON_PX;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        float cx = s / 2f;
        Path head = new Path();
        head.moveTo(cx - s * 0.30f, s * 0.08f);
        head.lineTo(cx + s * 0.30f, s * 0.08f);
        head.lineTo(cx + s * 0.18f, s * 0.36f);
        head.lineTo(cx - s * 0.18f, s * 0.36f);
        head.close();
        c.drawPath(head, p);
        RectF body = new RectF(cx - s * 0.16f, s * 0.40f, cx + s * 0.16f, s * 0.92f);
        c.drawRoundRect(body, s * 0.06f, s * 0.06f, p);
        p.setColor(0xFF000000);
        c.drawCircle(cx, s * 0.52f, s * 0.05f, p);
        torchIcon = Icon.createWithBitmap(bmp);
        return torchIcon;
    }
}
