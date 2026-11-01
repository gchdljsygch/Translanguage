package com.pingsu.transpiler;

/** Reusable domain capability; implementations do not retain per-file state. */
public interface Transpiler {
    TranspileResult transpile(String sourceName, String source);
}
