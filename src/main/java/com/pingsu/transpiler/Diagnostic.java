package com.pingsu.transpiler;

/**
 * A problem found in the input, located with one-based lines and columns.
 *
 * @param sourceName name supplied to {@link Transpiler#transpile(String, String)}
 * @param line one-based line of the first offending character
 * @param column one-based column of the first offending character
 * @param code stable machine-readable code such as {@code TS_SYNTAX} or {@code TS_UNSUPPORTED}
 * @param message human-readable description
 */
public record Diagnostic(String sourceName, int line, int column, String code, String message) {
    @Override public String toString() {
        return sourceName + ":" + line + ":" + column + ": error " + code + ": " + message;
    }
}
