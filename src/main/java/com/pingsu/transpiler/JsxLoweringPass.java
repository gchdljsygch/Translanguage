package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.emit.JavaScriptPrinter;
import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.internal.typescript.JsxEntities;
import com.pingsu.transpiler.internal.typescript.TypeOnlySyntax;
import com.pingsu.transpiler.syntax.SourceRange;
import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.ArrayList;
import java.util.List;

/** Classic JSX runtime lowering. Factory and fragment are qualified JavaScript identifiers. */
public final class JsxLoweringPass implements TransformationPass {
    private final String factory, fragment;
    public JsxLoweringPass() { this("React.createElement", "React.Fragment"); }
    public JsxLoweringPass(String factory, String fragment) {
        this.factory = qualified(factory); this.fragment = qualified(fragment);
    }
    private static String qualified(String value) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            throw new IllegalArgumentException("Expected a qualified JavaScript identifier");
        return value;
    }
    @Override public void transform(TransformationContext context) {
        context.jsxPass(this);
        context.program().walk(node -> {
            if (TypeOnlySyntax.DECLARATIONS.contains(node.kind())) return false;
            if (node.kind().equals("jsxElement")) {
                context.reference(node, factory.split("\\.")[0]);
                if (((ParsedNode) node).attribute("name").isEmpty()) context.reference(node, fragment.split("\\.")[0]);
            }
            return true;
        });
        context.program().walk(node -> {
            if (TypeOnlySyntax.DECLARATIONS.contains(node.kind()) || node.kind().equals("decorator") || node.kind().equals("enumDeclaration")) return false;
            if (node.kind().equals("jsxElement")) lower(context, (ParsedNode) node);
            return true;
        });
    }
    private void lower(TransformationContext context, ParsedNode node) {
        context.reference(node, factory.split("\\.")[0]);
        String name = node.attribute("name");
        String tag = name;
        if (name.isEmpty()) { tag = fragment; context.reference(node, fragment.split("\\.")[0]); }
        else if (com.pingsu.transpiler.internal.typescript.JsxNames.intrinsic(name)) tag = JavaScriptPrinter.quote(name);
        List<SyntaxNode> attributes = new ArrayList<>(), children = new ArrayList<>();
        for (SyntaxNode child : node.children()) {
            if (child.kind().equals("jsxAttribute") || child.kind().equals("jsxSpreadAttribute")) attributes.add(child);
            else if (!child.kind().equals("jsxTag") && !child.kind().equals("jsxTypeArguments")) children.add(child);
        }
        Writer output = new Writer(context, node.range().start());
        output.text.append(factory).append('(').append(tag).append(", ");
        if (attributes.isEmpty()) output.text.append("null");
        else {
            output.text.append('{'); boolean comma = false;
            for (SyntaxNode attribute : attributes) {
                if (comma) output.text.append(", "); comma = true;
                if (attribute.kind().equals("jsxSpreadAttribute")) { output.text.append("...("); output.keep(attribute.children().get(0)); output.text.append(')'); }
                else {
                    String key = ((ParsedNode) attribute).attribute("name");
                    // A computed key keeps __proto__ an own data property.
                    if (key.equals("__proto__")) output.text.append("[\"__proto__\"]"); else output.text.append(JavaScriptPrinter.quote(key));
                    output.text.append(": ");
                    if (attribute.children().isEmpty()) output.text.append("true");
                    else {
                        SyntaxNode value = attribute.children().get(0);
                        if (value.kind().equals("jsxString")) {
                            String raw = context.slice(value); output.text.append(JavaScriptPrinter.quote(JsxEntities.decode(raw.substring(1, raw.length() - 1))));
                        } else { output.text.append('('); output.keep(value); output.text.append(')'); }
                    }
                }
            }
            output.text.append('}');
        }
        for (SyntaxNode child : children) {
            if (child.kind().equals("jsxText")) {
                String text = text(context.slice(child));
                if (!text.isEmpty()) output.text.append(", ").append(JavaScriptPrinter.quote(JsxEntities.decode(text)));
            } else if (child.kind().equals("jsxSpreadChild")) {
                output.text.append(", ...("); output.keep(child.children().get(0)); output.text.append(')');
            } else if (child.kind().equals("jsxExpression")) {
                if (!child.children().isEmpty()) { output.text.append(", ("); output.keep(child.children().get(0)); output.text.append(')'); }
            } else { output.text.append(", "); output.keep(child); }
        }
        output.text.append(')'); output.finish(node.range().end());
    }
    private static String text(String raw) {
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace('\t', ' ');
            if (i > 0) line = line.replaceFirst("^ +", "");
            if (i + 1 < lines.length) line = line.replaceFirst(" +$", "");
            if (!line.isEmpty()) { if (result.length() > 0 && i > 0) result.append(' '); result.append(line); }
        }
        return result.toString();
    }
    private static final class Writer {
        final TransformationContext context; final StringBuilder text = new StringBuilder(); int cursor;
        Writer(TransformationContext context, int cursor) { this.context = context; this.cursor = cursor; }
        void keep(SyntaxNode node) {
            finish(node.range().start()); cursor = node.range().end();
        }
        void finish(int end) {
            if (end != cursor || text.length() > 0) context.replace(new SourceRange(cursor, end), text.toString());
            text.setLength(0); cursor = end;
        }
    }
}
