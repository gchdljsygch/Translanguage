package com.pingsu.transpiler;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Test-only reproducible source/output evidence; never included in production artifacts. */
final class CompilationEvidence implements BeforeEachCallback, AfterEachCallback {
    private static final ThreadLocal<String> CASE = new ThreadLocal<>();
    @Override public void beforeEach(ExtensionContext context) {
        CASE.set(context.getRequiredTestMethod().getName() + "-" + context.getDisplayName().replaceAll("[^A-Za-z0-9_-]", "_"));
    }
    @Override public void afterEach(ExtensionContext context) { CASE.remove(); }

    static Transpiler compiler() { return compiler(JavaScriptTarget.PRESERVE); }
    static Transpiler compiler(JavaScriptTarget target) { return wrap(new TypeScriptTranspiler(target), target); }
    static Transpiler compiler(List<TransformationPass> passes) { return compiler(JavaScriptTarget.PRESERVE, passes); }
    static Transpiler compiler(JavaScriptTarget target, List<TransformationPass> passes) {
        return wrap(new TypeScriptTranspiler(target, passes), target);
    }
    static Transpiler compiler(JavaScriptTarget target, List<TransformationPass> passes, JavaScriptEmitter emitter) {
        return wrap(new TypeScriptTranspiler(target, passes, emitter), target);
    }
    private static Transpiler wrap(Transpiler compiler, JavaScriptTarget target) {
        return (name, source) -> {
            TranspileResult result = compiler.transpile(name, source);
            save("compile", target.name() + "\n" + name + "\n" + source + "\n" + result,
                    "input.ts", source, "output.mjs", result.javaScript(), "result.txt",
                    "target=" + target + "\nsourceName=" + name + "\nsuccessful=" + result.successful()
                            + "\n" + result.diagnostics());
            return result;
        };
    }
    static void execution(String purpose, String stdout, String stderr, int exit) {
        save("execution", purpose + stdout + stderr + exit, "process.txt",
                "purpose=" + purpose + "\nexit=" + exit + "\n", "stdout.txt", stdout, "stderr.txt", stderr);
    }
    private static void save(String kind, String identity, String... contents) {
        String destination = System.getProperty("translanguage.evidence.dir");
        if (destination == null) return;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
            String caseId = CASE.get() == null ? "concurrent" : CASE.get();
            Path folder = Path.of(destination, caseId, kind + "-" + hash);
            Files.createDirectories(folder);
            // Concurrent identical compilations have identical evidence. Serialize the write only.
            synchronized (CompilationEvidence.class) {
                for (int i = 0; i < contents.length; i += 2)
                    Files.writeString(folder.resolve(contents[i]), contents[i + 1], StandardCharsets.UTF_8);
            }
        } catch (Exception error) { throw new AssertionError("Cannot record compilation evidence", error); }
    }
}
