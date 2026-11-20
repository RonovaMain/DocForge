package dev.ronova.docforge.print;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Описание способа печати: что именно отправлять в принтер и чем это готовить. */
public final class DriverSpec {
    public enum Kind {
        /** Встроенный PCL 5 (растр, чёрно-белый). */
        PCL5,
        /** Встроенный PostScript уровня 2 (растр, градации серого). */
        POSTSCRIPT,
        /** PDF отправляется принтеру как есть. */
        PDF,
        /** Внешний фильтр (foo2zjs и родственные): на вход — PBM-страницы, на выходе — данные для принтера. */
        FILTER
    }

    public final String id;
    public final String title;
    public final Kind kind;
    /** Для FILTER: имя программы (libfoo2zjs.so лежит в каталоге нативных библиотек как foo2zjs). */
    public final String binary;
    /** Для FILTER: разрешение растра, dpi. */
    public final int dpi;
    /** Для FILTER: шаблон аргументов, подстановки {dpi} {w} {h} {paper} {copies}. */
    public final String argsTemplate;
    /** Нужно ли загружать в принтер файл прошивки (.dl) после включения. */
    public final boolean needsFirmware;
    public final String note;

    public DriverSpec(String id, String title, Kind kind, String binary, int dpi, String argsTemplate,
                      boolean needsFirmware, String note) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.binary = binary;
        this.dpi = dpi;
        this.argsTemplate = argsTemplate;
        this.needsFirmware = needsFirmware;
        this.note = note;
    }

    /** Аргументы командной строки фильтра. [paperCode] — код бумаги (DMPAPER: 1 Letter, 5 Legal, 9 A4, 11 A5). */
    public List<String> filterArgs(int widthPx, int heightPx, int paperCode, int copies) {
        if (argsTemplate == null || argsTemplate.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for (String part : argsTemplate.trim().split("\\s+")) {
            out.add(part
                .replace("{dpi}", Integer.toString(dpi))
                .replace("{w}", Integer.toString(widthPx))
                .replace("{h}", Integer.toString(heightPx))
                .replace("{paper}", Integer.toString(paperCode))
                .replace("{copies}", Integer.toString(copies)));
        }
        return out;
    }

    @Override
    public String toString() {
        return title;
    }
}
