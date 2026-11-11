package com.pingsu.transpiler;

/** JavaScript syntax accepted by the output environment; this does not select runtime polyfills. */
public enum JavaScriptTarget {
    /** Preserve runtime syntax and source trivia, matching the original library behavior. */
    PRESERVE,
    /** ECMAScript 2015 (also known as ES6), retaining ES modules. */
    ES6,
    /** ECMAScript 2021, retaining ES modules. */
    ES2021,
    /** ECMAScript 5 script syntax, including lowered generators and explicit CommonJS forms; ES modules are diagnosed. */
    ES5
}
