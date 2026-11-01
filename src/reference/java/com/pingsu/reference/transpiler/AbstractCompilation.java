package com.pingsu.reference.transpiler;

import java.util.Objects;

/** Owns the lifecycle of a single compilation; a session is intentionally single use. */
public abstract class AbstractCompilation {
    /** Lifecycle phases of a single compilation session. */
    public enum State {
        /** Session created; parsing has not started. */
        CREATED,
        /** Parsing finished successfully. */
        PARSED,
        /** Every transformation pass finished. */
        TRANSFORMED,
        /** A result was produced successfully. */
        COMPLETED,
        /** Compilation stopped with diagnostics or an exception. */
        FAILED
    }
    private State state = State.CREATED;
    protected final String sourceName;
    protected final String source;

    /**
     * Creates a single-use session.
     *
     * @param sourceName diagnostic-only name of the source
     * @param source TypeScript text to compile
     */
    protected AbstractCompilation(String sourceName, String source) {
        this.sourceName = Objects.requireNonNull(sourceName);
        this.source = Objects.requireNonNull(source);
    }

    /** Returns the current lifecycle phase. */
    public final State state() { return state; }

    /**
     * Advances from one phase to the next.
     *
     * @param expected the only phase this transition is allowed from
     * @param next the phase to enter
     * @throws IllegalStateException when the session is not in {@code expected}
     */
    protected final void transition(State expected, State next) {
        if (state != expected) throw new IllegalStateException("Expected " + expected + ", was " + state);
        state = next;
    }

    /**
     * Runs this session exactly once.
     *
     * @return the generated JavaScript and diagnostics
     * @throws IllegalStateException when the session was already executed
     */
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

    /** Parses and transforms the source, returning the final result or diagnostics. */
    protected abstract TranspileResult compile();
}
