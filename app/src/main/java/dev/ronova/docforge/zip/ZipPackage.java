package dev.ronova.docforge.zip;

import dev.ronova.docforge.core.DocException;
import dev.ronova.docforge.xml.Xml;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Пакет Open XML / ODF = ZIP из частей. Целиком в памяти, только стандартные классы JVM —
 * поэтому одинаково работает на Android и в юнит-тестах.
 */
public final class ZipPackage {

    /** Связь между частями OOXML; target приведён к полному имени части. */
    public static final class Relationship {
        public final String id;
        public final String type;
        public final String target;
        public final boolean external;

        Relationship(String id, String type, String target, boolean external) {
            this.id = id;
            this.type = type;
            this.target = target;
            this.external = external;
        }
    }

    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";

    private final LinkedHashMap<String, byte[]> parts = new LinkedHashMap<>();

    private ZipPackage() {
    }

    public static ZipPackage create() {
        return new ZipPackage();
    }

    public static ZipPackage read(byte[] bytes) {
        ZipPackage pkg = new ZipPackage();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory()) {
                    pkg.parts.put(entry.getName(), readAll(zip));
                }
                entry = zip.getNextEntry();
            }
        } catch (IOException e) {
            throw new DocException("Файл повреждён или не является документом Office: " + e.getMessage(), e);
        }
        if (pkg.parts.isEmpty()) {
            throw new DocException("Файл не является документом (пустой ZIP-архив)");
        }
        return pkg;
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    public boolean has(String name) {
        return parts.containsKey(name);
    }

    public byte[] get(String name) {
        return parts.get(name);
    }

    public Set<String> names() {
        return parts.keySet();
    }

    public void put(String name, byte[] data) {
        parts.put(name, data);
    }

    public void remove(String name) {
        parts.remove(name);
    }

    /** Разобранный XML части или null, если такой части нет. */
    public Document xml(String name) {
        byte[] data = parts.get(name);
        return data == null ? null : Xml.parse(data);
    }

    public void putXml(String name, Document doc) {
        parts.put(name, Xml.toBytes(doc));
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                if ("mimetype".equals(e.getKey())) {
                    // ODF: mimetype хранится без сжатия, чтобы формат определялся по первым байтам файла
                    CRC32 crc = new CRC32();
                    crc.update(e.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.getValue().length);
                    entry.setCompressedSize(e.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new DocException("Не удалось собрать файл: " + ex.getMessage(), ex);
        }
        return out.toByteArray();
    }

    /** Связи части: например для xl/workbook.xml читается xl/_rels/workbook.xml.rels. */
    public List<Relationship> relationships(String partName) {
        String dir = partName.contains("/") ? partName.substring(0, partName.lastIndexOf('/')) : "";
        String file = partName.substring(partName.lastIndexOf('/') + 1);
        String relsName = (dir.isEmpty() ? "" : dir + "/") + "_rels/" + file + ".rels";
        List<Relationship> result = new ArrayList<>();
        Document doc = xml(relsName);
        if (doc == null) {
            return result;
        }
        for (Element e : Xml.descendants(doc, REL_NS, "Relationship")) {
            boolean external = "External".equalsIgnoreCase(e.getAttribute("TargetMode"));
            String target = e.getAttribute("Target");
            result.add(new Relationship(e.getAttribute("Id"), e.getAttribute("Type"),
                    external ? target : resolve(dir, target), external));
        }
        return result;
    }

    /** `../media/a.png` относительно `ppt/slides` -> `ppt/media/a.png`. */
    public static String resolve(String baseDir, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        List<String> stack = new ArrayList<>();
        if (!baseDir.isEmpty()) {
            for (String s : baseDir.split("/")) {
                stack.add(s);
            }
        }
        for (String seg : target.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else {
                stack.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(stack.get(i));
        }
        return sb.toString();
    }
}
