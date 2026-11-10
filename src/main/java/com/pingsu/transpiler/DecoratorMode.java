package com.pingsu.transpiler;

/** Selects the incompatible decorator calling conventions explicitly. */
public enum DecoratorMode {
    /** ECMAScript decorators: (value, context), initializers and replacement values. */
    STANDARD,
    /** TypeScript experimentalDecorators: target/key/descriptor and parameter decorators. */
    LEGACY
}
