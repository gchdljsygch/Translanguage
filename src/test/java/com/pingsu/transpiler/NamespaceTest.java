package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NamespaceTest {
    @TempDir Path directory;
    private void runs(String source, String expected) throws Exception {
        runs(source, expected, JavaScriptTarget.values());
    }
    private void runs(String source, String expected, JavaScriptTarget... targets) throws Exception {
        JavaScriptRuntime runtime = new JavaScriptRuntime(directory);
        for (JavaScriptTarget target : targets) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("namespace.ts", source);
            assertTrue(result.successful(), () -> target + " " + result.diagnostics());
            runtime.syntax(target == JavaScriptTarget.PRESERVE ? 2022 : target == JavaScriptTarget.ES5 ? 5 : target == JavaScriptTarget.ES6 ? 2015 : 2021,
                    "script", result.javaScript());
            assertEquals(expected, runtime.run(result.javaScript()), target + "\n" + result.javaScript());
        }
    }
    @Test void exportsTypesFunctionsClassesAndLiveVariables() throws Exception {
        runs("""
            namespace Counter {
              export interface Shape { count: number }
              export type NumberAlias = number;
              const secret = 2;
              export let count: number = 1;
              export let unset: number;
              export function bump(amount: number = secret) { return count += amount; }
              export class Box { #value = count; read() { return this.#value; } }
              export function snapshot() { return {count}; }
            }
            Counter.count = 10;
            console.log(Counter.bump(), Counter.snapshot().count, new Counter.Box().read(), Counter.unset === undefined);
            console.log(Object.keys(Counter).sort().join(','), 'secret' in Counter);
            """, "12 12 12 true\nBox,bump,count,snapshot false");
    }
    @Test void mergedNamespacesKeepPrivateLocalsSeparateAndShareExports() throws Exception {
        runs("""
            const hidden = 9;
            namespace N { const hidden = 1; export let x = hidden; export function first() { return hidden; } }
            namespace N { const hidden = 2; export function next() { return ++x + first() + hidden; } }
            namespace N { export const result = next(); export function shadow(x: number) { return x; } }
            console.log(N.x, N.result, N.shadow(20), hidden);
            """, "2 5 20 9");
    }
    @Test void dottedNestedAndLegacyModuleNamespacesMerge() throws Exception {
        runs("""
            namespace Root.Inner { export let count = 1; }
            namespace Root { export namespace Inner { export function next() { return ++count; } } }
            module Root.Inner { export const snapshot = next(); }
            namespace Root { namespace Hidden { export const value = 4; } export const local = Hidden.value; }
            console.log(Root.Inner.count, Root.Inner.snapshot, Root.local, 'Hidden' in Root);
            """, "2 2 4 false");
    }
    @Test void mergesWithClassFunctionAndEnumValues() throws Exception {
        runs("""
            class C { value = 3; }
            namespace C { export const label = 'class'; }
            function F() { return F.value; }
            namespace F { export let value = 4; }
            enum E { A = 2 }
            namespace E { export function next() { return E.A + 1; } }
            namespace N { export enum Kind { A=1, B } export const value = Kind.B; }
            console.log(new C().value, C.label, F(), E.next(), N.value, N.Kind[2]);
            """, "3 class 4 3 2 B");
    }
    @Test void exportedBindingsSupportDestructuringAndShadowedShorthands() throws Exception {
        runs("""
            namespace N {
              export const {a, nested: {b}, missing = 7} = {a: 1, nested: {b: 2}};
              export let [x, y = 5] = [3];
              export function values(a: number) { return {a, b, missing, x, y}; }
            }
            console.log(JSON.stringify(N.values(99)));
            """, "{\"a\":99,\"b\":2,\"missing\":7,\"x\":3,\"y\":5}", JavaScriptTarget.PRESERVE, JavaScriptTarget.ES6, JavaScriptTarget.ES2021);
    }
    @Test void typeOnlyAndAmbientNamespacesEraseWithoutCreatingModule() {
        for (String source : List.of("namespace N { export interface I {} export type T = number; }",
                "declare namespace N { export const x: number; }", "namespace N.Inner { export interface I {} }")) {
            TranspileResult result = new TypeScriptTranspiler().transpile("types.ts", source);
            assertTrue(result.successful(), result.diagnostics().toString()); assertEquals("", result.javaScript().trim());
        }
    }
    @Test void externalNamespaceExportsAndImportReferencesRetainModuleIdentity() throws Exception {
        Files.writeString(directory.resolve("dep.mjs"), "export const value = 3; export const shadow = 100;");
        String source = """
            import {value, shadow} from './dep.mjs';
            export namespace N { const shadow = 1; export const x = value + shadow; }
            export namespace N { export function read() { return x; } }
            export namespace Types { export interface I {} }
            """;
        for (JavaScriptTarget target : List.of(JavaScriptTarget.PRESERVE, JavaScriptTarget.ES6, JavaScriptTarget.ES2021)) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("module.ts", source);
            assertTrue(result.successful(), result.diagnostics().toString());
            assertFalse(result.javaScript().contains("{value, shadow}"));
            new JavaScriptRuntime(directory).syntax(target, result.javaScript());
            Files.writeString(directory.resolve("namespace.mjs"), result.javaScript());
            assertEquals("4 4 N", new JavaScriptRuntime(directory).run("import * as M from './namespace.mjs';console.log(M.N.x,M.N.read(),Object.keys(M).join(','));"));
        }
    }
    @Test void keywordsRemainUsableAsOrdinaryRuntimeIdentifiers() throws Exception {
        runs("const namespace = 1, module = 2; console.log(namespace + module);", "3");
    }
    @Test void privateNestedNamespacesDoNotMergeAcrossSeparateBodies() throws Exception {
        runs("""
            namespace N { namespace Hidden { export const x = 1; } export const first = Hidden.x; }
            namespace N { namespace Hidden { export const x = 2; } export const second = Hidden.x; }
            console.log(N.first, N.second, 'Hidden' in N);
            """, "1 2 false");
    }
    @Test void decoratorsAndJsxRemainComposableWithNamespaceExports() throws Exception {
        String source = """
            function decorate(value) { value.label = 'ok'; return value; }
            function h(type, props) { return type + ':' + props.value; }
            namespace N { export let value = 3; @decorate export class C { #x = value; read(){ return this.#x; } } export const view = <item value={value}/>; }
            console.log(N.C.label, new N.C().read(), N.view);
            """;
        for (JavaScriptTarget target : JavaScriptTarget.values()) {
            TranspileResult result = new TypeScriptTranspiler(target, List.of(new JsxLoweringPass("h", "Fragment"),
                    new TypeErasurePass(), new EnumLoweringPass(), new NamespaceLoweringPass(), new DecoratorLoweringPass()))
                    .transpile("namespace.tsx", source);
            assertTrue(result.successful(), result.diagnostics().toString());
            assertEquals("ok 3 item:3", new JavaScriptRuntime(directory).run(result.javaScript()));
        }
    }
    @Test void namespaceNestingRespectsParserResourceLimits() {
        TranspileResult result = new TypeScriptTranspiler().transpile("deep.ts", "namespace " + "A.".repeat(300) + "Z { export const x = 1; }");
        assertFalse(result.successful()); assertEquals("", result.javaScript());
        assertTrue(result.diagnostics().get(0).message().contains("limit"));
    }
    @Test void mergedExportBindingsDoNotRetainShadowedImports() throws Exception {
        String source = """
            import {x, y} from './missing.mjs';
            namespace N { export let x = 1; export namespace Inner { export const y = 2; } }
            namespace N { export function read() { return x; } export namespace Inner { export function read(){ return y; } } }
            console.log(N.read(), N.Inner.read());
            """;
        TranspileResult result = new TypeScriptTranspiler().transpile("imports.ts", source);
        assertTrue(result.successful(), result.diagnostics().toString());
        assertFalse(result.javaScript().contains("missing.mjs"));
        assertEquals("1 2", new JavaScriptRuntime(directory).run(result.javaScript()));
    }
    @Test void existingModuleAndEs5RestrictionsStillReturnNoPartialOutput() {
        for (String source : List.of("namespace N { import {x} from 'm'; }", "namespace N { export default 1; }",
                "namespace N { export {x}; }", "module 'external' {}", "namespace N { await Promise.resolve(); }")) {
            TranspileResult result = new TypeScriptTranspiler().transpile("invalid.ts", source);
            assertFalse(result.successful(), source); assertEquals("", result.javaScript());
        }
        for (String source : List.of("export namespace N {export const x=1;}", "namespace N {export const {x}={x:1};}")) {
            TranspileResult result = new TypeScriptTranspiler(JavaScriptTarget.ES5).transpile("es5.ts", source);
            assertFalse(result.successful(), source); assertEquals("", result.javaScript());
            assertEquals("JS_TARGET", result.diagnostics().get(0).code());
        }
    }
    @Test void typeOnlyBodiesDisappearEvenWhenNamespaceIsLaterInstantiated() throws Exception {
        TranspileResult result = new TypeScriptTranspiler().transpile("types.ts", "namespace N {export interface I{}} console.log(typeof N); namespace N {export const x=1;}");
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals("undefined", new JavaScriptRuntime(directory).run(result.javaScript()));
        assertTrue(new TypeScriptTranspiler().transpile("types.ts", "namespace Types {export interface I{}} eval('0');").successful());
    }
}
