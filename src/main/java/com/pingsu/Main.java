package com.pingsu;

import com.pingsu.transpiler.TranspileResult;
import com.pingsu.transpiler.TypeScriptTranspiler;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class Main {
    private static final String USAGE = """
            Usage: translanguage <input.ts | -> [-o <output.js | ->]
                   translanguage --help
            Transpile TypeScript into modern JavaScript with ANTLR.
            Without -o, JavaScript is written to stdout. Use - for stdin/stdout.
            Exit codes: 0 success, 1 syntax/unsupported feature, 2 arguments/I/O.
            """;

    private Main() { }
    public static void main(String[] args) {
        int status = run(args, System.in, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    /** Injectable streams keep the CLI usable by embedded callers and tests. */
    public static int run(String[] args, InputStream input, PrintStream output, PrintStream errors) {
        String inputName = null;
        String outputName = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> { output.print(USAGE); return 0; }
                    case "-o", "--out" -> {
                        if (outputName != null || ++i >= args.length) throw new IllegalArgumentException("-o requires one output path");
                        outputName = args[i];
                    }
                    default -> {
                        if (inputName != null || (args[i].startsWith("-") && !args[i].equals("-")))
                            throw new IllegalArgumentException("Unexpected argument: " + args[i]);
                        inputName = args[i];
                    }
                }
            }
            if (inputName == null) throw new IllegalArgumentException("An input file or - is required");
            Path sourcePath = inputName.equals("-") ? null : Path.of(inputName).toAbsolutePath().normalize();
            Path targetPath = outputName == null || outputName.equals("-") ? null : Path.of(outputName).toAbsolutePath().normalize();
            if (sourcePath != null && targetPath != null && (sourcePath.equals(targetPath)
                    || (Files.exists(targetPath) && Files.isSameFile(sourcePath, targetPath))))
                throw new IllegalArgumentException("Input and output must be different files");
            String source = sourcePath == null ? new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(sourcePath, StandardCharsets.UTF_8);
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            TranspileResult result = new TypeScriptTranspiler().transpile(inputName.equals("-") ? "<stdin>" : inputName, source);
            if (!result.successful()) {
                result.diagnostics().forEach(errors::println);
                return 1;
            }
            if (targetPath == null) {
                output.write(result.javaScript().getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (output.checkError()) throw new IOException("Failed to write JavaScript to stdout");
            } else {
                writeAtomically(targetPath, result.javaScript());
            }
            return 0;
        } catch (IllegalArgumentException | IOException error) {
            errors.println("translanguage: " + error.getMessage());
            return 2;
        }
    }

    private static void writeAtomically(Path target, String text) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".translanguage-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
