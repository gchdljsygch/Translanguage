package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptTargetTest {
    @TempDir Path temporary;

    private String compile(JavaScriptTarget target, String source) throws Exception {
        TranspileResult result = new TypeScriptTranspiler(target).transpile("fixture.ts", source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        checkSyntax(target, result.javaScript());
        return result.javaScript();
    }

    private void checkSyntax(JavaScriptTarget target, String output) throws Exception {
        Path acorn = temporary.resolve("acorn.mjs");
        if (!Files.exists(acorn)) {
            try (var resource = getClass().getResourceAsStream("/META-INF/resources/webjars/acorn/8.15.0/dist/acorn.mjs")) {
                assertNotNull(resource, "Acorn test parser missing");
                Files.copy(resource, acorn);
            }
        }
        Path artifact = temporary.resolve("artifact.mjs");
        Files.writeString(artifact, output, StandardCharsets.UTF_8);
        Path checker = temporary.resolve("check.mjs");
        Files.writeString(checker, """
                import {parse} from './acorn.mjs';
                import {readFileSync} from 'node:fs';
                parse(readFileSync(process.argv[2], 'utf8'), {ecmaVersion: Number(process.argv[3]), sourceType: 'module'});
                """, StandardCharsets.UTF_8);
        execute(List.of("node", checker.toString(), artifact.toString(), target == JavaScriptTarget.ES6 ? "2015" : "2021"));
    }

    private String execute(List<String> command) throws Exception {
        Path stdout = temporary.resolve("stdout.txt");
        Path stderr = temporary.resolve("stderr.txt");
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Node execution timed out");
        assertEquals(0, process.exitValue(), () -> read(stderr));
        return Files.readString(stdout, StandardCharsets.UTF_8).strip();
    }

    private static String read(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (Exception error) { return error.toString(); }
    }

    private String runs(JavaScriptTarget target, String source, String expected) throws Exception {
        String output = compile(target, source);
        Path script = temporary.resolve("fixture.mjs");
        Files.writeString(script, output, StandardCharsets.UTF_8);
        assertEquals(expected, execute(List.of("node", script.toString())));
        return output;
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void modernExpressionsPreserveEvaluationOrderAndMethodReceiver(JavaScriptTarget target) throws Exception {
        runs(target, """
                let calls: number = 0;
                const object = {value: 2, method(n: number) { return this.value + n; }};
                function get(): any { calls++; return object; }
                const missing: any = null;
                let skipped = 0;
                console.log(get()?.method?.(3), missing?.method(++skipped), missing?.['x'] ?? 7);
                let x = 0;
                x ||= 4; x &&= 2; x ??= 8;
                let y: any = null; y ??= x ** 3;
                let base = {value: 3};
                base.value **= 2;
                console.log(calls, skipped, x, y, base.value, 1_000, false ?? 2);
                """, "5 undefined 7\n1 0 2 8 9 1000 false");
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void asyncFunctionsCarryTheirOwnHelpers(JavaScriptTarget target) throws Exception {
        String output = runs(target, """
                const owner = {
                    base: 4,
                    async compute(n: number): Promise<number> {
                        const plus = async (value: number) => this.base + await Promise.resolve(value);
                        try { return await plus(n); } finally { console.log('finally'); }
                    }
                };
                async function fail() { throw new Error('expected'); }
                owner.compute(3).then(value => console.log(value)).then(() => fail()).catch(e => console.log(e.message));
                """, "finally\n7\nexpected");
        if (target == JavaScriptTarget.ES6) assertFalse(output.contains("async function"));
        else assertTrue(output.contains("async"));
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void classFieldsDefineOwnPropertiesAndPreserveInitializationOrder(JavaScriptTarget target) throws Exception {
        runs(target, """
                const events: string[] = [];
                class Base { constructor() { events.push('base'); } }
                Object.defineProperty(Base.prototype, 'value', {set(v) { throw new Error('setter'); }});
                class Item extends Base {
                    value: number = 3;
                    unset?: string;
                    copy = this.value + 1;
                    static label = this.name;
                    static empty: string;
                    constructor(public id: number) { super(); events.push(this.value + ':' + this.id); }
                }
                const item = new Item(9);
                const descriptor = Object.getOwnPropertyDescriptor(item, 'unset');
                console.log(item.value, item.copy, item.id, Object.keys(item).join(','));
                console.log(Item.label, Object.prototype.hasOwnProperty.call(Item, 'empty'));
                console.log(descriptor.writable, descriptor.enumerable, descriptor.configurable, descriptor.value);
                console.log(events.join(','));
                """, "3 4 9 value,unset,copy,id\nItem true\ntrue true true undefined\nbase,3:9");
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void computedAndExpressionClassesKeepKeysAndLexicalValues(JavaScriptTarget target) throws Exception {
        runs(target, """
                let count = 0;
                function key(): string { count++; return 'field'; }
                const token = Symbol('token');
                const outside = 7;
                const Box = class Local {
                    [key()] = outside;
                    [token] = 8;
                    static ['kind'] = this.name;
                };
                const first = new Box(); const second = new Box();
                console.log(count, first.field, second[token], Box.kind);
                """, "1 7 8 Local");
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void preservesModulesAndDoesNotResolveDependencies(JavaScriptTarget target) throws Exception {
        String output = compile(target, """
                import value from 'uninstalled-package';
                export {other} from './missing.js';
                export * from 'another-package';
                export const answer: number = value?.answer ?? 42;
                """);
        assertTrue(output.contains("uninstalled-package"));
        assertTrue(output.contains("./missing.js"));
        assertTrue(output.contains("export"));
        runs(target, """
                import {type Imaginary, basename} from 'node:path';
                export type {Missing} from 'missing';
                export enum E { Zero, One }
                export default function value(): string { return basename('a/b') + E.One; }
                console.log(value());
                """, "b1");
        assertTrue(compile(target, "export interface Empty {}").contains("export"));
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void spreadRestAndOptionalCatchBindings(JavaScriptTarget target) throws Exception {
        runs(target, """
                const original = {a: 1, b: 2};
                const object = {...original, c: 3};
                const {a, ...rest} = object;
                function total(a: number, b: number,) { return a + b; }
                try { throw 1; } catch { console.log(a, rest.b, rest.c, total(2, 3,)); }
                """, "1 2 3 5");
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void accessorsStaticSuperAndConstructorShadowingKeepSemantics(JavaScriptTarget target) throws Exception {
        runs(target, """
                const outer = 6;
                class Base {
                    get value(): number { return 1; }
                    set value(v: number) { throw new Error('setter'); }
                    static get label(): string { return this.name; }
                }
                class Derived extends Base {
                    value = outer;
                    static parentName = super.label;
                    static keys = Object.getOwnPropertyNames(this).filter(x => x.indexOf('$jscomp') >= 0).length;
                    constructor(outer: number) { super(); console.log(outer, this.value); }
                }
                const d = new Derived(9);
                console.log(Derived.parentName, Derived.keys, Object.keys(d).join(','));
                """, "9 6\nDerived 0 value");
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void asyncIterationKeepsCleanupAndResults(JavaScriptTarget target) throws Exception {
        runs(target, """
                async function* values() { try { yield 2; yield 3; } finally { console.log('closed'); } }
                async function sum() {
                    let total = 0;
                    for await (const n of values()) { total += n; if (total > 2) break; }
                    return total;
                }
                sum().then(console.log);
                """, "closed\n5");
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void defaultExportAndAnonymousClassNamesRemainStable(JavaScriptTarget target) throws Exception {
        runs(target, """
                export default class Thing { static label = this.name; }
                const Anonymous = class { value = 2; static label = this.name; };
                function create() { return class { value = 3; }; }
                console.log(Thing.label, Anonymous.label, create().name === '', new Anonymous().value);
                """, "Thing Anonymous true 2");
    }

    @Test void es2021RetainsSupportedSyntax() throws Exception {
        String output = runs(JavaScriptTarget.ES2021, """
                let count: bigint = 1_000n;
                const object: any = {value: 2};
                object.value ??= 3;
                console.log(count + 1n, object?.value ?? 0);
                """, "1001n 2");
        assertTrue(output.contains("?."));
        assertTrue(output.contains("1000n"));
        compile(JavaScriptTarget.ES2021, "const load = () => import('./external.mjs'); console.log(import.meta.url);");
    }

    @Test void incompatibleSyntaxFailsInsteadOfLeakingIntoAnOlderTarget() {
        for (String source : List.of("const value = 1n;", "const load = () => import('./x.mjs');",
                "console.log(import.meta.url);", "console.log(/(?<=a)b/.test('ab'));", "console.log(/x/s.test('x'));")) {
            TranspileResult result = new TypeScriptTranspiler(JavaScriptTarget.ES6).transpile("unsupported.ts", source);
            assertFalse(result.successful(), source + "\n" + result.javaScript());
            assertEquals("", result.javaScript());
            assertTrue(result.diagnostics().stream().allMatch(d -> d.code().equals("JS_TARGET")), result.diagnostics().toString());
        }
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void topLevelAwaitAndUnsafeDerivedInitializationFailExplicitly(JavaScriptTarget target) {
        for (String source : List.of("console.log(await Promise.resolve(1));",
                "class B {} class C extends B { value = 1; constructor(flag) { if (flag) super(); else super(); } }")) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("bad.ts", source);
            assertFalse(result.successful());
            assertEquals("", result.javaScript());
            assertEquals("bad.ts", result.diagnostics().get(0).sourceName());
            assertEquals("JS_TARGET", result.diagnostics().get(0).code());
        }
    }

    @Test void defaultsAndExtensionsRemainUsable() throws Exception {
        String original = "// 中文 😀\r\nconst value = object?.field ?? 3;\r\n";
        assertEquals(original, new TypeScriptTranspiler().transpile("a.ts", original).javaScript());
        assertEquals(JavaScriptTarget.PRESERVE, new TypeScriptTranspiler().target());
        Transpiler customized = new TypeScriptTranspiler(JavaScriptTarget.ES6,
                List.of(new TypeErasurePass(), context -> {
                    assertEquals(JavaScriptTarget.ES6, context.target());
                    context.append("\nconst square = 2 ** 3;");
                }));
        TranspileResult result = customized.transpile("a.ts", "const value: number = 1;");
        assertTrue(result.successful(), result.diagnostics().toString());
        checkSyntax(JavaScriptTarget.ES6, result.javaScript());
        assertTrue(result.javaScript().contains("Math.pow"));
        Transpiler customEmitter = new TypeScriptTranspiler(JavaScriptTarget.ES2021, List.of(new TypeErasurePass()),
                (name, js, target) -> new TranspileResult("// " + target + "\n" + js, List.of()));
        assertEquals("// ES2021\nconst x = 1;", customEmitter.transpile("a.ts", "const x: number = 1;").javaScript());
        assertThrows(NullPointerException.class, () -> new TypeScriptTranspiler((JavaScriptTarget) null));
    }

    @Test void repeatedAndConcurrentCompilationsDoNotShareState() throws Exception {
        var transpiler = new TypeScriptTranspiler(JavaScriptTarget.ES6);
        assertFalse(transpiler.transpile("bad.ts", "const x = 1n;").successful());
        String source = "export const x: number = ({value: 2})?.value ?? 3;";
        String expected = transpiler.transpile("same.ts", source).javaScript();
        assertFalse(expected.isEmpty());
        var executor = Executors.newFixedThreadPool(3);
        try {
            var tasks = java.util.stream.IntStream.range(0, 9)
                    .<java.util.concurrent.Callable<TranspileResult>>mapToObj(i -> () -> transpiler.transpile("same.ts", source)).toList();
            for (var future : executor.invokeAll(tasks)) {
                var result = future.get();
                assertTrue(result.successful(), result.diagnostics().toString());
                assertEquals(expected, result.javaScript());
            }
        } finally { executor.shutdownNow(); }
        assertTrue(transpiler.transpile("empty.ts", "").successful());
    }
}
