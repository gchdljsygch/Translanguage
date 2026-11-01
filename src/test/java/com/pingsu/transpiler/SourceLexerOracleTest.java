package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.SourceLexer;
import com.pingsu.transpiler.internal.parser.TokenReader.Goal;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Acorn supplies lexical goals and an independent UTF-16 token-boundary oracle. */
class SourceLexerOracleTest {
    @TempDir Path temporary;
    @Test void javascriptTokenBoundariesAgreeWithAcorn() throws Exception {
        List<String> fixtures = List.of(
                "// 中文 😀\r\nconst 𐐀 = 1_000; const a = 0xFFn;",
                "const x = /[}/]\\//giu; if (x) /x/.test('x'); const y = 6 / 2;",
                "const x = `a${(() => { return {v: `nested${1}`}; })().v}b`;",
                "function f() { return /* line\n */ /a:b/.test('a:b'); }",
                "const x = '\\u{1F600}'; x?.length ?? 0; let a = 0; a ||= 2; a **= 3;",
                "async function* f() { yield await Promise.resolve(1); }",
                "const x = (value) => value?.3:4; // conditional decimal\u2028",
                "const x = `\\`\\${raw}${/}/.test('}')}`;"
        );
        try (var resource = getClass().getResourceAsStream("/META-INF/resources/webjars/acorn/8.15.0/dist/acorn.mjs")) {
            assertNotNull(resource, "Acorn is required; do not skip this test");
            Files.copy(resource, temporary.resolve("acorn.mjs"));
        }
        Files.writeString(temporary.resolve("sources.txt"), fixtures.stream()
                .map(s -> Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)))
                .reduce((a, b) -> a + "\n" + b).orElseThrow(), StandardCharsets.UTF_8);
        Path script = temporary.resolve("oracle.mjs");
        Files.writeString(script, """
                import {tokenizer} from './acorn.mjs';
                import {readFileSync} from 'node:fs';
                const lines = readFileSync(new URL('./sources.txt', import.meta.url), 'utf8').split('\\n');
                for (let i = 0; i < lines.length; i++) {
                    const source = Buffer.from(lines[i], 'base64').toString('utf8');
                    for (const token of tokenizer(source, {ecmaVersion: 2021, sourceType: 'module'})) {
                        if (token.start !== token.end)
                            console.log([i, token.type.label, token.start, token.end].join('\\t'));
                    }
                }
                """, StandardCharsets.UTF_8);
        Path stdout = temporary.resolve("stdout.txt"), stderr = temporary.resolve("stderr.txt");
        Process process = new ProcessBuilder("node", script.toString()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Node lexical oracle timed out"); }
        assertEquals(0, process.exitValue(), () -> read(stderr));
        var lexers = fixtures.stream().map(s -> new SourceLexer("oracle.js", s)).toList();
        for (String line : Files.readAllLines(stdout, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t");
            int fixture = Integer.parseInt(fields[0]);
            SourceLexer lexer = lexers.get(fixture);
            Goal goal = fields[1].equals("regexp") ? Goal.REGULAR_EXPRESSION : Goal.DIVISION;
            Token token;
            do { token = lexer.next(goal); } while (token.trivia());
            assertEquals(Integer.parseInt(fields[2]), token.range().start(), line + " " + token);
            assertEquals(Integer.parseInt(fields[3]), token.range().end(), line + " " + token);
        }
        for (SourceLexer lexer : lexers) {
            Token token;
            do { token = lexer.next(Goal.DIVISION); } while (token.trivia());
            assertEquals(TokenKind.EOF, token.kind());
            assertTrue(lexer.diagnostics().isEmpty(), lexer.diagnostics().toString());
        }
    }
    private static String read(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (Exception error) { return error.toString(); }
    }
}
