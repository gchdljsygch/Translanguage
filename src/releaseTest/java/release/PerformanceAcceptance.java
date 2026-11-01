package release;

import com.pingsu.transpiler.*;
import com.pingsu.transpiler.internal.parser.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Development-only, fixed-workload performance gate; never packaged with the library. */
public final class PerformanceAcceptance {
    private static final int[] SIZES={1,32,128};
    private static final long[] LIMIT_MS={5000,10000,30000};
    private static final int WARMUP=2, SAMPLES=3;
    private static String source(int count) {
        StringBuilder text=new StringBuilder();
        for(int i=0;i<count;i++) text.append("function f").append(i).append("(input:{a?:number}){const {a,...rest}=input;let n=a??2;n**=2;return {...rest,n}}\n")
                .append("class C").append(i).append("{value=1;static k=2;async m(x:number=1){try{return await Promise.resolve(x+this.value)}finally{await 0}}}\n");
        return text.toString();
    }
    private static String run(String phase,JavaScriptTarget target,String ts,String js) {
        if(phase.equals("frontend")) {
            var result=new RecursiveDescentParser().parse("performance.ts",ts,SourceParser.Language.TYPESCRIPT);
            if(!result.successful())throw new AssertionError(result.diagnostics());return ts;
        }
        var result=phase.equals("backend") ? new DefaultJavaScriptEmitter().emit("performance.js",js,target)
                :new TypeScriptTranspiler(target).transpile("performance.ts",ts);
        if(!result.successful())throw new AssertionError(result.diagnostics());return result.javaScript();
    }
    public static void main(String[] args) throws Exception {
        Path report=Path.of(args[0]);Files.createDirectories(report.getParent());
        List<String> lines=new ArrayList<>(List.of("jdk\tphase\ttarget\tunits\tinputUtf8\tinputSha256\toutputUtf8\tmedianMs\tmaxMs\tbudgetMs\tpeakHeapBytes"));
        for(int size=0;size<SIZES.length;size++) {
            String ts=source(SIZES[size]);var erased=new TypeScriptTranspiler().transpile("performance.ts",ts);
            if(!erased.successful())throw new AssertionError(erased.diagnostics());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ts.getBytes(StandardCharsets.UTF_8)));
            for(String phase:List.of("frontend","backend","pipeline")) for(JavaScriptTarget target:JavaScriptTarget.values()) {
                if(phase.equals("frontend")&&target!=JavaScriptTarget.PRESERVE)continue;
                for(int i=0;i<WARMUP;i++)run(phase,target,ts,erased.javaScript());
                ManagementFactory.getMemoryPoolMXBeans().forEach(pool->pool.resetPeakUsage());
                long[] times=new long[SAMPLES];String output="";
                for(int i=0;i<SAMPLES;i++) {long begin=System.nanoTime();output=run(phase,target,ts,erased.javaScript());times[i]=(System.nanoTime()-begin)/1_000_000;}
                Arrays.sort(times);
                long peak=ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();
                int bytes=output.getBytes(StandardCharsets.UTF_8).length;
                lines.add(String.join("\t",System.getProperty("java.version"),phase,target.name(),""+SIZES[size],""+ts.getBytes(StandardCharsets.UTF_8).length,hash,""+bytes,""+times[1],""+times[2],""+LIMIT_MS[size],""+peak));
                Files.write(report,lines,StandardCharsets.UTF_8);
                if(times[2]>LIMIT_MS[size])throw new AssertionError("Latency budget: "+lines.get(lines.size()-1));
                if(bytes>ts.length()*40L+32768)throw new AssertionError("Output growth budget");
            }
        }
        // Adversarial inputs must terminate with an ordinary diagnostic and no partial output.
        for(String input:List.of("const x="+"(".repeat(20000)+"0"+")".repeat(20000)+";","type X="+"Array<".repeat(20000)+"number"+">".repeat(20000)+";")) {
            long begin=System.nanoTime();var result=new TypeScriptTranspiler().transpile("deep.ts",input);
            if(result.successful()||!result.javaScript().isEmpty()||(System.nanoTime()-begin)>5_000_000_000L)throw new AssertionError("Unbounded deep input");
        }
        System.out.println("performance-acceptance-ok "+report);
    }
}
