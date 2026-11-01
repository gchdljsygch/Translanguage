package com.pingsu.transpiler;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompilationLifecycleTest {
    private abstract static class Session extends AbstractCompilation {
        Session() { super("fixture.ts", ""); }
        void parsed() { transition(State.CREATED, State.PARSED); }
        void transformed() { transition(State.PARSED, State.TRANSFORMED); }
    }
    @Test void successfulSessionCompletesOnlyAfterAllPhases() {
        var session = new Session() {
            @Override protected TranspileResult compile() { parsed(); transformed(); return new TranspileResult("ok", List.of()); }
        };
        assertEquals(AbstractCompilation.State.CREATED, session.state());
        assertEquals("ok", session.execute().javaScript());
        assertEquals(AbstractCompilation.State.COMPLETED, session.state());
        assertThrows(IllegalStateException.class, session::execute);
        assertEquals(AbstractCompilation.State.COMPLETED, session.state());
    }
    @Test void skippingLifecycleStagesAndReentrantExecutionFail() {
        var skipped = new Session() {
            @Override protected TranspileResult compile() { transition(State.CREATED, State.TRANSFORMED); return new TranspileResult("", List.of()); }
        };
        assertThrows(IllegalStateException.class, skipped::execute);
        assertEquals(AbstractCompilation.State.FAILED, skipped.state());
        var reentrant = new Session() { @Override protected TranspileResult compile() { return execute(); } };
        assertThrows(IllegalStateException.class, reentrant::execute);
        assertEquals(AbstractCompilation.State.FAILED, reentrant.state());
    }
    @Test void diagnosticsAndExceptionsAlwaysFailTheSession() {
        var failed = new Session() {
            @Override protected TranspileResult compile() { parsed(); return new TranspileResult("partial output", List.of(new Diagnostic("a", 1, 1, "CUSTOM", "bad"))); }
        };
        assertEquals("", failed.execute().javaScript());
        assertEquals(AbstractCompilation.State.FAILED, failed.state());
        assertThrows(IllegalStateException.class, failed::execute);
        for (boolean fatal : List.of(false, true)) {
            var broken = new Session() {
                @Override protected TranspileResult compile() { if (fatal) throw new AssertionError("emitter"); throw new IllegalArgumentException("pass"); }
            };
            if (fatal) assertThrows(AssertionError.class, broken::execute);
            else assertThrows(IllegalArgumentException.class, broken::execute);
            assertEquals(AbstractCompilation.State.FAILED, broken.state());
        }
    }
    @Test void emitterDiagnosticsCannotReturnPartialJavaScript() {
        var compiler = new TypeScriptTranspiler(JavaScriptTarget.ES6, List.of(new TypeErasurePass()),
                (name, js, target) -> new TranspileResult(js, List.of(new Diagnostic(name, 1, 1, "JS_TARGET", "bad"))));
        assertEquals("", compiler.transpile("a.ts", "const value: number = 1;").javaScript());
    }
}
