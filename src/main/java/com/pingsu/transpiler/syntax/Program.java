package com.pingsu.transpiler.syntax;

import java.util.List;
import java.util.Objects;

/** Root syntax view. Tokens include all trivia and a final empty EOF token. */
public record Program(SourceRange range, List<SyntaxNode> children, List<Token> tokens) implements SyntaxNode {
    public Program {
        Objects.requireNonNull(range);
        if (range.start() != 0) throw new IllegalArgumentException("Program must start at zero");
        children = List.copyOf(children); tokens = List.copyOf(tokens);
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
    }
    @Override public String kind() { return "program"; }
}
