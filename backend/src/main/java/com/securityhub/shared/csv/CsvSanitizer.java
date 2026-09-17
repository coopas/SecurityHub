package com.securityhub.shared.csv;

/**
 * Turns a raw domain value into a CSV cell that no spreadsheet will execute and no parser
 * will misread.
 *
 * <p>The two defences are applied together on purpose. Prefixing a leading {@code =} with an
 * apostrophe is the well-known formula-injection fix, and on its own it is worthless: a cell
 * containing a comma or a newline splits the row, so the rest of the value lands at the start
 * of a <em>different</em> cell — one that was never inspected and therefore never prefixed.
 * Quoting every cell is what keeps a value a single cell, which is what makes the prefix
 * meaningful. Neither step is optional and neither is enough alone.
 *
 * <p>Accepted cost: a description that legitimately starts with {@code -} gains a leading
 * apostrophe. The alternative is a parser that tells a negative number apart from a formula,
 * and that parser is where every published bypass lives. Nothing this API exports is a
 * negative number: CVSS is 0.0..10.0, the rest is text, enum names, dates and booleans.
 */
public final class CsvSanitizer {

    private static final char PREFIX = '\'';
    private static final char QUOTE = '"';

    private CsvSanitizer() {
    }

    public static String cell(String raw) {
        String value = raw == null ? "" : stripControlCharacters(raw);
        StringBuilder result = new StringBuilder(value.length() + 4);
        result.append(QUOTE);
        if (!value.isEmpty() && isFormulaTrigger(value.charAt(0))) {
            result.append(PREFIX);
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == QUOTE) {
                result.append(QUOTE);
            }
            result.append(current);
        }
        return result.append(QUOTE).toString();
    }

    /**
     * Tab, CR and LF survive: they are legal inside a quoted cell and dropping them would
     * silently rewrite the user's own text. Every other C0 control and DEL goes, because
     * their only effect in a spreadsheet or a terminal is to disguise what the cell holds.
     */
    private static String stripControlCharacters(String raw) {
        StringBuilder kept = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (!isRemovableControl(current)) {
                kept.append(current);
            }
        }
        return kept.toString();
    }

    private static boolean isRemovableControl(char current) {
        if (current == '\t' || current == '\n' || current == '\r') {
            return false;
        }
        return current <= '' || current == '';
    }

    /**
     * The three whitespace characters are triggers too: a spreadsheet trims a cell before
     * deciding whether it holds a formula, so a tab followed by {@code =cmd|...} is
     * evaluated exactly like {@code =cmd|...}.
     */
    private static boolean isFormulaTrigger(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r' || first == '\n';
    }
}
