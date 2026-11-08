package dev.ronova.docforge.xml;

import java.util.List;
import org.w3c.dom.Element;

/**
 * Правка текста абзаца с сохранением форматирования.
 *
 * Абзац состоит из «ранов» (w:t, a:t) с разным оформлением. Мы находим, какая часть текста
 * изменилась (общее начало и общий конец отбрасываются), и правим только её: нетронутые раны остаются
 * как были, вставленный текст получает оформление рана, в котором стоит курсор.
 */
public final class TextSplice {
    private TextSplice() {
    }

    public static String join(List<Element> runs) {
        StringBuilder sb = new StringBuilder();
        for (Element e : runs) {
            sb.append(e.getTextContent());
        }
        return sb.toString();
    }

    /**
     * Меняет тексты ранов так, чтобы их склейка стала равна newText.
     * Возвращает false, если ранов нет (тогда вызывающий должен создать ран сам).
     */
    public static boolean apply(List<Element> runs, String newText, boolean preserveSpace) {
        if (runs.isEmpty()) {
            return false;
        }
        int n = runs.size();
        String[] texts = new String[n];
        int[] starts = new int[n];
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < n; i++) {
            texts[i] = runs.get(i).getTextContent();
            starts[i] = all.length();
            all.append(texts[i]);
        }
        String old = all.toString();
        if (old.equals(newText)) {
            return true;
        }

        int max = Math.min(old.length(), newText.length());
        int p = 0;
        while (p < max && old.charAt(p) == newText.charAt(p)) {
            p++;
        }
        if (p > 0 && Character.isHighSurrogate(newText.charAt(p - 1))) {
            p--; // не рвём суррогатную пару
        }
        int s = 0;
        int maxSuffix = max - p;
        while (s < maxSuffix && old.charAt(old.length() - 1 - s) == newText.charAt(newText.length() - 1 - s)) {
            s++;
        }
        if (s > 0 && Character.isLowSurrogate(old.charAt(old.length() - s))) {
            s--;
        }

        int delStart = p;
        int delEnd = old.length() - s;
        String insert = newText.substring(p, newText.length() - s);

        // В какой ран попадает вставка: при замене — в ран с первым заменяемым символом,
        // при чистой вставке — в ран, где стоит предыдущий символ (текст продолжает его оформление)
        int target = 0;
        int probe = delEnd > delStart ? delStart : delStart - 1;
        if (probe >= 0) {
            for (int i = 0; i < n; i++) {
                int end = starts[i] + texts[i].length();
                if (starts[i] <= probe && probe < end) {
                    target = i;
                    break;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            int rs = starts[i];
            int re = rs + texts[i].length();
            StringBuilder out = new StringBuilder();
            if (delStart > rs) {
                out.append(old, rs, Math.min(re, delStart));
            }
            if (i == target) {
                out.append(insert);
            }
            if (delEnd < re) {
                out.append(old, Math.max(rs, delEnd), re);
            }
            String result = out.toString();
            if (!result.equals(texts[i])) {
                Element e = runs.get(i);
                e.setTextContent(result);
                if (preserveSpace) {
                    e.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
                }
            }
        }
        return true;
    }

    /** Переводы строк в тексте одной строки (абзацы Word/PowerPoint не хранят их в w:t / a:t). */
    public static String singleLine(String text) {
        return text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
    }
}
