package com.pingsu.transpiler;

import com.pingsu.antlr.TypeScriptLexer;
import com.pingsu.antlr.TypeScriptParser;
import org.antlr.v4.runtime.*;
import java.util.ArrayList;
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
        this(target, List.of(new TypeErasurePass(), new EnumLoweringPass()));
    }

    /** Uses exactly the supplied TypeScript passes, preserving runtime syntax and source trivia. */
    public TypeScriptTranspiler(List<TransformationPass> passes) { this(JavaScriptTarget.PRESERVE, passes); }

    /** Uses exactly the supplied TypeScript passes, followed by target-version emission. */
    public TypeScriptTranspiler(JavaScriptTarget target, List<TransformationPass> passes) {
        this(target, passes, new ClosureJavaScriptEmitter());
    }

    /** Supplies both transformation and output capabilities for library extensions. */
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
            List<Diagnostic> diagnostics = new ArrayList<>();
            BaseErrorListener errors = new BaseErrorListener() {
                @Override public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line,
                                                  int column, String message, RecognitionException cause) {
                    diagnostics.add(new Diagnostic(sourceName, line, column + 1, "TS_SYNTAX", message));
                }
            };
            TypeScriptLexer lexer = new TypeScriptLexer(CharStreams.fromString(source, sourceName));
            lexer.removeErrorListeners();
            lexer.addErrorListener(errors);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            for (Token token : tokens.getTokens()) {
                if (token.getChannel() == TypeScriptLexer.ERROR) {
                    diagnostics.add(new Diagnostic(sourceName, token.getLine(), token.getCharPositionInLine() + 1,
                            "TS_LEXER", "Unexpected character: " + token.getText()));
                }
            }
            if (!diagnostics.isEmpty()) return new TranspileResult("", diagnostics);
            TypeScriptParser parser = new TypeScriptParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(errors);
            TypeScriptParser.ProgramContext program = parser.program();
            if (!diagnostics.isEmpty()) return new TranspileResult("", diagnostics);
            transition(State.CREATED, State.PARSED);
            TransformationContext context = new TransformationContext(sourceName, source, program, tokens, target);
            for (TransformationPass pass : passes) {
                pass.transform(context);
                if (!context.diagnostics().isEmpty()) return new TranspileResult("", context.diagnostics());
            }
            transition(State.PARSED, State.TRANSFORMED);
            return emitter.emit(sourceName, context.render(), target);
        }
    }
}
