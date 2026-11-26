package dev.flexy.dynamicisland.island;

import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.flexy.dynamicisland.xposed.XLog;

/**
 * Zsynchronizowany tekst piosenki z lrclib.net (jak Droidbubble): jedna linia na karcie muzyki,
 * dobierana do pozycji odtwarzania. Pobierane raz na utwór, w tle; brak tekstu też jest zapamiętany.
 */
final class Lyrics {
    static final class Line {
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static final List<Line> NONE = Collections.emptyList();
    private static final LruCache<String, List<Line>> CACHE = new LruCache<>(50);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IslandLyrics");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern TITLE_JUNK = Pattern.compile(
            "\\s*[\\(\\[](?:Official|Lyric|Audio|Music|Video|Visualizer|HD|HQ|4K|Remaster(?:ed)?|feat\\.|ft\\.)[^\\)\\]]*[\\)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static volatile String pending;
    static Runnable onLoaded;

    private Lyrics() {
    }

    static String key(CharSequence title, CharSequence artist) {
        String t = cleanTitle(title);
        if (t.isEmpty()) return null;
        return t.toLowerCase(Locale.ROOT) + "::" + cleanArtist(artist).toLowerCase(Locale.ROOT);
    }

    /** Linie dla utworu, jeśli już pobrane; w przeciwnym razie uruchamia pobieranie i zwraca pustą listę. */
    static List<Line> get(IslandItem it) {
        if (it == null || !it.isMedia || !IslandSettings.lyrics()) return NONE;
        final String key = key(it.title, it.subtitle);
        if (key == null) return NONE;
        List<Line> cached = CACHE.get(key);
        if (cached != null) return cached;
        if (key.equals(pending)) return NONE;
        pending = key;
        final String title = cleanTitle(it.title);
        final String artist = cleanArtist(it.subtitle);
        final long duration = it.mediaDuration;
        IO.execute(() -> {
            List<Line> lines = NONE;
            try {
                lines = fetch(title, artist, duration);
            } catch (Throwable t) {
                XLog.e("lyrics fetch", t);
            }
            CACHE.put(key, lines);
            if (key.equals(pending)) pending = null;
            XLog.i("lyrics " + key + ": " + lines.size() + " lines");
            if (!lines.isEmpty() && onLoaded != null) MAIN.post(onLoaded);
        });
        return NONE;
    }

    /** Aktywna linia dla pozycji (z wyprzedzeniem 120 ms) albo null. */
    static Line current(List<Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return null;
        long pos = Math.max(0, positionMs + 120);
        int lo = 0, hi = lines.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= pos) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best >= 0) return lines.get(best);
        Line first = lines.get(0);
        return first.timeMs - pos <= 3500 ? first : null;
    }

    private static String cleanTitle(CharSequence raw) {
        if (raw == null) return "";
        return TITLE_JUNK.matcher(raw.toString()).replaceAll("").trim();
    }

    private static String cleanArtist(CharSequence raw) {
        if (raw == null) return "";
        String s = raw.toString().replaceAll("(?i)\\s*-\\s*Topic$", "");
        String[] parts = s.split(",|;|&| feat\\.| ft\\.| Featuring ");
        return (parts.length > 0 ? parts[0] : s).trim();
    }

    private static List<Line> fetch(String title, String artist, long durationMs) throws Exception {
        boolean hasArtist = !artist.isEmpty() && !artist.equalsIgnoreCase("Unknown Artist");
        if (hasArtist) {
            String url = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(title, "UTF-8")
                    + "&artist_name=" + URLEncoder.encode(artist, "UTF-8")
                    + (durationMs > 2000 ? "&duration=" + (durationMs / 1000) : "");
            String body = httpGet(url);
            if (body != null) {
                List<Line> parsed = parse(new JSONObject(body).optString("syncedLyrics", ""));
                if (!parsed.isEmpty()) return parsed;
            }
        }
        String q = hasArtist ? title + " " + artist : title;
        String body = httpGet("https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8"));
        if (body == null) return NONE;
        JSONArray arr = new JSONArray(body);
        List<Line> best = NONE;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            List<Line> parsed = parse(o.optString("syncedLyrics", ""));
            if (parsed.isEmpty()) continue;
            if (durationMs <= 2000) return parsed;
            long diff = Math.abs((long) (o.optDouble("duration", 0) * 1000) - durationMs);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = parsed;
            }
        }
        return best;
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "OneUisland/1.0 (https://github.com/Them4112/OneUisland)");
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static List<Line> parse(String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) return NONE;
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = STAMP.matcher(line);
            List<long[]> stamps = new ArrayList<>();
            int textStart = 0;
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String frac = m.group(3) == null ? "" : m.group(3);
                long ms = frac.length() == 1 ? Long.parseLong(frac) * 100 : frac.length() == 2 ? Long.parseLong(frac) * 10
                        : frac.length() == 3 ? Long.parseLong(frac) : 0;
                stamps.add(new long[]{min * 60_000 + sec * 1000 + ms});
                textStart = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = line.substring(textStart).replaceAll("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>", "").trim();
            if (text.isEmpty()) continue;
            for (long[] s : stamps) out.add(new Line(s[0], text));
        }
        out.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out.isEmpty() ? NONE : out;
    }
}
