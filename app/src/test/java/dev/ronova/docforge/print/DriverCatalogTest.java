package dev.ronova.docforge.print;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class DriverCatalogTest {
    private static Set<String> cmds(String... c) {
        return new LinkedHashSet<String>(Arrays.asList(c));
    }

    private static String top(String mfg, String model, Set<String> c) {
        return DriverCatalog.select(mfg, model, c).get(0).spec.id;
    }

    @Test
    public void oldHpLaserJetsGoToZjsAndXqx() {
        assertEquals("foo2zjs", top("Hewlett-Packard", "HP LaserJet 1018", cmds("ACL", "PJL")));
        assertEquals("foo2zjs", top("HP", "LaserJet 1020", cmds()));
        assertEquals("foo2xqx", top("Hewlett-Packard", "HP LaserJet P1005", cmds()));
        assertEquals("foo2zjs", top("HP", "LaserJet Professional P1102", cmds()));
        assertEquals("foo2xqx", top("HP", "LaserJet M1005 MFP", cmds()));
    }

    @Test
    public void laserJet1010FamilyUsesBuiltInPcl() {
        assertEquals("pcl5", top("Hewlett-Packard", "hp LaserJet 1010", cmds("PJL", "MLC", "BIDI-ECP", "DW-PCL")));
        assertEquals("pcl5", top("HP", "LaserJet 1012", cmds()));
        assertEquals("pcl5", top("HP", "LaserJet 1015", cmds()));
        assertEquals("pcl5", top("Unknown", "Unknown", cmds("DW-PCL")));
    }

    @Test
    public void otherVendors() {
        assertEquals("foo2qpdl", top("Samsung", "ML-2010", cmds()));
        assertEquals("foo2slx", top("Xerox", "Phaser 6110", cmds()));
    }

    @Test
    public void declaredLanguagesDecideWhenModelIsUnknown() {
        assertEquals("pdf", top("Brother", "HL-L2350DW", cmds("PCL6", "PDF")));
        assertEquals("ps", top("X", "Y", cmds("POSTSCRIPT2", "PCL5E")));
        assertEquals("pcl5", top("X", "Y", cmds("PCL5E")));
        assertEquals("pcl5", top("", "", Collections.<String>emptySet()));
    }

    @Test
    public void hostBasedLanguageGetsHint() {
        assertEquals("GDI", DriverCatalog.unsupportedHint(cmds("GDI")));
        assertNull(DriverCatalog.unsupportedHint(cmds("PCL")));
    }

    @Test
    public void filterArgumentsAreExpanded() {
        assertEquals(Arrays.asList("-r600x600", "-g4960x7016", "-p9", "-n2"),
            DriverCatalog.byId("foo2zjs").filterArgs(4960, 7016, 9, 2));
    }
}
