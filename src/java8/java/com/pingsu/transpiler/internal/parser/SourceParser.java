package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.syntax.Program;
import java.util.List;

/** Parsing capability, independent of transformation and output backends. */
public interface SourceParser {
    enum Language { TYPESCRIPT, JAVASCRIPT }
    static final class Result {
        private final Program program;
        private final List<Diagnostic> diagnostics;
        public Result(Program program, List<Diagnostic> diagnostics) { diagnostics = Compat.copyList(diagnostics);
            this.program = program;
            this.diagnostics = diagnostics;
        }
        public Program program() { return program; }
        public List<Diagnostic> diagnostics() { return diagnostics; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Result)) return false;
            Result value = (Result) other;
            return java.util.Objects.equals(program, value.program) && java.util.Objects.equals(diagnostics, value.diagnostics);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(program);
            hash = 31 * hash + java.util.Objects.hashCode(diagnostics);
            return hash;
        }
        @Override public String toString() { return "Result[program=" + program + ", diagnostics=" + diagnostics + "]"; }


        public boolean successful() { return program != null && diagnostics.isEmpty(); }

    }
    Result parse(String sourceName, String source, Language language);
}
