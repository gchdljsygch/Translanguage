package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.RecursiveDescentParser;
import com.pingsu.transpiler.internal.parser.SourceParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SourceParserIsolationTest {
    @Test void moduleStrictnessAndFailedParsesDoNotLeakIntoLaterCalls() {
        SourceParser parser = new RecursiveDescentParser();
        String script = "with (object) {} let await = 1;";
        var expected = parser.parse("script.js", script, SourceParser.Language.JAVASCRIPT);
        assertTrue(expected.successful());
        for (String rejected : List.of(script + " export {};", "function f(){ continue; }", "const x = /r/gg;")) {
            assertFalse(parser.parse("module.js", rejected, SourceParser.Language.JAVASCRIPT).successful());
            assertEquals(expected, parser.parse("script.js", script, SourceParser.Language.JAVASCRIPT));
        }
        assertTrue(parser.parse("typed.ts", "let x: number = 1;", SourceParser.Language.TYPESCRIPT).successful());
        assertFalse(parser.parse("typed.js", "let x: number = 1;", SourceParser.Language.JAVASCRIPT).successful());
    }

    @Test void nestedGrammarRestoresFunctionClassLoopAndAmbientContexts() {
        SourceParser parser = new RecursiveDescentParser();
        for (String source : List.of(
                "outer: for (;;) { function f() { 'use strict'; return 1; } continue outer; } with (object) {}",
                "class C { method() { return 1; } } with (object) {}",
                "async function f() { await value; } let await = 1; function* g() { yield 1; } let yield = 2;",
                "declare namespace N { const value: number; } const value = 1;")) {
            var result = parser.parse("scope.ts", source, SourceParser.Language.TYPESCRIPT);
            assertTrue(result.successful(), () -> source + " " + result.diagnostics());
        }
        for (String source : List.of(
                "outer: for (;;) { function f() { continue outer; } }",
                "declare namespace N { const value: number; } const value: number;")) {
            assertFalse(parser.parse("scope.ts", source, SourceParser.Language.TYPESCRIPT).successful(), source);
        }
    }

    @Test void sharedParserProducesIdenticalTreesTokensAndDiagnosticsAcrossConcurrentCalls() throws Exception {
        SourceParser parser = new RecursiveDescentParser();
        var fixtures = List.of(
                "const f = async <T>(x:T): Promise<T> => x; export {f};",
                "const t = `${a < b ? `${/}/.test('}')}` : c}`;",
                "const x: A<B<C<number>>> = f<A<B<number>>>(v); let y = a >> b;",
                "function f() { 'use strict'; return 1; } with (object) {}",
                "let await = 1; export {};",
                "const value = ;",
                "const value = " + "(".repeat(160) + "1" + ")".repeat(160) + ";");
        var expected = fixtures.stream().map(source -> parser.parse("concurrent.ts", source, SourceParser.Language.TYPESCRIPT)).toList();
        List<Callable<SourceParser.Result>> tasks = new ArrayList<>();
        for (int repetition = 0; repetition < 8; repetition++) {
            for (String source : fixtures) tasks.add(() -> parser.parse("concurrent.ts", source, SourceParser.Language.TYPESCRIPT));
        }
        var executor = Executors.newFixedThreadPool(4);
        try {
            var results = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
            for (int i = 0; i < results.size(); i++) {
                assertFalse(results.get(i).isCancelled(), "Concurrent parse timed out");
                assertEquals(expected.get(i % fixtures.size()), results.get(i).get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}