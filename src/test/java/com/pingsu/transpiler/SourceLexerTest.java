package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.SourceLexer;
import com.pingsu.transpiler.internal.parser.TokenReader.Goal;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SourceLexerTest {
    private static List<Token> scan(SourceLexer lexer, Goal goal) {
        var tokens = new ArrayList<Token>();
        for (int budget = 0; budget < 100000; budget++) {
            Token token = lexer.next(goal); tokens.add(token);
            if (token.kind() == TokenKind.EOF) return tokens;
            assertTrue(token.range().length() > 0, "Every non-EOF token must advance");
        }
        fail("Scanner failed to terminate"); return tokens;
    }
    @Test void triviaHashbangUnicodeAndOffsetsRoundTripExactly() {
        String source = "#!/usr/bin/env node\r\n// 中文 😀\u2028const 𐐀 = '\u2029😀'; /* \r\n */\t";
        var lexer = new SourceLexer("unicode.ts", source);
        var tokens = scan(lexer, Goal.DIVISION);
        assertEquals(source, tokens.stream().map(Token::text).reduce("", String::concat));
        assertTrue(lexer.diagnostics().isEmpty(), lexer.diagnostics().toString());
        assertEquals(TokenKind.HASHBANG, tokens.get(0).kind());
        Token identifier = tokens.stream().filter(t -> t.text().equals("𐐀")).findFirst().orElseThrow();
        assertEquals(TokenKind.IDENTIFIER, identifier.kind());
        assertEquals(3, identifier.line()); assertEquals(7, identifier.column());
        assertEquals(2, identifier.range().length());
    }
    @Test void slashInterpretationIsSuppliedByTheParserNotByAHeuristic() {
        var regexp = new SourceLexer("a", "/[}/]\\//giu");
        assertEquals(TokenKind.REGULAR_EXPRESSION, regexp.next(Goal.REGULAR_EXPRESSION).kind());
        assertTrue(regexp.diagnostics().isEmpty());
        var division = new SourceLexer("a", "/= 2 / 3");
        assertEquals(List.of("/=", "2", "/", "3"), scan(division, Goal.DIVISION).stream()
                .filter(t -> !t.trivia() && t.kind() != TokenKind.EOF).map(Token::text).toList());
        var context = new SourceLexer("a", "/x/ / 2");
        assertEquals(TokenKind.REGULAR_EXPRESSION, context.next(Goal.REGULAR_EXPRESSION).kind());
        assertEquals(TokenKind.WHITESPACE, context.next(Goal.DIVISION).kind());
        assertEquals("/", context.next(Goal.DIVISION).text());
    }
    @Test void nestedTemplatesDistinguishObjectsBlocksStringsAndRegexBraces() {
        String source = "`outer${(() => { const x = {v: `inner${{n: 1}.n}`}; return /[}]/.test('}') ? x.v : ''; })()}end`";
        var lexer = new SourceLexer("nested.ts", source);
        var tokens = scan(lexer, Goal.REGULAR_EXPRESSION);
        assertTrue(lexer.diagnostics().isEmpty(), lexer.diagnostics().toString());
        assertEquals(source, tokens.stream().map(Token::text).reduce("", String::concat));
        assertEquals(2, tokens.stream().filter(t -> t.kind() == TokenKind.TEMPLATE && t.text().equals("${")).count());
        assertEquals(2, tokens.stream().filter(t -> t.kind() == TokenKind.TEMPLATE && t.text().equals("}")).count());
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == TokenKind.PUNCTUATION && t.text().equals("}")));
    }
    @ParameterizedTest(name = "LEX-NUMBER/{0}")
    @ValueSource(strings = {"0", "12_345", ".5", "1.", "1.2e+3", "0xF_F", "0b10_01", "0o7_1", "123n", "0xFFn"})
    void acceptsCompleteNumericTokens(String source) {
        var lexer = new SourceLexer("number.ts", source);
        Token token = lexer.next(Goal.DIVISION);
        assertTrue(token.kind() == TokenKind.NUMBER || token.kind() == TokenKind.BIGINT);
        assertEquals(source, token.text()); assertTrue(lexer.diagnostics().isEmpty());
        assertEquals(TokenKind.EOF, lexer.next(Goal.DIVISION).kind());
    }
    @ParameterizedTest(name = "LEX-REJECT/{index}")
    @ValueSource(strings = {"1_", "1__2", "0x_FF", "0b2", "1e+", "1.0n", "01n", "0_1", "00_1", "1abc", "0o8", "§", "\\q", "\\u{}", "\\u{110000}", "'\\xGG'", "'unterminated", "/*unterminated", "`unterminated", "`x${1"})
    void malformedTokensFailWithLocationsAndMakeProgress(String source) {
        var lexer = new SourceLexer("bad.ts", source);
        scan(lexer, Goal.DIVISION);
        assertFalse(lexer.diagnostics().isEmpty(), source);
        var diagnostic = lexer.diagnostics().get(0);
        assertEquals("TS_LEXER", diagnostic.code()); assertEquals("bad.ts", diagnostic.sourceName());
        assertTrue(diagnostic.line() >= 1 && diagnostic.column() >= 1);
        int count = lexer.diagnostics().size();
        assertEquals(TokenKind.EOF, lexer.next(Goal.DIVISION).kind());
        assertEquals(count, lexer.diagnostics().size(), "EOF must not duplicate errors");
    }
    @ParameterizedTest(name = "LEX-REGEXP-REJECT/{index}")
    @ValueSource(strings = {"/x/gg", "/x/uv", "/x/z", "/[x/", "/x\n/"})
    void invalidRegularExpressionsFail(String source) {
        var lexer = new SourceLexer("regex.ts", source);
        assertEquals(TokenKind.ERROR, lexer.next(Goal.REGULAR_EXPRESSION).kind());
        assertFalse(lexer.diagnostics().isEmpty());
    }
    @Test void escapesAndConditionalDecimalsHaveUnambiguousBoundaries() {
        String source = "const \\u{10400} = test?.3:4; const text = '\\u{1F600}\\x41';";
        var lexer = new SourceLexer("a", source);
        var tokens = scan(lexer, Goal.DIVISION);
        assertTrue(lexer.diagnostics().isEmpty(), lexer.diagnostics().toString());
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == TokenKind.IDENTIFIER && t.text().equals("\\u{10400}")));
        assertFalse(tokens.stream().anyMatch(t -> t.text().equals("?.")));
        assertTrue(tokens.stream().anyMatch(t -> t.text().equals(".3")));
    }
    @Test void templateNestingIsBoundedWithoutStackOverflow() {
        String source = "`x${".repeat(300) + "0" + "}`".repeat(300);
        var lexer = new SourceLexer("deep.ts", source);
        scan(lexer, Goal.DIVISION);
        assertTrue(lexer.diagnostics().stream().anyMatch(d -> d.message().contains("limit")));
    }
    @Test void fixedSeedTriviaInsertionPreservesTokenSequence() {
        Random random = new Random(0xC0FFEE);
        List<String> words = List.of("const", "value", "=", "12_345", "+", "'😀'", ";");
        List<String> trivia = List.of(" ", "\r\n", "/* 😀 */", "// comment\u2028", "\u00A0");
        for (int trial = 0; trial < 100; trial++) {
            String source = String.join(trivia.get(random.nextInt(trivia.size())), words);
            var lexer = new SourceLexer("seed.ts", source);
            assertEquals(words, scan(lexer, Goal.DIVISION).stream().filter(t -> !t.trivia() && t.kind() != TokenKind.EOF)
                    .map(Token::text).toList(), "seed=0xC0FFEE trial=" + trial);
            assertTrue(lexer.diagnostics().isEmpty());
        }
    }

    @Test void speculativeLexingRestoresDiagnosticsAndTemplateFrames() {
        var lexer = new SourceLexer("probe.ts", "`x${/broken}`");
        lexer.next(Goal.DIVISION); lexer.next(Goal.DIVISION); lexer.next(Goal.DIVISION);
        var position = lexer.checkpoint();
        assertEquals(TokenKind.ERROR, lexer.next(Goal.REGULAR_EXPRESSION).kind());
        assertFalse(lexer.diagnostics().isEmpty());
        lexer.restore(position);
        assertTrue(lexer.diagnostics().isEmpty());
        assertEquals("/", lexer.next(Goal.DIVISION).text());
        assertEquals("broken", lexer.next(Goal.DIVISION).text());
        assertEquals(TokenKind.TEMPLATE, lexer.next(Goal.DIVISION).kind());
        assertEquals("`", lexer.next(Goal.DIVISION).text());
        assertEquals(TokenKind.EOF, lexer.next(Goal.DIVISION).kind());
        assertTrue(lexer.diagnostics().isEmpty());
        var other = new SourceLexer("other.ts", "");
        assertThrows(IllegalArgumentException.class, () -> other.restore(position));
    }
}
