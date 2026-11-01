package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.syntax.Program;
import java.util.List;

/** Parsing capability, independent of transformation and output backends. */
public interface SourceParser {
    enum Language { TYPESCRIPT, JAVASCRIPT }
    record Result(Program program, List<Diagnostic> diagnostics) {
        public Result { diagnostics = List.copyOf(diagnostics); }
        public boolean successful() { return program != null && diagnostics.isEmpty(); }
    }
    Result parse(String sourceName, String source, Language language);
}
