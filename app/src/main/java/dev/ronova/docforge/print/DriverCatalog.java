package dev.ronova.docforge.print;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Каталог способов печати и автовыбор: по модели принтера из IEEE 1284 Device ID, затем по заявленным языкам (CMD).
 * Чем выше оценка, тем увереннее выбор. Таблица моделей составлена по документации foo2zjs и должна дополняться
 * по отзывам пользователей: ошибка в ней не опасна, подобранный способ всегда можно сменить вручную.
 */
public final class DriverCatalog {

    public static final class Match {
        public final DriverSpec spec;
        public final int score;
        public final String reason;

        Match(DriverSpec spec, int score, String reason) {
            this.spec = spec;
            this.score = score;
            this.reason = reason;
        }
    }

    private static final String ARGS_COMMON = "-r{dpi}x{dpi} -g{w}x{h} -p{paper} -n{copies}";

    private static final List<DriverSpec> ALL = new ArrayList<DriverSpec>();
    private static final Map<String, DriverSpec> BY_ID = new LinkedHashMap<String, DriverSpec>();

    private static void add(DriverSpec s) {
        ALL.add(s);
        BY_ID.put(s.id, s);
    }

    static {
        add(new DriverSpec("pcl5", "PCL 5 (встроенный, чёрно-белый)", DriverSpec.Kind.PCL5, null, 300, null, false,
            "Многие лазерные принтеры HP, Brother, Kyocera, Lexmark, OKI, Ricoh"));
        add(new DriverSpec("ps", "PostScript (встроенный, оттенки серого)", DriverSpec.Kind.POSTSCRIPT, null, 200, null, false,
            "Принтеры с PostScript 2/3"));
        add(new DriverSpec("pdf", "PDF напрямую", DriverSpec.Kind.PDF, null, 0, null, false,
            "Принтеры, которые сами читают PDF"));
        add(new DriverSpec("foo2zjs", "foo2zjs (ZjStream: HP LaserJet 1000/1005/1018/1020/1022, P1102/P1566/P1606, CP1025, P2035, Minolta)", DriverSpec.Kind.FILTER,
            "foo2zjs", 600, ARGS_COMMON, true, "Нужен файл прошивки .dl, загружается в принтер после включения"));
        add(new DriverSpec("foo2xqx", "foo2xqx (XQX: HP LaserJet P1005–P1008, P1505, P2014, M1005/M1120/M1212)", DriverSpec.Kind.FILTER,
            "foo2xqx", 600, ARGS_COMMON, true, "Для части моделей нужен файл прошивки .dl"));
        add(new DriverSpec("foo2qpdl", "foo2qpdl (QPDL/SPL: Samsung ML/CLP/SCX, Xerox Phaser 31xx)", DriverSpec.Kind.FILTER,
            "foo2qpdl", 600, ARGS_COMMON, false, "Чёрно-белая печать"));
        add(new DriverSpec("foo2slx", "foo2slx (SLX: Xerox Phaser 6110)", DriverSpec.Kind.FILTER,
            "foo2slx", 600, ARGS_COMMON, false, "Чёрно-белая печать"));
        add(new DriverSpec("foo2lava", "foo2lava (LAVA: Kyocera FS-1016MFP / FS-1118MFP)", DriverSpec.Kind.FILTER,
            "foo2lava", 600, ARGS_COMMON, false, "Чёрно-белая печать"));
    }

    private static final class Rule {
        final String driverId;
        final Pattern mfg;
        final Pattern model;

        Rule(String driverId, String mfg, String model) {
            this.driverId = driverId;
            this.mfg = Pattern.compile(mfg, Pattern.CASE_INSENSITIVE);
            this.model = Pattern.compile(model, Pattern.CASE_INSENSITIVE);
        }
    }

    private static final List<Rule> RULES = new ArrayList<Rule>();

    static {
        String hp = "(hp|hewlett)";
        // LaserJet 1010/1012/1015 — не foo2zjs: они понимают урезанный растровый PCL, его печатает встроенный PCL 5
        RULES.add(new Rule("pcl5", hp, "laserjet\\s+(1010|1012|1015)\\b"));
        RULES.add(new Rule("foo2zjs", hp, "laserjet\\s+(1000|1005|1018|1020|1022)\\b"));
        RULES.add(new Rule("foo2zjs", hp, "\\b(p1102w?|p1566|p1606|p2035|cp1025|m1319)"));
        RULES.add(new Rule("foo2zjs", "(minolta|konica|qms)", "magicolor\\s+(2200|2300)\\s*dl"));
        RULES.add(new Rule("foo2xqx", hp, "\\b(p100[5-8]|p1505|p2014|m1005|m1120|m1212)"));
        RULES.add(new Rule("foo2qpdl", "(samsung|xerox)",
            "\\b(clp-?3\\d\\d|ml-?(1610|1640|1660|1665|1860|2010|2240|2250|2510|2570|2571|2850|2851)|scx-?4[23]\\d\\d|phaser\\s+31(17|22|24|25|30|40|55|60))"));
        RULES.add(new Rule("foo2slx", "xerox", "phaser\\s+6110"));
        RULES.add(new Rule("foo2lava", "kyocera", "fs-?1(016|118)\\s*mfp"));
    }

    private DriverCatalog() {
    }

    public static List<DriverSpec> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static DriverSpec byId(String id) {
        return BY_ID.get(id);
    }

    /** Подходящие способы печати по убыванию уверенности. Список не пуст: в конце всегда есть запасные варианты. */
    public static List<Match> select(String manufacturer, String model, Set<String> commands) {
        String mfg = manufacturer == null ? "" : manufacturer;
        String mdl = model == null ? "" : model;
        Map<String, Match> best = new LinkedHashMap<String, Match>();

        for (Rule r : RULES) {
            if (r.mfg.matcher(mfg).find() && r.model.matcher(mdl).find()) {
                offer(best, r.driverId, 100, "модель " + (mfg + " " + mdl).trim() + " есть в таблице");
            }
        }
        for (String cmd : commands) {
            String c = cmd.toUpperCase(Locale.ROOT);
            if (c.equals("ZJS") || c.equals("ZJSTREAM")) offer(best, "foo2zjs", 90, "принтер заявляет язык " + c);
            else if (c.equals("XQX")) offer(best, "foo2xqx", 90, "принтер заявляет язык XQX");
            else if (c.startsWith("QPDL") || c.equals("SPL") || c.equals("SPLC")) offer(best, "foo2qpdl", 60, "принтер заявляет язык " + c);
            else if (c.equals("PDF")) offer(best, "pdf", 75, "принтер заявляет чтение PDF");
            else if (c.startsWith("POSTSCRIPT") || c.equals("PS") || c.equals("PS2") || c.equals("PS3")) {
                offer(best, "ps", 70, "принтер заявляет PostScript");
            } else if (c.equals("DW-PCL") || c.contains("FASTRASTER")) {
                offer(best, "pcl5", 65, "принтер заявляет растровый PCL (" + c + ") — так устроены HP LaserJet 1010/1012/1015");
            } else if (c.equals("PCL") || c.startsWith("PCL5") || c.equals("PCL3")) {
                offer(best, "pcl5", 60, "принтер заявляет PCL");
            } else if (c.startsWith("PCL6") || c.equals("PCLXL") || c.equals("PCL XL")) {
                offer(best, "pcl5", 35, "заявлен только PCL 6 — большинство таких принтеров понимают и PCL 5");
            }
        }
        // запасные варианты: пользователь увидит их в списке и сможет попробовать
        offer(best, "pcl5", 10, "запасной вариант");
        offer(best, "ps", 8, "запасной вариант");

        List<Match> out = new ArrayList<Match>(best.values());
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return b.score - a.score;
            }
        });
        return out;
    }

    /** Подсказка, если принтер говорит только на языках, для которых встроенных драйверов нет; иначе null. */
    public static String unsupportedHint(Set<String> commands) {
        boolean known = false;
        String host = null;
        for (String cmd : commands) {
            String c = cmd.toUpperCase(Locale.ROOT);
            if (c.startsWith("PCL") || c.startsWith("POSTSCRIPT") || c.startsWith("PS") || c.equals("PDF")
                || c.equals("ZJS") || c.equals("XQX") || c.startsWith("QPDL")) {
                known = true;
            } else if (c.equals("GDI") || c.startsWith("CAPT") || c.equals("SPL") || c.equals("SPLC") || c.equals("ACL")
                || c.equals("HBPL") || c.equals("PPA")) {
                host = c;
            }
        }
        if (known || host == null) return null;
        return host;
    }

    private static void offer(Map<String, Match> best, String driverId, int score, String reason) {
        DriverSpec spec = BY_ID.get(driverId);
        if (spec == null) return;
        Match old = best.get(driverId);
        if (old == null || old.score < score) best.put(driverId, new Match(spec, score, reason));
    }
}
