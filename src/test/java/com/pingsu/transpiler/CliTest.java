package com.pingsu.transpiler;

import com.pingsu.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path temporary;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private int run(String source, String... arguments) {
        return Main.run(arguments, new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(errors, true, StandardCharsets.UTF_8));
    }
    @Test void stdinWritesOnlyJavaScript() {
        assertEquals(0, run("const 文本: string = '你好😀';", "-"));
        assertEquals("const 文本 = '你好😀';", output.toString(StandardCharsets.UTF_8));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }
    @Test void writesFileAndCreatesParents() throws Exception {
        Path target = temporary.resolve("nested/out.js");
        assertEquals(0, run("\uFEFFconst x: number = 2;", "-", "-o", target.toString()));
        assertEquals("const x = 2;", Files.readString(target));
        assertEquals(0, output.size());
    }
    @Test void preservesExistingOutputOnCompilationFailure() throws Exception {
        Path target = temporary.resolve("out.js");
        Files.writeString(target, "original");
        assertEquals(1, run("const broken: = ;", "-", "-o", target.toString()));
        assertEquals("original", Files.readString(target));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("<stdin>:1:"));
        assertEquals(0, output.size());
    }
    @Test void refusesToOverwriteInput() throws Exception {
        Path source = temporary.resolve("input.ts");
        Files.writeString(source, "const x: number = 2;");
        assertEquals(2, run("", source.toString(), "-o", source.toString()));
        assertEquals("const x: number = 2;", Files.readString(source));
    }
    @Test void usageAndIoFailuresHaveDistinctStatus() {
        assertEquals(0, run("", "--help"));
        assertEquals(2, run(""));
        assertEquals(2, run("", "-", "-o"));
        assertEquals(2, run("", "--unknown"));
        assertEquals(2, run("", temporary.resolve("missing.ts").toString()));
    }
}
