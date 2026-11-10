package dev.sibarum.inspector.core;

import java.util.List;

/**
 * Text as comma-separated values (RFC 4180): what a table's rows are copied as, a header line first. Pure, so a step
 * may call it and a test reads it.
 *
 * <p>A field is quoted only when it has to be: when it holds a comma, a double quote, or a line break, or begins or
 * ends with a space, which a spreadsheet would otherwise trim. A quote inside a quoted field is doubled. Lines end
 * with CRLF, as the RFC has it and as a spreadsheet on any system reads.
 */
public final class Csv {

    /** What ends each line. */
    public static final String EOL = "\r\n";

    private Csv() {
    }

    /** {@code headers}, then each of {@code rows}, a line each, every line ended. */
    public static String of(List<String> headers, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        line(out, headers);
        for (List<String> row : rows) {
            line(out, row);
        }
        return out.toString();
    }

    /** {@code fields} as one line onto {@code out}, ended. */
    public static void line(StringBuilder out, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            field(out, fields.get(i));
        }
        out.append(EOL);
    }

    /** {@code value} as one field onto {@code out}, quoted only when it has to be. */
    public static void field(StringBuilder out, String value) {
        String v = value == null ? "" : value;
        if (!needsQuotes(v)) {
            out.append(v);
            return;
        }
        out.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"') {
                out.append('"');
            }
            out.append(c);
        }
        out.append('"');
    }

    private static boolean needsQuotes(String v) {
        if (v.isEmpty()) {
            return false;
        }
        if (v.charAt(0) == ' ' || v.charAt(v.length() - 1) == ' ') {
            return true;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }
}
