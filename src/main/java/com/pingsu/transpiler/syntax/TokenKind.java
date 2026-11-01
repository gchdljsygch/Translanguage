package com.pingsu.transpiler.syntax;

/** Lexical categories independent of parser token numbers. */
public enum TokenKind {
    IDENTIFIER, KEYWORD, NUMBER, BIGINT, STRING, REGULAR_EXPRESSION,
    TEMPLATE, PUNCTUATION, WHITESPACE, LINE_TERMINATOR, COMMENT, HASHBANG, ERROR, EOF
}
