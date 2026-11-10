package com.pingsu.transpiler.internal.typescript;

/** JSX runtime tag classification, shared by lowering and import analysis. */
public final class JsxNames {
    private JsxNames() { }
    public static boolean intrinsic(String name) {
        return !name.isEmpty() && (name.indexOf(':') >= 0 || name.indexOf('-') >= 0
                || name.indexOf('.') < 0 && name.charAt(0) >= 'a' && name.charAt(0) <= 'z');
    }
}
