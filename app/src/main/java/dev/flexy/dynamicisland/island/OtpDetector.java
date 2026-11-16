package dev.flexy.dynamicisland.island;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Szuka kodu weryfikacyjnego w tekście powiadomienia: słowa-wyzwalacze, kandydaci (4–8 cyfr),
 * punktacja wg odległości od wyzwalacza, kary za numer telefonu, cenę, datę i numer zamówienia.
 */
final class OtpDetector {
    private OtpDetector() {
    }

    private static final int MIN_DIGITS = 4;
    private static final int MAX_DIGITS = 8;
    private static final int INTRODUCED_BONUS = 400;
    private static final int CLOSENESS_RANGE = 220;
    private static final int MAX_TRIGGER_DISTANCE = 40;
    private static final int SEPARATED_BONUS = 12;
    private static final int PREFERRED_LENGTH = 8;
    private static final int SHORTER_CODE_BONUS = 3;

    private static final String[] STRONG_TRIGGERS = {
            "otp", "one-time password", "one time password", "verification", "verification code", "verify code",
            "confirmation code", "confirm code", "authentication code", "authorization code", "security code",
            "sign in code", "signin code", "login code", "passcode", "2fa", "auth code", "sms code", "validation code",
            "kod weryfikacyjny", "kod sms", "kod autoryzacyjny", "kod potwierdzenia", "kod potwierdzający",
            "kod logowania", "kod jednorazowy", "jednorazowy kod", "kod dostępu", "kod bezpieczeństwa",
            "kod aktywacyjny", "hasło jednorazowe", "twój kod", "kod to"};

    private static final String NOT_A_CODE_KIND = "(?<!postal )(?<!zip )(?<!area )(?<!country )(?<!promo )(?<!coupon )"
            + "(?<!discount )(?<!referral )(?<!invite )(?<!tracking )(?<!error )(?<!status )(?<!pocztowy )(?<!rabatowy )(?<!promocyjny )";
    private static final String TRIGGER_WORDS =
            "(?:" + NOT_A_CODE_KIND + "\\bcode\\b|\\botp\\b|\\bpasscode\\b|\\bpassword\\b|\\bpin\\b|\\bkod\\b|\\bkodem\\b|\\bhasło\\b)";
    private static final Pattern LOOSE_TRIGGER =
            Pattern.compile(TRIGGER_WORDS + "[^\\d\\n#]{0,12}?\\d(?:[\\s-]?\\d){3,7}(?![\\d*_])", Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> CODE_PATTERNS = new ArrayList<>();

    static {
        CODE_PATTERNS.add(Pattern.compile(TRIGGER_WORDS + "[^\\d\\n|]{0,80}(\\d(?:[\\s-]?\\d){3,7})(?!\\d)", Pattern.CASE_INSENSITIVE));
        CODE_PATTERNS.add(Pattern.compile("(?<![^\\s\\[(<{\"'„:>])(\\d{4,8})(?!\\d)"));
        CODE_PATTERNS.add(Pattern.compile("(?<![^\\s\\[(<{\"'„:>])(\\d(?:[\\s-]?\\d){3,7})(?!\\d)"));
        CODE_PATTERNS.add(Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]{1,3}-(\\d{4,8})(?!\\d)"));
    }

    private static final Pattern MONEY = Pattern.compile(
            "(\\$|€|£|zł|pln|\\b(?:usd|eur|price|total|amount|kwota|cena|saldo|przelew|płatno)\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_SUFFIX = Pattern.compile("^\\s?(?:zł|pln|\\$|€|£|usd|eur)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_PREFIX = Pattern.compile("(?:\\$|€|£|usd|eur|pln)\\s?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(?:19|20)\\d{2}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}[-./]\\d{1,2}[-./](?:19|20)\\d{2}");
    private static final Pattern PHONE_SHAPE = Pattern.compile("(?:0\\d{1,2}|1[5-8]\\d{2})-\\d{3,4}(?:-\\d{4})?|\\d{3}[- ]\\d{3}[- ]\\d{3}");
    private static final String[] ORDER_HINTS = {"order", "delivery", "receipt", "invoice", "transaction", "tracking",
            "zamówien", "dostaw", "paragon", "faktur", "transakcj", "przesyłk", "paczk"};
    private static final Pattern ADDRESS = Pattern.compile(
            "(?:\\b(?:street|suite|avenue|road|zip|postal|floor|apt)\\b|\\bul\\.|konto|rachun|karty)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_MARKER = Pattern.compile(
            "(?:phone|mobile|telephone|tel\\.?|call|whatsapp|zadzwoń|telefon|infolini|kontakt|☎)", Pattern.CASE_INSENSITIVE);

    static final class Match {
        final String code;

        Match(String code) {
            this.code = code;
        }

        /** 482913 -> "482 913": łatwiej przeczytać i przepisać. */
        String display() {
            int n = code.length();
            if (n == 6) return code.substring(0, 3) + " " + code.substring(3);
            if (n == 8) return code.substring(0, 4) + " " + code.substring(4);
            return code;
        }
    }

    private static final class Candidate {
        final String code;
        final int start;
        final int score;

        Candidate(String code, int start, int score) {
            this.code = code;
            this.start = start;
            this.score = score;
        }
    }

    /** Najbardziej prawdopodobny kod w tekście albo null. */
    static Match detect(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean strong = false;
        for (String trigger : STRONG_TRIGGERS) {
            if (lower.contains(trigger)) {
                strong = true;
                break;
            }
        }
        boolean loose = LOOSE_TRIGGER.matcher(lower).find();
        if (!strong && !loose) return null;
        if (!strong && looksLikeOrderContext(lower)) return null;

        List<int[]> triggerRanges = triggerRanges(lower);
        Map<String, Candidate> candidates = new HashMap<>();
        for (int patternIndex = 0; patternIndex < CODE_PATTERNS.size(); patternIndex++) {
            Matcher m = CODE_PATTERNS.get(patternIndex).matcher(text);
            while (m.find()) {
                String raw = m.group(1);
                int start = m.start(1);
                int end = m.end(1);
                int matchStart = m.start();
                StringBuilder digits = new StringBuilder();
                for (int i = 0; i < raw.length(); i++) {
                    if (Character.isDigit(raw.charAt(i))) digits.append(raw.charAt(i));
                }
                if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) continue;
                if (!hasTokenBoundaries(text, start, end)) continue;
                if (patternIndex == 0 ? attachedMoney(lower, start, end) : window(lower, start, end, 18, MONEY)) continue;
                if (dateOrPhone(text, start, end)) continue;
                if (patternIndex != 0 && window(lower, start, end, 24, ADDRESS)) continue;
                if (!strong && looksLikeOrderContext(around(lower, start, end, 24))) continue;
                int score = score(lower, raw, start, end, matchStart, patternIndex, triggerRanges);
                if (score < 0) continue;
                Candidate c = new Candidate(digits.toString(), start, score);
                Candidate previous = candidates.get(c.code);
                if (previous == null || c.score > previous.score) candidates.put(c.code, c);
            }
        }
        Candidate best = null;
        for (Candidate c : candidates.values()) {
            if (best == null || c.score > best.score || (c.score == best.score && c.start < best.start)) best = c;
        }
        if (best == null || best.score <= 0) return null;
        return new Match(best.code);
    }

    private static List<int[]> triggerRanges(String lower) {
        List<int[]> ranges = new ArrayList<>();
        for (String trigger : STRONG_TRIGGERS) {
            int at = lower.indexOf(trigger);
            while (at >= 0) {
                ranges.add(new int[]{at, at + trigger.length() - 1});
                at = lower.indexOf(trigger, at + trigger.length());
            }
        }
        Matcher m = LOOSE_TRIGGER.matcher(lower);
        while (m.find()) ranges.add(new int[]{m.start(), m.end() - 1});
        return ranges;
    }

    private static int score(String lower, String raw, int start, int end, int matchStart, int patternIndex, List<int[]> triggers) {
        int score = patternIndex == 0 ? INTRODUCED_BONUS : 0;
        int nearest = Integer.MAX_VALUE;
        for (int[] r : triggers) {
            int d = end <= r[0] ? r[0] - end : start > r[1] ? start - r[1] - 1 : 0;
            nearest = Math.min(nearest, d);
        }
        if (patternIndex != 0 && nearest > MAX_TRIGGER_DISTANCE) return -1;
        if (nearest != Integer.MAX_VALUE) score += CLOSENESS_RANGE - Math.min(nearest, CLOSENESS_RANGE);
        boolean separated = false;
        int digitCount = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '-' || Character.isWhitespace(c)) separated = true;
            if (Character.isDigit(c)) digitCount++;
        }
        if (separated) score += SEPARATED_BONUS;
        score += (PREFERRED_LENGTH - digitCount) * SHORTER_CODE_BONUS;
        if (likelyPhone(lower, start, end, matchStart, patternIndex)) return -1;
        return score;
    }

    private static boolean likelyPhone(String lower, int start, int end, int matchStart, int patternIndex) {
        String context = patternIndex == 0 ? lower.substring(matchStart, start) : around(lower, start, end, 28);
        boolean marker = PHONE_MARKER.matcher(context).find();
        boolean international = lower.substring(Math.max(0, start - 3), start).indexOf('+') >= 0;
        return marker || international;
    }

    private static boolean attachedMoney(String lower, int start, int end) {
        String after = lower.substring(end, Math.min(lower.length(), end + 5));
        String before = lower.substring(Math.max(0, start - 5), start);
        return MONEY_SUFFIX.matcher(after).find() || MONEY_PREFIX.matcher(before).find();
    }

    private static boolean dateOrPhone(String text, int start, int end) {
        int a = start;
        while (a > 0 && isNumberChar(text, a - 1)) a--;
        int b = end;
        while (b < text.length() && isNumberChar(text, b)) b++;
        String token = text.substring(a, b).replaceAll("^[-./ ]+|[-./ ]+$", "");
        return DATE.matcher(token).matches() || PHONE_SHAPE.matcher(token).matches();
    }

    private static boolean isNumberChar(String text, int i) {
        char c = text.charAt(i);
        if (Character.isDigit(c)) return true;
        return (c == '-' || c == '.' || c == '/') && i > 0 && i + 1 < text.length()
                && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1));
    }

    private static boolean looksLikeOrderContext(String lower) {
        for (String hint : ORDER_HINTS) if (lower.contains(hint)) return true;
        return false;
    }

    private static String around(String text, int start, int end, int radius) {
        return text.substring(Math.max(0, start - radius), Math.min(text.length(), end + radius));
    }

    private static boolean window(String text, int start, int end, int radius, Pattern p) {
        return p.matcher(around(text, start, end, radius)).find();
    }

    private static boolean hasTokenBoundaries(String text, int start, int end) {
        boolean leftOk = start == 0 || (!asciiAlnum(text.charAt(start - 1)) && text.charAt(start - 1) != '#' && text.charAt(start - 1) != '_');
        boolean rightOk = end >= text.length() || (!asciiAlnum(text.charAt(end)) && text.charAt(end) != '_' && text.charAt(end) != '*');
        return leftOk && rightOk;
    }

    private static boolean asciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
