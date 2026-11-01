package com.pingsu.transpiler;

/** Extension point for syntax-directed transformations after a successful parse. */
@FunctionalInterface
public interface TransformationPass {
    void transform(TransformationContext context);
}
