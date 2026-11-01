package com.pingsu.reference.transpiler;

/**
 * Reusable domain capability; implementations do not retain per-file state.
 */
public interface Transpiler {
    /**
     * Transforms one TypeScript source unit into JavaScript for the configured output target.
     *
     * @param sourceName diagnostic-only name of the source, such as a file name
     * @param source TypeScript text; a leading byte-order mark is ignored
     * @return generated JavaScript plus any diagnostics
     */
    TranspileResult transpile(String sourceName, String source);
}
