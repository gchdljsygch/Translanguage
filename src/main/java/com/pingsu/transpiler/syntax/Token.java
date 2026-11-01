package com.pingsu.transpiler.syntax;

import java.util.Objects;

/** Immutable lexical item; lines and code-point columns are one-based. */
public record Token(TokenKind kind, SourceRange range, String text, int line, int column) {
    public Token {
        Objects.requireNonNull(kind); Objects.requireNonNull(range); Objects.requireNonNull(text);
        if (line < 1 || column < 1) throw new IllegalArgumentException("Locations are one-based");
        if (text.length() != range.length()) throw new IllegalArgumentException("Token text must match its UTF-16 range");
        if (kind == TokenKind.EOF && range.length() != 0) throw new IllegalArgumentException("EOF must be empty");
    }
    public boolean trivia() {
        return kind == TokenKind.WHITESPACE || kind == TokenKind.LINE_TERMINATOR
                || kind == TokenKind.COMMENT || kind == TokenKind.HASHBANG;
    }
}
