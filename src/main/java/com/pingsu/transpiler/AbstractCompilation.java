package com.pingsu.transpiler;

import java.util.Objects;

/** Owns the lifecycle of a single compilation; a session is intentionally single use. */
public abstract class AbstractCompilation {
    public enum State { CREATED, PARSED, TRANSFORMED, COMPLETED, FAILED }
    private State state = State.CREATED;
    protected final String sourceName;
    protected final String source;

    protected AbstractCompilation(String sourceName, String source) {
        this.sourceName = Objects.requireNonNull(sourceName);
        this.source = Objects.requireNonNull(source);
    }

    public final State state() { return state; }

    protected final void transition(State expected, State next) {
        if (state != expected) throw new IllegalStateException("Expected " + expected + ", was " + state);
        state = next;
    }

    public final TranspileResult execute() {
        if (state != State.CREATED) throw new IllegalStateException("Compilation sessions are single use");
        try {
            TranspileResult result = compile();
            if (result.successful()) transition(State.TRANSFORMED, State.COMPLETED);
            else state = State.FAILED;
            return result;
        } catch (RuntimeException error) {
            state = State.FAILED;
            throw error;
        }
    }

    protected abstract TranspileResult compile();
}
