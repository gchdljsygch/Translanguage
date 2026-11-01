package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.Program;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ParserSessionTest {
    @Test void successfulAndFailedSessionsAreSingleUse() {
        for (String source : List.of("const value = 1;", "const value = ;")) {
            var session = new RecursiveDescentSession("session.ts", source, SourceParser.Language.TYPESCRIPT, false);
            assertEquals(AbstractParseSession.State.CREATED, session.state());
            var result = session.parse();
            var expected = result.successful() ? AbstractParseSession.State.COMPLETED : AbstractParseSession.State.FAILED;
            assertEquals(expected, session.state());
            assertThrows(IllegalStateException.class, session::parse);
            assertEquals(expected, session.state());
        }
    }

    @Test void unexpectedFailureTerminatesTheSessionAndIsNotConvertedToSyntaxError() {
        var error = new IllegalStateException("extension failure");
        var session = probe("", context -> {
            assertEquals(AbstractParseSession.State.PARSING, context.state());
            throw error;
        });
        assertSame(error, assertThrows(IllegalStateException.class, session::parse));
        assertEquals(AbstractParseSession.State.FAILED, session.state());
        assertThrows(IllegalStateException.class, session::parse);
    }

    @Test void checkpointRestoresSplitGreaterTokensAndTrivia() {
        String source = "/* leading */ >>>= value";
        var session = probe(source, context -> {
            assertEquals(">>>=", context.peek().text());
            var mark = context.mark();
            for (int i = 0; i < 3; i++) {
                context.greater();
                assertEquals(">", context.last().text());
                assertEquals(14 + i, context.last().range().start());
            }
            context.expect("=");
            context.expect("value");
            context.restore(mark);
            context.expect(">>>=");
            context.expect("value");
        });
        var result = session.parse();
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(source, sourceText(result.program()));
        assertEquals(List.of("/* leading */", " ", ">>>=", " ", "value", ""),
                result.program().tokens().stream().map(Token::text).toList());
    }

    @Test void checkpointRestoresSlashGoalAndSpeculativeLexerDiagnostics() {
        String source = "/* leading */ /unterminated";
        var session = probe(source, context -> {
            assertEquals("/", context.peek().text());
            var mark = context.mark();
            assertThrows(ParseFailure.class, () -> context.peek(TokenReader.Goal.REGULAR_EXPRESSION));
            context.restore(mark);
            context.expect("/");
            context.expect("unterminated");
        });
        var result = session.parse();
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(source, sourceText(result.program()));
    }

    @Test void slashCanBeRescannedAsRegularExpressionWithoutDuplicatingTrivia() {
        String source = "/* leading */ /r/g";
        var session = probe(source, context -> {
            assertEquals("/", context.peek().text());
            var mark = context.mark();
            assertEquals(TokenKind.REGULAR_EXPRESSION, context.peek(TokenReader.Goal.REGULAR_EXPRESSION).kind());
            context.restore(mark);
            assertEquals("/", context.peek().text());
            assertEquals("/r/g", context.peek(TokenReader.Goal.REGULAR_EXPRESSION).text());
            context.take();
        });
        var result = session.parse();
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(source, sourceText(result.program()));
        assertEquals(1, result.program().tokens().stream().filter(t -> t.kind() == TokenKind.REGULAR_EXPRESSION).count());
    }

    @Test void speculativeRollbackCannotRefundWorkBudget() {
        var session = probe("x", context -> {
            while (true) {
                var mark = context.mark();
                context.peek();
                context.restore(mark);
            }
        });
        var result = assertTimeoutPreemptively(Duration.ofSeconds(5), session::parse);
        assertNull(result.program());
        assertEquals("Parser work limit exceeded", result.diagnostics().get(0).message());
        assertEquals(AbstractParseSession.State.FAILED, session.state());
    }

    @Test void bindingModuleAcceptsAnExpressionCapabilityWithoutChangingOtherGrammar() {
        String source = "const value: number = @;";
        var session = new AbstractParseSession("extension.ts", source, SourceParser.Language.TYPESCRIPT, false) {
            @Override protected List<SyntaxNode> parseStatements() {
                var standard = new RecursiveDescentGrammar(this);
                GrammarRules.Expressions extension = new GrammarRules.Expressions() {
                    @Override public ParsedNode expression(int minimum) {
                        if (!at("@")) return standard.expression(minimum);
                        int start = start();
                        expect("@");
                        return node("extensionExpression", start);
                    }
                    @Override public void requireAssignable(ParsedNode value, boolean pattern) {
                        standard.requireAssignable(value, pattern);
                    }
                    @Override public ParsedNode initializer(String kind) { return standard.initializer(kind); }
                    @Override public ParsedNode template(boolean type) { return standard.template(type); }
                };
                return List.of(new BindingParser(this, standard, extension).variables(true));
            }
            @Override protected void validate(Program program) { CoverGrammarValidator.validate(this, program); }
        };
        var result = session.parse();
        assertTrue(result.successful(), result.diagnostics().toString());
        var declaration = result.program().children().get(0).children().get(0);
        assertEquals(List.of("identifier", "typeAnnotation", "extensionExpression"),
                declaration.children().stream().map(SyntaxNode::kind).toList());
        assertEquals(source, sourceText(result.program()));
        assertFalse(new RecursiveDescentParser().parse("standard.ts", source, SourceParser.Language.TYPESCRIPT).successful());
    }

    private static AbstractParseSession probe(String source, Consumer<AbstractParseSession> action) {
        return new AbstractParseSession("probe.ts", source, SourceParser.Language.TYPESCRIPT, false) {
            @Override protected List<SyntaxNode> parseStatements() {
                action.accept(this);
                return List.of();
            }
            @Override protected void validate(Program program) { }
        };
    }

    private static String sourceText(Program program) {
        return program.tokens().stream().map(Token::text).reduce("", String::concat);
    }
}