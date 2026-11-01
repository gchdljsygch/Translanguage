package com.pingsu.transpiler;

import java.util.List;

public record TranspileResult(String javaScript, List<Diagnostic> diagnostics) {
    public TranspileResult { diagnostics = List.copyOf(diagnostics); }
    public boolean successful() { return diagnostics.isEmpty(); }
}
