package dev.flexy.dynamicisland.xposed;

import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Chroni przed zapętlonym crashem SystemUI (rescue party Androida, potem tryb awaryjny LSPosed).
 *
 * Moduł wyłącza się na godzinę, gdy:
 *  - 3 starty SystemUI z rzędu nie dożyły 45 s (pętla na starcie, niezależnie od przyczyny),
 *  - 3 crashe w ciągu 10 min miały w stosie ten moduł.
 * Nieobsłużony wyjątek zapisywany jest do raportu, który przy następnym starcie trafia do logu
 * LSPosed (adb logcat / "DynamicIsland: crash guard"). Stan trzymany w device-protected storage,
 * bo SystemUI startuje przed pierwszym odblokowaniem.
 */
public final class CrashGuard {
    /** Podbij razem z wersją: nowy build dostaje czystą szansę. */
    static final String BUILD = "v30";

    private static final long STABLE_AFTER_MS = 45_000;
    private static final int MAX_UNSTABLE_STARTS = 3;
    private static final int MAX_OUR_CRASHES = 3;
    private static final long OUR_CRASH_WINDOW_MS = 10 * 60_000;
    private static final long OFF_EXPIRES_MS = 3_600_000L;
    private static final String STATE = "dynamicisland-guard.txt";
    private static final String REPORT = "dynamicisland-crash.txt";
    private static final String OUR_PACKAGE = "dev.flexy.dynamicisland";

    private static File dir;

    private CrashGuard() {
    }

    /** @return false, gdy moduł ma nie ładować się w tym procesie SystemUI. */
    static boolean enter(ApplicationInfo app) {
        try {
            String base = app.deviceProtectedDataDir != null ? app.deviceProtectedDataDir : app.dataDir;
            dir = new File(base, "files");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            replayPreviousCrash();
            State st = State.load();
            if (st.unstable >= MAX_UNSTABLE_STARTS) {
                XLog.i("crash guard: " + st.unstable + " unstable SystemUI starts in a row, module stays off for an hour");
                return false;
            }
            if (st.ourCrashes >= MAX_OUR_CRASHES) {
                XLog.i("crash guard: SystemUI crashed " + st.ourCrashes + " times inside the module within "
                        + (OUR_CRASH_WINDOW_MS / 60_000) + " min, module stays off for an hour");
                return false;
            }
            st.unstable++;
            st.lastUnstable = System.currentTimeMillis();
            st.save();
            watchForCrashes();
            new Handler(Looper.getMainLooper()).postDelayed(CrashGuard::markStable, STABLE_AFTER_MS);
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static final class State {
        int unstable;
        int ourCrashes;
        long firstOurCrash;
        long lastUnstable;

        static State load() {
            State st = new State();
            String[] lines = read(STATE);
            if (lines != null && lines.length >= 6 && BUILD.equals(lines[0])) {
                st.unstable = parseInt(lines[1]);
                st.ourCrashes = parseInt(lines[2]);
                st.firstOurCrash = parseLong(lines[3]);
                st.lastUnstable = parseLong(lines[4]);
                if (!fingerprint().equals(lines[5].trim())) st.reset();
            }
            long now = System.currentTimeMillis();
            if (st.unstable >= MAX_UNSTABLE_STARTS && now - st.lastUnstable > OFF_EXPIRES_MS) st.reset();
            if (st.ourCrashes >= MAX_OUR_CRASHES && now - st.firstOurCrash > OFF_EXPIRES_MS) st.reset();
            if (st.ourCrashes > 0 && st.ourCrashes < MAX_OUR_CRASHES && now - st.firstOurCrash > OUR_CRASH_WINDOW_MS) {
                st.ourCrashes = 0;
                st.firstOurCrash = 0;
            }
            return st;
        }

        void reset() {
            unstable = 0;
            ourCrashes = 0;
            firstOurCrash = 0;
        }

        void save() throws java.io.IOException {
            write(STATE, BUILD + "\n" + unstable + "\n" + ourCrashes + "\n" + firstOurCrash + "\n" + lastUnstable
                    + "\n" + fingerprint());
        }
    }

    private static void markStable() {
        try {
            State st = State.load();
            st.unstable = 0;
            st.save();
            XLog.i("crash guard: SystemUI stable, counter reset");
        } catch (Throwable ignored) {
        }
    }

    private static void watchForCrashes() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                boolean ours = sw.toString().contains(OUR_PACKAGE);
                write(REPORT, "build " + BUILD + " thread " + thread.getName() + " uptime " + SystemClock.uptimeMillis()
                        + "ms module-involved=" + ours + "\n" + sw);
                if (ours) {
                    State st = State.load();
                    if (st.ourCrashes == 0) st.firstOurCrash = System.currentTimeMillis();
                    st.ourCrashes++;
                    st.save();
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private static void replayPreviousCrash() {
        String[] report = read(REPORT);
        if (report == null) return;
        StringBuilder sb = new StringBuilder("crash guard: SystemUI crashed during the previous run:\n");
        for (String line : report) sb.append(line).append('\n');
        XLog.i(sb.toString());
        //noinspection ResultOfMethodCallIgnored
        new File(dir, REPORT).delete();
    }

    private static String fingerprint() {
        String f = android.os.Build.FINGERPRINT;
        return f == null ? "" : f.replace('\n', ' ');
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String[] read(String name) {
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(data, StandardCharsets.UTF_8).split("\n");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(String name, String content) throws java.io.IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
