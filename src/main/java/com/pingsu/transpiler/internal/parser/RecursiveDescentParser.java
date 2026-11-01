package com.pingsu.transpiler.internal.parser;

/** Handwritten recursive-descent grammar with a fresh, isolated session for every parse. */
public final class RecursiveDescentParser implements SourceParser {
    @Override public Result parse(String name, String source, Language language) {
        RecursiveDescentSession session = new RecursiveDescentSession(name, source, language, false);
        Result result = session.parse();
        // A module is strict from its first token, even when its first export occurs at the end.
        if (result.successful() && session.module()) {
            result = new RecursiveDescentSession(name, source, language, true).parse();
        }
        return result;
    }
}
