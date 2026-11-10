package com.pingsu.transpiler.internal.parser;

import java.util.HashSet;
import java.util.Set;

/** Context-sensitive grammar state, shared only by modules in a single parse session. */
final class GrammarContext {
    final boolean moduleCode;
    boolean strict, module, async, generator, ambientContext, allowIn = true;
    int functionDepth, loops, switches, namespaceBodyDepth;
    final Set<String> labels = new HashSet<>();
    final Set<String> iterationLabels = new HashSet<>();

    GrammarContext(boolean moduleCode) {
        this.moduleCode = moduleCode;
        this.strict = moduleCode;
    }
}
