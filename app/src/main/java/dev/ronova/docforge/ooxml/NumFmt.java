package dev.ronova.docforge.ooxml;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Форматирование числа по коду формата Excel (числа, проценты, даты, время, валюта в первом приближении). */
final class NumFmt {
    private NumFmt() {
    }

    static String builtin(int id) {
        switch (id) {
            case 1: return "0";
            case 2: return "0.00";
            case 3: return "#,##0";
            case 4: return "#,##0.00";
            case 9: return "0%";
            case 10: return "0.00%";
            case 11: return "0.00E+00";
            case 14: return "dd.mm.yyyy";
            case 15: return "d-mmm-yy";
            case 16: return "d-mmm";
            case 17: return "mmm-yy";
            case 18: return "h:mm AM/PM";
            case 19: return "h:mm:ss AM/PM";
            case 20: return "h:mm";
            case 21: return "h:mm:ss";
            case 22: return "dd.mm.yyyy h:mm";
            case 37: return "#,##0 ;(#,##0)";
            case 38: return "#,##0 ;[Red](#,##0)";
            case 39: return "#,##0.00;(#,##0.00)";
            case 40: return "#,##0.00;[Red](#,##0.00)";
            case 45: return "mm:ss";
            case 46: return "[h]:mm:ss";
            case 47: return "mm:ss.0";
            case 49: return "@";
            default: return "General";
        }
    }

    /** Основной формат «Общий»: до 10 значащих цифр, без хвостовых нулей. */
    static String general(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        BigDecimal bd = new BigDecimal(v).round(new MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros();
        String s = bd.abs().compareTo(new BigDecimal("1e11")) >= 0 || bd.abs().compareTo(new BigDecimal("1e-5")) < 0
                ? String.format(Locale.ROOT, "%.5E", v)
                : bd.toPlainString();
        return s;
    }

    static String format(double v, String code, boolean date1904) {
        if (code == null || code.isEmpty() || "General".equalsIgnoreCase(code)) {
            return general(v);
        }
        String[] sections = splitSections(code);
        String section = sections[0];
        double value = v;
        if (v < 0 && sections.length > 1) {
            section = sections[1];
            value = -v;
        } else if (v == 0 && sections.length > 2) {
            section = sections[2];
        }
        if ("@".equals(section.trim())) {
            return general(v);
        }
        if (isDate(section)) {
            return formatDate(v, section, date1904);
        }
        return formatNumber(value, section, v < 0 && sections.length == 1);
    }

    private static String[] splitSections(String code) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ';' && !quoted) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    /** Код без кавычек, экранированных символов и [скобок] — для распознавания типа. */
    private static String stripped(String code) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        boolean bracket = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quoted) {
                if (c == '"') quoted = false;
            } else if (bracket) {
                if (c == ']') bracket = false;
            } else if (c == '"') {
                quoted = true;
            } else if (c == '[') {
                bracket = true;
            } else if (c == '\\' || c == '_' || c == '*') {
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isDate(String section) {
        String s = stripped(section);
        if (s.contains("general")) {
            return false;
        }
        return s.contains("y") || s.contains("d") || s.contains("h") || s.contains("s") || s.contains("mmm");
    }

    // ------------------------------------------------------------------ числа

    private static String formatNumber(double value, String section, boolean negativeNeedsSign) {
        // литералы до первого и после последнего плейсхолдера (валюта, скобки, пробелы)
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();
        StringBuilder numberPattern = new StringBuilder();
        boolean seenPlaceholder = false;
        boolean quoted = false;
        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);
            if (quoted) {
                if (c == '"') {
                    quoted = false;
                } else {
                    (seenPlaceholder ? suffix : prefix).append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '\\' && i + 1 < section.length()) {
                (seenPlaceholder ? suffix : prefix).append(section.charAt(++i));
            } else if (c == '_' && i + 1 < section.length()) {
                i++;
                (seenPlaceholder ? suffix : prefix).append(' ');
            } else if (c == '*' && i + 1 < section.length()) {
                i++;
            } else if (c == '[') {
                int end = section.indexOf(']', i);
                if (end < 0) end = section.length() - 1;
                String inner = section.substring(i + 1, end);
                if (inner.startsWith("$")) {
                    int dash = inner.indexOf('-');
                    String symbol = dash > 1 ? inner.substring(1, dash) : inner.substring(1);
                    (seenPlaceholder ? suffix : prefix).append(symbol);
                }
                i = end;
            } else if ("0#?.,%Ee+-".indexOf(c) >= 0 && !(numberPattern.length() == 0 && (c == '-' || c == '+' || c == 'E' || c == 'e' || c == ','))) {
                numberPattern.append(c);
                seenPlaceholder = true;
            } else if (c == '/' || c == '(' || c == ')' || c == ' ' || c == '$' || c == '\u20AC' || c == '\u00A3'
                    || c == '\u20BD' || c == '\u00A5' || Character.isLetter(c)) {
                (seenPlaceholder ? suffix : prefix).append(c);
            } else {
                (seenPlaceholder ? suffix : prefix).append(c);
            }
        }
        String pattern = numberPattern.toString();
        if (pattern.isEmpty()) {
            return general(value);
        }
        boolean percent = pattern.indexOf('%') >= 0;
        boolean scientific = pattern.toUpperCase(Locale.ROOT).contains("E+") || pattern.toUpperCase(Locale.ROOT).contains("E-");
        String core = pattern.replace("%", "");
        double v = percent ? value * 100 : value;

        String body;
        if (scientific) {
            int mantissaDecimals = Math.max(0, core.indexOf('E') - core.indexOf('.') - 1);
            if (core.indexOf('.') < 0) mantissaDecimals = 0;
            body = String.format(Locale.ROOT, "%." + mantissaDecimals + "E", v).replace("E+0", "E+").replace("E-0", "E-");
        } else {
            int dot = core.indexOf('.');
            String intPart = dot < 0 ? core : core.substring(0, dot);
            String fracPart = dot < 0 ? "" : core.substring(dot + 1);
            boolean grouping = intPart.indexOf(',') >= 0;
            int minFrac = 0;
            for (int i = 0; i < fracPart.length(); i++) {
                if (fracPart.charAt(i) == '0') minFrac++;
            }
            int maxFrac = 0;
            for (int i = 0; i < fracPart.length(); i++) {
                char c = fracPart.charAt(i);
                if (c == '0' || c == '#' || c == '?') maxFrac++;
            }
            int minInt = 0;
            for (int i = 0; i < intPart.length(); i++) {
                if (intPart.charAt(i) == '0') minInt++;
            }
            BigDecimal bd = new BigDecimal(Double.toString(Math.abs(v))).setScale(maxFrac, RoundingMode.HALF_UP);
            String plain = bd.toPlainString();
            String ip = plain.indexOf('.') < 0 ? plain : plain.substring(0, plain.indexOf('.'));
            String fp = plain.indexOf('.') < 0 ? "" : plain.substring(plain.indexOf('.') + 1);
            while (fp.length() > minFrac && fp.endsWith("0")) {
                fp = fp.substring(0, fp.length() - 1);
            }
            while (ip.length() < minInt) {
                ip = "0" + ip;
            }
            if (ip.equals("0") && minInt == 0 && !fp.isEmpty()) {
                ip = "";
            }
            if (grouping) {
                StringBuilder g = new StringBuilder();
                for (int i = 0; i < ip.length(); i++) {
                    if (i > 0 && (ip.length() - i) % 3 == 0) g.append('\u00A0');
                    g.append(ip.charAt(i));
                }
                ip = g.toString();
            }
            body = fp.isEmpty() ? ip : ip + "." + fp;
            if (body.isEmpty()) {
                body = "0";
            }
            if (v < 0 && !(bd.signum() == 0)) {
                body = "-" + body;
            }
        }
        if (percent) {
            body += "%";
        }
        if (negativeNeedsSign && value < 0 && !body.startsWith("-")) {
            body = "-" + body;
        }
        return prefix + body + suffix;
    }

    // ------------------------------------------------------------------ даты

    private static String formatDate(double serial, String section, boolean date1904) {
        LocalDateTime base = date1904 ? LocalDateTime.of(1904, 1, 1, 0, 0) : LocalDateTime.of(1899, 12, 30, 0, 0);
        long days = (long) Math.floor(serial);
        long seconds = Math.round((serial - days) * 86400.0);
        LocalDateTime dt = base.plusDays(days).plusSeconds(seconds);
        String pattern = datePattern(section);
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(dt);
        } catch (RuntimeException e) {
            return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(dt);
        }
    }

    /** Код даты Excel -> шаблон java.time (m после h или перед s — минуты, иначе месяц). */
    private static String datePattern(String section) {
        String s = section;
        boolean ampm = s.toLowerCase(Locale.ROOT).contains("am/pm") || s.toLowerCase(Locale.ROOT).contains("a/p");
        StringBuilder out = new StringBuilder();
        int i = 0;
        char lastTimeUnit = 0; // 'h' или 's' — для различения минут и месяцев
        while (i < s.length()) {
            char c = s.charAt(i);
            char lc = Character.toLowerCase(c);
            if (c == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) end = s.length();
                appendLiteral(out, s.substring(i + 1, Math.min(end, s.length())));
                i = end + 1;
            } else if (c == '[') {
                int end = s.indexOf(']', i);
                if (end < 0) end = s.length() - 1;
                String inner = s.substring(i + 1, end).toLowerCase(Locale.ROOT);
                if (inner.startsWith("h")) {
                    lastTimeUnit = 'h';
                    out.append("HH");
                } else if (inner.startsWith("m")) {
                    out.append("mm");
                } else if (inner.startsWith("s")) {
                    out.append("ss");
                }
                i = end + 1;
            } else if (c == '\\' && i + 1 < s.length()) {
                appendLiteral(out, String.valueOf(s.charAt(i + 1)));
                i += 2;
            } else if (c == '_' || c == '*') {
                i += 2;
            } else if (lc == 'y') {
                int n = run(s, i, 'y');
                out.append(n >= 3 ? "yyyy" : "yy");
                i += n;
            } else if (lc == 'd') {
                int n = run(s, i, 'd');
                out.append(n >= 4 ? "EEEE" : n == 3 ? "EEE" : n == 2 ? "dd" : "d");
                i += n;
            } else if (lc == 'h') {
                int n = run(s, i, 'h');
                out.append(ampm ? (n >= 2 ? "hh" : "h") : (n >= 2 ? "HH" : "H"));
                lastTimeUnit = 'h';
                i += n;
            } else if (lc == 's') {
                int n = run(s, i, 's');
                out.append(n >= 2 ? "ss" : "s");
                lastTimeUnit = 's';
                i += n;
            } else if (lc == 'm') {
                int n = run(s, i, 'm');
                boolean minutes = lastTimeUnit == 'h' || nextTimeUnit(s, i + n) == 's';
                if (minutes) {
                    out.append(n >= 2 ? "mm" : "m");
                } else {
                    out.append(n >= 5 ? "MMMMM" : n == 4 ? "MMMM" : n == 3 ? "MMM" : n == 2 ? "MM" : "M");
                }
                i += n;
            } else if (lc == 'a' && s.regionMatches(true, i, "am/pm", 0, 5)) {
                out.append("a");
                i += 5;
            } else if (lc == 'a' && s.regionMatches(true, i, "a/p", 0, 3)) {
                out.append("a");
                i += 3;
            } else if (c == '.' && i + 1 < s.length() && s.charAt(i + 1) == '0') {
                int n = 1;
                while (i + n < s.length() && s.charAt(i + n) == '0') n++;
                out.append('.');
                for (int k = 1; k < n; k++) out.append('S');
                i += n;
            } else if (Character.isLetter(c)) {
                appendLiteral(out, String.valueOf(c));
                i++;
            } else {
                out.append(c == '\'' ? "''" : String.valueOf(c));
                i++;
            }
        }
        return out.toString();
    }

    private static char nextTimeUnit(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char lc = Character.toLowerCase(s.charAt(i));
            if (lc == 's') return 's';
            if (lc == 'd' || lc == 'y' || lc == 'h' || lc == 'm') return lc;
        }
        return 0;
    }

    private static int run(String s, int i, char lower) {
        int n = 0;
        while (i + n < s.length() && Character.toLowerCase(s.charAt(i + n)) == lower) {
            n++;
        }
        return n;
    }

    private static void appendLiteral(StringBuilder out, String text) {
        if (text.isEmpty()) {
            return;
        }
        out.append('\'').append(text.replace("'", "''")).append('\'');
    }
}
