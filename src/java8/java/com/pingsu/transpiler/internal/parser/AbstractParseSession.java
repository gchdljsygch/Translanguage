package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.internal.parser.SourceParser.Language;
import com.pingsu.transpiler.internal.parser.SourceParser.Result;
import com.pingsu.transpiler.syntax.Program;
import com.pingsu.transpiler.syntax.SourceRange;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.pingsu.transpiler.internal.parser.IdentifierRules.RESERVED;
import static com.pingsu.transpiler.internal.parser.IdentifierRules.STRICT_RESERVED;
import static com.pingsu.transpiler.internal.parser.IdentifierRules.spelling;

/**
 * Owns a single parse lifecycle, token cursor, checkpoints and resource accounting.
 * Grammar modules compose over this session; they never inherit or copy mutable parser state.
 */
abstract class AbstractParseSession {
    enum State { CREATED, PARSING, COMPLETED, FAILED }
    private State state = State.CREATED;
    private static final int MAX_DEPTH = 128;
    private final String name, source;
    private final Language language;
    private final SourceLexer lexer;
    private final List<Token> tokens = new ArrayList<>();
    private Token look, last;
    private TokenReader.Checkpoint beforeLook;
    private TokenReader.Goal lookGoal;
    private int lookTokenCount, depth;
    private long work;
    private final long budget;
    final GrammarContext grammar;

    AbstractParseSession(String name, String source, Language language, boolean moduleCode) {
        this.name = name;
        this.source = source;
        this.language = language;
        grammar = new GrammarContext(moduleCode);
        lexer = new SourceLexer(name, source);
        budget = 4096L + source.length() * 64L;
    }

    final Result parse() {
        if (state != State.CREATED) throw new IllegalStateException("Parse sessions are single use");
        state = State.PARSING;
        try {
            List<SyntaxNode> statements = parseStatements();
            expectEof();
            Program program = new Program(new SourceRange(0, source.length()), statements, tokens);
            validate(program);
            Result result = new Result(program, lexer.diagnostics());
            state = result.successful() ? State.COMPLETED : State.FAILED;
            return result;
        } catch (ParseFailure failure) {
            state = State.FAILED;
            List<Diagnostic> diagnostics = lexer.diagnostics();
            return new Result(null, diagnostics.isEmpty() ? Compat.list(failure.diagnostic) : diagnostics);
        } catch (RuntimeException | Error failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    protected abstract List<SyntaxNode> parseStatements();
    protected abstract void validate(Program program);

    final State state() { return state; }
    final boolean module() { return grammar.module; }
    final Language language() { return language; }
    final Token last() { return last; }
    final int depth() { return depth; }

    final ParseFailure failureAt(int offset, String message) {
        com.pingsu.transpiler.internal.source.SourceText.Location location = new com.pingsu.transpiler.internal.source.SourceText(source).location(offset);
        return new ParseFailure(new Diagnostic(name, location.line(), location.column(), "TS_SYNTAX", message));
    }

    /** A lexical checkpoint deliberately excludes the monotonically increasing work counter. */
    static final class Mark {
        private final TokenReader.Checkpoint lexer;
        private final Token look;
        private final Token last;
        private final TokenReader.Checkpoint beforeLook;
        private final TokenReader.Goal goal;
        private final int lookCount;
        private final int tokenCount;
        Mark(TokenReader.Checkpoint lexer, Token look, Token last, TokenReader.Checkpoint beforeLook,
                TokenReader.Goal goal, int lookCount, int tokenCount) {
            this.lexer = lexer;
            this.look = look;
            this.last = last;
            this.beforeLook = beforeLook;
            this.goal = goal;
            this.lookCount = lookCount;
            this.tokenCount = tokenCount;
        }
        public TokenReader.Checkpoint lexer() { return lexer; }
        public Token look() { return look; }
        public Token last() { return last; }
        public TokenReader.Checkpoint beforeLook() { return beforeLook; }
        public TokenReader.Goal goal() { return goal; }
        public int lookCount() { return lookCount; }
        public int tokenCount() { return tokenCount; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Mark)) return false;
            Mark value = (Mark) other;
            return java.util.Objects.equals(lexer, value.lexer) && java.util.Objects.equals(look, value.look) && java.util.Objects.equals(last, value.last) && java.util.Objects.equals(beforeLook, value.beforeLook) && java.util.Objects.equals(goal, value.goal) && lookCount == value.lookCount && tokenCount == value.tokenCount;
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(lexer);
            hash = 31 * hash + java.util.Objects.hashCode(look);
            hash = 31 * hash + java.util.Objects.hashCode(last);
            hash = 31 * hash + java.util.Objects.hashCode(beforeLook);
            hash = 31 * hash + java.util.Objects.hashCode(goal);
            hash = 31 * hash + Integer.hashCode(lookCount);
            hash = 31 * hash + Integer.hashCode(tokenCount);
            return hash;
        }
        @Override public String toString() { return "Mark[lexer=" + lexer + ", look=" + look + ", last=" + last + ", beforeLook=" + beforeLook + ", goal=" + goal + ", lookCount=" + lookCount + ", tokenCount=" + tokenCount + "]"; }

    }

    final Token peek() { return peek(TokenReader.Goal.DIVISION); }

    final Token peek(TokenReader.Goal goal) {
        if (++work > budget) throw limitFailure(last, "Parser work limit exceeded");
        if (look != null && beforeLook != null && lookGoal != goal && look.text().startsWith("/")) {
            lexer.restore(beforeLook); tokens.subList(lookTokenCount, tokens.size()).clear(); look = null;
        }
        if (look == null) {
            beforeLook = lexer.checkpoint(); lookTokenCount = tokens.size(); lookGoal = goal;
            do { look = lexer.next(goal); if (look.trivia()) tokens.add(look); } while (look.trivia());
            if (look.kind() == TokenKind.ERROR || !lexer.diagnostics().isEmpty()) throw failure(look, "Invalid token");
        }
        return look;
    }

    final Token take() { Token token = look == null ? peek() : look; tokens.add(token); last = token; look = null; return token; }

    final boolean at(String text) { return peek().text().equals(text); }

    final boolean eat(String text) { if (!at(text)) return false; take(); return true; }

    final Token expect(String text) { if (!at(text)) throw failure(peek(), "Expected '" + text + "'"); return take(); }

    final void expectEof() { if (peek().kind() != TokenKind.EOF) throw failure(peek(), "Expected end of input"); take(); }

    final boolean end() { return peek().kind() == TokenKind.EOF; }

    final boolean newline() {
        int start = last == null ? 0 : last.range().end();
        int stop = peek().range().start();
        for (int i = start; i < stop; i++) if ("\r\n\u2028\u2029".indexOf(source.charAt(i)) >= 0) return true;
        return false;
    }

    final ParseFailure failure(Token token, String message) {
        return new ParseFailure(new Diagnostic(name, token == null ? 1 : token.line(), token == null ? 1 : token.column(), "TS_SYNTAX", message));
    }

    final ParseFailure limitFailure(Token token, String message) { return new ParseFailure(failure(token, message).diagnostic, true); }

    final void unsupported(Token token, String message) {
        throw new ParseFailure(new Diagnostic(name, token.line(), token.column(), "TS_UNSUPPORTED", message));
    }

    final void ts() { if (language == Language.JAVASCRIPT) throw failure(peek(), "TypeScript syntax is not allowed in JavaScript"); }

    final int start() { return peek().range().start(); }

    final ParsedNode node(String kind, int start, List<? extends SyntaxNode> children) {
        return new ParsedNode(kind, new SourceRange(start, last == null ? start : Math.max(start, last.range().end())), new ArrayList<>(children), Compat.map());
    }

    final ParsedNode node(String kind, int start, SyntaxNode... children) { return node(kind, start, Compat.list(children)); }

    final ParsedNode leaf(String kind) { int start = start(); take(); return node(kind, start); }

    final String text(SyntaxNode node) { return source.substring(node.range().start(), node.range().end()); }

    final void enter() { if (depth >= MAX_DEPTH) throw limitFailure(peek(), "Syntax nesting limit exceeded (" + MAX_DEPTH + ")"); depth++; }

    final void leave() { depth--; }

    final Mark mark() { return new Mark(lexer.checkpoint(), look, last, beforeLook, lookGoal, lookTokenCount, tokens.size()); }

    final void restore(Mark mark) {
        lexer.restore(mark.lexer()); look = mark.look(); last = mark.last(); beforeLook = mark.beforeLook();
        lookGoal = mark.goal(); lookTokenCount = mark.lookCount();
        tokens.subList(mark.tokenCount(), tokens.size()).clear();
    }

    final String nextText() { Mark mark = mark(); take(); String next = peek().text(); restore(mark); return next; }

    final boolean identifier(Token token) {
        return (token.kind() == TokenKind.IDENTIFIER || token.kind() == TokenKind.KEYWORD)
                && !RESERVED.contains(spelling(token.text())) && (!grammar.strict || !STRICT_RESERVED.contains(spelling(token.text())))
                && !((grammar.async || grammar.moduleCode) && spelling(token.text()).equals("await")) && !(grammar.generator && spelling(token.text()).equals("yield"));
    }

    final void semicolon() {
        if (!eat(";") && !end() && !at("}") && !newline()) throw failure(peek(), "Expected semicolon or line terminator");
    }

    final void greater() {
        Token token = peek(); if (!token.text().startsWith(">")) throw failure(token, "Expected '>'");
        if (token.text().length() == 1) { take(); return; }
        Token head = new Token(TokenKind.PUNCTUATION, new SourceRange(token.range().start(), token.range().start() + 1), ">", token.line(), token.column());
        tokens.add(head); last = head;
        look = new Token(TokenKind.PUNCTUATION, new SourceRange(token.range().start() + 1, token.range().end()), token.text().substring(1), token.line(), token.column() + 1);
        beforeLook = null;
    }
}
