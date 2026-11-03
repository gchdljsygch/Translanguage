package com.pingsu.transpiler;

/**
 * A problem found in the input, located with one-based lines and columns.
 *
 * Component sourceName: name supplied to {@link Transpiler#transpile(String, String)}
 * Component line: one-based line of the first offending character
 * Component column: one-based column of the first offending character
 * Component code: stable machine-readable code such as {@code TS_SYNTAX} or {@code TS_UNSUPPORTED}
 * Component message: human-readable description
 */
public final class Diagnostic {
        private final String sourceName;
        private final int line;
        private final int column;
        private final String code;
        private final String message;
        public Diagnostic(String sourceName, int line, int column, String code, String message) {
            this.sourceName = sourceName;
            this.line = line;
            this.column = column;
            this.code = code;
            this.message = message;
        }
        public String sourceName() { return sourceName; }
        public int line() { return line; }
        public int column() { return column; }
        public String code() { return code; }
        public String message() { return message; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Diagnostic)) return false;
            Diagnostic value = (Diagnostic) other;
            return java.util.Objects.equals(sourceName, value.sourceName) && line == value.line && column == value.column && java.util.Objects.equals(code, value.code) && java.util.Objects.equals(message, value.message);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(sourceName);
            hash = 31 * hash + Integer.hashCode(line);
            hash = 31 * hash + Integer.hashCode(column);
            hash = 31 * hash + java.util.Objects.hashCode(code);
            hash = 31 * hash + java.util.Objects.hashCode(message);
            return hash;
        }

    @Override public String toString() {
        return sourceName + ":" + line + ":" + column + ": error " + code + ": " + message;
    }

    }
