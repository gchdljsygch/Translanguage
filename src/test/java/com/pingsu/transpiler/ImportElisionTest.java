package com.pingsu.transpiler;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportElisionTest {
    @TempDir Path temporary;
    private record Case(String name, String source, String expected) { }
    private String compile(JavaScriptTarget target, String source) {
        var result = new TypeScriptTranspiler(target).transpile("imports.ts", source);
        assertTrue(result.successful(), result.diagnostics().toString());
        return result.javaScript();
    }
    @TestFactory List<DynamicTest> runtimeBindingsAcrossTargets() {
        List<Case> cases = List.of(
            new Case("unused shapes", "import D from 'missing'; import * as N from 'missing'; import {A, B as C} from 'missing'; console.log('ok');", "ok"),
            new Case("type positions", """
                import D, {A, B as C} from 'missing'; import * as N from 'missing';
                interface I extends A {x: D} type T = typeof C; type U = N.Value;
                declare const absent: D; declare class Hidden { [C](): A; }
                function f(x: A): D; function f(x: unknown): unknown {return x;}
                class Example implements A {declare hidden: C; method<T extends D>(x:T):T{return x;}}
                const value: D = f<A>(7 as C) satisfies A; console.log(<D>value);
                """, "7"),
            new Case("partial named", "import {Missing, X as value, type T, Unused} from './dep.mjs'; console.log(value);", "7"),
            new Case("default removed", "import Missing, {X} from './named.mjs'; console.log(X);", "7"),
            new Case("named removed", "import D, {Missing, type T} from './dep.mjs'; console.log(D);", "7"),
            new Case("namespace removed", "import D, * as N from './dep.mjs'; console.log(D);", "7"),
            new Case("default before namespace removed", "import Missing, * as N from './named.mjs'; console.log(N.X);", "7"),
            new Case("local export", "import {X as value} from './dep.mjs'; export {value as renamed}; console.log(value);", "7"),
            new Case("export only reference", "import {X} from './dep.mjs'; export {X}; console.log('ok');", "ok"),
            new Case("default export", "import D from './dep.mjs'; export default D; console.log('ok');", "ok"),
            new Case("remote reexport", "import {X} from 'missing'; export {X as value} from './dep.mjs'; console.log('ok');", "ok"),
            new Case("explicit type export", "import {X} from 'missing'; export type {X}; console.log('ok');", "ok"),
            new Case("inline type export", "import {X} from 'missing'; export {type X}; console.log('ok');", "ok"),
            new Case("shorthand", "import {X} from './dep.mjs'; console.log(({X}).X);", "7"),
            new Case("computed key", "import {key} from './dep.mjs'; console.log(({[key](key){return key;}}).x(3));", "3"),
            new Case("class computed key", "import {key} from './dep.mjs'; class C {[key](key:number){return key;}} console.log(new C().x(3));", "3"),
            new Case("runtime typeof", "import {X} from './dep.mjs'; console.log(typeof X);", "number"),
            new Case("runtime extends", "import {Base} from './dep.mjs'; class C extends Base {} console.log(new C().value);", "7"),
            new Case("property and label names", "import {X} from 'missing'; const o = {X:7}; X: {console.log(o.X); break X;}", "7"),
            new Case("parameter shadow", "import {X} from 'missing'; function f(X:number){return {X}.X;} console.log(f(3));", "3"),
            new Case("hoisted var shadow", "import {X} from 'missing'; function f(){return typeof X; var X=3;} console.log(f());", "undefined"),
            new Case("block shadow", "import {X} from 'missing'; try {{console.log(f()); function f(){return X;} let X=3;}}catch(e){console.log(e instanceof ReferenceError);}", "true"),
            new Case("catch destructuring shadow", "import {X} from 'missing'; try {throw {X:3};} catch({X}:any){console.log(X);}", "3"),
            new Case("named expression shadow", "import {X} from 'missing'; const f=function X(){return typeof X;}; const C=class X {method(){return X.name;}}; console.log(f(),new C().method());", "function X"),
            new Case("block class shadow", "import {X} from 'missing'; {class X {static value=3;} console.log(X.value);}", "3"),
            new Case("switch discriminant", "import {X} from './dep.mjs'; switch(X){case 7: let X=3; console.log(X);}", "3"),
            new Case("loop shadow", "import {X} from 'missing'; for(let X of [3])console.log(X);", "3"),
            new Case("outer reference", "import {X} from './dep.mjs'; function f(X){return X;} console.log(f(3),X);", "3 7"),
            new Case("default before body var", "import {X} from './dep.mjs'; function f(value=X){var X=3;return value;} console.log(f());", "7"),
            new Case("generic type name", "import {X} from './dep.mjs'; function f<X>(){return X;} console.log(f<number>());", "7"),
            new Case("binding keys and defaults", "import {X,key} from './dep.mjs'; const {[key]: value=X}={}; console.log(value);", "7"),
            new Case("enum initializer", "import {X} from './dep.mjs'; enum E { A=X } console.log(E.A);", "7"),
            new Case("enum member shadow", "import {X} from 'missing'; enum E {X=3,Y=X} console.log(E.Y);", "3"),
            new Case("merged enum member shadow", "import {X} from 'missing'; enum E {X=3} enum E {Y=X} console.log(E.Y);", "3"),
            new Case("escaped import", "import {X as \\u0058} from './dep.mjs'; console.log(X);", "7"),
            new Case("escaped reference", "import {X} from './dep.mjs'; console.log(\\u0058);", "7"),
            new Case("bare side effect", "import './effect.mjs'; import {X} from 'missing'; console.log(globalThis.effect);", "1"),
            new Case("empty side effect", "import {} from './effect.mjs'; console.log(globalThis.effect);", "1"),
            new Case("template reference", "import {X} from './dep.mjs'; console.log(`value:${X}`);", "value:7"),
            new Case("assignment shorthand reference", "import {X} from './dep.mjs'; try {({X}={X:3});}catch(e){console.log(e instanceof TypeError);}", "true"),
            new Case("catch scope ends", "import {X} from './dep.mjs'; try {throw 3;}catch(X){} console.log(X);", "7"),
            new Case("loop scope ends", "import {X} from './dep.mjs'; for(let X of [3]){} console.log(X);", "7"),
            new Case("block var hoists", "import {X} from 'missing'; function f(){if(true){var X=3;}return X;} console.log(f());", "3"),
            new Case("parameter property", "import {X} from './dep.mjs'; class C {constructor(public value=X){}} console.log(new C().value);", "7"),
            new Case("type keyword import", "import {X as type} from './dep.mjs'; console.log(type);", "7"),
            new Case("literal text is not a reference", "import {X} from 'missing'; console.log('import(X)',`X`);", "import(X) X")
        );
        List<DynamicTest> tests = new ArrayList<>();
        for (Case test : cases) for (JavaScriptTarget target : List.of(JavaScriptTarget.PRESERVE, JavaScriptTarget.ES6, JavaScriptTarget.ES2021)) {
            tests.add(DynamicTest.dynamicTest(test.name + "/" + target, () -> {
                Files.writeString(temporary.resolve("dep.mjs"), "export default 7;export const X=7,key='x';export class Base {constructor(){this.value=7;}}", StandardCharsets.UTF_8);
                Files.writeString(temporary.resolve("named.mjs"), "export const X=7;", StandardCharsets.UTF_8);
                Files.writeString(temporary.resolve("effect.mjs"), "globalThis.effect=(globalThis.effect||0)+1;", StandardCharsets.UTF_8);
                String output = compile(target, test.source);
                assertFalse(output.contains("'missing'"), output);
                var runtime = new JavaScriptRuntime(temporary);
                runtime.syntax(target == JavaScriptTarget.PRESERVE ? 2022 : target == JavaScriptTarget.ES6 ? 2015 : 2021, "module", output);
                assertEquals(test.expected, runtime.run(output));
            }));
        }
        return tests;
    }

    @Test void preservesModuleIdentityAndUntouchedTrivia() {
        assertEquals("\nexport {};\n", compile(JavaScriptTarget.PRESERVE, "import {X} from 'missing';"));
        assertEquals("\nexport {};\n", compile(JavaScriptTarget.PRESERVE, "import type {X} from 'missing';"));
        assertEquals("export const x=1;", compile(JavaScriptTarget.PRESERVE, "import {X} from 'missing';export const x=1;"));
        String input = "// keep\r\nimport /* a */ D, { X /* b */ as value } from './dep.mjs';\r\nconsole.log(D,value);";
        assertEquals(input, compile(JavaScriptTarget.PRESERVE, input));
        assertEquals("import D from './dep.mjs';console.log(D);", compile(JavaScriptTarget.PRESERVE,
                "import D, {X} from './dep.mjs';console.log(D);"));
    }

    @Test void directEvalConservativelyKeepsVisibleImports() throws Exception {
        Files.writeString(temporary.resolve("dep.mjs"), "export const X=7;", StandardCharsets.UTF_8);
        String output = compile(JavaScriptTarget.PRESERVE, "import {X} from './dep.mjs';import type {T} from 'missing';console.log((eval)('X')); ");
        assertFalse(output.contains("missing"));
        assertEquals("7", new JavaScriptRuntime(temporary).run(output));
        String shadowed = compile(JavaScriptTarget.PRESERVE, "import {X} from 'missing';function f(X){return eval('X');} console.log(f(3));");
        assertFalse(shadowed.contains("missing"));
        assertEquals("3", new JavaScriptRuntime(temporary).run(shadowed));
    }

    @Test void retainedModulesKeepLiveExportsAndSideEffectOrder() throws Exception {
        Files.writeString(temporary.resolve("first.mjs"), "globalThis.events=['first'];", StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("live.mjs"), "globalThis.events.push('live');export let value=1;export function change(){value=2;}", StandardCharsets.UTF_8);
        Files.writeString(temporary.resolve("last.mjs"), "globalThis.events.push('last');", StandardCharsets.UTF_8);
        for (JavaScriptTarget target : List.of(JavaScriptTarget.PRESERVE, JavaScriptTarget.ES6, JavaScriptTarget.ES2021)) {
            String output = compile(target, "import './first.mjs';import {Missing,value,change} from './live.mjs';import * as Unused from 'missing';import './last.mjs';export {value as current,change};");
            Files.writeString(temporary.resolve("bridge.mjs"), output, StandardCharsets.UTF_8);
            assertEquals("first,live,last 1\n2", new JavaScriptRuntime(temporary).run(
                    "import {current,change} from './bridge.mjs';console.log(globalThis.events.join(','),current);change();console.log(current);"));
        }
    }

    @Test void dynamicImportsAndEscapedRemoteExportNamesRemainRuntimeSyntax() throws Exception {
        Files.writeString(temporary.resolve("dep.mjs"), "export const X=7;", StandardCharsets.UTF_8);
        for (JavaScriptTarget target : List.of(JavaScriptTarget.PRESERVE, JavaScriptTarget.ES2021)) {
            String output = compile(target, "import {X} from 'missing';import('./dep.mjs').then(m=>console.log(m.X));");
            assertFalse(output.contains("missing"));
            assertEquals("7", new JavaScriptRuntime(temporary).run(output));
        }
        String output = compile(JavaScriptTarget.PRESERVE, "import {X} from 'missing';export {'\\x58' as value} from './dep.mjs';");
        Files.writeString(temporary.resolve("bridge.mjs"), output, StandardCharsets.UTF_8);
        assertEquals("7", new JavaScriptRuntime(temporary).run("import {value} from './bridge.mjs';console.log(value);"));
    }

    @Test void analysisIsCompilationLocalAndCustomPassListsRemainExact() throws Exception {
        var compiler = new TypeScriptTranspiler();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> work = new ArrayList<>();
            for (int i=0; i<24; i++) {
                boolean used = i%2 == 0;
                work.add(() -> {
                    var result = compiler.transpile("parallel.ts", "import {X} from './dep.mjs';" + (used ? "console.log(X);" : "type T=X;"));
                    assertTrue(result.successful());
                    assertEquals(used, result.javaScript().contains("./dep.mjs"));
                    return result.javaScript();
                });
            }
            for (var result : executor.invokeAll(work)) result.get();
        } finally { executor.shutdownNow(); }
        String source = "import {X} from './dep.mjs';";
        assertEquals(source, new TypeScriptTranspiler(List.of()).transpile("custom.ts", source).javaScript());
    }
}