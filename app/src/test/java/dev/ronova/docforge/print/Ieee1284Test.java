package dev.ronova.docforge.print;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.Test;

public class Ieee1284Test {
    private static final String ID = "MFG:Hewlett-Packard;MDL:HP LaserJet 1018;CMD:ACL,PJL;CLS:PRINTER;DES:HP LaserJet 1018;";

    private static byte[] withLength(String s, boolean bigEndian) {
        byte[] text = s.getBytes(StandardCharsets.ISO_8859_1);
        int n = text.length + 2;
        byte[] raw = new byte[n];
        raw[bigEndian ? 0 : 1] = (byte) (n >> 8);
        raw[bigEndian ? 1 : 0] = (byte) n;
        System.arraycopy(text, 0, raw, 2, text.length);
        return raw;
    }

    @Test
    public void parsesBigEndianLength() {
        byte[] raw = withLength(ID, true);
        Ieee1284 id = Ieee1284.parse(raw, raw.length);
        assertEquals("Hewlett-Packard", id.manufacturer());
        assertEquals("HP LaserJet 1018", id.model());
        assertEquals(new LinkedHashSet<String>(Arrays.asList("ACL", "PJL")), id.commands());
    }

    @Test
    public void parsesLittleEndianLength() {
        byte[] raw = withLength(ID, false);
        assertEquals("HP LaserJet 1018", Ieee1284.parse(raw, raw.length).model());
    }

    @Test
    public void parsesWithoutLengthPrefix() {
        byte[] raw = ID.getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("HP LaserJet 1018", Ieee1284.parse(raw, raw.length).model());
    }

    @Test
    public void acceptsLongKeysAndEmptyInput() {
        assertEquals("X1", Ieee1284.parseString("MANUFACTURER:A;MODEL:X1;COMMAND SET:PJL;").model());
        assertTrue(Ieee1284.parse(new byte[0], 0).isEmpty());
    }
}
