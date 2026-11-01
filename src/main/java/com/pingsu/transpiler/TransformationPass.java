package com.pingsu.transpiler;

/**
 * Extension point for syntax-directed transformations after a successful parse.
 * A reusable transpiler shares its supplied pass instances; callers must make them thread-safe
 * when invoking the transpiler concurrently. Store per-compilation state in local variables.
 */
@FunctionalInterface
public interface TransformationPass {
    /**
     * Applies this pass to the parsed program.
     *
     * @param context parse tree, token stream, source edits, and diagnostics for one compilation
     */
    void transform(TransformationContext context);
}
