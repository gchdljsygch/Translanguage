package com.pingsu.transpiler.internal.js;

/** Captured JS Reference: evaluation setup, read/write target and call receiver. */
public record ExpressionReference(String setup, String target, String receiver) {
    public String read() { return target; }
    public String write(String value) { return target + "=(" + value + ")"; }
}
