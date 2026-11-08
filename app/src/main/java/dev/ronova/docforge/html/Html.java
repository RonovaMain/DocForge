package dev.ronova.docforge.html;

import dev.ronova.docforge.core.DocFormat;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Общий каркас HTML-страницы, экранирование и мелкие помощники для CSS. */
public final class Html {
    public static final String BASE_URL = "https://docforge.local/";
    public static final String MEDIA_PREFIX = BASE_URL + "m/";
    public static final String PDF_PREFIX = BASE_URL + "pdf/";

    private Html() {
    }

    /** Экранирует текст для вставки в HTML (и в значения атрибутов). */
    public static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Ссылка на часть пакета (картинку); WebView получает её через перехват запросов. */
    public static String mediaUrl(String partName) {
        StringBuilder sb = new StringBuilder(MEDIA_PREFIX);
        byte[] bytes = partName.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/';
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return sb.toString();
    }

    /** MIME картинки по имени части; null — браузер такой формат не покажет (EMF, WMF, TIFF...). */
    public static String imageMime(String partName) {
        String n = partName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".jpe")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return null;
    }

    /** Число для CSS: без лишних нулей и всегда с точкой. */
    public static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.indexOf('.') >= 0) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && s.charAt(end - 1) == '.') {
                end--;
            }
            s = s.substring(0, end);
        }
        return s;
    }

    /** Шестнадцатеричный цвет RRGGBB без решётки. */
    public static boolean isHexColor(String s) {
        if (s == null || s.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Название шрифта, безопасное для вставки в CSS. */
    public static String fontFamily(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') {
                clean.append(c);
            }
        }
        String n = clean.toString().trim();
        if (n.isEmpty()) {
            return null;
        }
        String lower = n.toLowerCase(Locale.ROOT);
        boolean serif = lower.contains("times") || lower.contains("cambria") || lower.contains("georgia")
                || lower.contains("garamond") || lower.contains("serif") && !lower.contains("sans");
        boolean mono = lower.contains("courier") || lower.contains("consolas") || lower.contains("mono");
        return "'" + n + "'," + (mono ? "monospace" : serif ? "serif" : "sans-serif");
    }

    // ------------------------------------------------------------------ страница

    private static final String BASE_CSS =
            "*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}"
            + "body{margin:0;background:#e6e7eb;font-family:sans-serif;color:#000}"
            + ".editing [data-id]{outline:1px dashed rgba(90,110,255,.7);outline-offset:1px;cursor:pointer}"
            + "p:empty:before{content:'\\200b'}";

    private static final String TEXT_CSS =
            ".page{background:#fff;max-width:880px;margin:8px auto;padding:20px 18px 48px;"
            + "box-shadow:0 1px 4px rgba(0,0,0,.25);min-height:96vh;overflow-wrap:anywhere}"
            + ".page p{margin:0 0 6pt;white-space:pre-wrap}"
            + ".page table{border-collapse:collapse;width:100%;margin:4px 0}"
            + ".page table.b td{border:1px solid #888}"
            + ".page td{vertical-align:top;padding:2px 4px}"
            + ".page td>p:last-child{margin-bottom:0}"
            + ".page img{max-width:100%;height:auto}"
            + ".page ul,.page ol{margin:0 0 6pt;padding-left:1.6em}"
            + ".tab{display:inline-block;width:2.5em}"
            + ".mk{display:inline-block;text-indent:0}"
            + ".pb{border:0;border-top:1px dashed #bbb;margin:12px 0}"
            + ".noimg{color:#888;font-style:italic}";

    private static final String SHEET_CSS =
            "body{background:#fff}"
            + ".tabs{position:sticky;top:0;left:0;width:100vw;z-index:5;display:flex;gap:4px;overflow-x:auto;padding:6px;"
            + "background:#f1f3f4;border-bottom:1px solid #d0d3d6}"
            + ".tabs button{flex:none;border:1px solid #c8ccd0;background:#fff;border-radius:8px;padding:6px 12px;"
            + "font-size:14px;color:#222}"
            + ".tabs button.on{background:#d9e6ff;border-color:#7aa2f7;font-weight:bold}"
            + ".sheetwrap{display:none}.sheetwrap.first{display:block}"
            + "table.sheet{border-collapse:collapse;font-size:13px;background:#fff;table-layout:fixed}"
            + "table.sheet td{border:1px solid #dcdfe2;padding:1px 4px;overflow:hidden;text-overflow:ellipsis;"
            + "height:21px;vertical-align:bottom}"
            + "table.sheet th{background:#f1f3f4;color:#666;font-weight:normal;border:1px solid #d0d3d6;font-size:11px;"
            + "padding:1px 4px;position:sticky;top:0;z-index:2}"
            + "table.sheet th.rh{left:0;z-index:3;top:auto;min-width:36px;width:36px}"
            + "table.sheet th.corner{left:0;z-index:4}"
            + ".note{padding:8px;color:#666;font-size:13px}";

    private static final String SLIDE_CSS =
            "body{padding:6px}"
            + ".slidebox{position:relative;width:100%;margin:0 0 10px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.3);background:#fff}"
            + ".slide{position:absolute;left:0;top:0;transform-origin:0 0;overflow:hidden;background:#fff}"
            + ".sh{position:absolute}"
            + ".sh .tx{display:flex;flex-direction:column;width:100%;height:100%}"
            + ".sh p{margin:0;white-space:pre-wrap;overflow-wrap:break-word}"
            + ".sh table{border-collapse:collapse;table-layout:fixed;width:100%}"
            + ".sh td{overflow:hidden;vertical-align:top}"
            + ".sh img{display:block}"
            + ".sh ul,.sh ol{margin:0;padding-left:1.4em}"
            + ".mk{display:inline-block;text-indent:0}"
            + ".num{position:absolute;right:6px;bottom:4px;font:11px sans-serif;color:#888}";

    private static final String COMMON_JS =
            "var editing=false;"
            + "window.setEditing=function(on){editing=!!on;document.body.classList.toggle('editing',editing);};"
            + "window.setText=function(id,t){var e=document.querySelector('[data-id=\"'+id+'\"]');if(e){e.textContent=t;}};"
            + "document.addEventListener('click',function(ev){if(!editing){return;}var n=ev.target;"
            + "while(n&&n!==document.body){var id=n.getAttribute&&n.getAttribute('data-id');"
            + "if(id){ev.preventDefault();ev.stopPropagation();if(window.Android){window.Android.edit(id);}return;}"
            + "n=n.parentNode;}},true);";

    private static final String SHEET_JS =
            "window.showSheet=function(i){var s=document.querySelectorAll('.sheetwrap'),b=document.querySelectorAll('.tabs button');"
            + "for(var k=0;k<s.length;k++){s[k].style.display=k===i?'block':'none';b[k].className=k===i?'on':'';}};"
            + "var tb=document.querySelectorAll('.tabs button');"
            + "for(var q=0;q<tb.length;q++){(function(n){tb[n].addEventListener('click',function(){window.showSheet(n);});})(q);}";

    private static final String SLIDE_JS =
            "function fit(){var bs=document.querySelectorAll('.slidebox');"
            + "for(var i=0;i<bs.length;i++){var b=bs[i],w=+b.getAttribute('data-w'),h=+b.getAttribute('data-h'),k=b.clientWidth/w;"
            + "b.firstChild.style.transform='scale('+k+')';b.style.height=(h*k)+'px';}}"
            + "window.addEventListener('resize',fit);fit();";

    /**
     * Собирает страницу. Политика CSP запрещает всё, кроме наших картинок и нашего скрипта (по nonce):
     * даже если текст документа как-то попадёт в разметку неэкранированным, выполнить скрипт он не сможет.
     */
    public static String page(DocFormat.Kind kind, String extraCss, String body) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String css = BASE_CSS;
        String js = COMMON_JS;
        switch (kind) {
            case TEXT: css += TEXT_CSS; break;
            case SHEET: css += SHEET_CSS; js += SHEET_JS; break;
            case SLIDES: css += SLIDE_CSS; js += SLIDE_JS; break;
            default: break;
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src " + BASE_URL
                + " data:; style-src 'unsafe-inline'; script-src 'nonce-" + nonce + "'\">"
                + "<style>" + css + (extraCss == null ? "" : extraCss) + "</style></head><body>"
                + body
                + "<script nonce=\"" + nonce + "\">(function(){" + js + "})();</script></body></html>";
    }

    /** Страница-обёртка для PDF: по картинке на страницу, подгружаются лениво по мере прокрутки. */
    public static String pdfPages(List<int[]> sizes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<style>body{margin:0;background:#c9cbd1}img{display:block;width:100%;height:auto;margin:0 0 6px;background:#fff}</style>")
                .append("</head><body>");
        for (int i = 0; i < sizes.size(); i++) {
            int[] s = sizes.get(i);
            sb.append("<img loading=\"lazy\" width=\"").append(s[0]).append("\" height=\"").append(s[1])
                    .append("\" src=\"").append(PDF_PREFIX).append(i).append("\" alt=\"\">");
        }
        sb.append("</body></html>");
        return sb.toString();
    }
}
