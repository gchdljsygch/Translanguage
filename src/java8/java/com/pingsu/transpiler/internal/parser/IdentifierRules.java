package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.compat.Compat;
import java.util.Set;

/** Context-independent identifier spelling and reserved-word tables. */
final class IdentifierRules {
    private IdentifierRules() { }
    static final Set<String> RESERVED = Compat.set("break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally",
            "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super", "switch",
            "this", "throw", "true", "try", "typeof", "var", "void", "while", "with");
    static final Set<String> STRICT_RESERVED = Compat.set("implements", "interface", "let", "package",
            "private", "protected", "public", "static", "yield");

    static String spelling(String raw) {
        if (raw.indexOf('\\') < 0) return raw;
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '\\') { decoded.append(raw.charAt(i++)); continue; }
            i += 2;
            if (raw.charAt(i) == '{') { int end = raw.indexOf('}', ++i); decoded.appendCodePoint(Integer.parseInt(raw.substring(i, end), 16)); i = end + 1; }
            else { decoded.append((char) Integer.parseInt(raw.substring(i, i + 4), 16)); i += 4; }
        }
        return decoded.toString();
    }
}
