package com.pingsu.transpiler.internal.js;

/** Decodes a lexer-validated quoted property name without evaluating source code. */
public final class StringLiterals {
    private StringLiterals() { }
    public static String decode(String raw) {
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (c != '\\') { value.append(c); continue; }
            c = raw.charAt(++i);
            switch (c) {
                case 'n': value.append('\n'); break;
                case 'r': value.append('\r'); break;
                case 't': value.append('\t'); break;
                case 'b': value.append('\b'); break;
                case 'f': value.append('\f'); break;
                case 'v': value.append('\u000b'); break;
                case '\n':case '\u2028':case '\u2029': { } break;
                case '\r': { if (raw.charAt(i + 1) == '\n') i++; } break;
                case 'x': { value.append((char) Integer.parseInt(raw.substring(i + 1, i + 3), 16)); i += 2; } break;
                case 'u': {
                    if (raw.charAt(i + 1) == '{') {
                        int end = raw.indexOf('}', i + 2);
                        value.appendCodePoint(Integer.parseInt(raw.substring(i + 2, end), 16)); i = end;
                    } else { value.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16)); i += 4; }
                } break;
                default: {
                    if (c >= '0' && c <= '7') {
                        int number = c - '0', remaining = c <= '3' ? 2 : 1;
                        while (remaining-- > 0 && raw.charAt(i + 1) >= '0' && raw.charAt(i + 1) <= '7') number = number * 8 + raw.charAt(++i) - '0';
                        value.append((char) number);
                    } else value.append(c);
                } break;
            }
        }
        return value.toString();
    }
}