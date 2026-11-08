package dev.ronova.docforge.core;

import dev.ronova.docforge.odf.OdpEngine;
import dev.ronova.docforge.odf.OdsEngine;
import dev.ronova.docforge.odf.OdtEngine;
import dev.ronova.docforge.ooxml.DocxEngine;
import dev.ronova.docforge.ooxml.PptxEngine;
import dev.ronova.docforge.ooxml.XlsxEngine;

/** Единственное место, где формат связывается с конкретным движком. */
public final class Engines {
    private Engines() {
    }

    public static OfficeEngine forFormat(DocFormat format) {
        switch (format) {
            case DOCX:
                return new DocxEngine();
            case XLSX:
                return new XlsxEngine();
            case PPTX:
                return new PptxEngine();
            case ODT:
                return new OdtEngine();
            case ODS:
                return new OdsEngine();
            case ODP:
                return new OdpEngine();
            default:
                throw new DocException("Формат " + format.title + " обрабатывается отдельным просмотрщиком");
        }
    }
}
