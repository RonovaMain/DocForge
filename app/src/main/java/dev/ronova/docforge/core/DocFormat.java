package dev.ronova.docforge.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Поддерживаемые форматы документов. */
public enum DocFormat {
    DOCX("docx", "Word", Kind.TEXT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("xlsx", "Excel", Kind.SHEET, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX("pptx", "PowerPoint", Kind.SLIDES, "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    ODT("odt", "LibreOffice Writer", Kind.TEXT, "application/vnd.oasis.opendocument.text"),
    ODS("ods", "LibreOffice Calc", Kind.SHEET, "application/vnd.oasis.opendocument.spreadsheet"),
    ODP("odp", "LibreOffice Impress", Kind.SLIDES, "application/vnd.oasis.opendocument.presentation"),
    PDF("pdf", "PDF", Kind.PDF, "application/pdf");

    public enum Kind { TEXT, SHEET, SLIDES, PDF }

    public final String extension;
    public final String title;
    public final Kind kind;
    public final String mime;

    DocFormat(String extension, String title, Kind kind, String mime) {
        this.extension = extension;
        this.title = title;
        this.kind = kind;
        this.mime = mime;
    }

    /** Все форматы, кроме PDF, можно править (меняется текст, структура файла сохраняется). */
    public boolean isEditable() {
        return this != PDF;
    }

    /**
     * Определяет формат по содержимому, а если не вышло — по расширению имени.
     * Содержимое надёжнее: файлы из мессенджеров и облаков часто приходят с чужим именем.
     */
    public static DocFormat detect(String fileName, byte[] data) {
        if (data != null && data.length > 4) {
            if (startsWith(data, '%', 'P', 'D', 'F')) {
                return PDF;
            }
            if (startsWith(data, 0xD0, 0xCF, 0x11, 0xE0)) {
                throw new DocException("Это старый бинарный формат Office (.doc, .xls, .ppt). "
                        + "Откройте файл в Word/Excel/PowerPoint или LibreOffice и пересохраните как DOCX, XLSX, PPTX или ODF.");
            }
            if (startsWith(data, 'P', 'K')) {
                DocFormat byContent = sniffZip(data);
                if (byContent != null) {
                    return byContent;
                }
            }
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "docx": case "docm": case "dotx": return DOCX;
            case "xlsx": case "xlsm": case "xltx": return XLSX;
            case "pptx": case "pptm": case "ppsx": case "potx": return PPTX;
            case "odt": case "ott": return ODT;
            case "ods": case "ots": return ODS;
            case "odp": case "otp": return ODP;
            case "pdf": return PDF;
            case "doc": case "xls": case "ppt":
                throw new DocException("Старый бинарный формат ." + ext
                        + " не поддерживается. Пересохраните файл как DOCX, XLSX, PPTX или ODF.");
            default:
                throw new DocException("Неподдерживаемый тип файла: " + (fileName == null ? "?" : fileName)
                        + ". Поддерживаются DOCX, XLSX, PPTX, ODT, ODS, ODP и PDF.");
        }
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static DocFormat sniffZip(byte[] data) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                String name = entry.getName();
                if ("word/document.xml".equals(name)) {
                    return DOCX;
                } else if ("xl/workbook.xml".equals(name)) {
                    return XLSX;
                } else if ("ppt/presentation.xml".equals(name)) {
                    return PPTX;
                } else if ("mimetype".equals(name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[256];
                    int n;
                    while (out.size() < 256 && (n = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                    String mime = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
                    if (mime.startsWith("application/vnd.oasis.opendocument.text")) {
                        return ODT;
                    } else if (mime.startsWith("application/vnd.oasis.opendocument.spreadsheet")) {
                        return ODS;
                    } else if (mime.startsWith("application/vnd.oasis.opendocument.presentation")) {
                        return ODP;
                    }
                }
                entry = zip.getNextEntry();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
