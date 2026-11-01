package com.pingsu.transpiler;

import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.DiagnosticGroups;
import com.google.javascript.jscomp.CustomPassExecutionTime;
import com.google.javascript.jscomp.deps.ModuleLoader;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.rhino.Node;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Pure Java syntax lowering, without optimization, bundling, file I/O or API polyfills. */
public final class ClosureJavaScriptEmitter implements JavaScriptEmitter {
    @Override public TranspileResult emit(String sourceName, String javaScript, JavaScriptTarget target) {
        Objects.requireNonNull(sourceName);
        Objects.requireNonNull(javaScript);
        Objects.requireNonNull(target);
        if (target == JavaScriptTarget.PRESERVE) return new TranspileResult(javaScript, List.of());
        CompilerOptions options = new CompilerOptions();
        // Public class fields are behind UNSTABLE in the pinned Java 17-compatible backend.
        options.setLanguageIn(CompilerOptions.LanguageMode.UNSTABLE);
        CompilerOptions.LanguageMode outputLanguage = switch (target) {
            case ES6 -> CompilerOptions.LanguageMode.ECMASCRIPT_2015;
            case ES2021 -> CompilerOptions.LanguageMode.ECMASCRIPT_2021;
            case PRESERVE -> throw new IllegalStateException("Preserve mode already returned");
        };
        options.setLanguageOut(outputLanguage);
        options.setSkipNonTranspilationPasses(true);
        options.setEs6ModuleTranspilation(CompilerOptions.Es6ModuleTranspilation.NONE);
        options.setModuleResolutionMode(ModuleLoader.ResolutionMode.NODE);
        options.setWarningLevel(DiagnosticGroups.MODULE_LOAD, CheckLevel.OFF);
        options.setRewritePolyfills(false);
        options.setEmitUseStrict(false);
        options.setPrettyPrint(true);
        options.setPreserveNonJSDocComments(true);
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8)) {
            RuntimeCompiler compiler = new RuntimeCompiler(sink);
            compiler.disableThreads();
            ClassFieldSemantics fields = new ClassFieldSemantics(compiler);
            options.addCustomPass(CustomPassExecutionTime.BEFORE_CHECKS, fields::capture);
            var result = compiler.compile(List.of(), List.of(SourceFile.fromCode(sourceName, javaScript)), options);
            if (!result.success) return new TranspileResult("", result.errors.stream()
                    .map(error -> diagnostic(sourceName, target, error)).toList());
            fields.restore(compiler.getRoot());
            if (compiler.hasErrors()) return new TranspileResult("", compiler.getErrors().stream()
                    .map(error -> diagnostic(sourceName, target, error)).toList());
            String output = compiler.toSource();
            // Some backend passes intentionally retain unsupported syntax (e.g. dynamic import).
            // Parse again and inspect actual output features rather than trusting the requested mode.
            var validator = new com.google.javascript.jscomp.Compiler(sink);
            validator.disableThreads();
            CompilerOptions validationOptions = new CompilerOptions();
            validationOptions.setLanguageIn(CompilerOptions.LanguageMode.UNSTABLE);
            validator.initOptions(validationOptions);
            Node script = validator.parse(SourceFile.fromCode(sourceName, output));
            if (validator.hasErrors()) return new TranspileResult("", validator.getErrors().stream()
                    .map(error -> diagnostic(sourceName, target, error)).toList());
            FeatureSet allowed = outputLanguage.toFeatureSet();
            FeatureSet unsupported = ((FeatureSet) script.getProp(Node.FEATURE_SET)).without(allowed);
            if (!unsupported.getFeatures().isEmpty()) return new TranspileResult("", List.of(
                    new Diagnostic(sourceName, 1, 1, "JS_TARGET", target + ": Cannot lower " + unsupported)));
            return new TranspileResult(output, List.of());
        }
    }

    private static Diagnostic diagnostic(String sourceName, JavaScriptTarget target, JSError error) {
        return new Diagnostic(sourceName, Math.max(1, error.getLineno()), Math.max(1, error.getCharno() + 1),
                "JS_TARGET", target + ": " + error.getDescription());
    }

    /** Transpile-only mode normally expects helpers externally; emit those helpers into each result. */
    private static final class RuntimeCompiler extends com.google.javascript.jscomp.Compiler {
        RuntimeCompiler(PrintStream sink) { super(sink); }

        @Override protected Node ensureLibraryInjected(String resourceName, boolean force) {
            return super.ensureLibraryInjected(resourceName, true);
        }
    }
}
