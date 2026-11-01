package com.pingsu.reference.transpiler;

import java.util.List;

/**
 * Outcome of one transpilation.
 *
 * @param javaScript generated JavaScript; empty when diagnostics were reported
 * @param diagnostics problems found while parsing or transforming; empty on success
 */
public record TranspileResult(String javaScript, List<Diagnostic> diagnostics) {
    public TranspileResult { diagnostics = List.copyOf(diagnostics); }

    /** Returns {@code true} when no diagnostics were reported. */
    public boolean successful() { return diagnostics.isEmpty(); }
}
