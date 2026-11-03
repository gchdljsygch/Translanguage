package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.Program;
import com.pingsu.transpiler.syntax.SyntaxNode;

/** Validates context-dependent object shorthand after the complete tree is available. */
final class CoverGrammarValidator {
    private CoverGrammarValidator() { }
    static void validate(AbstractParseSession context, Program program) {
        final class Visit {
        private final SyntaxNode node;
        private final boolean pattern;
        Visit(SyntaxNode node, boolean pattern) {
            this.node = node;
            this.pattern = pattern;
        }
        public SyntaxNode node() { return node; }
        public boolean pattern() { return pattern; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Visit)) return false;
            Visit value = (Visit) other;
            return java.util.Objects.equals(node, value.node) && pattern == value.pattern;
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(node);
            hash = 31 * hash + Boolean.hashCode(pattern);
            return hash;
        }
        @Override public String toString() { return "Visit[node=" + node + ", pattern=" + pattern + "]"; }

    }
        java.util.ArrayDeque<Visit> pending = new java.util.ArrayDeque<Visit>(); pending.push(new Visit(program, false));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop(); SyntaxNode node = visit.node();
            if (node.kind().equals("type") || node.kind().equals("objectType")) continue;
            boolean defaultProperty = node instanceof ParsedNode && ((ParsedNode) node).attribute("shorthandDefault").equals("true");
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
