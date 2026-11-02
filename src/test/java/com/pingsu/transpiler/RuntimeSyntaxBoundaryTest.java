package com.pingsu.transpiler;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSyntaxBoundaryTest {
    @TempDir Path temporary;

    private void runs(JavaScriptTarget target, String source, String expected) throws Exception {
        var result = new TypeScriptTranspiler(target).transpile("boundary.ts", source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        var runtime = new JavaScriptRuntime(temporary);
        runtime.syntax(target == JavaScriptTarget.PRESERVE ? 2022 : target == JavaScriptTarget.ES6 ? 2015 : 2021, "module", result.javaScript());
        assertEquals(expected, runtime.run(result.javaScript()), result.javaScript());
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void branchesArrowsAndReturnsInitializeBeforeTheContainingExpression(JavaScriptTarget target) throws Exception {
        runs(target, """
                const log:string[]=[];
                class Base {
                    constructor(){log.push('base')}
                    set x(v:number){log.push('set:'+v)}
                }
                class Child extends Base {
                    literal=1;
                    field=(log.push('field'),2);
                    constructor(public x:number, mode:number) {
                        'custom directive';
                        if(mode===0){let x=90;super();log.push('branch:'+x)}
                        else if(mode===1){const run=(x:number)=>super();log.push(run(80)===this)}
                        else return (log.push('before'),super());
                        log.push('body');
                    }
                }
                new Child(7,0);new Child(8,1);const c=new Child(9,2);
                console.log(log.join(','),c.literal,c.field);
                """, "base,field,set:7,branch:90,body,base,field,set:8,true,body,before,base,field,set:9 1 2");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void simpleFieldsSupportLoopsSwitchShortCircuitAndArrowDefaults(JavaScriptTarget target) throws Exception {
        runs(target, """
                class B {}
                class C extends B {
                    value=3;
                    constructor(mode:number){
                        switch(mode){
                            case 0: for(let i=0;i<1;i++) super(); break;
                            case 1: true && super(); break;
                            case 2: false ? 0 : super(); break;
                            default: ((x=super())=>x)();
                        }
                    }
                }
                console.log([0,1,2,3].map(n=>new C(n).value).join(','));
                """, "3,3,3,3");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void throwingAndRepeatedSuperAndObjectReturnsRetainNativeBehavior(JavaScriptTarget target) throws Exception {
        runs(target, """
                const log=[];let fail=true;
                class B {constructor(){log.push('base');if(fail)throw Error('stop');return {fromBase:true}}}
                class C extends B {
                    field=(log.push('field'),1);
                    constructor(public value:number,mode:number){
                        if(mode===0)return {bypass:true};
                        try {super()} catch(e){log.push('caught');fail=false;super()}
                        if(mode===2){try{super()}catch(e){log.push(e instanceof ReferenceError)}}
                        log.push(this.value,this.fromBase);
                    }
                }
                console.log(new C(4,0).bypass);new C(5,1);new C(6,2);console.log(log.join(','));
                """, "true\nbase,caught,base,field,5,true,base,field,base,true,6,true");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void nestedClassesKeepSeparateSuperBindingsAndInitializeInParameterDefaults(JavaScriptTarget target) throws Exception {
        runs(target, """
                const log=[];const outer=10;
                class B {}
                class C extends B {
                    value=(log.push('outer'),outer);
                    constructor(public x:number){
                        class Nested extends B {value=(log.push('inner'),20);constructor(){return super()}}
                        log.push(new Nested().value);return super();
                    }
                }
                class D extends B {value=30;constructor(value=super()){log.push(value===this)}}
                class F extends B {value=outer;constructor(value=super()){log.push(value===this)}}
                const c=new C(7);console.log(c.value,c.x,new D().value,new F().value,log.join(','));
                """, "10 7 30 10 inner,20,outer,true,true");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void computedEnumsEvaluateOnceInOrderWithReferencesAndNumericReverseMappings(JavaScriptTarget target) throws Exception {
        runs(target, """
                let calls=0;const events=[];
                function next(v:number):number{events.push(v);calls++;return v}
                export enum E {
                    A=1<<2, B=A|1, C=E.B+2, Auto,
                    Dynamic=next(C+2), Later=next(Dynamic+1),
                    Text=('pre'+'fix'), TextAlias=Text,
                    DynamicText=(()=> 'runtime')(),
                    Typed=(next<number>(20) as number)!,
                    Choose= true ? next(21) : next(99)
                }
                console.log(E.A,E.B,E.C,E.Auto,E.Dynamic,E.Later,E[9],E[10],E.Text,E.TextAlias,
                    E.DynamicText,E.runtime,E.prefix,E.Typed,E.Choose,calls,events.join(','));
                """, "4 5 7 8 9 10 Dynamic Later prefix prefix runtime undefined undefined 20 21 4 9,10,20,21");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void enumReferencesRespectBindingsPropertyNamesAndLexicalContext(JavaScriptTarget target) throws Exception {
        runs(target, """
                const A=100;
                function build(seed:number){
                    enum E {
                        A=2,
                        Arrow=((A:number)=>A+1)(5),
                        Local=(()=>{const A=7;return A})(),
                        Hoist=(()=>{return typeof A;var A=8})(),
                        Default=((x=A)=>{var A=99;return x})(),
                        Object=({A,other:A}).A,
                        Key=({[A](A:number){return A}})[2](11),
                        Catch=(()=>{try{throw 12}catch(A){return A}})(),
                        AfterCatch=(()=>{try{}catch(A){}return A})(),
                        Loop=(()=>{for(let A=0;A<1;A++){}return A})(),
                        Context=(()=>this.factor+arguments[0]+A)(),
                        Nested=(()=>{enum Inner {Value=A+1} return Inner.Value})()
                    }
                    return E;
                }
                const e=build.call({factor:3},4);
                console.log(A,e.Arrow,e.Local,e.Hoist,e.Default,e.Object,e.Key,e.Catch,e.AfterCatch,e.Loop,e.Context,e.Nested);
                """, "100 6 7 undefined 2 2 11 12 2 2 9 3");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void enumEscapesMergedDeclarationsAndFailuresRemainSequential(JavaScriptTarget target) throws Exception {
        runs(target, """
                enum E {'\\x41'=1, B=A+1, '\\u{43}'=B+1, D=C+1}
                enum E {More=D+2, Last}
                let count=0;
                function stop(){throw Error('stop')}
                try {enum Broken {A=++count,B=stop(),C=++count}}catch(e){console.log(e.message,count)}
                const enum K {Value=((v:number):number=>v*2)(4)}
                console.log(E.A,E.B,E.C,E.D,E.More,E.Last,K.Value);
                """, "stop 1\n1 2 3 4 6 7 8");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void lexicalSuperInNestedClassKeysAndHeritageBelongsToTheOuterConstructor(JavaScriptTarget target) throws Exception {
        runs(target, """
                class B {}
                class Heritage extends B {
                    field=1;
                    constructor(public value:number){class Inner extends (super(),B) {};this.inner=new Inner()}
                }
                class Key extends B {
                    field=(1 //;
                    + 2);
                    constructor(public value:number){class Inner {[(super(),'method')](){return 4}};this.inner=new Inner()}
                }
                const h=new Heritage(5),k=new Key(6);
                console.log(h.field,h.value,h.inner instanceof B,k.field,k.value,k.inner.method());
                """, "1 5 true 3 6 4");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void computedEnumInitializersComposeWithClassesAndAvoidGeneratedNameCapture(JavaScriptTarget target) throws Exception {
        runs(target, """
                const $tl$enum$0=100;class Base {}
                enum E {
                    A=1,
                    ClassValue=new (class extends Base {
                        field=2;
                        constructor(public value:number){if(value)super();else super()}
                        sum(){return this.field+this.value+A}
                    })(4).sum(),
                    Capture=$tl$enum$0+A,
                    arguments=3,
                    Args=(function(){return arguments[0]})(9),
                    Static=(()=>{class C {static {var A=90}}return A})(),
                    Pattern=(({A=8}={}),A)
                }
                console.log(E.ClassValue,E.Capture,E.Args,E.Static,E.Pattern,E.A);
                """, "7 101 9 1 8 8");
    }

    @ParameterizedTest @EnumSource(JavaScriptTarget.class)
    void unsupportedBoundariesReturnDiagnosticsWithoutPartialOutput(JavaScriptTarget target) {
        for (String source : List.of("enum E {A=read(),B}", "enum E {A='x',B}",
                "enum E {A=eval('1')}", "enum E {A=await read()}", "class C extends B {constructor(public x=1,y=super()){}}")) {
            var result=new TypeScriptTranspiler(target).transpile("boundary.ts",source);
            assertFalse(result.successful(),source);
            assertEquals("",result.javaScript());
            assertEquals("TS_UNSUPPORTED",result.diagnostics().get(0).code());
            assertEquals("boundary.ts",result.diagnostics().get(0).sourceName());
        }
    }
}