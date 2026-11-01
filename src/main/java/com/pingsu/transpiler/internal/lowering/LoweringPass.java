package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.js.JsProgram;

/** A target lowering capability. Every returned artifact is parsed before the next pass. */
public interface LoweringPass {
    String lower(JsProgram program);
}
