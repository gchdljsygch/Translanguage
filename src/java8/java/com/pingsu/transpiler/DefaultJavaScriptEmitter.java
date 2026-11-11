package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.emit.*;
import com.pingsu.transpiler.internal.js.*;
import com.pingsu.transpiler.internal.lowering.*;
import java.util.List;
import java.util.Objects;

/**
 * Self-contained ES2021, ES6 and ES5 emission, including asynchronous control flow.
 * The complete compilation path requires only java.base.
 * Instances are immutable and safe to share between compilation sessions.
 */
public final class DefaultJavaScriptEmitter implements JavaScriptEmitter {
    @Override public TranspileResult emit(String sourceName, String javaScript, JavaScriptTarget target) {
        Objects.requireNonNull(sourceName); Objects.requireNonNull(javaScript); Objects.requireNonNull(target);
        if (target == JavaScriptTarget.PRESERVE) return new TranspileResult(javaScript, Compat.list());
        return new Session(sourceName, javaScript, target).execute();
    }
    private static final class Session extends AbstractCompilation {
        private final JavaScriptTarget target;
        Session(String name, String source, JavaScriptTarget target) { super(name, source); this.target = target; }
        @Override protected TranspileResult compile() {
            try {
                JsProgram input = JsProgram.parse(sourceName, source);
                transition(State.CREATED, State.PARSED);
                com.pingsu.transpiler.internal.emit.SyntaxTargetValidator validator = new SyntaxTargetValidator(); validator.validateInput(input, target);
                input = JsProgram.parse(sourceName, new ResourceManagementLowering().lower(input));
                String fields = target == JavaScriptTarget.ES5 && !ClassFieldsLowering.hasPrivate(input.root()) ? input.source() : new ClassFieldsLowering().lower(input);
                JsProgram fieldOutput = JsProgram.parse(sourceName, fields);
                if (target == JavaScriptTarget.ES6 || target == JavaScriptTarget.ES5) fieldOutput = JsProgram.parse(sourceName, new AsyncLowering(target == JavaScriptTarget.ES5).lower(fieldOutput));
                if (target == JavaScriptTarget.ES6) fieldOutput = JsProgram.parse(sourceName, new ObjectRestLowering().lower(fieldOutput));
                String result = target == JavaScriptTarget.ES5 ? new Es5Lowering().lower(fieldOutput)
                        : target == JavaScriptTarget.ES6 ? new RegularLowering().lower(fieldOutput)
                        : new JavaScriptPrinter(fieldOutput) {
                            @Override protected String render(JsNode node) {
                                if (node.is("numericLiteral") || node.is("bigintLiteral")) return text(node).replace("_", "");
                                return super.render(node);
                            }
                        }.print();
                transition(State.PARSED, State.TRANSFORMED);
                JsProgram lowered = JsProgram.parse(sourceName, result);
                validator.validate(lowered, target);
                result = new ReadableJavaScriptPrinter(lowered).print();
                // Formatting is part of emission: validate the exact text returned to callers.
                validator.validate(JsProgram.parse(sourceName, result), target);
                return new TranspileResult(result, Compat.list());
            } catch (TargetFailure failure) { return new TranspileResult("", failure.diagnostics()); }
        }
    }
}
