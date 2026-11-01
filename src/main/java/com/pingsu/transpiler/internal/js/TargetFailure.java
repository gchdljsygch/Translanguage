package com.pingsu.transpiler.internal.js;

import com.pingsu.transpiler.Diagnostic;
import java.util.List;

/** Expected target rejection, with no partially emitted artifact. */
public final class TargetFailure extends RuntimeException {
    private final List<Diagnostic> diagnostics;
    public TargetFailure(List<Diagnostic> diagnostics) { super(null, null, false, false); this.diagnostics = List.copyOf(diagnostics); }
    public List<Diagnostic> diagnostics() { return diagnostics; }
}
