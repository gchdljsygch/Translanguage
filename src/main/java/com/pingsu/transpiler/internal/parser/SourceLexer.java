package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.internal.source.SourceText;
import com.pingsu.transpiler.syntax.SourceRange;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Independent UTF-16 lexer. No generated code or third-party runtime.
 * The parser supplies the lexical goal at each slash; this class never guesses from the last token.
 * Template frames count expression braces; strings, regexps and nested templates are opaque.
 */
public final class SourceLexer implements TokenReader {
    private static final int MAX_TEMPLATE_DEPTH = 256;
    private static final Set<String> KEYWORDS = Set.of(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
            "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "import", "in",
            "instanceof", "new", "null", "return", "super", "switch", "this", "throw", "true", "try", "typeof",
            "var", "void", "while", "with", "let", "static", "yield", "await", "async", "implements", "interface",
            "package", "private", "protected", "public", "type", "declare", "namespace", "module", "abstract",
            "readonly", "override", "as", "satisfies", "keyof", "infer", "is", "asserts", "of", "from", "get", "set",
            "any", "unknown", "never", "number", "string", "boolean", "bigint", "symbol", "object", "undefined");
    // Longest first. '?.' before a decimal digit is conditional syntax, not optional access.
    private static final List<String> OPERATORS = List.of(
            ">>>=", "===", "!==", "**=", "&&=", "||=", "??=", "<<=", ">>=", ">>>", "...",
            "=>", "==", "!=", "<=", ">=", "++", "--", "**", "&&", "||", "??", "?.", "<<", ">>",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "{", "}", "(", ")", "[", "]", ";", ",", ".", ":", "?", "+", "-", "*", "/", "%",
            "<", ">", "=", "!", "~", "&", "|", "^", "@", "#");
    private static final class TemplateFrame { boolean text = true; int braces; }
    private record FrameState(boolean text, int braces) { }
    private record Position(SourceLexer owner, int offset, List<FrameState> frames,
                            List<Diagnostic> diagnostics) implements Checkpoint { }
    private final String name;
    private final SourceText source;
    private final String text;
    private final ArrayDeque<TemplateFrame> templates = new ArrayDeque<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int offset;

    public SourceLexer(String sourceName, String source) {
        this.name = Objects.requireNonNull(sourceName);
        this.source = new SourceText(source);
        this.text = source;
    }
    @Override public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }
    @Override public Checkpoint checkpoint() {
        return new Position(this, offset, templates.stream().map(frame -> new FrameState(frame.text, frame.braces)).toList(), diagnostics());
    }
    @Override public void restore(Checkpoint checkpoint) {
        if (!(checkpoint instanceof Position position) || position.owner() != this)
            throw new IllegalArgumentException("Checkpoint belongs to another token reader");
        offset = position.offset(); templates.clear();
        for (FrameState state : position.frames()) {
            TemplateFrame frame = new TemplateFrame(); frame.text = state.text(); frame.braces = state.braces();
            templates.addLast(frame);
        }
        diagnostics.clear(); diagnostics.addAll(position.diagnostics());
    }
    @Override public Token next(Goal goal) {
        Objects.requireNonNull(goal);
        int start = offset;
        if (offset == text.length()) {
            if (!templates.isEmpty()) { templates.clear(); report(start, "Unterminated template literal"); }
            return token(TokenKind.EOF, start);
        }
        if (!templates.isEmpty() && templates.peek().text) return templateText();
        int cp = text.codePointAt(offset);
        if (lineTerminator(cp)) {
            offset++;
            if (cp == '\r' && at('\n')) offset++;
            return token(TokenKind.LINE_TERMINATOR, start);
        }
        if (space(cp)) {
            do { offset += Character.charCount(text.codePointAt(offset)); }
            while (offset < text.length() && space(text.codePointAt(offset)));
            return token(TokenKind.WHITESPACE, start);
        }
        if (start == 0 && starts("#!")) {
            while (offset < text.length() && !lineTerminator(text.codePointAt(offset))) offset++;
            return token(TokenKind.HASHBANG, start);
        }
        if (starts("//")) {
            offset += 2;
            while (offset < text.length() && !lineTerminator(text.codePointAt(offset))) offset++;
            return token(TokenKind.COMMENT, start);
        }
        if (starts("/*")) {
            offset += 2;
            while (offset < text.length() && !starts("*/")) offset++;
            if (offset == text.length()) return error(start, "Unterminated block comment");
            offset += 2; return token(TokenKind.COMMENT, start);
        }
        if (cp == '\'' || cp == '"') return string();
        if (cp == '`') {
            offset++;
            if (templates.size() >= MAX_TEMPLATE_DEPTH) {
                templates.clear(); offset = text.length(); return error(start, "Template nesting limit exceeded");
            }
            templates.push(new TemplateFrame()); return token(TokenKind.TEMPLATE, start);
        }
        if (!templates.isEmpty()) {
            if (cp == '{') templates.peek().braces++;
            if (cp == '}') {
                if (templates.peek().braces == 0) {
                    offset++; templates.peek().text = true; return token(TokenKind.TEMPLATE, start);
                }
                templates.peek().braces--;
            }
        }
        if (identifierStart(cp) || cp == '\\') return identifier();
        if (digit(cp) || cp == '.' && offset + 1 < text.length() && digit(text.charAt(offset + 1))) return number();
        if (cp == '/' && goal == Goal.REGULAR_EXPRESSION) return regularExpression();
        for (String operator : OPERATORS) {
            if (operator.equals("?.") && offset + 2 < text.length() && digit(text.charAt(offset + 2))) continue;
            if (starts(operator)) { offset += operator.length(); return token(TokenKind.PUNCTUATION, start); }
        }
        offset += Character.charCount(cp);
        return error(start, "Unexpected character U+" + Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT));
    }
    private Token templateText() {
        int start = offset;
        if (at('`')) { offset++; templates.pop(); return token(TokenKind.TEMPLATE, start); }
        if (starts("${")) { offset += 2; templates.peek().text = false; return token(TokenKind.TEMPLATE, start); }
        while (offset < text.length() && !at('`') && !starts("${")) {
            if (at('\\')) {
                offset++;
                if (offset < text.length()) {
                    char escaped = text.charAt(offset++);
                    if (escaped == '\r' && at('\n')) offset++;
                }
            } else offset += Character.charCount(text.codePointAt(offset));
        }
        return token(TokenKind.TEMPLATE, start);
    }
    private Token string() {
        int start = offset;
        char quote = text.charAt(offset++);
        while (offset < text.length()) {
            char c = text.charAt(offset);
            if (c == quote) { offset++; return token(TokenKind.STRING, start); }
            if (c == '\r' || c == '\n') return error(start, "Unterminated string literal");
            if (c == '\\') {
                offset++;
                if (offset == text.length()) break;
                char escaped = text.charAt(offset++);
                if (escaped == '\r' && at('\n')) offset++;
                else if (escaped == 'x') {
                    if (!hexDigits(2)) return error(start, "Invalid hexadecimal string escape");
                } else if (escaped == 'u') {
                    if (unicodeValue() < 0) return error(start, "Invalid Unicode string escape");
                }
            } else offset += Character.charCount(text.codePointAt(offset));
        }
        return error(start, "Unterminated string literal");
    }
    private Token identifier() {
        int start = offset;
        StringBuilder decoded = new StringBuilder();
        boolean first = true;
        while (offset < text.length()) {
            int cp = text.codePointAt(offset);
            if (cp == '\\') {
                offset++;
                if (!at('u')) return error(start, "Identifiers only permit Unicode escapes");
                offset++;
                cp = unicodeValue();
                if (cp < 0 || !(first ? identifierStart(cp) : identifierPart(cp)))
                    return error(start, "Invalid escaped identifier character");
            } else {
                if (!(first ? identifierStart(cp) : identifierPart(cp))) break;
                offset += Character.charCount(cp);
            }
            decoded.appendCodePoint(cp); first = false;
        }
        return token(KEYWORDS.contains(decoded.toString()) ? TokenKind.KEYWORD : TokenKind.IDENTIFIER, start);
    }
    /** Reads digits after an already consumed Unicode escape prefix. */
    private int unicodeValue() {
        int value = 0;
        if (at('{')) {
            offset++; int count = 0;
            while (offset < text.length() && hex(text.charAt(offset)) >= 0) {
                int digit = hex(text.charAt(offset++));
                if (value <= 0x10FFFF) value = value * 16 + digit;
                count++;
            }
            if (!at('}') || count == 0 || value > 0x10FFFF) return -1;
            offset++; return value;
        }
        for (int i = 0; i < 4; i++) {
            if (offset == text.length() || hex(text.charAt(offset)) < 0) return -1;
            value = value * 16 + hex(text.charAt(offset++));
        }
        return value;
    }
    private boolean hexDigits(int count) {
        for (int i = 0; i < count; i++) {
            if (offset == text.length() || hex(text.charAt(offset)) < 0) return false;
            offset++;
        }
        return true;
    }
    private Token number() {
        int start = offset;
        boolean valid = true;
        boolean fractional = false;
        boolean exponent = false;
        boolean prefixed = false;
        if (starts("0x") || starts("0X") || starts("0o") || starts("0O") || starts("0b") || starts("0B")) {
            char prefix = Character.toLowerCase(text.charAt(offset + 1));
            int radix = prefix == 'x' ? 16 : prefix == 'o' ? 8 : 2;
            offset += 2; valid = digits(radix); prefixed = true;
        } else {
            if (!at('.')) valid = digits(10);
            if (at('.')) {
                fractional = true; offset++;
                if (offset < text.length() && (digit(text.charAt(offset)) || at('_'))) valid &= digits(10);
            }
            if (at('e') || at('E')) {
                exponent = true; offset++;
                if (at('+') || at('-')) offset++;
                valid &= digits(10);
            }
        }
        boolean bigint = at('n');
        if (bigint) { offset++; valid &= !fractional && !exponent; }
        if (!prefixed && offset - start > 1 && text.charAt(start) == '0') {
            char second = text.charAt(start + 1);
            if (second == '_' || bigint && digit(second) || digit(second) && text.substring(start, offset).indexOf('_') >= 0) valid = false;
        }
        if (offset < text.length() && (identifierStart(text.codePointAt(offset)) || digit(text.charAt(offset)) || at('\\'))) {
            valid = false;
            while (offset < text.length() && (identifierPart(text.codePointAt(offset)) || at('\\')))
                offset += Character.charCount(text.codePointAt(offset));
        }
        return valid ? token(bigint ? TokenKind.BIGINT : TokenKind.NUMBER, start) : error(start, "Invalid numeric literal");
    }
    private boolean digits(int radix) {
        boolean any = false, previousDigit = false, valid = true;
        while (offset < text.length()) {
            int value = hex(text.charAt(offset));
            if (value >= 0 && value < radix) { any = true; previousDigit = true; offset++; }
            else if (at('_')) { valid &= previousDigit; previousDigit = false; offset++; }
            else break;
        }
        return any && previousDigit && valid;
    }
    private Token regularExpression() {
        int start = offset++;
        boolean characterClass = false;
        boolean closed = false;
        while (offset < text.length()) {
            char c = text.charAt(offset);
            if (lineTerminator(c)) break;
            offset++;
            if (c == '\\') {
                if (offset == text.length() || lineTerminator(text.charAt(offset))) break;
                offset += Character.charCount(text.codePointAt(offset));
            } else if (c == '[') characterClass = true;
            else if (c == ']') characterClass = false;
            else if (c == '/' && !characterClass) { closed = true; break; }
        }
        if (!closed) return error(start, "Unterminated regular expression literal");
        StringBuilder flags = new StringBuilder();
        boolean valid = true;
        while (offset < text.length() && identifierPart(text.codePointAt(offset))) {
            int cp = text.codePointAt(offset);
            if (cp > 127 || "dgimsuvy".indexOf(cp) < 0 || flags.indexOf(Character.toString(cp)) >= 0) valid = false;
            flags.appendCodePoint(cp); offset += Character.charCount(cp);
        }
        if (flags.indexOf("u") >= 0 && flags.indexOf("v") >= 0) valid = false;
        return valid ? token(TokenKind.REGULAR_EXPRESSION, start) : error(start, "Invalid regular expression flags");
    }
    private Token token(TokenKind kind, int start) {
        SourceRange range = new SourceRange(start, offset);
        var location = source.location(start);
        return new Token(kind, range, source.slice(range), location.line(), location.column());
    }
    private Token error(int start, String message) { report(start, message); return token(TokenKind.ERROR, start); }
    private void report(int start, String message) {
        var location = source.location(start);
        diagnostics.add(new Diagnostic(name, location.line(), location.column(), "TS_LEXER", message));
    }
    private boolean at(char c) { return offset < text.length() && text.charAt(offset) == c; }
    private boolean starts(String value) { return text.startsWith(value, offset); }
    private static boolean digit(int c) { return c >= '0' && c <= '9'; }
    private static int hex(int c) {
        if (digit(c)) return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
    }
    private static boolean identifierStart(int cp) { return cp == '$' || cp == '_' || Character.isUnicodeIdentifierStart(cp); }
    private static boolean identifierPart(int cp) {
        return identifierStart(cp) || cp == 0x200C || cp == 0x200D || Character.isUnicodeIdentifierPart(cp) && !Character.isIdentifierIgnorable(cp);
    }
    private static boolean lineTerminator(int cp) { return cp == '\r' || cp == '\n' || cp == 0x2028 || cp == 0x2029; }
    private static boolean space(int cp) { return cp == '\t' || cp == 0x0B || cp == 0x0C || cp == 0xFEFF || Character.getType(cp) == Character.SPACE_SEPARATOR; }
}
