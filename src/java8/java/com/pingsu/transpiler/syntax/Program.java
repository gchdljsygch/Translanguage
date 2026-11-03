package com.pingsu.transpiler.syntax;

import com.pingsu.transpiler.internal.compat.Compat;
import java.util.List;
import java.util.Objects;

/** Root syntax view. Tokens include all trivia and a final empty EOF token. */
public final class Program implements SyntaxNode {
        private final SourceRange range;
        private final List<SyntaxNode> children;
        private final List<Token> tokens;
        public Program(SourceRange range, List<SyntaxNode> children, List<Token> tokens) {
        Objects.requireNonNull(range);
        if (range.start() != 0) throw new IllegalArgumentException("Program must start at zero");
        children = Compat.copyList(children); tokens = Compat.copyList(tokens);
        for (SyntaxNode child : children)
            if (!range.contains(child.range())) throw new IllegalArgumentException("Child outside program");
        int cursor = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.range().start() != cursor || !range.contains(token.range()))
                throw new IllegalArgumentException("Tokens must partition the complete source");
            if ((token.kind() == TokenKind.EOF) != (i == tokens.size() - 1))
                throw new IllegalArgumentException("Exactly one final EOF token required");
            cursor = token.range().end();
        }
        if (tokens.isEmpty() || cursor != range.end()) throw new IllegalArgumentException("Incomplete token stream");

            this.range = range;
            this.children = children;
            this.tokens = tokens;
        }
        public SourceRange range() { return range; }
        public List<SyntaxNode> children() { return children; }
        public List<Token> tokens() { return tokens; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Program)) return false;
            Program value = (Program) other;
            return java.util.Objects.equals(range, value.range) && java.util.Objects.equals(children, value.children) && java.util.Objects.equals(tokens, value.tokens);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(range);
            hash = 31 * hash + java.util.Objects.hashCode(children);
            hash = 31 * hash + java.util.Objects.hashCode(tokens);
            return hash;
        }
        @Override public String toString() { return "Program[range=" + range + ", children=" + children + ", tokens=" + tokens + "]"; }


    @Override public String kind() { return "program"; }

    }
