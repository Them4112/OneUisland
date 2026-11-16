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

    private Notification build(String slot, int id, IslandItem it) {
        boolean torch = it.torch;
        String title = torch ? "Flashlight" : str(it.title, it.appName);
        String primary = title;
        String secondary;
        if (torch) {
            secondary = it.torchMax > 1 ? "On  ·  Level " + it.torchLevel + "/" + it.torchMax : "On";
        } else {
            String sub = str(it.subtitle, null);
            String detail = str(it.detail, null);
            if (detail != null && sub != null) secondary = detail + "  ·  " + sub;
            else secondary = detail != null ? detail : sub != null ? sub : str(it.appName, "");
        }
        Icon small = torch ? torchIcon() : toIcon(it.icon, !it.iconIsImage);
        if (small == null) small = Icon.createWithResource(ctx, android.R.drawable.stat_notify_sync);
        Icon large = toIcon(it.cardIcon != null ? it.cardIcon : it.cardImage != null ? it.cardImage : it.icon, false);
        if (large == null) large = small;
        int accent = it.accent == 0 ? 0xFFFFFFFF : it.accent;

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(small)
                .setLargeIcon(large)
                .setContentTitle(title)
                .setContentText(secondary)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(accent)
                .setColorized(false);
        if (it.contentIntent != null) b.setContentIntent(it.contentIntent);
        if (!torch && it.progress >= 0) b.setProgress(100, Math.round(Math.max(0f, Math.min(1f, it.progress)) * 100f), false);

        List<IslandItem.Action> actions = new ArrayList<>();
        if (torch) {
            if (it.torchToggle != null) actions.add(new IslandItem.Action("Turn off", null, null, it.torchToggle, true));
        } else {
            actions.addAll(it.actions);
        }
        int slotBase = id * 8;
        for (int i = 0; i < actions.size() && i < 3; i++) {
            IslandItem.Action a = actions.get(i);
            PendingIntent pi = a.intent;
            if (pi == null && a.runnable != null) {
                int slotId = slotBase + i;
                runnables.put(slotId, a.runnable);
                Intent in = new Intent(ACTION).setPackage(ctx.getPackageName()).putExtra("slot", slotId);
                pi = PendingIntent.getBroadcast(ctx, slotId, in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            }
            if (pi == null || a.title == null) continue;
            Icon ai = toIcon(a.icon, true);
            if (ai == null) ai = small;
            b.addAction(new Notification.Action.Builder(ai, a.title, pi).build());
        }

        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(b, true);
        } catch (Throwable ignored) {
        }
        Bundle extras = new Bundle();
        extras.putString("android.substName", str(it.appName, title));
        extras.putInt(OA + "style", 1);
        extras.putCharSequence(OA + "primaryInfo", primary);
        extras.putCharSequence(OA + "secondaryInfo", secondary);
        extras.putCharSequence(OA + "chipExpandedText", torch ? "Flashlight" : primary);
        extras.putCharSequence(OA + "nowbarPrimaryInfo", primary);
        extras.putCharSequence(OA + "nowbarSecondaryInfo", secondary);
        extras.putInt(OA + "chipBgColor", 0xFF000000);
        extras.putBoolean("android.showSmallIcon", true);
        extras.putParcelable(OA + "chipIcon", small);
        extras.putParcelable(OA + "nowbarIcon", large);
        extras.putParcelable(OA + "firstIcon", small);
        extras.putParcelable(OA + "secondaryInfoIcon", small);
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
