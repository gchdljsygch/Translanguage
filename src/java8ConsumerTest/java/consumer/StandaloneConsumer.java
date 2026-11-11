package consumer;

import com.pingsu.transpiler.*;
import com.pingsu.transpiler.syntax.*;
import java.util.List;

/** Compiled with only the main JAR; this is test infrastructure, never a library entry point. */
public final class StandaloneConsumer {
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] arguments) {
        SourceRange range = new SourceRange(0, 3);
        require(range.length() == 3, "range API");
        com.pingsu.transpiler.syntax.Token token = new Token(TokenKind.IDENTIFIER, range, "abc", 1, 1);
        require(!token.trivia(), "token API");
        com.pingsu.transpiler.syntax.Program program = new Program(range, java.util.Arrays.asList(new BranchNode("identifier", range, java.util.Arrays.asList())),
                java.util.Arrays.asList(token, new Token(TokenKind.EOF, SourceRange.at(3), "", 1, 4)));
        int[] visited = {0}; program.walk(node -> { visited[0]++; return true; });
        require(visited[0] == 2, "visitor API");
        if (arguments.length > 0 && arguments[0].equals("--api-only")) {
            System.out.println("extension-api-ok"); return;
        }
        boolean regularOnly = arguments.length > 0 && arguments[0].equals("--regular-only");
        boolean preserveOnly = arguments.length > 0 && arguments[0].equals("--preserve-only");
        for (JavaScriptTarget target : preserveOnly ? new JavaScriptTarget[]{JavaScriptTarget.PRESERVE} : JavaScriptTarget.values()) {
            com.pingsu.transpiler.TypeScriptTranspiler compiler = new TypeScriptTranspiler(target);
            String source = "const value: number = ({answer: 42})?.answer ?? 0; console.log(value);";
            com.pingsu.transpiler.TranspileResult result = compiler.transpile("consumer.ts", source);
            require(result.successful(), result.diagnostics().toString());
            require(!result.javaScript().isEmpty(), "missing output");
            require(compiler.transpile("empty.ts", "").successful(), "empty input");
            com.pingsu.transpiler.TranspileResult failure = compiler.transpile("bad.ts", "const value = ;");
            require(!failure.successful() && failure.javaScript().isEmpty(), "failure contract");
            require(result.equals(compiler.transpile("consumer.ts", source)), "session isolation");
            if(target!=JavaScriptTarget.PRESERVE) {
                com.pingsu.transpiler.TranspileResult fields=compiler.transpile("fields.ts","const key=Symbol(); class B {static get title(){return this.name}} class C extends B { [key]=2;static titleCopy=super.title;constructor(public value:number){super();} } const c=new C(3);");
                require(fields.successful(),fields.diagnostics().toString());
                require(fields.javaScript().contains("defineProperty"),"DefineField lowering");
                com.pingsu.transpiler.TranspileResult regular=compiler.transpile("regular.ts","function f({a,...rest},n=2){try{let x=null;x??=n**2;return {...rest,x,a}}catch{return null}} const y=f({a:1,b:2})?.x??0;");
                require(regular.successful(),regular.diagnostics().toString());
                require(!compiler.transpile("reject.ts","console.log(await value)").successful(),"top level await rejection");
                if(target==JavaScriptTarget.ES2021) require(compiler.transpile("async.ts","async function* f(){yield await Promise.resolve(2)} async function g(){for await(const n of f()){console.log(n)}}").successful(),"native async is self-contained");
            }
            if(!regularOnly&&target==JavaScriptTarget.ES6) {
                com.pingsu.transpiler.TranspileResult asynchronous=compiler.transpile("p6.ts","async function f(){return await Promise.resolve(2)} f().then(console.log);");
                require(asynchronous.successful(),asynchronous.diagnostics().toString());
                for(String code:java.util.Arrays.asList(
                        "async function* f(){try{yield await 2;yield* [3]}finally{await 0}} async function g(){for await(const n of f()){if(n)break}}",
                        "class A{m(){return 1}} class B extends A{async m(){const f=async()=>await super.m();return await f()}}",
                        "async function f({a,...r}){return await r.x} async function* g(x=1){yield x} const it=g();it.next();it.return(2);")) {
                    com.pingsu.transpiler.TranspileResult output=compiler.transpile("async-consumer.ts",code);
                    require(output.successful(),output.diagnostics().toString());
                }
                com.pingsu.transpiler.TypeScriptTranspiler extended=new TypeScriptTranspiler(target,java.util.Arrays.asList(context -> context.append(
                        "\nasync function* appended(){yield await 1} async function consume(){for await(const x of appended())break}")),new DefaultJavaScriptEmitter());
                com.pingsu.transpiler.TranspileResult extensionResult=extended.transpile("async-extension.ts", "const x=1;");
                require(extensionResult.successful(),extensionResult.diagnostics().toString());

            }
            if(target==JavaScriptTarget.ES5) {
                require(compiler.transpile("es5.ts","const fs=[];for(let i=0;i<3;i++)fs.push(()=>i);class Box{value=4;}console.log(fs[0](),new Box().value);").successful(),"ES5 synchronous lowering");
                require(!compiler.transpile("module.ts","export const value=1;").successful(),"ES5 modules must be diagnosed");
                for(String code:new String[]{
                        "async function f(){return await 1;} function* g(){try{yield* [1,2];}finally{yield 3;}}",
                        "function tag(parts){return parts.raw[0];}tag`raw`;class C{static{this.n=2;}accessor value=3;accessor #private=4;}const big=9007199254740993n**2n;",
                        "import x=require('business');declare global{interface I{value:number;}}export as namespace Library;export=x;",
                        "function f(){using x={[Symbol.dispose](){}};return 1;}async function g(){await using x=null;return await 2;}"}) {
                    TranspileResult output=compiler.transpile("es5-extension.ts",code);
                    require(output.successful(),output.diagnostics().toString());
                }
            }
            System.out.println("target=" + target + " ok");
        }
        com.pingsu.transpiler.TypeScriptTranspiler preserve = new TypeScriptTranspiler();
        com.pingsu.transpiler.TranspileResult features = preserve.transpile("中文.ts", "export interface I {} enum E {A, B=3} class C { constructor(public x:number) {} } const c = new C(E.B);");
        require(features.successful(), features.diagnostics().toString());
        require(features.javaScript().contains("this.x = x;") && features.javaScript().contains("export {}"), "P3 transforms");
        TransformationPass extension = context -> {
            require(context.program().range().end() == context.source().length(), "program extent");
            context.program().walk(node -> {
                if (node.kind().equals("typeAnnotation")) { context.remove(node); return false; }
                return true;
            });
            Token value = context.tokens().stream().filter(t -> t.text().equals("1")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
            context.replace(value, "2");
            context.append("\n// extension");
        };
        com.pingsu.transpiler.TypeScriptTranspiler custom = new TypeScriptTranspiler(JavaScriptTarget.PRESERVE, java.util.Arrays.asList(extension),
                (name, js, target) -> new TranspileResult(js + "\n// emitter", java.util.Arrays.asList()));
        require(custom.transpile("extension.ts", "const value: number = 1;").javaScript()
                .equals("const value = 2;\n// extension\n// emitter"), "extension output");
        System.out.println("standalone-consumer-ok");
    }
}
