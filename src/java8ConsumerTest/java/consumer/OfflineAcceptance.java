package consumer;

import com.pingsu.transpiler.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Runs only in the minimal no-NIC guest; artifacts are later verified by the host test oracle. */
public final class OfflineAcceptance {
    public static void main(String[] args) throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()) {
            java.net.NetworkInterface network=interfaces.nextElement();
            if(!network.isLoopback()&&!network.getName().equals("lo"))throw new AssertionError("Unexpected NIC: "+network);
        }
        try(java.net.Socket socket=new Socket()) {
            try {socket.connect(new InetSocketAddress("192.0.2.1",9),1000);throw new AssertionError("Network reachable");}
            catch(java.io.IOException expected){System.out.println("network-unreachable="+expected.getClass().getSimpleName());}
        }
        for(String variable:java.util.Arrays.asList("CLASSPATH","NODE_PATH","NODE_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","GRADLE_USER_HOME"))
            if(System.getenv(variable)!=null)throw new AssertionError("Inherited environment: "+variable);
        for(String tool:java.util.Arrays.asList("node","nodejs","npm","gradle","mvn","maven"))for(String directory:System.getenv("PATH").split(":"))
            if(Files.exists(Paths.get(directory,tool)))throw new AssertionError("Unexpected tool: "+tool);
        for(String cache:java.util.Arrays.asList(".gradle",".m2",".npm",".cache"))
            if(Files.exists(Paths.get(System.getProperty("user.home"),cache)))throw new AssertionError("Unexpected cache: "+cache);
        String source="enum E {A=2,B}\n" +
            "class A{set value(v){throw 1}}\n" +
            "class B extends A{value=E.B;constructor(public count:number){super()}}\n" +
            "const b=new B(4);const {count,...rest}=b;\n" +
            "async function* values(){try{yield await Promise.resolve(rest.value);yield* [9]}finally{console.log('closed')}}\n" +
            "async function run(){let n=null;n??=count**2;for await(const v of values()){console.log(n,v,b?.value??0);break}}\n" +
            "run();\n" +
            "";
        for(JavaScriptTarget target:new JavaScriptTarget[]{JavaScriptTarget.PRESERVE, JavaScriptTarget.ES6, JavaScriptTarget.ES2021}) {
            com.pingsu.transpiler.TranspileResult result=new TypeScriptTranspiler(target).transpile("隔离 消费.ts",source);
            if(!result.successful())throw new AssertionError(result.diagnostics());
            emit(target.name(),result.javaScript());
        }
        com.pingsu.transpiler.TypeScriptTranspiler extension=new TypeScriptTranspiler(JavaScriptTarget.ES6,java.util.Arrays.asList(c->c.append("\nasync function f(){return await 7}f().then(console.log);")),new DefaultJavaScriptEmitter());
        com.pingsu.transpiler.TranspileResult result=extension.transpile("extension.ts","const x=1;");
        if(!result.successful())throw new AssertionError(result.diagnostics());
        emit("EXTENSION",result.javaScript());
        System.out.println("offline-acceptance-ok jdk="+consumer.Support.jdk());
    }
    private static void emit(String target,String code) {
        System.out.println("ARTIFACT\t"+consumer.Support.jdk()+"\t"+target+"\t"+Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8)));
    }
}
