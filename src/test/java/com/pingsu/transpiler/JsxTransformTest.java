package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsxTransformTest {
    @TempDir Path directory;
    private final String runtime = "const React = {Fragment:'fragment', createElement:function(tag, props) {return {tag:tag,props:props,children:Array.prototype.slice.call(arguments,2)}}};";
    @Test void elementsFragmentsTypesEntitiesAndSpreadExecuteForEveryTarget() throws Exception {
        String source = runtime + "function Box(p: any) {} const extra={id:'x'}; const value: number=2; "
                + "const node = <><div enabled {...extra} data-x={'a'} title=\"A &amp; B\">Hello &lt;world&gt;{value}<Box<number> value={value}/></div></>;"
                + "console.log(JSON.stringify(node));";
        for (JavaScriptTarget target : JavaScriptTarget.values()) {
            TranspileResult result = new TypeScriptTranspiler(target).transpile("view.tsx", source);
            assertTrue(result.successful(), () -> target + ": " + result.diagnostics());
            JavaScriptRuntime js = new JavaScriptRuntime(directory);
            if (target != JavaScriptTarget.PRESERVE) js.syntax(target, result.javaScript());
            assertEquals("{\"tag\":\"fragment\",\"props\":null,\"children\":[{\"tag\":\"div\",\"props\":{\"enabled\":true,\"id\":\"x\",\"data-x\":\"a\",\"title\":\"A & B\"},\"children\":[\"Hello <world>\",2,{\"props\":{\"value\":2},\"children\":[]}]}]}", js.run(result.javaScript()));
        }
    }
    @Test void lexicalModesPreserveTextAndNestedTemplates() throws Exception {
        String source = runtime + "const node=<p path=\"C:\\new\\dir\">\n  hello // text\n  don't /* comment */ &copy;\n  {BACKTICKaDOLLAR{<i>{/x/.test('x') ? <b/> : <em/>}</i>}zBACKTICK}\n</p>;console.log(JSON.stringify(node));".replace("BACKTICK", "\u0060").replace("DOLLAR", "$");
        TranspileResult result = new TypeScriptTranspiler().transpile("view.jsx", source);
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(new JavaScriptRuntime(directory).run(result.javaScript()).contains("hello // text don't /* comment */ ©"));
    }
    @Test void importsIncludeComponentsFactoriesAndExpressionReferences() {
        TranspileResult result = new TypeScriptTranspiler().transpile("view.tsx", "import React, {Widget, Unused, T, ui} from 'ui'; const node=<><Widget<T>/><ui.Button/></>; ");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.javaScript().contains("React")); assertTrue(result.javaScript().contains("Widget"));
        assertFalse(result.javaScript().contains("Unused")); assertFalse(result.javaScript().contains(" T"));
        assertTrue(result.javaScript().contains("ui"));
    }
    @Test void customFactoryAndFragment() {
        TranspileResult result = new TypeScriptTranspiler(List.of(new JsxLoweringPass("h", "Fragment"), new TypeErasurePass(), new EnumLoweringPass()))
                .transpile("view.tsx", "import {h, Fragment, Gone} from 'ui'; const n=<><x/></>;");
        assertTrue(result.successful(), () -> result.diagnostics().toString());
        assertTrue(result.javaScript().contains("h(Fragment, null, h(\"x\", null))"));
        assertFalse(result.javaScript().contains("Gone"));
    }
    @Test void nestedEnumsDecoratorsSpreadChildrenAndUnicodeComponents() throws Exception {
        String source=runtime+"function dec(C:any){C.n=4;} const π='unicode'; enum E { A=(<π>{...[1,2]}</π>).children.length, B=(()=>{ @dec class C{} return C.n;})() } console.log(E.A,E.B);";
        for(JavaScriptTarget target:JavaScriptTarget.values()) {
            TranspileResult result=new TypeScriptTranspiler(target).transpile("test.tsx",source);
            assertTrue(result.successful(),()->result.diagnostics().toString());
            assertEquals("2 4",new JavaScriptRuntime(directory).run(result.javaScript()));
        }
    }
    @Test void jsxInsideDecoratedMethodsAndDecoratorArguments() throws Exception {
        String source=runtime+"function make(element:any){return function(C:any){C.element=element;};} @make(<div>{(2 as number)}</div>) class C { render(){return <span>{3}</span>;} } console.log(C.element.children[0],new C().render().children[0]);";
        TranspileResult result=new TypeScriptTranspiler().transpile("test.tsx",source);
        assertTrue(result.successful(),()->result.diagnostics().toString());
        assertEquals("2 3",new JavaScriptRuntime(directory).run(result.javaScript()));
    }
    @Test void configuredFactoryAlsoAppliesInsideDecoratorExpressions() throws Exception {
        String source="function h(tag,props){return tag;}function make(tag){return function(C){C.tag=tag;};}@make(<widget/>) class C{} console.log(C.tag);";
        var compiler=new TypeScriptTranspiler(List.of(new JsxLoweringPass("h","Fragment"),new TypeErasurePass(),new EnumLoweringPass(),new DecoratorLoweringPass()));
        var result=compiler.transpile("factory.tsx",source);
        assertTrue(result.successful(),()->result.diagnostics().toString());
        assertEquals("widget",new JavaScriptRuntime(directory).run(result.javaScript()));
    }
    @Test void originalTokenCoverageAndDiagnosticsSurviveJsxLexicalGoals() {
        String source="// 😀\nconst n=<ui.Card data-123=\"a\\b\" title=\"first\nsecond\">can't // &amp; {/* hi */}{/x/.test('x')}</ui.Card>;";
        var parser=new com.pingsu.transpiler.internal.parser.RecursiveDescentParser();
        var result=parser.parse("coverage.tsx",source,com.pingsu.transpiler.internal.parser.SourceParser.Language.TYPESCRIPT);
        assertTrue(result.successful(),()->result.diagnostics().toString());
        assertEquals(source,result.program().tokens().stream().map(com.pingsu.transpiler.syntax.Token::text).collect(java.util.stream.Collectors.joining()));
        var broken=new TypeScriptTranspiler().transpile("broken.tsx","// 😀\nconst n=<div>\n</span>;");
        assertFalse(broken.successful());assertEquals(3,broken.diagnostics().get(0).line());assertEquals("",broken.javaScript());
        var deep=new TypeScriptTranspiler().transpile("deep.tsx","const n="+"<x>".repeat(140)+"</x>".repeat(140)+";");
        assertFalse(deep.successful());assertEquals("",deep.javaScript());
    }
    @Test void preservesAttributeAndChildEvaluationOrderAndCommentOnlyContainers() throws Exception {
        String source=runtime+"const log=[];function step(n){log.push(n);return n;}const n=<x a={step(1)} {...{b:step(2)}} a={step(3)}>{/*none*/}{step(4)}{... [step(5)]}</x>;console.log(JSON.stringify([n.props,n.children,log]));";
        for(JavaScriptTarget target:JavaScriptTarget.values()) {
            var result=new TypeScriptTranspiler(target).transpile("order.jsx",source);
            assertTrue(result.successful(),()->result.diagnostics().toString());
            assertEquals("[{\"a\":3,\"b\":2},[4,5],[1,2,3,4,5]]",new JavaScriptRuntime(directory).run(result.javaScript()));
        }
    }
    @Test void disambiguatesGenericsAndRejectsMalformedJsx() {
        assertTrue(new TypeScriptTranspiler().transpile("view.tsx", "const id=<T,>(v:T):T=>v; const n=<span>{id<number>(2)}</span>;").successful());
        assertTrue(new TypeScriptTranspiler().transpile("view.ts", "const n=<number>2;").successful());
        for (String source : List.of("const n=<div></span>;", "const n=<div>;", "const n=<x a={}/>;", "const n=<x>{foo bar}</x>;", "const n=<number>2;", "const n=<T>(x:T)=>x;", "const n=<a.b-c/>;")) {
            TranspileResult result = new TypeScriptTranspiler().transpile("view.tsx", source);
            assertFalse(result.successful(), source); assertEquals("", result.javaScript());
        }
    }
}
