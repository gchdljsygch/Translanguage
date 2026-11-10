package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.emit.*;
import com.pingsu.transpiler.internal.js.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class OwnJavaScriptBackendTest {
    @TempDir Path temporary;
    private void equivalent(JavaScriptTarget target, String source) throws Exception {
        var result = new DefaultJavaScriptEmitter().emit("oracle.js", source,target);
        assertTrue(result.successful(),()->result.diagnostics()+"\n"+source);
        JavaScriptRuntime runtime = new JavaScriptRuntime(temporary);
        runtime.syntax(target,result.javaScript());
        assertEquals(runtime.run(source),runtime.run(result.javaScript()),result.javaScript());
    }
    static Stream<Arguments> semantics() {
        Map<String,String> cases = new LinkedHashMap<>();
        cases.put("reference-once", """
            const events=[];let value=null;
            const object={get x(){events.push('get');return value},set x(v){events.push('set:'+v);value=v}};
            function base(){events.push('base');return object} function key(){events.push('key');return {toString(){events.push('convert');return 'x'}}}
            function rhs(){events.push('rhs');return 3}
            console.log(base()[key()]??=rhs(),base()[key()]||=rhs(),base()[key()]&&=rhs(),base()[key()]**=rhs());
            console.log(events.join(','),value);
            """);
        cases.put("optional-chain", """
            const events=[];const owner={x:4,get method(){events.push('getter');return function(v){events.push('call');return {value:this.x+v}}}};
            function arg(){events.push('arg');return 2}function key(){events.push('key');return 'value'}
            console.log(owner?.method?.(arg())[key()],null?.method(arg())[key()],owner.method?.(arg())?.value);
            console.log(delete null?.[key()],delete owner?.x,events.join(','));
            let f=function(){return this===undefined};f.call=()=>false;console.log(f?.());
            """);
        cases.put("parenthesized-call", """
            const o={x:7,m(){return this.x}};console.log((o.m)?.(),(o?.m)());
            try{(null?.m)()}catch(e){console.log(e.name)}
            """);
        cases.put("yield-expression", """
            function* f(){const object={x:null};object.x??=yield 3;return object.x**2}
            const it=f();console.log(it.next().value,it.next(4).value);
            function* g(){return (yield 2)?.x??9}const j=g();console.log(j.next().value,j.next({x:5}).value);
            """);
        cases.put("directive-parameter-arrow", """
            function f(x=({v:2})?.v??3){'use strictish';return x}
            function strict(){'use strict';let x=null;x??=4;return [this===undefined,x].join(',')}
            const arrow=(x)=>x?.a??2;console.log(f(),strict(),arrow(null),arrow({a:5}));
            """);
        cases.put("computed-key-order", """
            const events=[];let count=0;
            function key(v){events.push('key:'+v);return {toString(){events.push('convert:'+v);return v}}}
            class C{[key('a')]=(++count);[key('m')](){};static [key('s')]=(events.push('static'),this.name);b=count}
            const c=new C(),d=new C();console.log(events.join(','),c.a,d.a,c.b,d.b,C.s,Object.getOwnPropertySymbols(C).length,Object.getOwnPropertySymbols(C.prototype).length);
            """);
        cases.put("field-home-and-shadowing", """
            const outer=4;class B{get value(){return this.id}static get value(){return this.name}set field(v){throw 1}}
            class C extends B {id=3;field=outer;read=()=>super.value;static valueCopy=super.value;static reflect=[Object.getOwnPropertyNames(this),Object.getOwnPropertySymbols(this)].map(x=>x.length).join(',');constructor(outer){super();this.last=outer}}
            const c=new C(9);console.log(c.field,c.read(),c.last,C.valueCopy,C.reflect,Object.getOwnPropertyDescriptor(c,'field').enumerable);
            """);
        cases.put("class-expression-repeated", """
            let k=0;function make(v){return class {['x'+(++k)]=v;static n=this.name}}
            const A=make(1),B=make(2);console.log(new A().x1,new B().x2,A.n,B.n);
            const Named=class Local {static label=this.name;field=()=>Local};console.log(Named.label,new Named().field()===Named);
            """);
        cases.put("static-order-and-name", """
            const trace=[];const A=class {static first=(trace.push(this.name),1);static {trace.push(this.first)}static last=(trace.push('end'),2)};
            class B{static name(){return 'method'};static seen=this.name();fn=function(){};arrow=()=>1}
            console.log(A.name,trace.join(','),B.seen,new B().fn.name,new B().arrow.name);
            """);
        cases.put("spread-order-symbol-null", """
            const log=[],s=Symbol('s');const a={get x(){log.push('get');return 1},[s]:2};
            const b={...(log.push('a'),a),y:(log.push('y'),3),...null,...undefined,x:4};
            console.log(log.join(','),b.x,b.y,b[s],Object.keys(b).join(','));
            """);
        cases.put("rest-keys-and-proxy", """
            const log=[],s=Symbol('s');const source=new Proxy({a:1,b:2,[s]:3},{get(t,k,r){log.push('get:'+String(k));return Reflect.get(t,k,r)},ownKeys(t){log.push('keys');return Reflect.ownKeys(t)},getOwnPropertyDescriptor(t,k){log.push('desc:'+String(k));return Reflect.getOwnPropertyDescriptor(t,k)}});
            const key={toString(){log.push('key');return 'a'}};const {[key]:a,[s]:symbol,...rest}=source;
            console.log(a,symbol,JSON.stringify(rest),log.join(','));
            let touched=0;try{const {[++touched]:x,...r}=null}catch(e){console.log(e.name,touched)}
            """);
        cases.put("rest-nested-default-assignment", """
            const events=[];const source={a:{x:2,y:3},b:4};
            const {a:{x,...inner},missing:{z,...fallback}=(events.push('default'),{z:8,k:9}),...rest}=source;
            let a,r;const returned=({a,...r}=source);console.log(x,inner.y,z,fallback.k,rest.b,returned===source,a===source.a,r.b,events.join(','));
            """);
        cases.put("rest-array-iterator-close", """
            const events=[];function* values(){try{yield {x:1,a:2};yield {y:3,b:4};yield 9}finally{events.push('close')}}
            const [{x,...one},{y,...two}]=values();console.log(x,one.a,y,two.b,events.join(','));
            const [,{...r},...tail]=[{a:1},{b:2},3,4];console.log(r.b,tail.join(','));
            """);
        cases.put("rest-parameters-loops-catch", """
            function f({a,...r},b=a,{...s}={z:b}){let hidden=7;return [a,r.x,b,s.z,hidden,arguments.length]}
            const arrow=({a,...r})=>a+r.b;let total=0;
            for(const {a,...r} of [{a:1,b:2},{a:3,b:4}]){total+=a+r.b}
            try{throw {x:5,y:6}}catch({x,...r}){console.log(x,r.y)}
            console.log(f({a:2,x:3}).join(','),f.length,arrow({a:1,b:2}),total);
            """);
        cases.put("numeric-text-and-catch", """
            function f(x,/*a*/){return x}const text='1_000 ?? ** ?. ...';const re=/[?*._]/;
            try{throw 1}catch/* a */{console.log(1_000,0xA_B,f(2,/*b*/),text,re.test('?'))}
            """);
        cases.put("collision-and-recursion", """
            const $tl$value$0=12,$tl$class$0=7;const \u0024tl$value$1=8;
            function f(n){let o={x:n};o.x**=n?f(n-1):2;return o.x||1}
            console.log($tl$value$0,$tl$value$1,$tl$class$0,f(3));
            """);
        cases.put("anonymous-class-names", """
            const log=[];const key={toString(){log.push('key');return 'Computed'}};
            const obj={[key]:class {static label=this.name; x=1}};
            const Parent=class {Child=class {static label=this.name;x=2};static Child=class{static label=this.name;x=3}};
            const {Default=class {static label=this.name;x=4}}={};
            const Wrapped=(class {static label=this.name;x=5});
            console.log(obj.Computed.label,new Parent().Child.label,Parent.Child.label,Default.label,Wrapped.label,log.join(','));
            """);
        cases.put("rest-array-rest-target", """
            const events=[];function* seq(){try{yield 1;yield 2;yield 3}finally{events.push('close')}}
            const [head,...{0:first,...tail}]=seq();console.log(head,first,JSON.stringify(tail),events.join(','));
            let rest;let it=(function*(){try{yield {a:1};yield 2;yield 3}finally{events.push('second')}})();
            ([{...rest},,]=it);console.log(rest.a,events.join(','));
            """);
        cases.put("delete-parenthesized", """
            let o={a:2};console.log(delete (o?.a),Object.keys(o).length,delete (null?.a));
            """);
        cases.put("rest-parameter-environments", """
            function f({x,...r},get=()=>x){var x;console.log(x,get());x=8;console.log(x,get());return r.a}
            console.log(f({x:2,a:3}));
            let calls=0;function* g({x,...r},b=++calls){yield [x,r.a,b,arguments.length].join(',')}
            const iterator=g({x:1,a:2});console.log(calls,iterator.next().value);try{g(null)}catch(e){console.log(e.name)}
            const frozen={ [Symbol.iterator](){let done=false;return {next(){if(done)return {done:true};done=true;return Object.freeze({done:false,value:{a:3,b:4}})}}}};
            const [{a,...rest}]=frozen;console.log(a,rest.b);
            """);
        cases.put("generator-super-and-tag", """
            class B{m(x){return this.value+x}get n(){return this.value}set n(v){this.value=v}}
            class C extends B{constructor(){super();this.value=3}*g({x,...r}){yield super.m(x);yield super.n??=5;yield super.n**=2;return [r.a,arguments.length].join(',')}}
            const iterator=new C().g({x:2,a:4});console.log(iterator.next().value,iterator.next().value,iterator.next().value,iterator.next().value);
            const tag={v:9,tag(parts,x){return this.v+':'+parts.raw[0]+':'+x}};console.log((tag?.tag)`text${2}`);
            """);
        return cases.entrySet().stream().flatMap(e -> Stream.of(JavaScriptTarget.ES6,JavaScriptTarget.ES2021).map(t -> Arguments.of(e.getKey(),t,e.getValue())));
    }
    @ParameterizedTest(name="{0}/{1}") @MethodSource("semantics")
    void preservesNativeObservableBehavior(String name, JavaScriptTarget target,String source) throws Exception { equivalent(target,source); }

    @Test void actualArtifactsAreReparsedAndChecked() {
        var emitter = new DefaultJavaScriptEmitter();
        for (String source : List.of("const x: number=1", "const x=1; !", "await value", "class C { read(){ return this.#missing; } }", "const x=/(?i:a)/", "const x=/(/")) {
            var result=emitter.emit("emitted.js",source,JavaScriptTarget.ES2021);assertFalse(result.successful(),source);assertEquals("",result.javaScript());
            assertTrue(result.diagnostics().stream().allMatch(d->d.code().equals("JS_TARGET")&&d.sourceName().equals("emitted.js")));
        }
        var validator=new SyntaxTargetValidator();
        for(String source:List.of("let x=a?.b", "let x=2**3", "try{}catch{}", "function f(a,){}", "class C{x=1}"))
            assertThrows(TargetFailure.class,()->validator.validate(JsProgram.parse("artifact.js",source),JavaScriptTarget.ES6),source);
    }
    @Test void dynamicScopeCombinationsFailExplicitly() {
        for(String source:List.of("function f(){eval('var x');return object?.x}","with(object){value??=1}","class C{x=eval('name')}")) {
            var result=new DefaultJavaScriptEmitter().emit("dynamic.js",source,JavaScriptTarget.ES6);
            assertFalse(result.successful());assertEquals("",result.javaScript());assertEquals("JS_TARGET",result.diagnostics().get(0).code());
        }
    }
    @Test void printerRoundTripIsStableAndPreservesTrivia() {
        String source="// unicode 😀\r\n'use strict';\nconst value=(a+b)*c;\nfunction f(){return\n/regexp/.test('x')}";
        var first=JsProgram.parse("roundtrip.js",source);String output=new JavaScriptPrinter(first).print();
        assertEquals(source,output);assertEquals(first.root(),JsProgram.parse("roundtrip.js",output).root());
    }
    @Test void seededExpressionsAgreeWithNative() throws Exception {
        Random random=new Random(0x50345035L);StringBuilder source=new StringBuilder("const values=[];\n");
        for(int i=0;i<60;i++){int a=random.nextInt(5),b=random.nextInt(4);source.append("values.push(({x:").append(a).append("})?.x ** ").append(b).append(" ?? 7);\n");}
        source.append("console.log(values.join(','));");equivalent(JavaScriptTarget.ES6,source.toString());
    }
}
