package com.pingsu.transpiler;

import java.util.List;

/**
 * Outcome of one transpilation.
 *
 * @param javaScript generated JavaScript; empty when diagnostics were reported
 * @param diagnostics problems found while parsing or transforming; empty on success
 */
public record TranspileResult(String javaScript, List<Diagnostic> diagnostics) {
    public TranspileResult {
        java.util.Objects.requireNonNull(javaScript);
        diagnostics = List.copyOf(diagnostics);
        if (!diagnostics.isEmpty()) javaScript = "";
    }

    /** Returns {@code true} when no diagnostics were reported. */
    public boolean successful() { return diagnostics.isEmpty(); }
}
