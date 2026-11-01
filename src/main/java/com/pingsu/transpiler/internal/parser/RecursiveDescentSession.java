package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.Program;
import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.List;

/** Default grammar and final validation for a single recursive-descent parse. */
final class RecursiveDescentSession extends AbstractParseSession {
    private final GrammarRules.Statements statements;

    RecursiveDescentSession(String name, String source, SourceParser.Language language, boolean moduleCode) {
        super(name, source, language, moduleCode);
        statements = new RecursiveDescentGrammar(this);
    }

    @Override protected List<SyntaxNode> parseStatements() {
        return statements.statements(false, true);
    }

    @Override protected void validate(Program program) {
        CoverGrammarValidator.validate(this, program);
    }
}
