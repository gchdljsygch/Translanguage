package com.pingsu.transpiler.internal.emit;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.js.JsNode;
import com.pingsu.transpiler.internal.js.JsProgram;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.*;

/**
 * Formats a validated artifact without rewriting its tokens. Statement and body boundaries
 * come from the AST; existing line terminators survive to preserve automatic semicolons.
 * Templates are opaque so neither cooked values nor tagged-template raw text can change.
 */
public final class ReadableJavaScriptPrinter extends JavaScriptPrinter {
    private final Set<Integer> breaks = new HashSet<>();
    private final Set<Integer> opens = new HashSet<>();
    private final Set<Integer> closes = new HashSet<>();
    private final Map<Integer, Integer> templates = new HashMap<>();
    private static final Set<String> OPERATORS = Compat.set(
            "=", "+=", "-=", "*=", "/=", "%=", "**=", "&&=", "||=", "??=", "&=", "|=", "^=", "<<=", ">>=", ">>>=",
            "+", "-", "*", "/", "%", "**", "==", "!=", "===", "!==", "<", ">", "<=", ">=",
            "&&", "||", "??", "&", "|", "^", "<<", ">>", ">>>", "?", "=>");
    private static final Set<String> CONTROL = Compat.set("if", "for", "while", "switch", "catch", "with");

    public ReadableJavaScriptPrinter(JsProgram program) {
        super(program);
        layout(program.root());
    }

    private void layout(JsNode node) {
        if (node.is("templateLiteral")) {
            templates.put(node.range().start(), node.range().end());
            return;
        }
        if (node.is("program") || node.is("block") || node.is("functionBody")) {
            for (JsNode child : node.children()) breaks.add(child.range().start());
            if (!node.is("program")) body(node.range().start(), node.range().end() - 1, !node.children().isEmpty());
        } else if (node.is("classDeclaration") || node.is("classExpression")) {
            // Header expressions may themselves contain braces, so skip their entire ranges.
            List<JsNode> members = node.children().stream().filter(n ->
                    n.is("methodDeclaration") || n.is("constructorDeclaration")).collect(Compat.toList());
            int headerEnd = node.range().start();
            for (JsNode child : node.children()) {
                if (members.contains(child)) break;
                headerEnd = child.range().end();
            }
            for (Token token : program.significantTokens(node)) {
                if (token.range().start() >= headerEnd && token.text().equals("{")) {
                    body(token.range().start(), node.range().end() - 1, !members.isEmpty());
                    break;
                }
            }
            for (JsNode member : members) breaks.add(member.range().start());
        }
        for (JsNode child : node.children()) layout(child);
    }

    private void body(int opening, int closing, boolean nonempty) {
        if (!nonempty) return;
        opens.add(opening);
        closes.add(closing);
        breaks.add(opening + 1);
        breaks.add(closing);
    }

    @Override public String print() {
        StringBuilder out = new StringBuilder();
        Token previous = null;
        int indent = 0, lines = 0, skipUntil = -1;
        boolean space = false, lineStart = true;
        for (Token token : program.tokens()) {
            int offset = token.range().start();
            if (offset < skipUntil) continue;
            if (token.kind() == TokenKind.EOF) break;
            if (breaks.contains(offset)) lines = Math.max(lines, 1);
            if (token.kind() == TokenKind.WHITESPACE) { space = true; continue; }
            if (token.kind() == TokenKind.LINE_TERMINATOR) { lines = 1; continue; }
            if (closes.contains(offset)) indent--;
            if (lines > 0 && out.length() > 0) {
                out.append(Compat.repeat("\n", lines));
                lineStart = true;
            }
            if (lineStart) out.append(Compat.repeat("  ", Math.max(0, indent)));
            else if (space || separated(previous, token)) out.append(' ');
            Integer templateEnd = templates.get(offset);
            if (templateEnd != null) {
                out.append(program.source(), offset, templateEnd);
                skipUntil = templateEnd;
            } else out.append(token.text());
            lineStart = false;
            lines = 0;
            space = false;
            if (opens.contains(offset)) { indent++; lines = 1; }
            if (token.kind() == TokenKind.HASHBANG || token.kind() == TokenKind.COMMENT && token.text().startsWith("//")) lines = 1;
            previous = token;
        }
        if (out.length() > 0) out.append('\n');
        return out.toString();
    }

    private boolean separated(Token left, Token right) {
        if (left == null) return false;
        String a = left.text(), b = right.text();
        if (left.kind() == TokenKind.COMMENT || right.kind() == TokenKind.COMMENT) return true;
        if (a.equals(",") || a.equals(";") || a.equals(":")) return true;
        if (OPERATORS.contains(a) || OPERATORS.contains(b)) return true;
        if (b.equals("(")) return CONTROL.contains(a);
        if (a.equals(")") && right.kind() == TokenKind.KEYWORD) return true;
        if (b.equals("{") && !Compat.set("(", "[", ".", "?.").contains(a)) return true;
        if (a.equals("}") && Compat.set("else", "catch", "finally", "while").contains(b)) return true;
        return false;
    }
}
