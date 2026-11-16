package dev.flexy.dynamicisland.island;

import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.flexy.dynamicisland.xposed.Refl;
import dev.flexy.dynamicisland.xposed.XLog;

/**
 * "Aparat / mikrofon / lokalizacja w użyciu": zamiast anonimowej kropki w pasku stanu wyspa mówi,
 * która aplikacja używa którego czujnika, a dotknięcie otwiera jej stronę uprawnień.
 */
final class PrivacySource {
    private static final long GRACE_MS = 1500;
    private static final long NOTED_HOLD_MS = 5000;
    private static final int FIRST_APP_UID = 10000;
    private static final int ORANGE = 0xFFFF9F0A;
    private static final int GREEN = 0xFF30D158;
    private static final int BLUE = 0xFF0A84FF;

    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
            "com.samsung.android.incallui", "com.samsung.android.dialer", "com.google.android.dialer",
            "com.android.dialer", "com.android.incallui", "com.samsung.android.bixby.service",
            "com.samsung.android.app.telephonyui", "com.google.android.gms", "com.samsung.android.location",
            "dev.flexy.dynamicisland"));

    private static final class Use {
        final String op;
        final String pkg;
        boolean active;
        long lastActive;
        boolean noted;

        Use(String op, String pkg) {
            this.op = op;
            this.pkg = pkg;
        }
    }

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final Runnable tick = this::refresh;
    private final Map<String, Use> uses = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private IslandItem cached;
    private String cachedSignature = "";

    PrivacySource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
    }

    private void refresh() {
        onChange.run();
    }

    void start() {
        try {
            AppOpsManager appOps = ctx.getSystemService(AppOpsManager.class);
            appOps.startWatchingActive(
                    new String[]{AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO,
                            AppOpsManager.OPSTR_FINE_LOCATION, AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION},
                    ctx.getMainExecutor(), this::onOp);
        } catch (Throwable t) {
            XLog.e("privacy indicator unavailable", t);
        }
        try {
            // usługa lokalizacji nie startuje FINE_LOCATION jako aktywnej operacji, tylko "notuje" każdy fix
            AppOpsManager appOps = ctx.getSystemService(AppOpsManager.class);
            Class<?> listenerType = Class.forName("android.app.AppOpsManager$OnOpNotedListener");
            Object listener = Refl.proxy(listenerType, (proxy, method, args) -> {
                if ("onOpNoted".equals(method.getName()) && args != null && args.length >= 6
                        && args[5] instanceof Integer && (Integer) args[5] == AppOpsManager.MODE_ALLOWED) {
                    final String op = (String) args[0];
                    final int uid = (Integer) args[1];
                    final String pkg = (String) args[2];
                    main.post(() -> noted(op, uid, pkg));
                }
                return null;
            });
            AppOpsManager.class.getMethod("startWatchingNoted", String[].class, listenerType)
                    .invoke(appOps, new String[]{AppOpsManager.OPSTR_FINE_LOCATION}, listener);
        } catch (Throwable t) {
            XLog.e("location access watch unavailable", t);
        }
    }

    private void onOp(String op, int uid, String pkg, boolean active) {
        try {
            record(op, uid, pkg, active);
        } catch (Throwable t) {
            XLog.e("privacy onOp", t);
        }
    }

    private void noted(String op, int uid, String pkg) {
        if (pkg == null || uid < FIRST_APP_UID || IGNORED.contains(pkg)) return;
        String key = op + "|" + pkg;
        Use use = uses.get(key);
        boolean fresh = use == null;
        if (fresh) {
            use = new Use(op, pkg);
            uses.put(key, use);
        }
        use.noted = true;
        use.lastActive = SystemClock.uptimeMillis();
        if (fresh) onChange.run();
        main.removeCallbacks(tick);
        main.postDelayed(tick, NOTED_HOLD_MS + 50);
    }

    private void record(String op, int uid, String pkg, boolean active) {
        if (pkg == null || uid < FIRST_APP_UID || IGNORED.contains(pkg)) return;
        String key = op + "|" + pkg;
        Use use = uses.get(key);
        if (use == null) {
            if (!active) return;
            use = new Use(op, pkg);
            uses.put(key, use);
        }
        use.active = active;
        use.lastActive = SystemClock.uptimeMillis();
        onChange.run();
    }

    void collect(List<IslandItem> out, String foregroundPackage) {
        if (uses.isEmpty() || !IslandSettings.alertPrivacy()) return;
        long now = SystemClock.uptimeMillis();
        boolean location = IslandSettings.privacyLocation();
        boolean cam = false, mic = false, loc = false;
        List<String> packages = new ArrayList<>();
        long next = Long.MAX_VALUE;
        for (Iterator<Use> it = uses.values().iterator(); it.hasNext(); ) {
            Use use = it.next();
            long hold = use.noted ? NOTED_HOLD_MS : GRACE_MS;
            boolean visible = use.active || now - use.lastActive < hold;
            if (!visible) {
                it.remove();
                continue;
            }
            if (!use.active) next = Math.min(next, use.lastActive + hold);
            boolean isLocation = AppOpsManager.OPSTR_FINE_LOCATION.equals(use.op)
                    || AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION.equals(use.op);
            if (isLocation && !location) continue;
            // czujnik używany przez aplikację na ekranie to norma: pokazuj tylko to, co dzieje się w tle
            if (use.pkg.equals(foregroundPackage)) continue;
            cam |= AppOpsManager.OPSTR_CAMERA.equals(use.op);
            mic |= AppOpsManager.OPSTR_RECORD_AUDIO.equals(use.op);
            loc |= isLocation;
            if (!packages.contains(use.pkg)) packages.add(use.pkg);
        }
        if (next != Long.MAX_VALUE) {
            main.removeCallbacks(tick);
            main.postDelayed(tick, Math.max(16L, next - now + 20));
        }
        if (packages.isEmpty() || !(cam || mic || loc)) return;
        for (IslandItem it : out) if (it.kind == IslandItem.Kind.CALL || it.kind == IslandItem.Kind.RECORDING) return;
        out.add(item(cam, mic, loc, packages));
    }

    private IslandItem item(boolean cam, boolean mic, boolean loc, List<String> packages) {
        StringBuilder sig = new StringBuilder().append(cam).append(mic).append(loc);
        for (String p : packages) sig.append('|').append(p);
        String signature = sig.toString();
        if (cached != null && signature.equals(cachedSignature)) return cached;
        IslandItem it = new IslandItem("privacy");
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = packages.get(0);
        it.postTime = Long.MAX_VALUE;
        it.title = cam && mic ? "Camera and microphone in use" : cam ? "Camera in use" : mic ? "Microphone in use" : "Location in use";
        it.accent = cam ? GREEN : mic ? ORANGE : BLUE;
        it.icon = new GlyphDrawable(cam ? GlyphDrawable.CAMERA : mic ? GlyphDrawable.MIC : GlyphDrawable.LOCATION, true);
        it.iconColor = true;
        it.iconTint = it.accent;
        StringBuilder who = new StringBuilder();
        for (int i = 0; i < packages.size() && i < 2; i++) {
            if (i > 0) who.append(", ");
            who.append(label(packages.get(i)));
        }
        if (packages.size() > 2) who.append(" +").append(packages.size() - 2);
        it.subtitle = who.toString();
        it.appName = label(packages.get(0));
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = packages.size() == 1 ? label(packages.get(0)) : String.valueOf(packages.size());
        it.trailingColor = it.accent;
        it.contentIntent = settingsIntent(packages.get(0));
        cached = it;
        cachedSignature = signature;
        return it;
    }

    private PendingIntent settingsIntent(String pkg) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Throwable t) {
            return null;
        }
    }

    private String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        l = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            l = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
        }
        labels.put(pkg, l);
        return l;
    }
}
