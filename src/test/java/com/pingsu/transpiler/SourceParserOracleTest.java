package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Independent parser acceptance oracle; every fixture is checked by Acorn and our JS grammar. */
class SourceParserOracleTest {
    @TempDir Path temporary;
    @Test void acceptsAndRejectsTheSameGrammarBoundariesAsAcorn() throws Exception {
        List<String> fixtures = new ArrayList<>(List.of(
            "const x = 1 + 2 * 3 ** 4;", "const x = 2 ** 3 ** 2;", "const x = (-2) ** 2;",
            "let x = 1; x\n++x;", "const x = a ?? (b || c);", "const x = (a && b) ?? c;",
            "function f() {return /*\n*/ /r/.test('r')} if (true) /r/.test('r');",
            "const x = ({v: 6}).v / 2; const f = function(){} / 2;",
            "const t = `${(() => {return {v: `${1}`}})().v}`;",
            "const t = tag`raw${/}/.test('}')}`;", "const x = a < b >> c;",
            "for (const x of [1]) {} for (let x in {}) {} for(let x=0;x<2;x++){}",
            "async function* f() {for await (const x of g()) {yield x}}",
            "const o = {async f() {return await 1}, *g() {yield* [1]}, get x() {return 1}, set x(v) {}};",
            "class C extends B { constructor(x) {super(x)} static f() {return super.f()} [key()] = 1; }",
            "const f = async (x=1, ...rest) => await x;", "const x = new (getClass())(1).value;",
            "let a; ({a = 1} = {});", "const f = (a,a) => a;", "const o = {a = 1};", "const x = new obj?.Ctor();", "class C {get x(a){} set x() {}}", "for (const a=1 of [2]) {}", "for (const a;;) {}", "if (true) {export {};}", "let \\u0076ar = 1;", "const x = {default};", "const x = /a/u;", "let await = 1; export {};", "const f = ({a}, a) => a;", "function f({a},a) {}", "function f([a],{b:a}) {}", "const f=({a=foo(b)}, b)=>a;", "function f(a=b,b=1) {}", "a.'b';", "a.[0];", "a?.'b';", "({a: 1} = x);", "[1] = x;", "[...a,] = x;", "({...a,} = x);", "({f(){}}=x);", "const o={get x(a) {}};", "({a: obj.x, b: [c=1]} = x);", "try {} catch ({message}) {} finally {}",
            "import x, {y as z} from 'm'; export {z};", "export default function() {}",
            "function f(){'use strict'; return 1;} const n = 1_000;",
            "let a; a?.b?.(1); a?.[0];", "const x = /a/g; const y = 5 / /a/.source.length;",
            "const x = ;", "const x = 1; )", "const x = 1 2;", "const x = a ?? b || c;",
            "const x = a && b ?? c;", "const x = -2 ** 2;", "const x = 1; x++; 1 = x;",
            "const f = (x)\n=> x;", "function f(){throw\n1}", "const f = (...x,) => x;",
            "'use strict'; with (x) {}", "with (x) {} export {};", "'use strict'; const eval = 1;",
            "function f(a,a){'use strict'}", "class C { f(a,a) {} }", "'use strict'; delete x;",
            "'use strict'; let n = 012;", "return 1;", "break;", "continue;", "try{}",
            "switch(x){default: break; default: break;}", "const x = a?.b = 1;", "const x = 1; §",
            "const x = /a/gg;", "const x = `${1`;", "const x = <T>value;", "let x: number = 1;"
        ));
        Random random = new Random(0x50325033);
        String[] binary = {"+", "-", "*", "/", "**", "<<", ">>", "===", "<", ">", "&", "|", "&&", "||", "??"};
        for (int i = 0; i < 100; i++) {
            String a = binary[random.nextInt(binary.length)], b = binary[random.nextInt(binary.length)];
            fixtures.add("const value = 2 " + a + " 3 " + b + " 4;");
        }
        try (var resource = getClass().getResourceAsStream("/META-INF/resources/webjars/acorn/8.15.0/dist/acorn.mjs")) {
            assertNotNull(resource); Files.copy(resource, temporary.resolve("acorn.mjs"));
        }
        Files.write(temporary.resolve("fixtures.txt"), fixtures.stream().map(s -> Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8))).toList(), StandardCharsets.UTF_8);
        Path script = temporary.resolve("oracle.mjs");
        Files.writeString(script, """
            import {parse} from './acorn.mjs';
            import {readFileSync} from 'node:fs';
            for (const line of readFileSync(new URL('./fixtures.txt', import.meta.url), 'utf8').trim().split(/\\r?\\n/)) {
                const source = Buffer.from(line, 'base64').toString('utf8');
                try { parse(source, {ecmaVersion: 2022, sourceType: 'module'}); console.log('true'); }
                catch { console.log('false'); }
            }
            """, StandardCharsets.UTF_8);
        Path output = temporary.resolve("output.txt"), error = temporary.resolve("error.txt");
        Process process = new ProcessBuilder("node", script.toString()).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Acorn oracle timed out"); }
        assertEquals(0, process.exitValue(), () -> { try {return Files.readString(error);} catch(Exception e){return e.toString();} });
        List<String> expected = Files.readAllLines(output); assertEquals(fixtures.size(), expected.size());
        List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
        for (int i = 0; i < fixtures.size(); i++) {
            String source = fixtures.get(i);
            var actual = new RecursiveDescentParser().parse("oracle.js", source + "\nexport {};", SourceParser.Language.JAVASCRIPT);
            boolean accepted = Boolean.parseBoolean(expected.get(i));
            assertions.add(() -> assertEquals(accepted, actual.successful(), source + " " + actual.diagnostics()));
        }
        assertAll(assertions);
    }
}
