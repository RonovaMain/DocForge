package dev.ronova.docforge.print;

import java.nio.charset.StandardCharsets;

/** Заголовки растровых файлов PBM/PGM, которые читают фильтры foo2*. */
public final class Pnm {
    private Pnm() {
    }

    /** P4 — чёрно-белый (1 бит на пиксель, 1 = чёрный, строки выровнены по байту). */
    public static byte[] pbmHeader(int width, int height) {
        return ("P4\n" + width + " " + height + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /** P5 — оттенки серого, 8 бит на пиксель. */
    public static byte[] pgmHeader(int width, int height) {
        return ("P5\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII);
    }
}
