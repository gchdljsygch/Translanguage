package com.pingsu.transpiler;

/** Extension point for syntax-directed transformations after a successful parse. */
@FunctionalInterface
public interface TransformationPass {
    /**
     * Applies this pass to the parsed program.
     *
     * @param context parse tree, token stream, source edits, and diagnostics for one compilation
     */
    void transform(TransformationContext context);
}
