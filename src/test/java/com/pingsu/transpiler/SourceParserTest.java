package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.*;
import com.pingsu.transpiler.syntax.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceParserTest {
    private SourceParser.Result parse(String source) {
        return new RecursiveDescentParser().parse("parser.ts", source, SourceParser.Language.TYPESCRIPT);
    }
    @ParameterizedTest @ValueSource(strings = {
        "type F = <T extends object>(x: T, ...rest: T[]) => T;",
        "type C = new <T>(x: T) => Box<T>;",
        "type T = { readonly name?: string; (x: number): string; new (s: string): Date; method<T>(x:T): T; [key: string]: unknown };",
        "type T = [name: string, age?: number, ...rest: boolean[]];",
        "type T<X> = X extends {id: infer U} ? U : never;",
        "type T<X> = { -readonly [K in keyof X as K]-?: X[K] };",
        "type T = `prefix${string}`; type V = typeof import('m').value;",
        "type T = (A | B) & { readonly x: unique symbol };",
        "interface I<T extends X = X> extends A<T>, B { [k: number]: string; a?: T }",
        "function f(x: unknown): x is string { return typeof x === 'string'; }",
        "function f(x: unknown): asserts x is string {}",
        "const f = <T extends {x: number},>(x: T): T => x; const x = f<{x:number}>({x:1});",
        "const x: A<B<C<number>>> = f<A<B<number>>>(v); let y = a >> b; y >>>= 1;",
        "const x = value as number < 3;", "function f(this: X, x: number) { 'use strict'; return x; }",
        "declare namespace N { namespace Inner { const x: number; } }",
        "const x = a < b && c > d; const y = f < A > +b;",
        "const x = <A<B<number>>>value; const y = value as number + 1;",
        "function f() { return\n/x/.test('x'); } if (true) /x/.test('x'); const a = ({x: 4}).x / 2;",
        "const x = `${(() => { return /}/.test('}') ? `${{v: 2}.v}` : ''; })()}`;",
        "let a = 1\na\n++a; do { a--; } while (a) console.log(a);",
        "const f = async <T>(x:T): Promise<T> => x; async function* g() { yield* [1]; yield await f(2); }",
        "outer: for (let x of [1,2]) { switch(x) {case 1: continue outer; default: break;} }",
        "try { throw 1; } catch ({message}: unknown) {} finally {}",
        "class C<T> extends Base<T> implements Named { static x=1; readonly value!: T; constructor(public x:T) { super(); } }",
        "declare namespace N { export interface I {} export const x: number; }",
        "import D, {type X, value as v} from 'm'; export {type X, v}; export * as ns from 'm';",
        "import type D from 'm'; export type * from 'm'; export default class extends Base {}",
        "const f = (a = 1, {x}, ...rest) => ({a,x,rest});",
        "const obj = {get value(): number {return 1}, set value(x:number) {}, async *values() {yield 2}};"
    }) void parsesIndependentTypeAndRuntimeGrammar(String source) {
        SourceParser.Result result = parse(source);
        assertTrue(result.successful(), () -> source + "\n" + result.diagnostics());
        assertEquals(source, result.program().tokens().stream().map(Token::text).reduce("", String::concat));
        result.program().walk(node -> { assertTrue(result.program().range().contains(node.range())); return true; });
    }
    @ParameterizedTest @ValueSource(strings = {
        "const x = ;", "const x: = 1;", "const x = 1; )", "const x = 1; garbage garbage;",
        "function f() { throw\n1; }", "const f = (x)\n=> x;", "const x = 1 2;",
        "let x = a ?? b || c;", "let x = a && b ?? c;", "let x = -2 ** 2;",
        "1 = x;", "foo()++;", "obj?.x = 1;", "const f = (...args,) => args;",
        "return 1;", "break;", "continue;", "try {}", "switch(x) {default: break; default: break;}",
        "'use strict'; with (x) {}", "with (x) {} export {};", "'use strict'; const eval = 1;",
        "'use strict'; delete x;", "'use strict'; let x = 012;", "'use strict'; let x = '\\1';",
        "function f(a,a) {'use strict';}", "function f(a=1) {'use strict';}", "class C { method(a,a) {} }",
        "class eval {}", "function eval(){'use strict'}", "function f(){ await x; }", "const t = `${1`;", "const x = /bad/gg;", "let x: Array<Array<number> = 1;",
        "type X = A extends B ? C;", "type X = {a: ;};", "type X = (x: number) => ;"
    }) void rejectsInvalidInputWithoutPartialProgram(String source) {
        SourceParser.Result result = parse(source);
        assertFalse(result.successful(), source); assertNull(result.program(), source);
        assertFalse(result.diagnostics().isEmpty(), source);
    }
    @Test void javascriptModeRejectsEveryTypeScriptEntryPoint() {
        for (String source : List.of("let x: number = 1;", "interface X {}", "type X = number;", "enum E {A}",
                "const x = v as T;", "const x = <T>v;", "const x = v!;", "class C { public x; }",
                "const f = <T>(x:T) => x;", "import type {X} from 'm';", "function f(x) {}")) {
            var result = new RecursiveDescentParser().parse("output.js", source, SourceParser.Language.JAVASCRIPT);
            assertEquals(source.equals("function f(x) {}"), result.successful(), source);
        }
    }
    @Test void depthAndWorkLimitsReturnDiagnosticsRatherThanOverflowOrHang() {
        for (String source : List.of("const x = " + "(".repeat(1000) + "1" + ")".repeat(1000) + ";",
                "type X = " + "Array<".repeat(1000) + "number" + ">".repeat(1000) + ";",
                "function f(){".repeat(1000) + "}".repeat(1000),
                "const x = " + "!".repeat(2000) + "true;", "const x = " + "new ".repeat(2000) + "C;")) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                var result = parse(source); assertFalse(result.successful());
                assertTrue(result.diagnostics().get(0).message().contains("limit"), result.diagnostics().toString());
            });
        }
    }
    @Test void failedTypeArgumentProbeRestoresTokensDiagnosticsAndTemplateState() {
        for (String source : List.of("const x = a < b / c > d;", "const x = `${a < b ? `${c}` : d}`;",
                "const x = a < b >> c;", "const x = a < b / /r/.source.length;")) {
            var result = parse(source); assertTrue(result.successful(), result.diagnostics().toString());
            assertEquals(source, result.program().tokens().stream().map(Token::text).reduce("", String::concat));
        }
    }
    @Test void utf16RangesAndCodePointDiagnosticsSurviveAllNewlines() {
        String source = "//😀\r\nconst 𐐀 = '😀';\u2028let x: = 1;";
        var result = parse(source); assertEquals(3, result.diagnostics().get(0).line()); assertEquals(8, result.diagnostics().get(0).column());
    }
    @Test void prattTreeRespectsAssociativityAndPrecedence() {
        var result = parse("let x = a + b * c ** d ** e - f; x = y = z;"); assertTrue(result.successful());
        List<String> operators = new ArrayList<>();
        result.program().walk(n -> { if (n instanceof ParsedNode p && !p.attribute("operator").isEmpty()) operators.add(p.attribute("operator")); return true; });
        assertEquals(List.of("-", "+", "*", "**", "**", "=", "="), operators);
    }
}
