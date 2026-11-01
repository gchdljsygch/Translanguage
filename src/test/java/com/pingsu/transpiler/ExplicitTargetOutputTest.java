package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.emit.ReadableJavaScriptPrinter;
import com.pingsu.transpiler.internal.js.JsProgram;
import com.pingsu.transpiler.syntax.TokenKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitTargetOutputTest {
    @TempDir Path temporary;

    private String emit(JavaScriptTarget target, String source) {
        var result = new TypeScriptTranspiler(target).transpile("output.ts", source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        return result.javaScript();
    }

    private void equivalent(JavaScriptTarget target, String source) throws Exception {
        String output = emit(target, source);
        var runtime = new JavaScriptRuntime(temporary);
        runtime.syntax(target, output);
        assertEquals(runtime.run(source), runtime.run(output), output);
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void simpleClassesRemainClassesAndShareOneDefinition(JavaScriptTarget target) throws Exception {
        String source = "class Counter { value = 0; }";
        String output = emit(target, source);
        assertTrue(output.startsWith("class Counter {\n  constructor() {\n"), output);
        assertFalse(output.contains("Symbol("), output);
        assertTrue(output.length() < 300, output); // Previously 480 bytes for this ASCII fixture.
        String many = emit(target, "class Data { a = 1; b = 2; c = 3; d = 4; } class More { e = 5; }");
        assertEquals(1, many.split("function \\$tl\\$defineField\\$", -1).length - 1);
        assertTrue(many.length() < 550, many);
        equivalent(target, source + "console.log(new Counter().value, Object.keys(new Counter()).join(','));");
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void fastPathPreservesInitializationAndNaming(JavaScriptTarget target) throws Exception {
        equivalent(target, """
                const log=[];let outer=7;
                class Base { set value(v){throw new Error('setter')} get n(){return 3} }
                class Simple extends Base {
                  value=outer; next=this.value+super.n; absent; arrow=()=>this.next;
                  fn=(function(){}); Child=(class { static label=this.name; });
                }
                class Explicit extends Base {
                  value=1; flag=true; negative=-2;
                  constructor(Object,outer){super();log.push(this.value,this.flag,this.negative,Object,outer)}
                }
                class Shadow { value=outer; constructor(outer){this.after=outer} }
                class Target { value=new.target; }
                const s=new Simple(),e=new Explicit('shadow',9),h=new Shadow(11);
                console.log(s.value,s.next,s.arrow(),s.fn.name,s.Child.name,s.Child.label,Object.hasOwn(s,'absent'));
                console.log(h.value,h.after,new Target().value,log.join(','));
                console.log(JSON.stringify(Object.getOwnPropertyDescriptor(e,'value')));
                """);
    }

    @ParameterizedTest @EnumSource(value = JavaScriptTarget.class, names = {"ES6", "ES2021"})
    void exportsAndComplexFieldsKeepTheirSemantics(JavaScriptTarget target) throws Exception {
        equivalent(target, """
                export class Named { value=1; }
                export default class { value=2; }
                const log=[];
                class Complex {
                  [(log.push('key'),'x')]=(log.push('instance'),1);
                  static n=(log.push('static'),this.name);
                  static { log.push(Object.getOwnPropertySymbols(this).length); }
                }
                console.log(new Named().value,new Complex().x,Complex.n,log.join(','));
                """);
        String staticOnly = emit(target, "class Flags { static ready = true; }");
        assertFalse(staticOnly.contains("$tl$instance$"), staticOnly);
        assertFalse(staticOnly.contains("$tl$key$"), staticOnly);
        String complex = emit(target, "class Box { value = read(); constructor() {} }");
        assertFalse(complex.contains("$tl$static$"), complex);
        assertFalse(complex.contains("$tl$key$"), complex);
    }

    @Test void objectRestIncludesOnlyTheRequiredAdapters() throws Exception {
        String source = "const source={x:1,y:2};const {x,...rest}=source;console.log(x,rest.y);";
        String output = emit(JavaScriptTarget.ES6, source);
        assertFalse(output.contains("Symbol.iterator"), output);
        assertFalse(output.contains("WeakMap"), output);
        assertTrue(output.length() < 1400, output); // Former helper alone exceeded 1800 bytes.
        equivalent(JavaScriptTarget.ES6, source);
        equivalent(JavaScriptTarget.ES6, """
                const s={x:1,y:2};let x,r;const original=({x,...r}=s);
                const [{x:a,...b}]=[s];const {nested:{x:c,...d}={x:3,y:4},...e}={z:5};
                console.log(original===s,x,r.y,a,b.y,c,d.y,e.z);
                """);
        String array = emit(JavaScriptTarget.ES6, "const [{x,...rest}] = source;");
        assertTrue(array.contains("Symbol.iterator"), array);
        assertFalse(array.contains("WeakMap"), array);
        String assignment = emit(JavaScriptTarget.ES6, "let x,rest;({x,...rest}=source);");
        assertTrue(assignment.contains("WeakMap"), assignment);
        assertFalse(assignment.contains("Symbol.iterator"), assignment);
        assertEquals(output, emit(JavaScriptTarget.ES6, source));
    }

    @Test void formattingPreservesTokensTemplatesCommentsAndAutomaticSemicolons() throws Exception {
        String source = """
                #!/usr/bin/env node
                // Keep this comment and Unicode 字符 😀
                'use strict';function f(){return
                /x/.test('x')}
                let value=1;value
                ++value;
                const raw=String.raw`first  line
                  second\\n${(()=>{const x=2;return x})()}`;
                const object={get x(){/* access */return 4},m(){return this.x}};
                console.log(f(),value,raw,object.m(),1 + +2,3 - -4);
                """;
        JsProgram parsed = JsProgram.parse("format.js", source);
        String output = new ReadableJavaScriptPrinter(parsed).print();
        assertEquals(tokens(parsed), tokens(JsProgram.parse("format.js", output)));
        assertEquals(output, new ReadableJavaScriptPrinter(JsProgram.parse("format.js", output)).print());
        // Hashbangs are a host extension before ES2023: Node checks the full artifact,
        // while Acorn checks the remaining text against the declared language version.
        var runtime = new JavaScriptRuntime(temporary);
        assertEquals(runtime.run(source), runtime.run(output));
        String withoutHashbang = source.substring(source.indexOf('\n') + 1);
        equivalent(JavaScriptTarget.ES6, withoutHashbang);
        equivalent(JavaScriptTarget.ES2021, withoutHashbang);
        assertEquals(source, new DefaultJavaScriptEmitter().emit("format.js", source, JavaScriptTarget.PRESERVE).javaScript());
    }

    private List<String> tokens(JsProgram program) {
        return program.tokens().stream().filter(t -> t.kind() != TokenKind.WHITESPACE && t.kind() != TokenKind.LINE_TERMINATOR)
                .map(t -> t.kind() + ":" + t.text()).toList();
    }
}
