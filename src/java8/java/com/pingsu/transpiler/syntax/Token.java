package com.pingsu.transpiler.syntax;

import java.util.Objects;

/** Immutable lexical item; lines and code-point columns are one-based. */
public final class Token {
        private final TokenKind kind;
        private final SourceRange range;
        private final String text;
        private final int line;
        private final int column;
        public Token(TokenKind kind, SourceRange range, String text, int line, int column) {
        Objects.requireNonNull(kind); Objects.requireNonNull(range); Objects.requireNonNull(text);
        if (line < 1 || column < 1) throw new IllegalArgumentException("Locations are one-based");
        if (text.length() != range.length()) throw new IllegalArgumentException("Token text must match its UTF-16 range");
        if (kind == TokenKind.EOF && range.length() != 0) throw new IllegalArgumentException("EOF must be empty");

            this.kind = kind;
            this.range = range;
            this.text = text;
            this.line = line;
            this.column = column;
        }
        public TokenKind kind() { return kind; }
        public SourceRange range() { return range; }
        public String text() { return text; }
        public int line() { return line; }
        public int column() { return column; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Token)) return false;
            Token value = (Token) other;
            return java.util.Objects.equals(kind, value.kind) && java.util.Objects.equals(range, value.range) && java.util.Objects.equals(text, value.text) && line == value.line && column == value.column;
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(kind);
            hash = 31 * hash + java.util.Objects.hashCode(range);
            hash = 31 * hash + java.util.Objects.hashCode(text);
            hash = 31 * hash + Integer.hashCode(line);
            hash = 31 * hash + Integer.hashCode(column);
            return hash;
        }
        @Override public String toString() { return "Token[kind=" + kind + ", range=" + range + ", text=" + text + ", line=" + line + ", column=" + column + "]"; }


    public boolean trivia() {
        return kind == TokenKind.WHITESPACE || kind == TokenKind.LINE_TERMINATOR
                || kind == TokenKind.COMMENT || kind == TokenKind.HASHBANG;
    }

    }
