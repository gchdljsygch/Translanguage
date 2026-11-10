package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DecoratorTransformTest {
    @TempDir Path directory;
    private String execute(String source, DecoratorMode mode, JavaScriptTarget target) throws Exception {
        TranspileResult result = new TypeScriptTranspiler(target, mode).transpile("decorators.ts", source);
        assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
        JavaScriptRuntime runtime = new JavaScriptRuntime(directory);
        if (target != JavaScriptTarget.PRESERVE) runtime.syntax(target, result.javaScript());
        return runtime.run(result.javaScript());
    }
    @Test void legacyClassMethodFieldAccessorAndParameterDecorators() throws Exception {
        String source = """
                const log: string[]=[];
                function dec(label:string) { log.push('eval:'+label); return function(target:any,key?:string,arg?:any) {
                  log.push('apply:'+label+':'+(key || 'class')+':'+(typeof arg));
                  if (label==='method') {const fn=arg.value;arg.value=function(x:number){return fn.call(this,x)+1;};return arg;}
                }; }
                @dec('class') class Box {
                  @dec('field') value:number=2;
                  constructor(@dec('ctor-param') public label:string) {}
                  @dec('method') sum(@dec('param') n:number):number {return this.value+n;}
                  @dec('getter') get total():number {return this.value;}
                  @dec('static') static make():number {return 3;}
                }
                console.log(JSON.stringify([new Box('x').sum(3), new Box('x').label, log]));
                """;
        String expected = "[6,\"x\",[\"eval:field\",\"apply:field:value:undefined\",\"eval:method\",\"eval:param\",\"apply:param:sum:number\",\"apply:method:sum:object\",\"eval:getter\",\"apply:getter:total:object\",\"eval:static\",\"apply:static:make:object\",\"eval:class\",\"eval:ctor-param\",\"apply:ctor-param:class:number\",\"apply:class:class:undefined\"]]";
        for (JavaScriptTarget target : JavaScriptTarget.values()) assertEquals(expected, execute(source, DecoratorMode.LEGACY, target));
    }
    @Test void legacyComputedNamesEvaluateOnceAndReplacementIsUsed() throws Exception {
        String source = """
                let count=0; const key={toString(){count++;return 'm';}};
                function double(target:any,key:string,d:any){const fn=d.value;d.value=function(){return fn.call(this)*2;};}
                function replace(C:any){return class extends C {extra=7;};}
                @replace class C { @double [key](){return 3;} }
                const c=new C(); console.log(JSON.stringify([count,c.m(),c.extra]));
                """;
        for (JavaScriptTarget target : JavaScriptTarget.values()) assertEquals("[1,6,7]", execute(source, DecoratorMode.LEGACY, target));
    }
    @Test void modernMethodsFieldsAndInitializerOrdering() throws Exception {
        String source = """
                const log:string[]=[];
                function dec(label:string){log.push('eval:'+label);return function(value:any,context:any){
                  log.push('apply:'+label+':'+context.kind);
                  context.addInitializer(function(){log.push('extra:'+label);});
                  if(context.kind==='field') return function(value:number){log.push('init:'+label);return value+1;};
                  if(context.kind==='method')return function(){return value.call(this)+2;};
                };}
                @dec('class') class Box {
                  @dec('method') read(){return this.value;}
                  @dec('field') value:number=(log.push('value'),3);
                  after:number=(log.push('after'),5);
                  @dec('static') static field:number=(log.push('static-value'),7);
                  constructor(){log.push('body');}
                }
                const box=new Box(); console.log(JSON.stringify([box.read(),Box.field,log]));
                """;
        String expected="[6,8,[\"eval:class\",\"eval:method\",\"eval:field\",\"eval:static\",\"apply:method:method\",\"apply:static:field\",\"apply:field:field\",\"apply:class:class\",\"static-value\",\"init:static\",\"extra:static\",\"extra:class\",\"extra:method\",\"value\",\"init:field\",\"extra:field\",\"after\",\"body\"]]";
        for (JavaScriptTarget target : JavaScriptTarget.values()) assertEquals(expected, execute(source, DecoratorMode.STANDARD, target));
    }
    @Test void modernDerivedClassAndRepeatedClassExpressionsHaveIsolatedQueues() throws Exception {
        String source = """
                const log=[]; function bind(value:any,c:any){c.addInitializer(function(){this.bound=this.m.bind(this);});}
                function field(value:any,c:any){c.addInitializer(function(){log.push(this.x);});return function(n:number){return n*2;};}
                class Base {constructor(){log.push('base');}}
                function make(){return class extends Base { @bind m(){return this.x;} @field x:number=4;
                  constructor(flag:boolean){if(flag) super();else super(); log.push('body');}
                };}
                const A=make(),B=make();const a=new A(true),b=new B(false);console.log(JSON.stringify([a.bound(),b.bound(),log]));
                """;
        for (JavaScriptTarget target : JavaScriptTarget.values()) assertEquals("[8,8,[\"base\",8,\"body\",\"base\",8,\"body\"]]", execute(source, DecoratorMode.STANDARD, target));
    }
    @Test void modernAccessContextsGetterSetterAndReturnValidation() throws Exception {
        String source="""
                let access:any, late:any;
                function getter(value:any,c:any){access=c.access;late=c.addInitializer;return function(){return value.call(this)+1;};}
                function setter(value:any,c:any){return function(n:number){value.call(this,n*2);};}
                class C{v=2; @getter get x(){return this.v;} @setter set x(n:number){this.v=n;}}
                const c=new C();c.x=4;let error=false;try{late(function(){});}catch(e){error=e instanceof TypeError;}
                console.log(JSON.stringify([c.x,access.get(c),access.has(c),error]));
                """;
        for(JavaScriptTarget target:JavaScriptTarget.values()) assertEquals("[9,9,true,true]",execute(source,DecoratorMode.STANDARD,target));
    }
    @Test void namedExportsAndImportsRetainDecoratorBindings() {
        for(String source:List.of("import {dec,Gone} from 'd'; @dec export class C {}", "import {dec,Gone} from 'd'; export @dec class C {}", "import {dec,Gone} from 'd'; export default @dec class C {} console.log(C);", "import {dec,Gone} from 'd'; @dec export default class C {} console.log(C);")) {
            TranspileResult result=new TypeScriptTranspiler().transpile("decorators.ts",source);
            assertTrue(result.successful(),()->result.diagnostics().toString()); assertFalse(result.javaScript().contains("Gone"));assertTrue(result.javaScript().contains("from 'd'"),result.javaScript());
        }
    }
    @Test void modernReplacementUpdatesClassSelfReferenceAndMetadata() throws Exception {
        String source = """
                Symbol.metadata = Symbol('metadata'); let original:any;
                function replace(C:any, context:any){original=C;context.metadata.label='ok';return class extends C {};}
                @replace class C {static ref=C;self(){return C;}}
                console.log(JSON.stringify([C===C.ref,new C().self()===C,original===C.ref,C[Symbol.metadata].label,C.name]));
                """;
        for(JavaScriptTarget target:JavaScriptTarget.values()) assertEquals("[true,true,false,\"ok\",\"\"]",execute(source,DecoratorMode.STANDARD,target));
    }
    @Test void legacyReplacementUpdatesClassSelfReferenceAndAnonymousNames() throws Exception {
        String source="function replace(C:any){return class extends C {}} @replace class C {self(){return C;}} console.log(new C().self()===C);";
        for(JavaScriptTarget target:JavaScriptTarget.values())assertEquals("true",execute(source,DecoratorMode.LEGACY,target));
        assertEquals("C C",execute("function dec(C:any,c:any){console.log(c.name);return C;} const C=@dec class{};console.log(C.name);",DecoratorMode.STANDARD,JavaScriptTarget.PRESERVE).replace('\n',' '));
    }
    @Test void parameterDecoratorUsesOuterBindingDespiteParameterShadow() {
        TranspileResult result = new TypeScriptTranspiler(JavaScriptTarget.PRESERVE,DecoratorMode.LEGACY)
                .transpile("test.ts","import {dec,Gone} from 'd'; class C {method(@dec dec:number){return dec;}}");
        assertTrue(result.successful(),()->result.diagnostics().toString());
        assertTrue(result.javaScript().contains("from 'd'"));assertFalse(result.javaScript().contains("Gone"));
    }
    @Test void decoratorFactoriesCanUseTypeArgumentsAndTypedCallbacks() throws Exception {
        String source="function factory<T>(f:(x:number)=>number){return function(C:any){C.answer=f(3);};} @factory<number>((x:number)=>x*2) class C{} console.log(C.answer);";
        for(DecoratorMode mode:DecoratorMode.values())assertEquals("6",execute(source,mode,JavaScriptTarget.PRESERVE));
    }
    @Test void reverseApplicationUsesBareCallsAndSharedInitializerWindow() throws Exception {
        String source="""
                'use strict'; const log=[]; let add:any;
                function top(value:any,c:any){log.push(this===undefined);add(function(){log.push('late');});return value;}
                function bottom(value:any,c:any){log.push(this===undefined);add=c.addInitializer;return value;}
                @top @bottom class C{} console.log(JSON.stringify(log));
                """;
        for(JavaScriptTarget target:JavaScriptTarget.values())assertEquals("[true,true,\"late\"]",execute(source,DecoratorMode.STANDARD,target));
        assertEquals("true",execute("'use strict';function dec(C:any){console.log(this===undefined);return C;} @dec class C{}",DecoratorMode.LEGACY,JavaScriptTarget.PRESERVE));
    }
    @Test void concurrentCompilationsKeepDecoratorAndJsxStateIsolated() throws Exception {
        TypeScriptTranspiler compiler=new TypeScriptTranspiler(JavaScriptTarget.ES6);
        String source="function dec(C:any){return C;} @dec class C{ render(){return <section/>;} }";
        TranspileResult baseline=compiler.transpile("parallel.tsx",source);assertTrue(baseline.successful(),()->baseline.diagnostics().toString());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs=new java.util.ArrayList<java.util.concurrent.Callable<TranspileResult>>();
            for(int i=0;i<24;i++)jobs.add(()->compiler.transpile("parallel.tsx",source));
            for(var result:pool.invokeAll(jobs))assertEquals(baseline,result.get());
        } finally {pool.shutdownNow();}
    }
    @Test void invalidPositionsAndModernParametersAreDiagnostics() {
        for(String source:List.of("@dec const x=1;", "@dec export const x=1;", "function f(@dec x:number){}", "class C { m(@dec x:number){} }", "class C { @dec constructor(){} }", "class C { @dec accessor x=1; }", "function dec(v:any){return v;} @dec class C{} eval('C');")) {
            TranspileResult result=new TypeScriptTranspiler().transpile("bad.ts",source);
            assertFalse(result.successful(),source); assertEquals("",result.javaScript());
        }
    }
}
