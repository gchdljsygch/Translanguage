package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.Set;

/** Emits runtime enum objects from parsed literal initializers, including const enums. */
public final class EnumLoweringPass implements TransformationPass {
    @Override public void transform(TransformationContext context) {
        context.program().walk(node -> {
            if (Set.of("ambientDeclaration", "interfaceDeclaration", "typeAliasDeclaration").contains(node.kind())) return false;
            if (!node.kind().equals("enumDeclaration")) return true;
            String name = ((ParsedNode) node).attribute("name");
            StringBuilder output = new StringBuilder("var " + name + ";\n(function (" + name + ") {\n");
            String nextValue = "0";
            for (SyntaxNode member : node.children()) {
                SyntaxNode property = member.children().get(0); String raw = context.slice(property);
                boolean quoted = raw.startsWith("\"") || raw.startsWith("'");
                if (!property.kind().equals("propertyName") || !quoted && Character.isDigit(raw.charAt(0))) {
                    context.unsupported(member, "Enum member names must be identifiers or string literals."); continue;
                }
                String key = quoted ? raw : "\"" + raw + "\"";
                SyntaxNode initializer = member.children().size() == 1 ? null : member.children().get(1);
                boolean stringValue = initializer != null && initializer.kind().equals("stringLiteral");
                if (initializer != null && !stringValue && !isNumber(initializer)) {
                    context.unsupported(member, "Computed enum initializers are not supported; use numeric or string literals."); continue;
                }
                String value = initializer == null ? nextValue : context.slice(initializer);
                if (value == null) { context.unsupported(member, "An enum member after a string value needs an initializer."); continue; }
                String access = name + "[" + key + "]";
                if (stringValue) output.append("  ").append(access).append(" = ").append(value).append(";\n");
                else output.append("  ").append(name).append("[").append(access).append(" = ").append(value).append("] = ").append(key).append(";\n");
                nextValue = stringValue ? null : access + " + 1";
            }
            output.append("})(").append(name).append(" || (").append(name).append(" = {}));");
            context.replace(node, output.toString()); return false;
        });
    }
    private static boolean isNumber(SyntaxNode node) {
        if (node.kind().equals("numericLiteral")) return true;
        return node instanceof ParsedNode parsed && node.kind().equals("unaryExpression")
                && Set.of("+", "-").contains(parsed.attribute("operator")) && isNumber(node.children().get(0));
    }
}
