package com.ziprun.service.ai;

/**
 * Pulls the first "reasoning" string value out of a JSON reply while it is
 * still arriving, so the SSE stream can show ops the explanation as prose
 * instead of raw JSON.
 *
 * feed() returns only the newly decoded characters. Decoding stops before an
 * incomplete escape sequence, so what has been emitted is always a stable
 * prefix of the final value.
 */
final class ReasoningExtractor {

    private static final String KEY = "\"reasoning\"";

    private final StringBuilder raw = new StringBuilder();
    private int emitted;

    /** @return the newly available reasoning text (possibly empty) */
    String feed(String chunk) {
        raw.append(chunk);
        String decoded = extract(raw);
        if (decoded.length() <= emitted) {
            return "";
        }
        String delta = decoded.substring(emitted);
        emitted = decoded.length();
        return delta;
    }

    void reset() {
        raw.setLength(0);
        emitted = 0;
    }

    static String extract(CharSequence s) {
        int keyAt = s.toString().indexOf(KEY);
        if (keyAt < 0) {
            return "";
        }
        int i = skipWhitespace(s, keyAt + KEY.length());
        if (i >= s.length() || s.charAt(i) != ':') {
            return "";
        }
        i = skipWhitespace(s, i + 1);
        if (i >= s.length() || s.charAt(i) != '"') {
            return "";
        }
        i++;

        StringBuilder out = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                break; // end of value
            }
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= s.length()) {
                break; // escape not complete yet
            }
            char e = s.charAt(i + 1);
            if (e == 'u') {
                if (i + 6 > s.length()) {
                    break;
                }
                try {
                    out.append((char) Integer.parseInt(s.subSequence(i + 2, i + 6).toString(), 16));
                } catch (NumberFormatException bad) {
                    // malformed escape: show nothing for it
                }
                i += 6;
                continue;
            }
            out.append(switch (e) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                default -> e; // \" \\ \/
            });
            i += 2;
        }
        return out.toString();
    }

    private static int skipWhitespace(CharSequence s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
