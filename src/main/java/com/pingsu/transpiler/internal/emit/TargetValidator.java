package com.pingsu.transpiler.internal.emit;

import com.pingsu.transpiler.JavaScriptTarget;
import com.pingsu.transpiler.internal.js.JsProgram;

/** Checks a parsed actual artifact against a target grammar. */
public interface TargetValidator {
    void validate(JsProgram program, JavaScriptTarget target);
}
