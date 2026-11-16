package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.os.Bundle;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Postęp zapisany tylko w tekście powiadomienia ("Downloading update 73%", "12 MB / 40 MB"),
 * bez pól progress. Liczba musi mieć w pobliżu słowo o postępie i żadnego o zniżce/pogodzie/baterii.
 */
final class TextProgress {
    private TextProgress() {
    }

    private static final int WINDOW = 80;
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d.,])(\\d{1,3}(?:[.,]\\d+)?)\\s*%");
    private static final Pattern INCLUDE = Pattern.compile(
            "(remaining|progress|completed|completing|downloading|uploading|updating|installing|sync(?:ing)?|"
                    + "processing|processed|render(?:ing)?|encoding|decoding|copying|transferring|restoring|"
                    + "pobieran|pobran|wysyłan|aktualiz|instal|synchroniz|przetwarzan|kopiowan|przesyłan|pozostał)");
    private static final Pattern EXCLUDE = Pattern.compile(
            "(discount|promo|coupon|sale|cashback|off\\b|bonus|save|deal|special\\s+offer|limited\\s+time|"
                    + "humidity|weather|temperature|pressure|precip|commission|fee\\b|tax\\b|apr\\b|battery|charging|"
                    + "zniżk|rabat|kupon|promocj|wilgotno|pogod|temperatur|bateri|ładowan)");
    private static final Pattern SIZES = Pattern.compile(
            "(?<![\\d.,])(\\d{1,6}(?:[.,]\\d+)?)\\s*([kmgt]?)i?b?\\s*/\\s*(\\d{1,6}(?:[.,]\\d+)?)\\s*([kmgt])i?b?(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    /** Wszystkie teksty powiadomienia w jednym ciągu. */
    static String joined(Notification n) {
        Bundle e = n.extras;
        Set<String> parts = new LinkedHashSet<>();
        add(parts, e.getCharSequence(Notification.EXTRA_TITLE));
        add(parts, e.getCharSequence(Notification.EXTRA_TEXT));
        add(parts, e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        add(parts, e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        add(parts, e.getCharSequence(Notification.EXTRA_INFO_TEXT));
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence l : lines) add(parts, l);
        return String.join(" ", parts);
    }

    private static void add(Set<String> out, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().replaceAll("\\s+", " ").trim();
        if (!s.isEmpty()) out.add(s);
    }

    /** @return 0..100 albo -1, gdy tekst nie niesie postępu. */
    static int detect(String text) {
        if (text == null || text.isEmpty()) return -1;
        int bySize = detectSizes(text);
        if (bySize >= 0) return bySize;
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher m = PERCENT.matcher(text);
        while (m.find()) {
            int value;
            try {
                value = Math.round(Float.parseFloat(m.group(1).replace(',', '.')));
            } catch (NumberFormatException e) {
                continue;
            }
            if (value < 0 || value > 100) continue;
            String ctx = lower.substring(Math.max(0, m.start() - WINDOW), Math.min(lower.length(), m.end() + WINDOW));
            if (!INCLUDE.matcher(ctx).find() || EXCLUDE.matcher(ctx).find()) continue;
            return value;
        }
        return -1;
    }

    private static int detectSizes(String text) {
        Matcher m = SIZES.matcher(text);
        while (m.find()) {
            double done = Double.parseDouble(m.group(1).replace(',', '.')) * unit(m.group(2));
            double total = Double.parseDouble(m.group(3).replace(',', '.')) * unit(m.group(4));
            if (total <= 0 || done > total) continue;
            String ctx = text.substring(Math.max(0, m.start() - WINDOW), Math.min(text.length(), m.end() + WINDOW))
                    .toLowerCase(Locale.ROOT);
            if (!INCLUDE.matcher(ctx).find() || EXCLUDE.matcher(ctx).find()) continue;
            return (int) Math.round(100.0 * done / total);
        }
        return -1;
    }

    private static double unit(String prefix) {
        switch (prefix.toLowerCase(Locale.ROOT)) {
            case "k": return 1024.0;
            case "m": return 1024.0 * 1024.0;
            case "g": return 1024.0 * 1024.0 * 1024.0;
            case "t": return 1024.0 * 1024.0 * 1024.0 * 1024.0;
            default: return 1.0;
        }
    }
}
