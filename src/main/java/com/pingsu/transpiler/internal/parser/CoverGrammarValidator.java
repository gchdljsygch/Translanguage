package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.Program;
import com.pingsu.transpiler.syntax.SyntaxNode;

/** Validates context-dependent object shorthand after the complete tree is available. */
final class CoverGrammarValidator {
    private CoverGrammarValidator() { }
    static void validate(AbstractParseSession context, Program program) {
        record Visit(SyntaxNode node, boolean pattern) { }
        var pending = new java.util.ArrayDeque<Visit>(); pending.push(new Visit(program, false));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop(); SyntaxNode node = visit.node();
            if (node.kind().equals("type") || node.kind().equals("objectType")) continue;
            boolean defaultProperty = node instanceof ParsedNode p && p.attribute("shorthandDefault").equals("true");
            if (defaultProperty && !visit.pattern()) {
                throw context.failureAt(node.range().start(), "Shorthand default is only valid in an assignment pattern");
            }
            for (int i = 0; i < node.children().size(); i++) {
                boolean pattern = visit.pattern() && !defaultProperty;
                if (node.kind().equals("assignmentExpression")) pattern = i == 0;
                pending.push(new Visit(node.children().get(i), pattern));
            }
        }
    }
}
