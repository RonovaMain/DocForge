package dev.ronova.docforge.print;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Потоковый вывод страниц-растров в PostScript уровня 2 (оттенки серого, 8 бит).
 * Данные страницы жмутся RunLength и кодируются ASCII85, поэтому принтер получает чистый текст.
 * Строки можно подавать порциями — вся страница в памяти не нужна.
 */
public final class PostScriptStream {
    private final OutputStream out;
    private final Ascii85 a85;
    private int page;
    private int rowBytes;
    private boolean inPage;

    public PostScriptStream(OutputStream out, String title) throws IOException {
        this.out = out;
        this.a85 = new Ascii85(out);
        String safe = title == null ? "document" : title.replaceAll("[^A-Za-z0-9 ._-]", "_");
        text("%!PS-Adobe-3.0\n%%Creator: DocForge\n%%Title: " + safe + "\n%%LanguageLevel: 2\n%%Pages: (atend)\n%%EndComments\n");
    }

    /** Начинает страницу: растр [widthPx]×[heightPx], размер листа в пунктах (1/72 дюйма). */
    public void beginPage(int widthPx, int heightPx, int widthPt, int heightPt) throws IOException {
        if (inPage) throw new IllegalStateException("страница уже начата");
        page++;
        rowBytes = widthPx;
        inPage = true;
        text("%%Page: " + page + " " + page + "\n"
            + "<< /PageSize [" + widthPt + " " + heightPt + "] >> setpagedevice\n"
            + "gsave\n"
            + widthPt + " " + heightPt + " scale\n"
            + "/DeviceGray setcolorspace\n"
            + "/A currentfile /ASCII85Decode filter def\n"
            + "/DF A /RunLengthDecode filter def\n"
            + "<< /ImageType 1 /Width " + widthPx + " /Height " + heightPx + " /BitsPerComponent 8 /Decode [0 1]\n"
            + "/ImageMatrix [" + widthPx + " 0 0 -" + heightPx + " 0 " + heightPx + "] /DataSource DF >> image\n");
    }

    /** Добавляет [rows] строк по [widthPx] байт оттенков серого (0 — чёрный, 255 — белый). */
    public void rows(byte[] gray, int off, int rows) throws IOException {
        if (!inPage) throw new IllegalStateException("страница не начата");
        int total = rows * rowBytes;
        a85.write(PackBits.encode(gray, off, total));
    }

    public void endPage() throws IOException {
        if (!inPage) throw new IllegalStateException("страница не начата");
        a85.write(new byte[] {(byte) 128}); // признак конца RunLength
        a85.finish();
        text("\nDF status { DF flushfile } if\nA status { A flushfile } if\ngrestore\nshowpage\n");
        inPage = false;
    }

    public void finish() throws IOException {
        if (inPage) endPage();
        text("%%Trailer\n%%Pages: " + page + "\n%%EOF\n");
        out.flush();
    }

    private void text(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** RunLengthEncode / PackBits: 0..127 — n+1 байт как есть, 129..255 — один байт повторить 257-n раз. */
    static final class PackBits {
        private PackBits() {
        }

        static byte[] encode(byte[] src, int off, int len) {
            java.io.ByteArrayOutputStream dst = new java.io.ByteArrayOutputStream(len + len / 100 + 16);
            int i = off;
            int end = off + len;
            while (i < end) {
                int run = 1;
                while (i + run < end && run < 128 && src[i + run] == src[i]) run++;
                if (run >= 2) {
                    dst.write(257 - run);
                    dst.write(src[i]);
                    i += run;
                } else {
                    int start = i;
                    int n = 0;
                    while (i < end && n < 128) {
                        // литерал заканчивается, когда впереди начинается повтор из 3+ байт
                        if (i + 2 < end && src[i] == src[i + 1] && src[i] == src[i + 2]) break;
                        i++;
                        n++;
                    }
                    if (n == 0) { // повтор из 2 байт у самого конца: пишем как литерал
                        n = 1;
                        i = start + 1;
                    }
                    dst.write(n - 1);
                    dst.write(src, start, n);
                }
            }
            return dst.toByteArray();
        }
    }

    /** Кодировщик ASCII85 с переносом строк; finish() пишет признак конца «~>». */
    static final class Ascii85 {
        private final OutputStream out;
        private final byte[] group = new byte[4];
        private int count;
        private int column;

        Ascii85(OutputStream out) {
            this.out = out;
        }

        void write(byte[] data) throws IOException {
            for (byte b : data) {
                group[count++] = b;
                if (count == 4) {
                    emit(4);
                    count = 0;
                }
            }
        }

        void finish() throws IOException {
            if (count > 0) {
                for (int i = count; i < 4; i++) group[i] = 0;
                emit(count);
                count = 0;
            }
            out.write('~');
            out.write('>');
            column = 0;
        }

        private void emit(int bytes) throws IOException {
            long v = ((group[0] & 0xFFL) << 24) | ((group[1] & 0xFFL) << 16) | ((group[2] & 0xFFL) << 8) | (group[3] & 0xFFL);
            if (v == 0 && bytes == 4) {
                put('z');
                return;
            }
            char[] c = new char[5];
            for (int i = 4; i >= 0; i--) {
                c[i] = (char) (v % 85 + '!');
                v /= 85;
            }
            for (int i = 0; i < bytes + 1; i++) put(c[i]);
        }

        private void put(char ch) throws IOException {
            out.write(ch);
            if (++column >= 72) {
                out.write('\n');
                column = 0;
            }
        }
    }
}
