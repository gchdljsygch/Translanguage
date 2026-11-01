package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.js.JsProgram;
import com.pingsu.transpiler.internal.emit.SyntaxTargetValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Native Node event traces and independent Acorn grammar checks for asynchronous lowering. */
class OwnAsyncBackendTest {
    @TempDir Path temporary;
    static Stream<Arguments> semantics(){
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("await-order-thenable", """
            const log=[];const thenable={get then(){log.push('get');return r=>{log.push('then');r(3)}}};
            async function f(){log.push('start');const x=await thenable;log.push(x);await 0;log.push('end');return 4}
            const p=f();log.push('sync');Promise.resolve().then(()=>log.push('micro'));p.then(v=>console.log(log.join(','),v));
            """);
        cases.put("async-parameter-rejection", """
            const log=[];function bad(){log.push('parameter');throw 5}
            async function f(x=bad()){log.push('body')}
            let p;try{p=f();log.push('returned')}catch(e){log.push('sync:'+e)}
            Promise.resolve(p).catch(e=>log.push('reject:'+e)).then(()=>console.log(log.join(','),f.length));
            """);
        cases.put("parameters-body-scope", """
            let x=7;async function f(a=x,b=()=>a,...rest){var a=3;await 0;return [a,b(),rest.length,arguments.length,arguments[0],this.id,new.target].join(':')}
            f.call({id:8},void 0,void 0,4,5).then(console.log);
            """);
        cases.put("arrow-capture", """
            function make(x){const f=async (a=arguments[0])=>{await 0;return [a,this.id,arguments[0],new.target===make,(()=>arguments[0])(),({arguments}).arguments[0]].join(',')};return f}
            make.call({id:5},8)().then(console.log);new make(9)().then(console.log);
            """);
        cases.put("arrow-local-arguments", """
            const f=async (arguments)=>{await 0;return arguments+1};f(8).then(console.log);
            """.replace("arguments", "local"));
        cases.put("nested-functions", """
            async function f(a){function plain(){return arguments[0]}const arrow=()=>a;const inner=async x=>{await x;return arguments[0]};await 0;return [plain(3),arrow(),await inner(4)].join(',')}
            f(7).then(console.log);
            """);
        cases.put("strict-this", """
            async function f(){'use strict';await 0;return this===undefined}f().then(console.log);
            """);
        cases.put("control-completions", """
            async function f(n){try{for(let i=0;i<4;i++){try{await 0;if(i===1)continue;if(i===3)break;n+=i}finally{n+=10}}switch(n){case 42:return await Promise.resolve(n);default:throw n}}catch(e){return e+1}finally{if(n<0)return 99}}
            Promise.all([f(0),f(-100)]).then(x=>console.log(x.join(',')));
            """);
        cases.put("super-method-references", """
            const log=[];class A{get x(){log.push('get');return this.v}set x(v){log.push('set:'+v);this.v=v}m(v){return this.v+v}tag(s,v){return this.v+v}}
            class B extends A{async f(){const key={toString(){log.push('key');return 'x'}};super[key]+=await 2;super.x++;const a=await super.m(await 3);const b=await super.m?.(await 4);return [a,b,super.tag`v${5}`,super.x,(()=>super.m(1))()].join(',')}}
            const b=new B();b.v=1;b.f().then(v=>console.log(v,log.join(',')));
            """);
        cases.put("object-and-static-methods", """
            const base={m(){return this.x}};const o={__proto__:base,x:3,async ['f'](a=1){await 0;return super.m()+a}};
            class A{static m(){return this.name}}class B extends A{static async f(){return await super.m()}}
            Promise.all([o.f(),B.f()]).then(x=>console.log(x.join(',')));
            """);
        cases.put("nested-async-arrow-super", """
            class A{m(){return this.x}}class B extends A{constructor(){super();this.x=6}async f(){const a=async()=>{await 0;return super.m()+this.x};return await a()}}
            new B().f().then(console.log);
            """);
        cases.put("async-fields-and-rest", """
            class C{x=3;f=async({a,...rest})=>{await 0;return this.x+a+rest.b};async g({a,...r},n=a){return await this.f({a:n,...r})}}
            new C().g({a:2,b:4}).then(console.log);
            """);
        cases.put("generator-lazy-parameters", """
            const log=[];function parameter(){log.push('parameter');return 2}
            async function* f(a=parameter()){log.push('body');try{yield a;yield await 3;return 4}finally{log.push('finally')}}
            const it=f();log.push('created');Promise.all([it.next(),it.next(),it.next(),it.next()]).then(v=>console.log(JSON.stringify(v),log.join(',')));
            """);
        cases.put("generator-parameter-errors", """
            async function* f({x}){yield x}try{f(null);console.log('bad')}catch(e){console.log(e.name)}
            async function* g({a,...r}){yield a+r.b}const it=g({a:2,b:3});it.next().then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generator-throw-return-finally", """
            async function* f(){try{yield 1;yield 2}catch(e){yield 'caught:'+e}finally{yield 'cleanup';await 0}return 8}
            const it=f();Promise.all([it.next(),it.throw('x'),it.return(Promise.resolve(9)),it.next(),it.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generator-rejected-yield-return", """
            async function* f(){try{yield Promise.reject('yield')}catch(e){yield e}try{return Promise.reject('return')}catch(e){yield e}return 3}
            const it=f();Promise.all([it.next(),it.next(),it.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generator-rejected-request-return", """
            async function* f(){try{yield 1}catch(e){yield 'caught:'+e}finally{yield 'closed'}}
            const it=f();const rejected={then(a,b){b('return')}};Promise.all([it.next(),it.return(rejected),it.next(),it.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generator-before-start-and-completed", """
            const log=[];async function* f(){log.push('body');yield 1}
            const a=f(),b=f();Promise.all([a.return(Promise.resolve(3)),a.next(),a.throw(4).catch(e=>'e'+e),b.throw(5).catch(e=>'e'+e),b.return(6),b.next()]).then(v=>console.log(JSON.stringify(v),log.join(',')));
            """);
        cases.put("generator-nested-sync-arrow", """
            async function* f(){const a=()=>3;function* g(){yield 2}yield a();yield g().next().value}
            const it=f();Promise.all([it.next(),it.next(),it.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generator-delegate-sync", """
            async function* f(){const x=yield* [Promise.resolve(2),3];yield String(x);return 8}
            const it=f();Promise.all([it.next(),it.next(),it.next(),it.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generator-delegate-async", """
            async function* inner(){try{yield 1;yield 2;return 4}finally{console.log('inner-close')}}
            async function* f(){try{const n=yield* inner();yield n}finally{console.log('outer-close')}}
            const it=f();Promise.all([it.next(),it.return(9),it.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generator-delegate-throw", """
            async function* inner(){try{yield 1}catch(e){yield 'caught:'+e}return 3}
            async function* f(){yield yield* inner();return 5}
            const it=f();Promise.all([it.next(),it.throw(8),it.next(),it.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("delegate-missing-throw", """
            const log=[];const source={[Symbol.asyncIterator](){return this},next(){return Promise.resolve({value:1,done:false})},return(){log.push('close');return Promise.resolve({done:true})}};
            async function* f(){try{yield* source}catch(e){yield e.name}}
            const it=f();Promise.all([it.next(),it.throw(2),it.next()]).then(v=>console.log(JSON.stringify(v),log.join(',')));
            """);
        cases.put("for-await-sync-break", """
            const log=[];function* source(){try{yield Promise.resolve(2);yield 3}finally{log.push('close')}}
            async function f(){for await(const x of source()){log.push(x);break}log.push('after')}
            f().then(()=>console.log(log.join(',')));
            """);
        cases.put("for-await-async-values", """
            const log=[];const source={[Symbol.asyncIterator](){return this},next(){return Promise.resolve({done:false,value:Promise.resolve(3)})},return(){log.push('return');return {done:true}}};
            async function f(){for await(const x of source){log.push(x instanceof Promise);log.push(await x);break}}
            f().then(()=>console.log(log.join(',')));
            """);
        cases.put("for-await-close-precedence", """
            const log=[];function source(){return {[Symbol.asyncIterator](){return this},next(){return {value:1,done:false}},return(){log.push('close');return Promise.reject('cleanup')}}}
            async function f(mode){try{for await(const x of source()){if(mode)throw 'body';break}}catch(e){log.push(e)}}
            f(1).then(()=>f(0)).then(()=>console.log(log.join(',')));
            """);
        cases.put("for-await-next-error-no-close", """
            const log=[];const source={[Symbol.asyncIterator](){return this},next(){return Promise.reject('next')},return(){log.push('close');return {done:true}}};
            async function f(){try{for await(const x of source()){} }catch(e){log.push(e.name)}}
            async function g(){try{for await(const x of source){} }catch(e){log.push(e)}}
            g().then(()=>console.log(log.join(',')));
            """);
        cases.put("for-await-labels-bindings", """
            const log=[],closures=[];async function f(){outer:for await(let x of [1,2,3]){for await(const y of [1,2]){if(x===2)continue outer;closures.push(()=>x+':'+y);if(x===3)break outer}}let z;for await(z of [5,6])log.push(z);return closures.map(f=>f()).join(',')}
            f().then(x=>console.log(x,log.join(',')));
            """);
        cases.put("for-await-rest-binding-close", """
            const log=[];async function* source(){try{yield {a:2,b:3};yield null}finally{log.push('close')}}
            async function f(){try{for await(const {a,...r} of source()){log.push(a+r.b)}}catch(e){log.push(e.name)}}
            f().then(()=>console.log(log.join(',')));
            """);
        cases.put("for-await-tdz", """
            let x=[1];async function f(){try{for await(let x of x){}}catch(e){console.log(e.name)}}f();
            """);
        cases.put("for-await-return-cleanup", """
            const log=[];async function* source(){try{yield 1}finally{await 0;log.push('close')}}
            async function f(){for await(const n of source())return n}f().then(v=>console.log(v,log.join(',')));
            """);
        cases.put("generator-for-await", """
            async function* f(){for await(const n of [1,Promise.resolve(2)])yield n*2}
            async function g(){const result=[];for await(const n of f())result.push(n);console.log(result.join(','))}g();
            """);
        cases.put("async-no-symbol-polyfill", """
            async function f(){return await 3}f().then(console.log);
            """);

        cases.put("queue-stress", """
            async function* f(){await 0;return 2}const it=f(),jobs=[];for(let i=0;i<12000;i++)jobs.push(it.next());
            Promise.all(jobs).then(x=>console.log(x.length,x[0].value,x[11999].done));
            """);
        cases.put("es6-symbol-runtime", """
            const NativeSymbol=Symbol;function ES6Symbol(x){return NativeSymbol(x)}ES6Symbol.iterator=NativeSymbol.iterator;
            globalThis.Symbol=ES6Symbol;
            async function* f(){yield 1;yield* [2]}
            async function g(){const result=[];for await(const x of f())result.push(x);console.log(result.join(','),ES6Symbol.asyncIterator===undefined)}g();
            """);
        cases.put("generator-scheduling", """
            const log=[];async function* f(){log.push('start');yield 1;log.push('after');yield await 2;log.push('end');return 3}
            const it=f();it.next().then(v=>log.push('a:'+v.value));it.next().then(v=>log.push('b:'+v.value));it.return(9).then(v=>log.push('c:'+v.value));
            Promise.resolve().then(()=>log.push('m1')).then(()=>log.push('m2')).then(()=>log.push('m3')).then(()=>log.push('m4'));
            setTimeout(()=>console.log(log.join(',')),0);
            """);
        cases.put("delegate-scheduling", """
            const log=[];async function* f(){yield* [1,Promise.resolve(2)];log.push('done')}
            const it=f();it.next().then(v=>log.push('a:'+v.value));it.next().then(v=>log.push('b:'+v.value));it.next().then(v=>log.push('c:'+v.done));
            let p=Promise.resolve();for(let i=0;i<14;i++)p=p.then(()=>log.push('m'+i));setTimeout(()=>console.log(log.join(',')),0);
            """);
        cases.put("sync-rejected-value-cleanup", """
            const log=[];const source={[Symbol.iterator](){return this},next(){return {done:false,value:Promise.reject('value')}},return(){log.push('close');return {done:true}}};
            async function f(){try{for await(const x of source){log.push('body')}}catch(e){log.push(e)}}f().then(()=>console.log(log.join(',')));
            """);
        cases.put("invalid-iterator-results", """
            const log=[];function source(result,close){return {[Symbol.asyncIterator](){return this},next(){return result},return(){log.push('close');return close}}}
            async function f(value,abrupt){try{for await(const x of value){if(abrupt)throw 'body';break}}catch(e){log.push(e.name||e)}}
            f(source(1,{})).then(()=>f(source({done:false,value:1},void 0))).then(()=>f(source({done:false,value:1},null),true)).then(()=>console.log(log.join(',')));
            """);
        cases.put("iterator-getters", """
            const log=[];const source={get [Symbol.asyncIterator](){log.push('async');return null},get [Symbol.iterator](){log.push('sync');return function(){log.push('receiver:'+ (this===source));return this}},get next(){log.push('next');let i=0;return function(){log.push('call:'+ (this===source));return {get done(){log.push('done');return i++>0},get value(){log.push('value');return 3}}}}};
            async function f(){for await(const x of source)log.push(x)}f().then(()=>console.log(log.join(',')));
            """);
        cases.put("delegate-return-done-false", """
            const log=[];const source={[Symbol.asyncIterator](){return this},next(v){log.push('next:'+v);return {value:2,done:false}},return(v){log.push('return:'+v);return {value:3,done:false}},throw(v){log.push('throw:'+v);return {value:4,done:true}}};
            async function* f(){try{const v=yield* source;yield v}finally{log.push('finally')}}
            const it=f();Promise.all([it.next(),it.return(8),it.throw(9),it.next()]).then(v=>console.log(JSON.stringify(v),log.join(',')));
            """);
        cases.put("delegate-throw-missing-sync", """
            const log=[];const source={[Symbol.iterator](){return this},next(){return {value:1,done:false}},return(){log.push('close');return {value:2,done:true}}};
            async function* f(){try{yield* source}catch(e){yield e.name}}
            const it=f();Promise.all([it.next(),it.throw(8),it.next()]).then(v=>console.log(JSON.stringify(v),log.join(',')));
            """);
        cases.put("nested-arrow-this-and-arguments", """
            function start(n){return (async()=>{await 0;return await (async()=>{await 0;return [this.x,arguments[0],new.target===start].join(',')})()})()}
            start.call({x:5},7).then(console.log);
            """);
        cases.put("derived-arrow-before-super", """
            const log=[];class A{}class B extends A{constructor(){const f=async()=>{log.push('start');await 0;return this};const p=f();log.push('call');super();p.then(v=>console.log(v instanceof B,log.join(',')))}}new B();
            """);
        cases.put("computed-method-context", """
            async function f(){const o={[(await Promise.resolve('m'))](){return 4}};class C{[(await Promise.resolve('m'))](){return 5}}return o.m()+new C().m()}
            f().then(console.log);
            """);
        cases.put("super-destructuring-and-order", """
            const log=[];class A{set x(v){log.push('set:'+v);this.v=v}get x(){log.push('get');return this.v}}
            class B extends A{async f(){const key={toString(){log.push('key');return 'x'}};super[key]=await (log.push('rhs'),3);({x:super[key]}={x:4});return super[key]++}}
            const b=new B();b.f().then(v=>console.log(v,b.v,log.join(',')));
            """);

        cases.put("async-delegate-scheduling", """
            const log=[];let n=0;const source={[Symbol.asyncIterator](){return this},next(){return {value:++n,done:n>2}},return(v){return {value:v,done:true}}};
            async function* f(){yield* source;log.push('done')}
            const it=f();it.next().then(v=>log.push('a:'+v.value));it.next().then(v=>log.push('b:'+v.value));it.return(8).then(v=>log.push('c:'+v.value));
            let p=Promise.resolve();for(let i=0;i<14;i++)p=p.then(()=>log.push('m'+i));setTimeout(()=>console.log(log.join(',')),0);
            """);
        cases.put("delegate-async-promise-value", """
            const log=[];const source={[Symbol.asyncIterator](){return this},next(){return {value:Promise.resolve(3),done:false}},return(v){return {value:v,done:true}}};
            async function* f(){yield* source}const it=f();it.next().then(v=>log.push(v.value instanceof Promise));it.return(8).then(v=>log.push(v.value));
            setTimeout(()=>console.log(log.join(',')),0);
            """);
        cases.put("delegate-return-rejection-finally", """
            const log=[];const source={[Symbol.asyncIterator](){return this},next(){return {value:1,done:false}},return(v){log.push('return:'+v);const bad=Promise.reject('bad');bad.catch(()=>{});return {value:bad,done:true}}};
            async function* f(){try{yield* source}catch(e){yield e}finally{log.push('finally')}}const it=f();Promise.all([it.next(),it.return(8),it.next()]).then(v=>console.log(JSON.stringify(v),log.join(',')));
            """);
        cases.put("iterator-result-getter-errors", """
            const log=[];function source(field){return {[Symbol.asyncIterator](){return this},next(){return field==='done'?{get done(){throw 'done'}}:{done:false,get value(){throw 'value'}}},return(){log.push('close');return {done:true}}}}
            async function f(v){try{for await(const x of v){}}catch(e){log.push(e)}}f(source('done')).then(()=>f(source('value'))).then(()=>console.log(log.join(',')));
            """);
        cases.put("for-await-continue-cleanup", """
            const log=[];async function* source(){try{yield 1;yield 2}finally{log.push('close')}}async function f(){for await(const x of source()){log.push(x);continue}log.push('after')}
            f().then(()=>console.log(log.join(',')));
            """);
        cases.put("comments-directives-default-exports", """
            export default async function f(){/*before*/'use strict';const a=await 2;return a}f().then(console.log);
            const o={async /*a*/ * /*b*/ ['g'](){yield 3}};o.g().next().then(v=>console.log(v.value));
            """);
        cases.put("async-function-constructors", """
            async function f(){}async function* g(){}for(const c of [f,g])try{new c()}catch(e){console.log(e.name)}
            """);
        cases.put("super-default-parameter", """
            class A{get x(){return 3}}class B extends A{async f(x=super.x){return await x}async *g(x=super.x){yield x}}
            const b=new B();Promise.all([b.f(),b.g().next()]).then(v=>console.log(v[0],v[1].value));
            """);

        cases.put("for-await-head-closure-tdz", """
            let capture;async function f(){for await(let x of (capture=()=>x,[1])){}try{capture()}catch(e){console.log(e.name)}}f();
            """);
        cases.put("for-await-rhs-await", """
            async function f(){let result=0;for await(const x of await Promise.resolve([2,3]))result+=x;return result}f().then(console.log);
            """);
        cases.put("delegate-sync-missing-methods", """
            const log=[];function source(close){return {[Symbol.iterator](){return this},next(){return {value:1,done:false}},return:close}}
            async function* f(s){try{yield* s}catch(e){yield e.name}}
            const it=f(source(function(){log.push('close');return {get done(){log.push('done');return true},get value(){log.push('value');return 5}}}));
            Promise.all([it.next(),it.throw(7),it.next()]).then(v=>console.log(JSON.stringify(v),log.join(',')));
            const other=f(source(null));Promise.all([other.next(),other.return(8),other.next()]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("for-await-sync-close-value", """
            const log=[];const source={[Symbol.iterator](){return this},next(){return {value:1,done:false}},return(){log.push('close');return {done:true,value:{then(resolve){log.push('then');resolve(8)}}}}};
            async function f(){for await(const n of source)break;log.push('after')}f().then(()=>console.log(log.join(',')));
            """);

        cases.put("sync-return-rejection-single-close", """
            const log=[];const source={[Symbol.iterator](){return this},next(){return {value:1,done:false}},return(){log.push('close');return {done:false,value:Promise.reject('bad')}}};
            async function* f(){try{yield* source}catch(e){log.push(e)}}const it=f();it.next().then(()=>it.return(2)).then(()=>console.log(log.join(',')));
            """);
        cases.put("iterator-request-receiver", """
            async function* f(){yield 1}const it=f(),next=it.next,ret=it.return,thr=it.throw;
            Promise.all([next().catch(e=>e.name),ret.call({}).catch(e=>e.name),thr.call(null).catch(e=>e.name),next.call(it)]).then(v=>console.log(JSON.stringify(v)));
            """);
        cases.put("generated-name-collisions", """
            const $tl$run$4=8,$tl$super$0=5;async function f(){return await $tl$run$4+$tl$super$0}f().then(console.log);
            """);

        cases.put("arrow-unbound-arguments-typeof", """
            const f=async()=>{await 0;return [typeof arguments,typeof (arguments),await (async()=>typeof arguments)()].join(',')};f().then(console.log);
            """);

        cases.put("super-parameter-optional-call", """
            class A{m(){return this.x}}class B extends A{constructor(){super();this.x=7}async f(v=super.m?.()){return await v}async *g(v=super.m?.()){yield v}}
            const b=new B();Promise.all([b.f(),b.g().next()]).then(v=>console.log(v[0],v[1].value));
            """);
        cases.put("super-lexical-arrow-reentry", """
            let calls=0,fn;class A{get m(){const n=++calls;return x=>n}}class B extends A{async f(){fn=(reenter)=>super.m?.(reenter?fn(false):0);return await fn(true)}}
            new B().f().then(v=>console.log(v,calls));
            """);
        return cases.entrySet().stream().flatMap(e->Stream.of(JavaScriptTarget.ES6,JavaScriptTarget.ES2021).map(t->Arguments.of(e.getKey(),t,e.getValue())));
    }
    @ParameterizedTest(name="{0}-{1}") @MethodSource("semantics")
    void matchesNative(String name,JavaScriptTarget target,String source)throws Exception{
        var result=new DefaultJavaScriptEmitter().emit(name+".js",source,target);
        assertTrue(result.successful(),()->result.diagnostics()+"\n"+source);
        var runtime=new JavaScriptRuntime(temporary);runtime.syntax(target,result.javaScript());
        assertEquals(runtime.run(source),runtime.run(result.javaScript()),result.javaScript());
    }
    @Test void helpersAreDeterministicAndLocal(){
        var emitter=new DefaultJavaScriptEmitter();String source="async function a(){await 0}async function b(){await 1}async function* c(){yield 2}";
        var first=emitter.emit("helpers.js",source,JavaScriptTarget.ES6);assertTrue(first.successful(),first.diagnostics().toString());
        assertEquals(first,emitter.emit("helpers.js",source,JavaScriptTarget.ES6));
        new SyntaxTargetValidator().validate(JsProgram.parse("helpers.js",first.javaScript()),JavaScriptTarget.ES6);
        assertEquals(1,first.javaScript().split("function \\$tl\\$run\\$",-1).length-1);
    }
    @Test void dynamicScopeFailsExplicitly(){
        var result=new DefaultJavaScriptEmitter().emit("eval.js","async function f(){eval('x');await 1}",JavaScriptTarget.ES6);
        assertFalse(result.successful());assertEquals("",result.javaScript());assertEquals("JS_TARGET",result.diagnostics().get(0).code());
    }
}
