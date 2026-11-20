package dev.ronova.docforge.print;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Разбор строки IEEE 1284 Device ID, которую USB-принтер отдаёт по запросу GET_DEVICE_ID, например
 * {@code MFG:Hewlett-Packard;MDL:HP LaserJet 1018;CMD:ACL;CLS:PRINTER;}.
 * Чистая Java: проверяется обычными тестами на JVM.
 */
public final class Ieee1284 {
    private final Map<String, String> fields;

    private Ieee1284(Map<String, String> fields) {
        this.fields = fields;
    }

    public static Ieee1284 empty() {
        return new Ieee1284(Collections.<String, String>emptyMap());
    }

    /** Разбирает сырой ответ принтера: первые два байта — длина (обычно big-endian, у части принтеров little-endian). */
    public static Ieee1284 parse(byte[] raw, int length) {
        if (raw == null || length <= 0) return empty();
        int len = Math.min(length, raw.length);
        int start = 0;
        int end = len;
        if (len >= 2) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            boolean looksLikeText = first >= 0x41 && second >= 0x20 && first < 0x7F && second < 0x7F;
            if (!looksLikeText) {
                int big = (first << 8) | second;
                int little = (second << 8) | first;
                int declared = (big >= 2 && big <= len) ? big : ((little >= 2 && little <= len) ? little : len);
                start = 2;
                end = Math.min(len, declared);
            }
        }
        if (end < start) end = start;
        return parseString(new String(raw, start, end - start, StandardCharsets.ISO_8859_1));
    }

    public static Ieee1284 parseString(String text) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (text == null) return empty();
        for (String part : text.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String key = part.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            String value = part.substring(colon + 1).trim().replace("\0", "");
            if (!key.isEmpty() && !map.containsKey(key)) map.put(key, value);
        }
        return new Ieee1284(map);
    }

    private String first(String... keys) {
        for (String k : keys) {
            String v = fields.get(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public String manufacturer() {
        return first("MFG", "MANUFACTURER");
    }

    public String model() {
        return first("MDL", "MODEL");
    }

    public String description() {
        return first("DES", "DESCRIPTION");
    }

    /** Языки принтера из поля CMD / COMMAND SET, в верхнем регистре: PCL, PJL, POSTSCRIPT, ZJS… */
    public Set<String> commands() {
        Set<String> out = new LinkedHashSet<String>();
        String list = first("CMD", "COMMAND SET", "COMMANDSET");
        for (String s : list.split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Название для списка: «Производитель Модель», иначе описание. */
    public String displayName() {
        String mfg = manufacturer();
        String mdl = model();
        if (!mdl.isEmpty()) {
            if (!mfg.isEmpty() && !mdl.toLowerCase(Locale.ROOT).startsWith(mfg.toLowerCase(Locale.ROOT))) {
                return mfg + " " + mdl;
            }
            return mdl;
        }
        return description();
    }
}
