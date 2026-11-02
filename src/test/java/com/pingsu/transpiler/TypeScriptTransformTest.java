package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TypeScriptTransformTest {
    @TempDir Path temporary;
    private final Transpiler compiler = new TypeScriptTranspiler();
    private String compile(String source) {
        var result = compiler.transpile("p3.ts", source);
        assertTrue(result.successful(), result.diagnostics().toString()); return result.javaScript();
    }
    private void runs(String source, String expected) throws Exception {
        Path script = temporary.resolve("output.mjs"), output = temporary.resolve("stdout.txt"), error = temporary.resolve("stderr.txt");
        String js = compile(source);
        Files.writeString(script, js, StandardCharsets.UTF_8);
        try (var resource = getClass().getResourceAsStream("/META-INF/resources/webjars/acorn/8.15.0/dist/acorn.mjs")) {
            assertNotNull(resource); Files.copy(resource, temporary.resolve("acorn.mjs"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path check = temporary.resolve("check.mjs");
        Files.writeString(check, "import {parse} from './acorn.mjs'; import {readFileSync} from 'node:fs'; parse(readFileSync(new URL('./output.mjs', import.meta.url), 'utf8'), {ecmaVersion: 2022, sourceType: 'module'});", StandardCharsets.UTF_8);
        run(check, output, error); run(script, output, error);
        assertEquals(expected, Files.readString(output, StandardCharsets.UTF_8).strip());
    }
    private void run(Path script, Path output, Path error) throws Exception {
        Process process = new ProcessBuilder("node", script.toString()).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Node/Acorn timeout"); }
        assertEquals(0, process.exitValue(), () -> {try{return Files.readString(error);}catch(Exception e){return e.toString();}});
    }
    @Test void typesNeverEraseAdjacentRuntimeExpressions() throws Exception {
        runs("""
            type M<T> = {readonly [K in keyof T]?: T[K]};
            type F = <T>(x:T) => T; type C = new (x:number) => Date;
            const f = <T,>(x:T):T => x;
            const x = 3 as number + 2; const y = <number>f<number>(4) * 2;
            const a = {x} satisfies M<{x:number}>;
            console.log(x, y, a.x, f < f, f<number>(7));
            """, "5 8 5 false 7");
    }
    @Test void parameterPropertiesAssignThroughSettersAfterFieldsAndSuper() throws Exception {
        runs("""
            const events: string[] = [];
            class Base {
                set x(value: number) { events.push(`set:${value}`); }
                constructor() { events.push('base'); }
            }
            class Child extends Base {
                field: number = (events.push('field'), 0);
                constructor(public x: number) { events.push('before'); super(); events.push('body'); }
            }
            new Child(7);
            console.log(events.join(','));
            """, "before,base,field,set:7,body");
    }
    @Test void parameterPropertiesDoNotRunWhenSuperThrows() throws Exception {
        runs("""
            let count = 0;
            class Base { constructor() {throw Error('stop')} set x(v:number) {count++} }
            class Child extends Base { constructor(public x:number) {super()} }
            try {new Child(1)} catch(e:unknown) {console.log(count)}
            """, "0");
    }
    @Test void moduleTypeItemsDoNotLoadMissingModulesAndKeepDefaultBinding() throws Exception {
        runs("""
            import path, {type Imaginary, basename as base} from 'node:path';
            import type Missing from 'not-installed';
            export type * from 'also-not-installed';
            export {type Imaginary, base};
            export interface Hidden {x:number}
            console.log(path.basename('a/b'), base('c/d'));
            """, "b d");
        assertEquals("\nexport {};\n", compile("export type X = string;"));
        assertEquals("", compile("declare namespace N { export const x: number; export interface I {} }"));
        assertEquals("\nexport {};\n", compile("import path, {type X} from 'node:path';"));
        assertEquals("import path from 'node:path'; console.log(path);",
                compile("import path, {type X} from 'node:path'; console.log(path);"));
    }
    @Test void enumLiteralsAndConstEnumsRetainReverseMappings() throws Exception {
        runs("""
            export const enum E { 'first' = -0x2, Next, Big = 1_000, More, Text='v', Reset=0b10, Last }
            console.log(E.first, E.Next, E[-1], E.More, E.Text, E.Last);
            """, "-2 -1 Next 1001 v 3");
    }
    @Test void unsupportedParameterPropertiesAndInvalidEnumsNeverReturnPartialOutput() {
        for (String source : List.of(
                "class C {constructor(public {x}: {x:number}){}}",
                "enum E { A=foo(), B }", "enum E {A='a', B}", "enum E { [foo()]=1 }", "enum E { 1=2 }",
                "@decorator class C {}", "class C { #x=1 }", "namespace N {const x=1}")) {
            var result = compiler.transpile("unsupported.ts", source);
            assertFalse(result.successful(), source); assertEquals("", result.javaScript());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("TS_UNSUPPORTED")), source + result.diagnostics());
        }
    }
    @Test void preservesUnicodeTriviaOutsidePreciselyErasedRanges() {
        String input = "//😀\r\nconst 𐐀: /*类型*/ number = 1;\u2028const text = `as number ${𐐀}`;";
        assertEquals("//😀\r\nconst 𐐀 = 1;\u2028const text = `as number ${𐐀}`;", compile(input));
    }
}
