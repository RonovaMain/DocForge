package dev.ronova.docforge.print;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.Test;

public class PostScriptStreamTest {

    private static byte[] decodeAscii85(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] g = new long[5];
        int n = 0;
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == ' ') continue;
            if (c == 'z' && n == 0) {
                out.write(0); out.write(0); out.write(0); out.write(0);
                continue;
            }
            g[n++] = c - '!';
            if (n == 5) {
                long v = 0;
                for (int i = 0; i < 5; i++) v = v * 85 + g[i];
                for (int i = 3; i >= 0; i--) out.write((int) (v >> (8 * i)) & 0xFF);
                n = 0;
            }
        }
        if (n > 1) {
            for (int i = n; i < 5; i++) g[i] = 84;
            long v = 0;
            for (int i = 0; i < 5; i++) v = v * 85 + g[i];
            for (int i = 0; i < n - 1; i++) out.write((int) (v >> (8 * (3 - i))) & 0xFF);
        }
        return out.toByteArray();
    }

    private static byte[] decodePackBits(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = 0;
        while (p < in.length) {
            int n = in[p++] & 0xFF;
            if (n == 128) break;
            if (n < 128) {
                out.write(in, p, n + 1);
                p += n + 1;
            } else {
                for (int i = 0; i < 257 - n; i++) out.write(in[p]);
                p++;
            }
        }
        return out.toByteArray();
    }

    @Test
    public void pageDataSurvivesEncodingRoundTrip() throws Exception {
        int w = 64;
        int h = 40;
        byte[] gray = new byte[w * h];
        Random r = new Random(7);
        for (int i = 0; i < gray.length; i++) gray[i] = (byte) ((i % 200 < 120) ? 255 : (i % 3 == 0 ? 0 : r.nextInt(256)));

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PostScriptStream ps = new PostScriptStream(bo, "Doc");
        ps.beginPage(w, h, 595, 842);
        ps.rows(gray, 0, 17);
        ps.rows(gray, 17 * w, h - 17);
        ps.endPage();
        ps.finish();

        String text = new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%!PS-Adobe-3.0"));
        String marker = "/DataSource DF >> image\n";
        int start = text.indexOf(marker) + marker.length();
        int end = text.indexOf("~>", start);
        byte[] decoded = decodePackBits(decodeAscii85(text.substring(start, end)));
        assertArrayEquals(gray, decoded);
    }
}
