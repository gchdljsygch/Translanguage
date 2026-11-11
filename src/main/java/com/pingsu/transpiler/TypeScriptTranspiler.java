package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.RecursiveDescentParser;
import com.pingsu.transpiler.internal.parser.SourceParser;
import java.util.List;
import java.util.Objects;

/** Emits JavaScript for a selected syntax target, preserving ES modules. No type checking. */
public final class TypeScriptTranspiler implements Transpiler {
    private final List<TransformationPass> passes;
    private final JavaScriptTarget target;
    private final JavaScriptEmitter emitter;

    /** Uses the default passes and preserves runtime syntax and source trivia. */
    public TypeScriptTranspiler() { this(JavaScriptTarget.PRESERVE); }

    /** Uses the default TypeScript passes and emits the requested JavaScript syntax version. */
    public TypeScriptTranspiler(JavaScriptTarget target) {
        this(target, DecoratorMode.STANDARD);
    }

    /** Uses default passes with an explicit decorator calling convention. */
    public TypeScriptTranspiler(JavaScriptTarget target, DecoratorMode decorators) {
        this(target, java.util.Arrays.asList(new JsxLoweringPass(), new TypeErasurePass(), new EnumLoweringPass(), new NamespaceLoweringPass(), new AutoAccessorLoweringPass(), new DecoratorLoweringPass(decorators)));
    }

    /** Uses exactly the supplied TypeScript passes, preserving runtime syntax and source trivia. */
    public TypeScriptTranspiler(List<TransformationPass> passes) { this(JavaScriptTarget.PRESERVE, passes); }

    /** Uses exactly the supplied TypeScript passes, followed by target-version emission. */
    public TypeScriptTranspiler(JavaScriptTarget target, List<TransformationPass> passes) {
        this(target, passes, new DefaultJavaScriptEmitter());
    }

    /**
     * Supplies both transformation and output capabilities for library extensions.
     * Supplied passes and emitter are shared across calls and must be thread-safe for concurrent use.
     */
    public TypeScriptTranspiler(JavaScriptTarget target, List<TransformationPass> passes, JavaScriptEmitter emitter) {
        this.target = Objects.requireNonNull(target);
        this.passes = List.copyOf(passes);
        this.emitter = Objects.requireNonNull(emitter);
    }

    /** Returns this reusable transpiler's immutable output target. */
    public JavaScriptTarget target() { return target; }

    /** {@inheritDoc} */
    @Override public TranspileResult transpile(String sourceName, String source) {
        return new Session(sourceName, source != null && source.startsWith("\uFEFF") ? source.substring(1) : source).execute();
    }

    private final class Session extends AbstractCompilation {
        Session(String name, String source) { super(name, source); }

        @Override protected TranspileResult compile() {
            SourceParser.Result parsed = new RecursiveDescentParser().parse(sourceName, source, SourceParser.Language.TYPESCRIPT);
            if (!parsed.successful()) return new TranspileResult("", parsed.diagnostics());
            transition(State.CREATED, State.PARSED);
            TransformationContext context = new TransformationContext(sourceName, source, parsed.program(), target);
            for (TransformationPass pass : passes) if (pass instanceof DecoratorLoweringPass) ((DecoratorLoweringPass) pass).configure(context);
            for (TransformationPass pass : passes) {
                pass.transform(context);
                if (!context.diagnostics().isEmpty()) return new TranspileResult("", context.diagnostics());
            }
            transition(State.PARSED, State.TRANSFORMED);
            return emitter.emit(sourceName, context.render(), target);
        }
    }
}
