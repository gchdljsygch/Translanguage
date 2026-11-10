package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrivateFieldsTest {
    @TempDir Path directory;
    private void runs(String source, String expected) throws Exception {
        JavaScriptRuntime runtime = new JavaScriptRuntime(directory);
        for (JavaScriptTarget target : JavaScriptTarget.values()) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("private.ts", source);
            assertTrue(result.successful(), () -> target + " " + result.diagnostics());
            if (target == JavaScriptTarget.PRESERVE) runtime.syntax(2022, "script", result.javaScript());
            else runtime.syntax(target, result.javaScript());
            assertEquals(expected, runtime.run(result.javaScript(), "script"), target + "\n" + result.javaScript());
        }
    }
    @Test void typedInstanceAndStaticFieldsRetainPrivacyAndInitializationOrder() throws Exception {
        runs("""
            const log: string[] = [];
            class Box {
              #value: number = (log.push('private'), 1);
              visible = (log.push('public'), this.#value + 1);
              #unset!: number;
              static #count: number = 7;
              static label = this.#count + 1;
              constructor(public title: string) { log.push('body'); }
              read(other: Box = this) { return [other.#value, other.#unset, Box.#count]; }
              bump() { return this.#value++; }
              static has(value: object) { return #value in value; }
            }
            const a = new Box('a'), b = new Box('b');
            console.log(a.bump(), a.read()[0], b.read()[0], Box.label);
            console.log(Object.keys(a).sort().join(','), Object.getOwnPropertySymbols(a).length, Box.has(a), Box.has({}));
            console.log(log.join(','), a.read()[1] === undefined);
            """, "1 2 1 8\ntitle,visible 0 true false\nprivate,public,body,private,public,body true");
    }
    @Test void receiverErrorsAndWritesPreserveEvaluationOrder() throws Exception {
        runs("""
            const log = [];
            class C {
              #x = 1;
              static assign(o) { o.#x = (log.push('rhs'), 4); }
              static compound(o) { o.#x += (log.push('compound'), 2); }
              static has(o) { return #x in o; }
              value() { return this.#x; }
            }
            for (const operation of [() => C.assign({}), () => C.compound({}), () => C.has(null), () => C.has(1)]) {
              try { operation(); } catch (e) { log.push(e instanceof TypeError); }
            }
            const c = new C(); C.assign(c);
            console.log(log.join(','), c.value(), C.has(new Proxy(c, {})));
            """, "rhs,true,true,true,true,rhs 4 false");
    }
    @Test void privateReferencesSupportCallsUpdatesPatternsAndOptionalChains() throws Exception {
        runs("""
            let calls = 0;
            class C {
              #x = 1;
              #fn = function (x) { return this.#x + x; };
              #object = {value: 9};
              receiver() { calls++; return this; }
              exercise() {
                this.receiver().#x += 2;
                const old = this.receiver().#x++;
                ++this.receiver().#x;
                this.#x = 11;
                this.#x &&= 12; this.#x ||= 20; this.#x ??= 30;
                this.#fn.call = function () { throw Error('wrong call'); };
                return [old, this.receiver().#fn(2), (this.#fn)(3), this.#fn?.(4), this.#x];
              }
              static optional(o) { return [o?.#object.value, o?.#fn(1)]; }
            }
            const c = new C();
            console.log(c.exercise().join(','), calls);
            console.log(C.optional(c).join(','), C.optional(null).join(','));
            """, "3,14,15,16,12 4\n9,13 ,");
    }
    @Test void inheritanceNestedClassesAndRepeatedClassEvaluationHaveDistinctBrands() throws Exception {
        runs("""
            const x = 5;
            class Base { #x = 1; read() { return this.#x; } }
            class Child extends Base {
              #x = x;
              constructor(flag) { const x = 99; if (flag) super(); else super(); }
              readChild() { return this.#x; }
              inner() { return class { #x = 8; read(o) { return this.#x + o.readChild(); } }; }
              capture() { return class { read(o) { return o.#x; } }; }
            }
            function factory() { return class { #x = 1; static has(o) { return #x in o; } }; }
            const a = new Child(true), b = new Child(false), I = a.inner(), Capture = a.capture(), A = factory(), B = factory();
            console.log(a.read(), b.readChild(), new I().read(a), new Capture().read(a), A.has(new A()), A.has(new B()));
            """, "1 5 13 5 true false");
    }
    @Test void staticBrandsAreNotInheritedAndInitializersCannotReadLaterFields() throws Exception {
        runs("""
            const log = [];
            class A {
              static #x = 1;
              static read() { return this.#x; }
              static has(o) { return #x in o; }
              static { log.push(this.#x); this.#x++; }
            }
            class B extends A {}
            try { B.read(); } catch (e) { log.push(e instanceof TypeError); }
            try { new (class { #a = this.#b; #b = 1; })(); } catch (e) { log.push(e instanceof TypeError); }
            console.log(log.join(','), A.read(), A.has(A), A.has(B));
            """, "1,true,true 2 true false");
    }
    @Test void privateFieldsCanStampReturnedObjectsOnlyOnce() throws Exception {
        runs("""
            class Base { constructor(o) { return o; } }
            class Stamp extends Base { #x = 2; static get(o) { return o.#x; } }
            const o = Object.freeze({}); new Stamp(o);
            let duplicate = false; try { new Stamp(o); } catch (e) { duplicate = e instanceof TypeError; }
            console.log(Stamp.get(o), duplicate, Object.keys(o).length);
            """, "2 true 0");
    }
    @Test void privateNamesAndAnonymousFieldNamesArePreserved() throws Exception {
        runs("""
            class C { #value = 1; #fn = () => this.#value; #klass = class {};
              names() { return [this.#fn.name, this.#klass.name, this.#fn()]; } }
            console.log(new C().names().join(','));
            """, "#fn,#klass,1");
    }
    @Test void invalidPrivateSyntaxReturnsDiagnosticsWithoutOutput() {
        for (String source : List.of(
                "class C { #x; #x; }", "class C { #x; static #x; }", "class C { #constructor; }",
                "class C { # x; }", "const o = { #x: 1 };", "class C { read() { return this.#missing; } }",
                "class C { #x; read(){ return delete (this.#x); } }",
                "class C extends B { #x; read(){ return super.#x; } }", "const x = object.#x;",
                "class C { #x; read(){ return #x; } }")) {
            TranspileResult result = new TypeScriptTranspiler().transpile("invalid.ts", source);
            assertFalse(result.successful(), source); assertEquals("", result.javaScript());
            assertEquals("TS_SYNTAX", result.diagnostics().get(0).code(), source);
        }
    }
    @Test void constructorFieldsDefaultParametersAndComputedKeysRetainTheirReferences() throws Exception {
        runs("""
            class C {
              #Ctor = class { value = 7; };
              #x = 3;
              create() { return new this.#Ctor(); }
              read(value = this.#x) { return value; }
              computed() { const outer = this; return class { [outer.#x]() { return outer.#x; } }; }
            }
            const c = new C(), Inner = c.computed();
            console.log(c.create().value, c.read(), new Inner()[3]());
            """, "7 3 3");
    }
    @Test void privateDestructuringAndTaggedTemplatesUseTheOriginalReceiver() throws Exception {
        String source = """
            class C {
              #x = 1;
              #tag = function(parts, x) { return this.#x + ':' + parts[0] + ':' + x; };
              run() { [this.#x] = [3]; ({x: this.#x} = {x: 4}); return this.#tag`text${5}`; }
            }
            console.log(new C().run());
            """;
        for (JavaScriptTarget target : List.of(JavaScriptTarget.PRESERVE, JavaScriptTarget.ES6, JavaScriptTarget.ES2021)) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("private.ts", source);
            assertTrue(result.successful(), result.diagnostics().toString());
            new JavaScriptRuntime(directory).syntax(target == JavaScriptTarget.PRESERVE ? 2022 : target == JavaScriptTarget.ES6 ? 2015 : 2021, "module", result.javaScript());
            assertEquals("4:text:5", new JavaScriptRuntime(directory).run(result.javaScript()));
        }
    }
    @Test void asyncMethodsAndClassDecoratorsComposeWithPrivateFields() throws Exception {
        String source = """
            function decorate(value, context) { context.addInitializer(function(){ this.decorated = true; }); return value; }
            @decorate class C { #x = 3; async read() { return await Promise.resolve(this.#x); } }
            new C().read().then(value => console.log(value, C.decorated));
            """;
        for (JavaScriptTarget target : List.of(JavaScriptTarget.PRESERVE, JavaScriptTarget.ES6, JavaScriptTarget.ES2021)) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("private.ts", source);
            assertTrue(result.successful(), result.diagnostics().toString());
            assertEquals("3 true", new JavaScriptRuntime(directory).run(result.javaScript()));
        }
    }
    @Test void nativePrivateMethodsArePreservedAndExplicitTargetLimitsAreDiagnosed() throws Exception {
        String source = "class C { #m(){return 1;} get #x(){return 2;} value(){return this.#m()+this.#x;} } console.log(new C().value());";
        TranspileResult preserved = new TypeScriptTranspiler().transpile("methods.ts", source);
        assertTrue(preserved.successful(), preserved.diagnostics().toString());
        assertEquals("3", new JavaScriptRuntime(directory).run(preserved.javaScript()));
        for (JavaScriptTarget target : List.of(JavaScriptTarget.ES5, JavaScriptTarget.ES6, JavaScriptTarget.ES2021)) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("methods.ts", source);
            assertFalse(result.successful()); assertEquals("", result.javaScript()); assertEquals("JS_TARGET", result.diagnostics().get(0).code());
        }
    }
}
