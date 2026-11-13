package org.coryd.ops.platform;

import java.util.List;
import java.util.stream.Collectors;

/** RFC 4180 lines, with spreadsheet formula injection neutralised - the same rules as trip-service's exports. */
final class Csv {

    private Csv() {
    }

    static String line(List<?> cells) {
        return cells.stream().map(Csv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
