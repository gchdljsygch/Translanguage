package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(CompilationEvidence.class)
class TranspilerTest {
    @TempDir Path temporary;
    private final Transpiler transpiler = CompilationEvidence.compiler();

    private String compile(String source) {
        TranspileResult result = transpiler.transpile("fixture.ts", source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        return result.javaScript();
    }

    private void runs(String source, String expected) throws Exception {
        Path script = temporary.resolve("fixture.mjs");
        Files.writeString(script, compile(source), StandardCharsets.UTF_8);
        Path stdout = temporary.resolve("stdout.txt");
        Path stderr = temporary.resolve("stderr.txt");
        Process process = new ProcessBuilder("node", script.toString()).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile()).start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Node.js execution timed out");
        CompilationEvidence.execution("TranspilerTest Node/Acorn", read(stdout), read(stderr), process.exitValue());
        assertEquals(0, process.exitValue(), () -> read(stderr));
        assertEquals(expected, Files.readString(stdout).strip());
    }
    private static String read(Path file) {
        try { return Files.readString(file); } catch (Exception error) { return error.toString(); }
    }

    @org.junit.jupiter.api.DisplayName("TS-001 erasesTypesAndPreservesExpressions")
    @Test void erasesTypesAndPreservesExpressions() throws Exception {
        runs("""
                // 留下注释 😀
                interface User { name: string; age?: number; }
                type Id = number | string;
                function greet<T extends User>(user: T, suffix?: string): string {
                    return `${user.name}${suffix ?? "!"}`;
                }
                const user: User = {name: "世界"};
                const id: Id = 3;
                console.log(greet<User>(user), id + 2);
                """, "世界! 5");
    }

    @org.junit.jupiter.api.DisplayName("TS-002 genericArrowsAssertionsAndNonNull")
    @Test void genericArrowsAssertionsAndNonNull() throws Exception {
        runs("""
                const identity = <T>(value: T): T => value;
                const values: Array<number> = [identity<number>(3)];
                const object = {value: values[0]} satisfies {value: number};
                const value = (<number>object.value) as number;
                console.log(values![0] + value!, !false, value < 10, value > 1);
                """, "6 true true true");
    }

    @org.junit.jupiter.api.DisplayName("TS-003 classesAndParameterProperties")
    @Test void classesAndParameterProperties() throws Exception {
        runs("""
                interface Named { name: string; }
                abstract class Base { abstract size(): number; }
                class Person extends Base implements Named {
                    public readonly name: string = "Ada";
                    private age?: number;
                    override size(): number { return 3; }
                }
                class Box<T> {
                    constructor(public value: T, private readonly label: string = "value") { }
                    getValue(): T { return this.value; }
                }
                console.log(new Person().size(), new Box<number>(7).getValue());
                """, "3 7");
    }

    @org.junit.jupiter.api.DisplayName("TS-004 enumsAndConstEnums")
    @Test void enumsAndConstEnums() throws Exception {
        runs("""
                enum Color { Red, Green = 4, Blue }
                enum Text { Yes = "YES", No = 'NO' }
                const enum Direction { Left = -2, Right }
                console.log(Color.Red, Color.Blue, Color[5], Text.Yes, Direction.Right);
                """, "0 5 Blue YES -1");
    }

    @org.junit.jupiter.api.DisplayName("TS-005 overloadsAmbientAndThisParameters")
    @Test void overloadsAmbientAndThisParameters() throws Exception {
        runs("""
                declare const external: string;
                declare function absent(value: number): string;
                declare class Invisible { value: number; }
                function twice(value: number): number;
                function twice(value: number): number { return value * 2; }
                function f(this: { x: number }, value: number): number { return this.x + value; }
                class Item { declare missing: string; value!: number; }
                console.log(twice(2), f.call({x: 3}, 2), new Item().value);
                """, "4 5 undefined");
    }

    @org.junit.jupiter.api.DisplayName("TS-006 explicitTypeOnlyImportsDoNotLoadModules")
    @Test void explicitTypeOnlyImportsDoNotLoadModules() throws Exception {
        runs("""
                import type { NotInstalled } from "missing-package";
                import { type AlsoMissing } from "also-missing";
                export type { Nothing } from "another-missing";
                export interface Public { name: string; }
                export type ID = string;
                export const value: number = 3;
                console.log(value);
                """, "3");
    }

    @org.junit.jupiter.api.DisplayName("TS-007 mixedImportsAndExportsKeepRuntimeBindings")
    @Test void mixedImportsAndExportsKeepRuntimeBindings() throws Exception {
        runs("""
                import { type Stats, basename as name } from "node:path";
                export { type Imaginary, name };
                console.log(name("a/b.txt"));
                """, "b.txt");
    }

    @org.junit.jupiter.api.DisplayName("TS-008 preservesPlainJavaScriptAndTrivia")
    @Test void preservesPlainJavaScriptAndTrivia() {
        String source = "// 😀 中文\r\nconst message = 'as number: T';\r\nconst x = {type: 3, name: 'A'};\r\nconsole.log(/a:b/.test('a:b'), message, x.type);\r\n";
        assertEquals(source, compile(source));
    }

    @org.junit.jupiter.api.DisplayName("TS-009 nestedTemplatesAndControlFlow")
    @Test void nestedTemplatesAndControlFlow() throws Exception {
        runs("""
                const values: number[] = [1, 2];
                let sum: number = 0;
                for (const value of values) { sum += value; }
                console.log(`${values.map((x: number): string => `${x * 2}`).join(",")}`, sum);
                """, "2,4 3");
    }

    @org.junit.jupiter.api.DisplayName("TS-010 lineTerminatorsInsideCommentsPreserveReturnSemantics")
    @Test void lineTerminatorsInsideCommentsPreserveReturnSemantics() throws Exception {
        runs("function f(): number | undefined { return /* newline\n */ 3; }\nconsole.log(f());", "undefined");
    }

    @org.junit.jupiter.api.DisplayName("TS-011 nestedTemplateBlocksKeepBraceState")
    @Test void nestedTemplateBlocksKeepBraceState() throws Exception {
        runs("""
                const x: number = 3;
                console.log(`${[x].map((n: number) => { return `${{text: `${n}`}.text}!`; }).join("")}`);
                """, "3!");
    }

    @org.junit.jupiter.api.DisplayName("TS-012 derivedParameterPropertiesRunAfterSuper")
    @Test void derivedParameterPropertiesRunAfterSuper() throws Exception {
        runs("""
                class Base { constructor(public prefix: string) {} }
                class Derived extends Base {
                    constructor(public value: number) {
                        const name: string = "v";
                        super(name);
                        console.log(this.prefix, this.value);
                    }
                }
                new Derived(7);
                """, "v 7");
    }

    @org.junit.jupiter.api.DisplayName("TS-013 typedObjectMethodsAndAnonymousFunctions")
    @Test void typedObjectMethodsAndAnonymousFunctions() throws Exception {
        runs("""
                const object = { twice(x: number): number { return x * 2; } };
                const identity = function<T>(x: T): T { return x; };
                function first<T extends string = string>(xs: readonly T[]): T { return xs[0]; }
                const generic = <T,>(x: T): T => x;
                console.log(object.twice(2), identity<number>(5), first(["a"]), generic(6));
                """, "4 5 a 6");
    }

    @org.junit.jupiter.api.DisplayName("TS-014 comparisonsAndOptionalChainsRemainJavaScript")
    @Test void comparisonsAndOptionalChainsRemainJavaScript() throws Exception {
        runs("""
                const object: {value: number} | null = {value: 2};
                const compare = (x: number, y: number): boolean => x < y && y > 0;
                console.log(object?.value, object?.["value"], compare(2, 3));
                """, "2 2 true");
    }

    @org.junit.jupiter.api.DisplayName("TS-015 reportsSyntaxAndLexerLocationsWithoutOutput")
    @Test void reportsSyntaxAndLexerLocationsWithoutOutput() {
        for (String source : new String[]{"const broken: = 2;", "const x = §;", "const x = ;"}) {
            TranspileResult result = transpiler.transpile("bad.ts", source);
            assertFalse(result.successful(), source);
            assertEquals("", result.javaScript());
            assertEquals("bad.ts", result.diagnostics().get(0).sourceName());
            assertTrue(result.diagnostics().get(0).line() > 0);
            assertTrue(result.diagnostics().get(0).column() > 0);
        }
    }

    @org.junit.jupiter.api.DisplayName("TS-016 unsupportedRuntimeFeaturesFailExplicitly")
    @Test void unsupportedRuntimeFeaturesFailExplicitly() {
        for (String source : new String[]{
                "enum E { A = calculate(), B }",
                "enum E { A = 'a', B }",
                "class B {} class C extends B { constructor(public {x}: {x:number}) { super(); } }"}) {
            TranspileResult result = transpiler.transpile("unsupported.ts", source);
            assertFalse(result.successful(), source);
            assertEquals("", result.javaScript());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("TS_UNSUPPORTED")), result.diagnostics().toString());
        }
    }

    @org.junit.jupiter.api.DisplayName("TS-017 erasingAmbientNamespaceDoesNotCreateAnExternalModule")
    @Test void erasingAmbientNamespaceDoesNotCreateAnExternalModule() {
        assertFalse(compile("declare namespace Foo { export interface Bar {} }").contains("export"));
        assertTrue(compile("export interface Foo {}").contains("export {}"));
        assertEquals("const x = 1;", compile("\uFEFFconst x: number = 1;"));
    }

    @org.junit.jupiter.api.DisplayName("TS-018 exportsKeepRuntimeDeclarations")
    @Test void exportsKeepRuntimeDeclarations() throws Exception {
        runs("""
                export enum E { A, B }
                export class C { value: number = E.B; }
                export function f(): number { return new C().value; }
                export default f;
                console.log(f());
                """, "1");
    }

    @org.junit.jupiter.api.DisplayName("TS-019 asyncDestructuringRestAndExceptionTypes")
    @Test void asyncDestructuringRestAndExceptionTypes() throws Exception {
        runs("""
                async function sum({start}: {start: number}, ...values: number[]): Promise<number> {
                    return values.reduce((a: number, b: number): number => a + b, start);
                }
                try { console.log(await sum({start: 1}, 2, 3)); }
                catch (error: unknown) { console.log(error); }
                """, "6");
    }

    @org.junit.jupiter.api.DisplayName("TS-020 reusableTranspilerDoesNotLeakState")
    @Test void reusableTranspilerDoesNotLeakState() {
        assertFalse(transpiler.transpile("bad.ts", "const = ;").successful());
        assertEquals("const x = 1;", compile("const x: number = 1;"));
        assertEquals("", compile(""));
    }
}
