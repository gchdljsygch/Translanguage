package consumer;

import com.pingsu.transpiler.*;
import com.pingsu.transpiler.syntax.*;
import com.pingsu.transpiler.internal.parser.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Java 8 native consumer gate; no JUnit, test backend or main JAR on its classpath. */
public final class Java8Contracts {
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static void illegal(Runnable action) { try{action.run();throw new AssertionError("Expected IllegalStateException");}catch(IllegalStateException expected){} }
    private static final class Session extends AbstractCompilation {
        private final int mode;
        Session(int mode){super("session.ts","");this.mode=mode;}
        @Override protected TranspileResult compile() {
            if(mode==1)return execute();
            if(mode==2)throw new IllegalArgumentException("extension");
            if(mode==3){transition(State.CREATED,State.COMPLETED);}
            transition(State.CREATED,State.PARSED);
            if(mode==4)return new TranspileResult("partial",Arrays.asList(new Diagnostic("s",1,1,"CUSTOM","bad")));
            transition(State.PARSED,State.TRANSFORMED);return new TranspileResult("ok",Collections.<Diagnostic>emptyList());
        }
    }
    public static void main(String[] args) throws Exception {
        String expected=System.getProperty("java8.jar");
        if(expected!=null)require(Paths.get(TypeScriptTranspiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Paths.get(expected).toRealPath()),"Wrong library origin");
        require(Collections.list(TypeScriptTranspiler.class.getClassLoader().getResources("com/pingsu/transpiler/TypeScriptTranspiler.class")).size()==1,"Multiple library artifacts");
        StandaloneConsumer.main(new String[0]);
        for(int mode=0;mode<5;mode++) {
            Session session=new Session(mode);require(session.state()==AbstractCompilation.State.CREATED,"created");
            if(mode==1||mode==3)illegal(session::execute);
            else if(mode==2){try{session.execute();throw new AssertionError();}catch(IllegalArgumentException expectedFailure){}}
            else require(session.execute().javaScript().equals(mode==0?"ok":""),"output contract");
            require(session.state()==(mode==0?AbstractCompilation.State.COMPLETED:AbstractCompilation.State.FAILED),"terminal state");illegal(session::execute);
        }
        SourceLexer lexer=new SourceLexer("checkpoint.ts","/* c */ /r/g");
        TokenReader.Checkpoint mark=lexer.checkpoint();Token first=lexer.next(TokenReader.Goal.REGULAR_EXPRESSION);
        lexer.restore(mark);require(first.equals(lexer.next(TokenReader.Goal.REGULAR_EXPRESSION)),"checkpoint restore");
        try{new SourceLexer("other","").restore(mark);throw new AssertionError("foreign checkpoint");}catch(IllegalArgumentException expectedFailure){}
        for(JavaScriptTarget target:JavaScriptTarget.values()) {
            TypeScriptTranspiler compiler=new TypeScriptTranspiler(target);
            String source="import {Unused} from './missing.js'; enum E{A,B=3} class C{value=E.B;async m(){return await this.value}} const {value,...rest}=new C();";
            TranspileResult baseline=compiler.transpile("concurrent.ts",source);require(baseline.successful(),baseline.diagnostics().toString());
            require(!baseline.javaScript().contains("missing.js"),"unused import not erased");
            ExecutorService pool=Executors.newFixedThreadPool(4);
            try { List<Callable<TranspileResult>> jobs=new ArrayList<>();for(int i=0;i<48;i++)jobs.add(()->compiler.transpile("concurrent.ts",source));for(Future<TranspileResult> result:pool.invokeAll(jobs))require(baseline.equals(result.get()),"concurrent compiler state leaked"); }
            finally {pool.shutdownNow();}
            // Unicode 13 identifier introduced after the Unicode baseline of Java 8.
            require(compiler.transpile("unicode.ts","const \u1C90 = 1; const \uD801\uDC00 = \u1C90;").successful(),"pinned Unicode identifier");
            require(!compiler.transpile("surrogate.ts","const \uD800 = 1;").successful(),"unpaired surrogate accepted");
        }
        int count=0;
        if(args.length>0) {
            Path fixtures=Paths.get(args[0]);List<String> rows=Files.readAllLines(fixtures.resolve("cases.tsv"),StandardCharsets.UTF_8);
            for(String line:rows.subList(1,rows.size())) {if(line.isEmpty())continue;String[] row=line.split("\t",-1);
                for(int i=0;i<3;i++) {JavaScriptTarget target=JavaScriptTarget.valueOf(new String[]{"PRESERVE","ES6","ES2021"}[i]);
                    String input=new String(Files.readAllBytes(fixtures.resolve(row[0]+".ts")),StandardCharsets.UTF_8);
                    TranspileResult result=new TypeScriptTranspiler(target).transpile(row[0]+".ts",input);
                    require(result.successful()==row[i+1].equals("OK"),row[0]+"/"+target+": "+result.diagnostics());
                    if(!result.successful()){require(result.javaScript().isEmpty(),"partial output");final String code=row[i+1];require(result.diagnostics().stream().anyMatch(d->d.code().equals(code)),"wrong diagnostic");}
                    count++;
                }
            }
        }
        System.out.println("java8-contracts-ok jdk="+Support.jdk()+" fixedCases="+count);
    }
}
