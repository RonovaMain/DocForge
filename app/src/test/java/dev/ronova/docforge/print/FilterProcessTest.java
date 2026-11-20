package dev.ronova.docforge.print;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.Assume;
import org.junit.Test;

/** Нужны /bin/sh и cat: на Windows тесты пропускаются, в GitHub Actions (Linux) идут. */
public class FilterProcessTest {
    private static String cat() {
        for (String p : new String[] {"/usr/bin/cat", "/bin/cat"}) if (new File(p).exists()) return p;
        return null;
    }

    @Test
    public void outputOfFilterReachesSinkIntact() throws Exception {
        String cat = cat();
        Assume.assumeTrue(cat != null);
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        FilterProcess fp = FilterProcess.start(Arrays.asList(cat), new FilterProcess.Sink() {
            @Override
            public void write(byte[] data, int length) {
                got.write(data, 0, length);
            }
        });
        byte[] payload = new byte[2_000_000];
        new Random(1).nextBytes(payload);
        byte[] header = Pnm.pbmHeader(8, 8);
        fp.stdin().write(header);
        fp.stdin().write(payload);
        fp.finish();
        byte[] expect = new byte[header.length + payload.length];
        System.arraycopy(header, 0, expect, 0, header.length);
        System.arraycopy(payload, 0, expect, header.length, payload.length);
        assertArrayEquals(expect, got.toByteArray());
    }

    @Test
    public void nonZeroExitReportsStderr() throws Exception {
        Assume.assumeTrue(new File("/bin/sh").exists());
        FilterProcess fp = FilterProcess.start(Arrays.asList("/bin/sh", "-c", "echo oops >&2; cat >/dev/null; exit 3"),
            new FilterProcess.Sink() {
                @Override
                public void write(byte[] data, int length) {
                }
            });
        fp.stdin().write(1);
        try {
            fp.finish();
            fail("ожидалась ошибка");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("3") && e.getMessage().contains("oops"));
        }
    }
}
