package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.emit.SyntaxTargetValidator;
import com.pingsu.transpiler.internal.js.JsProgram;
import com.pingsu.transpiler.internal.js.TargetFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class Es5TargetTest {
    @TempDir Path temporary;
    private String compile(String source) throws Exception {
        TranspileResult result=new TypeScriptTranspiler(JavaScriptTarget.ES5).transpile("es5.ts",source);
        assertTrue(result.successful(),()->result.diagnostics()+"\n"+source);
        new JavaScriptRuntime(temporary).syntax(JavaScriptTarget.ES5,result.javaScript());
        return result.javaScript();
    }
    private void equivalent(String source) throws Exception {
        String result=compile(source);JavaScriptRuntime runtime=new JavaScriptRuntime(temporary);
        assertEquals(runtime.run(source,"script"),runtime.run(result,"script"),result);
    }
    @Test void erasesTypesAndExposesTargetThroughPublicApi() throws Exception {
        String output=compile("interface User {name:string} const greet=(u:User):string=>`Hello ${u.name}`; console.log(greet({name:'ES5'}));");
        assertEquals("Hello ES5",new JavaScriptRuntime(temporary).run(output,"script"));
        assertTrue(new DefaultJavaScriptEmitter().emit("plain.js","var x=1;",JavaScriptTarget.ES5).successful());
    }
    @Test void lexicalBindingsKeepShadowingTdzAndConstWrites() throws Exception {
        equivalent("""
            let x=1;{let x=2;console.log(x);}console.log(x);
            try {console.log(x);let x=3;}catch(e){console.log(e.name);}
            const value=4;try {value=5;}catch(e){console.log(e.name,value);}
            let a=1,b=a+1;console.log(a,b);
            function read(){return later;}try{read();}catch(e){console.log(e.name);}let later=7;console.log(read());
            """);
    }
    @Test void loopClosuresKeepPerIterationEnvironmentsAndControlFlow() throws Exception {
        equivalent("""
            const callbacks=[];
            for(let i=0;i<3;i++){let x=i*2;callbacks.push(()=>[i,x]);if(i===1)continue;}
            console.log(callbacks.map(f=>f().join(':')).join(','));
            const more=[];let i=0;while(i<3){let n=i++;more.push(function(){return n;});}
            console.log(more.map(f=>f()).join(','));
            for(let n=0,read=()=>n;n<1;n++){n=5;console.log(read(),n);}
            """);
    }
    @Test void arrowsCaptureThisAndArgumentsAcrossNestedFunctions() throws Exception {
        equivalent("""
            function build(v){return ()=>()=>this.base+arguments[0]+v;}
            console.log(build.call({base:2},3)()());
            const owner={value:7,run(){const f=(x=2,...rest)=>this.value+x+rest[0];return f(3,4);}};
            console.log(owner.run());
            function f(a=2,b=a+1,...rest){return [a,b,rest.join(':')].join(',');}
            console.log(f(),f(4,void 0,7,8));
            """);
    }
    @Test void templatesUnicodeAndNumericLiteralsAreEs5() throws Exception {
        equivalent("""
            let calls=0;const x={toString(){calls++;return 'x';}};
            console.log(`line\n${x}:${calls}:${'\\u{1f600}'}`,0b101,0o17,1_000);
            console.log({'\\u{61}':7,0b11:4}.a,calls);
            """);
    }
    @Test void objectMethodsComputedPropertiesAndSpreadPreserveOrder() throws Exception {
        equivalent("""
            let calls=0;const key=()=>{calls++;return 'x';};const a=3;
            const o={a,[key()]:4,get value(){return this.x;},set value(v){this.x=v;},method(n){return this.a+n;},...{b:5}};
            o.value=8;console.log(calls,o.a,o.x,o.value,o.method(2),o.b,Object.keys(o).join(','));
            """);
    }
    @Test void spreadCallsKeepReceiversAndSparseArrays() throws Exception {
        equivalent("""
            let calls=0;const o={x:4,sum(a,b){return this.x+a+b;}};function get(){calls++;return o;}
            console.log(get().sum(...[2,3]),calls,[0,...'馃榾x',,4].join(':'));
            function C(a,b){this.value=a+b;}console.log(new C(...[3,4]).value);
            """);
    }
    @Test void destructuringDefaultsRestAndComputedKeysEvaluateOnce() throws Exception {
        equivalent("""
            let calls=0;const key=()=>{calls++;return 'x';};
            const {[key()]:x=4,nested:{a},...rest}={nested:{a:2},other:8};
            const [first,,third=3,...tail]=[1,2,void 0,4,5];
            function f({x=7},[a,b]){return x+a+b;}
            console.log(x,a,rest.other,calls,first,third,tail.join(','),f({},[2,3]));
            """);
    }
    @Test void forOfClosesIteratorsOnBreakAndCapturesBindings() throws Exception {
        equivalent("""
            const events=[];const iterable={[Symbol.iterator](){let i=0;return {next(){return {value:++i,done:i>3};},return(){events.push('close');return {done:true};}};}};
            const reads=[];outer:for(const n of iterable){reads.push(()=>n);if(n===2)break outer;}
            console.log(reads.map(f=>f()).join(','),events.join(','));
            for(const [a,b] of [[1,2],[3,4]])console.log(a+b);
            """);
    }
    @Test void classesFieldsMethodsAndSimpleInheritance() throws Exception {
        equivalent("""
            class Base {constructor(x){this.x=x;}get value(){return this.x;}method(n){return this.x+n;}}
            class Derived extends Base {constructor(x){super(x);this.y=2;}method(n){return super.method(n)+this.y;}static label(){return 'derived';}}
            const d=new Derived(3);console.log(d.value,d.method(4),d instanceof Base,d instanceof Derived,Derived.label());
            class Box {value=4;empty;static tag='box';read(){return this.value;}}
            const box=new Box();console.log(box.read(),Box.tag,Object.keys(box).join(','),Object.keys(Box.prototype).length);
            """);
    }
    @Test void laterExpressionsLowerAndPreserveShortCircuiting() throws Exception {
        equivalent("""
            const o={x:3,method(n){return this.x+n;}};let calls=0,x=null;
            console.log(o?.method?.(2),null?.method(++calls),x??7);x??=2;x**=3;console.log(x,calls);
            try{throw 1;}catch{console.log('caught');}
            """);
    }
    @Test void directivesDetachedCallsAndParameterArgumentsStayCorrect() throws Exception {
        equivalent("""
            'use strict';
            const f=function(){return this;};let g=f;console.log(f()===undefined,(g)()===undefined);
            function defaults(a,b=3){arguments[0]=9;return a;}console.log(defaults(2));
            function late(a=b,b=1){return a;}try{late();}catch(e){console.log(e.name);}
            function self(a=a){return a;}try{self();}catch(e){console.log(e.name);}
            function strict(){'use strict';let x=1;return this===undefined;}console.log(strict());
            const o={get value(){return 3;},set value(v){this.saved=v;}};o.value=5;console.log(o.value,o.saved);
            """);
    }
    @Test void derivedFieldsAndReturnValuesPreserveInitialization() throws Exception {
        equivalent("""
            class B {constructor(x){this.x=x;}}
            class D extends B {field=4;constructor(x){if(x)super(x);else super(2);this.y=this.field+this.x;}}
            console.log(new D(3).y,new D(0).y);
            class A extends B {constructor(){const read=()=>this.x;const init=()=>super(5);init();this.read=read;}}console.log(new A().read());
            class O extends B {constructor(){return {x:7};}}console.log(new O().x);
            class P extends B {constructor(){super(1);return 4;}}try{new P();}catch(e){console.log(e.name);}
            """);
    }
    @Test void boundedDestructuringClosesIteratorWithoutExhaustingIt() throws Exception {
        equivalent("""
            const events=[];const iterable={[Symbol.iterator](){let i=0;return {next(){events.push('next');return {value:++i,done:false};},return(){events.push('close');return {};}};}};
            const [a,b]=iterable;console.log(a,b,events.join(','));
            """);
    }
    @Test void fieldArrowsNativeInheritanceAndEs5OnlyBuiltins() throws Exception {
        equivalent("""
            class C {value=4;read=()=>this.value;static n=7;static get=()=>this.n;}
            const c=new C();console.log(c.read(),C.get());
            function make(key){return class {[this.key](){return 2;}[arguments[0]]=3;};}
            const Local=make.call({key:'method'},'field');console.log(new Local().method(),new Local().field);
            class E extends Error {constructor(message){super(message);this.extra=2;}}
            const e=new E('failed');console.log(e instanceof E,e instanceof Error,e.message,e.extra);
            """);
        String output=compile("const values=[1,2];const o={x:3,read(){return this.x;}};console.log(o?.read?.(),[...values].join(','));for(const n of values)console.log(n);");
        assertEquals("3 1,2\n1\n2",new JavaScriptRuntime(temporary).run("Symbol=void 0;Reflect=void 0;Object.assign=void 0;"+output,"script"));
    }
    @Test void lexicalForOfHeaderKeepsTdzAndComputedRestCoercesKeysOnce() throws Exception {
        equivalent("""
            const x=[1];try{for(let x of x){}}catch(e){console.log(e.name);}
            let calls=0;const key={toString(){calls++;return '1';}};const {[key]:value,...rest}={1:4,other:5};
            console.log(value,rest.other,Object.keys(rest).join(','),calls);
            """);
    }
    @Test void optionalSpreadAndCommentsComposeWithLowering() throws Exception {
        equivalent("""
            const o={base:4,method(a,b){return this.base+a+b;}};const xs=[2,3];
            console.log(o?.method?.(...xs),null?.method(...xs));
            function f(x){return x;}console.log(f(/* ... comment */ 2));
            """);
    }
    @Test void unsupportedFeaturesProduceLocatedDiagnosticsAndNoPartialOutput() {
        for(String source:List.of("export const x=1;","import './x';","export interface Empty {}","async function f(){await p;}","function* f(){yield 1;}","const x=1n;","const x=/x/u;","const x=/(?<name>x)/;","const x=tag`x`;","function f(){return new.target;}","const x=import('x');","const \uD801\uDC00=1;")) {
            TranspileResult result=new TypeScriptTranspiler(JavaScriptTarget.ES5).transpile("unsupported.ts",source);
            assertFalse(result.successful(),source);assertEquals("",result.javaScript());
            assertEquals("JS_TARGET",result.diagnostics().get(0).code());assertEquals("unsupported.ts",result.diagnostics().get(0).sourceName());assertTrue(result.diagnostics().get(0).line()>0);
        }
    }
    @Test void validatorRejectsResidualModernSyntax() {
        for(String source:List.of("let x=1;","const f=()=>1;","class C {}","var x=`s`;","var o={method(){}};","var o={x};","var [x]=a;","f(...a);","for(var x of a){}","var n=0b10;","function f(x=2){}")) {
            assertThrows(TargetFailure.class,()->new SyntaxTargetValidator().validate(JsProgram.parse("leak.js",source),JavaScriptTarget.ES5),source);
        }
    }
    @Test void sharedCompilerHasIsolatedConcurrentSessions() throws Exception {
        TypeScriptTranspiler compiler=new TypeScriptTranspiler(JavaScriptTarget.ES5);ExecutorService executor=Executors.newFixedThreadPool(4);
        try {List<Callable<String>> tasks=new java.util.ArrayList<>();for(int i=0;i<16;i++)tasks.add(()->{TranspileResult r=compiler.transpile("parallel.ts","let x=1;const f=()=>x+1;console.log(f());");assertTrue(r.successful(),r.diagnostics().toString());return r.javaScript();});String expected=null;for(Future<String> task:executor.invokeAll(tasks)){String output=task.get();if(expected==null)expected=output;else assertEquals(expected,output);}}
        finally {executor.shutdownNow();}
    }
}
