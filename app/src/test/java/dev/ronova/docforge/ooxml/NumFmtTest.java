package dev.ronova.docforge.ooxml;

import static org.junit.Assert.assertEquals;

import dev.ronova.docforge.zip.ZipPackage;
import java.util.Locale;
import org.junit.Test;

public class NumFmtTest {
    private static final String NBSP = "\u00A0";

    @Test
    public void generalDropsFloatNoise() {
        assertEquals("42", NumFmt.format(42.0, "General", false));
        assertEquals("0.3", NumFmt.format(0.1 + 0.2, "General", false));
        assertEquals("-1.5", NumFmt.format(-1.5, "General", false));
    }

    @Test
    public void numbersPercentsAndThousands() {
        assertEquals("3.14", NumFmt.format(3.14159, "0.00", false));
        assertEquals("1" + NBSP + "234" + NBSP + "567", NumFmt.format(1234567, "#,##0", false));
        assertEquals("1" + NBSP + "250.50", NumFmt.format(1250.5, "#,##0.00", false));
        assertEquals("87.3%", NumFmt.format(0.8734, "0.0%", false));
        assertEquals("-5", NumFmt.format(-5, "0", false));
        assertEquals("007", NumFmt.format(7, "000", false));
        assertEquals("1.5", NumFmt.format(1.5, "0.0#", false));
        assertEquals("1.25", NumFmt.format(1.25, "0.0#", false));
    }

    @Test
    public void currencyLiteralsAreKept() {
        assertEquals("1" + NBSP + "250.50 \u20BD", NumFmt.format(1250.5, "#,##0.00 \"\u20BD\"", false));
        assertEquals("$3.00", NumFmt.format(3, "[$$-409]0.00", false));
    }

    @Test
    public void datesAndTimes() {
        Locale.setDefault(Locale.US);
        // 46037 = 15 января 2026
        assertEquals("15.01.2026", NumFmt.format(46037, "dd.mm.yyyy", false));
        assertEquals("2026-01-15 12:30", NumFmt.format(46037.5208333, "yyyy-mm-dd hh:mm", false));
        assertEquals("12:30:45", NumFmt.format(0.52135417, "h:mm:ss", false));
    }

    @Test
    public void relativePartNamesResolve() {
        assertEquals("ppt/media/a.png", ZipPackage.resolve("ppt/slides", "../media/a.png"));
        assertEquals("xl/worksheets/sheet1.xml", ZipPackage.resolve("xl", "worksheets/sheet1.xml"));
        assertEquals("docProps/core.xml", ZipPackage.resolve("xl", "/docProps/core.xml"));
    }
}
