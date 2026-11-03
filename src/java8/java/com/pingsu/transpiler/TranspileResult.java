package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.compat.Compat;
import java.util.List;

/**
 * Outcome of one transpilation.
 *
 * Component javaScript: generated JavaScript; empty when diagnostics were reported
 * Component diagnostics: problems found while parsing or transforming; empty on success
 */
public final class TranspileResult {
        private final String javaScript;
        private final List<Diagnostic> diagnostics;
        public TranspileResult(String javaScript, List<Diagnostic> diagnostics) {
        java.util.Objects.requireNonNull(javaScript);
        diagnostics = Compat.copyList(diagnostics);
        if (!diagnostics.isEmpty()) javaScript = "";

            this.javaScript = javaScript;
            this.diagnostics = diagnostics;
        }
        public String javaScript() { return javaScript; }
        public List<Diagnostic> diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TranspileResult)) return false;
            TranspileResult value = (TranspileResult) other;
            return java.util.Objects.equals(javaScript, value.javaScript) && java.util.Objects.equals(diagnostics, value.diagnostics);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(javaScript);
            hash = 31 * hash + java.util.Objects.hashCode(diagnostics);
            return hash;
        }
        @Override public String toString() { return "TranspileResult[javaScript=" + javaScript + ", diagnostics=" + diagnostics + "]"; }



    /** Returns {@code true} when no diagnostics were reported. */
    public boolean successful() { return diagnostics.isEmpty(); }

    }
