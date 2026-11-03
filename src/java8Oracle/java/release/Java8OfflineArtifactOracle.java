package release;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Verify actual offline-guest outputs using the independent host test tools. */
public final class Java8OfflineArtifactOracle {
    public static void main(String[] args) throws Exception {
        Path log=Path.of(args[0]),jar=Path.of(args[1]),directory=Path.of(args[2]);Files.createDirectories(directory);
        String text=Files.readString(log).replace("\r\n","\n");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if(!text.contains("JAVA8_GUEST_SUCCESS\n")||text.contains("OFFLINE_FAILED")||!text.contains(digest+"  library.jar"))throw new AssertionError("Guest failed or used a different JAR");
        for(int jdk:List.of(8,17,25))if(!text.contains("offline-acceptance-ok jdk="+jdk))throw new AssertionError("Missing guest JDK "+jdk);
        Path verifier=directory.resolve("oracle.mjs");
        Files.writeString(verifier,"import {parse} from './acorn.mjs';import {readFileSync} from 'node:fs';parse(readFileSync(process.argv[2],'utf8'),{sourceType:'module',ecmaVersion:Number(process.argv[3])});");
        Set<String> found=new TreeSet<>();List<String> report=new ArrayList<>(List.of("jarSha256="+digest));
        for(String line:text.split("\n"))if(line.startsWith("ARTIFACT\t")) {
            String[] fields=line.split("\t",-1);if(fields.length!=4)throw new AssertionError("Invalid guest record");
            String id=fields[1]+"-"+fields[2];if(!found.add(id))throw new AssertionError("Duplicate output "+id);
            if(!Set.of("8","17","25").contains(fields[1])||!Set.of("PRESERVE","ES6","ES2021","EXTENSION").contains(fields[2]))throw new AssertionError(id);
            Path artifact=directory.resolve(id+".mjs");byte[] bytes=Base64.getDecoder().decode(fields[3]);Files.write(artifact,bytes);
            String year=fields[2].equals("PRESERVE")?"2022":fields[2].equals("ES2021")?"2021":"2015";
            execute(List.of("node",verifier.toString(),artifact.toString(),year),directory);
            String result=execute(List.of("node",artifact.toString()),directory);
            String expected=fields[2].equals("EXTENSION")?"7":"16 3 3\nclosed";
            if(!result.equals(expected))throw new AssertionError(id+": "+result);
            report.add(id+"\tacorn="+year+"\tstdout="+result.replace("\n","\\n")+"\tsha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        if(found.size()!=12)throw new AssertionError("Expected 12 guest artifacts: "+found);
        Files.write(directory.resolve("verification.txt"),report,StandardCharsets.UTF_8);
        System.out.println("offline-artifact-oracle-ok artifacts=12 jar="+digest);
    }
    private static String execute(List<String> command,Path directory) throws Exception {
        Path output=directory.resolve("process-output.txt");
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Oracle timeout");}
        String text=Files.readString(output).replace("\r\n","\n").strip();
        if(process.exitValue()!=0)throw new AssertionError(text);return text;
    }
}
