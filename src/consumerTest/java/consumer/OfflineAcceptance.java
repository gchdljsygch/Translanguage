package consumer;

import com.pingsu.transpiler.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Runs only in the minimal no-NIC guest; artifacts are later verified by the host test oracle. */
public final class OfflineAcceptance {
    public static void main(String[] args) throws Exception {
        var interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()) {
            var network=interfaces.nextElement();
            if(!network.isLoopback()&&!network.getName().equals("lo"))throw new AssertionError("Unexpected NIC: "+network);
        }
        try(var socket=new Socket()) {
            try {socket.connect(new InetSocketAddress("192.0.2.1",9),1000);throw new AssertionError("Network reachable");}
            catch(java.io.IOException expected){System.out.println("network-unreachable="+expected.getClass().getSimpleName());}
        }
        for(String variable:List.of("CLASSPATH","NODE_PATH","NODE_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","GRADLE_USER_HOME"))
            if(System.getenv(variable)!=null)throw new AssertionError("Inherited environment: "+variable);
        for(String tool:List.of("node","nodejs","npm","gradle","mvn","maven"))for(String directory:System.getenv("PATH").split(":"))
            if(Files.exists(Path.of(directory,tool)))throw new AssertionError("Unexpected tool: "+tool);
        for(String cache:List.of(".gradle",".m2",".npm",".cache"))
            if(Files.exists(Path.of(System.getProperty("user.home"),cache)))throw new AssertionError("Unexpected cache: "+cache);
        String source="""
                enum E {A=2,B}
                class A{set value(v){throw 1}}
                class B extends A{value=E.B;constructor(public count:number){super()}}
                const b=new B(4);const {count,...rest}=b;
                async function* values(){try{yield await Promise.resolve(rest.value);yield* [9]}finally{console.log('closed')}}
                async function run(){let n=null;n??=count**2;for await(const v of values()){console.log(n,v,b?.value??0);break}}
                run();
                """;
        for(JavaScriptTarget target:JavaScriptTarget.values()) {
            var result=new TypeScriptTranspiler(target).transpile("隔离 消费.ts",source);
            if(!result.successful())throw new AssertionError(result.diagnostics());
            emit(target.name(),result.javaScript());
        }
        var extension=new TypeScriptTranspiler(JavaScriptTarget.ES6,List.of(c->c.append("\nasync function f(){return await 7}f().then(console.log);")),new DefaultJavaScriptEmitter());
        var result=extension.transpile("extension.ts","const x=1;");
        if(!result.successful())throw new AssertionError(result.diagnostics());
        emit("EXTENSION",result.javaScript());
        System.out.println("offline-acceptance-ok jdk="+Runtime.version().feature());
    }
    private static void emit(String target,String code) {
        System.out.println("ARTIFACT\t"+Runtime.version().feature()+"\t"+target+"\t"+Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8)));
    }
}
