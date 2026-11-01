package com.pingsu.transpiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Independent Acorn grammar and Node execution oracle shared by backend regressions. */
final class JavaScriptRuntime {
    private final Path directory;
    JavaScriptRuntime(Path directory) { this.directory = directory; }
    void syntax(JavaScriptTarget target, String source) throws Exception {
        syntax(target == JavaScriptTarget.ES6 ? 2015 : 2021, "module", source);
    }
    void syntax(int version, String mode, String source) throws Exception {
        Path acorn = directory.resolve("acorn.mjs");
        if (!Files.exists(acorn)) try (var input = getClass().getResourceAsStream("/META-INF/resources/webjars/acorn/8.15.0/dist/acorn.mjs")) {
            assertNotNull(input); Files.copy(input, acorn);
        }
        Path script = directory.resolve("syntax.mjs"), artifact = directory.resolve("artifact.mjs");
        Files.writeString(artifact, source, StandardCharsets.UTF_8);
        Files.writeString(script, "import {parse} from './acorn.mjs';import {readFileSync} from 'node:fs';parse(readFileSync(process.argv[2],'utf8'),{sourceType:process.argv[4],ecmaVersion:Number(process.argv[3])});", StandardCharsets.UTF_8);
        execute(List.of("node",script.toString(),artifact.toString(),Integer.toString(version),mode));
    }
    String run(String source) throws Exception {
        return run(source, "module");
    }
    String run(String source, String mode) throws Exception {
        Path script = directory.resolve(mode.equals("module") ? "runtime.mjs" : "runtime.cjs"); Files.writeString(script,source,StandardCharsets.UTF_8);
        return execute(List.of("node",script.toString()));
    }
    private String execute(List<String> command) throws Exception {
        Path stdout=directory.resolve("stdout.txt"),stderr=directory.resolve("stderr.txt");
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        boolean finished = process.waitFor(20,TimeUnit.SECONDS); if(!finished)process.destroyForcibly();
        assertTrue(finished,"JavaScript oracle timed out");
        assertEquals(0,process.exitValue(),()->read(stderr));
        return read(stdout).replace("\r\n","\n").strip();
    }
    private String read(Path path) { try{return Files.readString(path,StandardCharsets.UTF_8);}catch(Exception error){return error.toString();} }
}
