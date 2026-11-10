package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.syntax.Token;
import java.util.List;

/** Parser-directed lexical capability: expression context determines slash interpretation. */
public interface TokenReader {
    enum Goal { DIVISION, REGULAR_EXPRESSION, JSX_TAG, JSX_TEXT }
    interface Checkpoint { }
    Token next(Goal goal);
    Checkpoint checkpoint();
    void restore(Checkpoint checkpoint);
    List<Diagnostic> diagnostics();
}
