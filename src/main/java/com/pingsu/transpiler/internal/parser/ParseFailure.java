package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.Diagnostic;

/** Internal, stackless control flow; resource-limit failures must never be swallowed by probes. */
final class ParseFailure extends RuntimeException {
    final Diagnostic diagnostic;
    final boolean limit;

    ParseFailure(Diagnostic diagnostic) { this(diagnostic, false); }
    ParseFailure(Diagnostic diagnostic, boolean limit) {
        super(null, null, false, false);
        this.diagnostic = diagnostic;
        this.limit = limit;
    }
}
