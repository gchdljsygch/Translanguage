package com.pingsu.transpiler;

/** A source location uses one-based lines and columns. */
public record Diagnostic(String sourceName, int line, int column, String code, String message) {
    @Override public String toString() {
        return sourceName + ":" + line + ":" + column + ": error " + code + ": " + message;
    }
}
