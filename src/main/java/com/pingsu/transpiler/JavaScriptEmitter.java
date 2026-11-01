package com.pingsu.transpiler;

/** Output capability, separate from TypeScript tree transformations and compilation lifecycle. */
@FunctionalInterface
public interface JavaScriptEmitter {
    /**
     * Emits JavaScript for a target. Implementations must be reusable across independent sessions.
     * Diagnostic positions refer to the JavaScript supplied to this method.
     *
     * @param sourceName diagnostic-only source name
     * @param javaScript JavaScript after TypeScript transformations
     * @param target requested syntax version
     * @return compatible JavaScript, or diagnostics with no output
     */
    TranspileResult emit(String sourceName, String javaScript, JavaScriptTarget target);
}
