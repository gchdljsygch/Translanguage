package com.pingsu.transpiler;

import com.pingsu.antlr.TypeScriptParser.*;
import com.pingsu.antlr.TypeScriptParserBaseVisitor;

/** Emits runtime enum objects. Literal initializers only; computed values fail explicitly. */
public final class EnumLoweringPass implements TransformationPass {
    @Override public void transform(TransformationContext context) {
        new TypeScriptParserBaseVisitor<Void>() {
            @Override public Void visitAmbientDeclaration(AmbientDeclarationContext node) { return null; }
            @Override public Void visitNamespaceDeclaration(NamespaceDeclarationContext node) { return null; }
            @Override public Void visitInterfaceDeclaration(InterfaceDeclarationContext node) { return null; }
            @Override public Void visitTypeAliasDeclaration(TypeAliasDeclarationContext node) { return null; }

            @Override public Void visitEnumDeclaration(EnumDeclarationContext node) {
                String name = node.identifier().getText();
                StringBuilder output = new StringBuilder("var " + name + ";\n(function (" + name + ") {\n");
                String nextValue = "0";
                if (node.enumBody() != null) {
                    for (EnumMemberContext member : node.enumBody().enumMemberList().enumMember()) {
                        PropertyNameContext property = member.propertyName();
                        if (property.identifierName() == null && property.StringLiteral() == null) {
                            context.unsupported(member, "Enum member names must be identifiers or string literals."); continue;
                        }
                        String key = property.StringLiteral() != null ? property.getText() : "\"" + property.getText() + "\"";
                        SingleExpressionContext initializer = member.singleExpression();
                        String value = initializer == null ? nextValue : context.tokens().getText(initializer);
                        boolean stringValue = initializer instanceof LiteralExpressionContext literal
                                && literal.literal().StringLiteral() != null;
                        if (initializer != null && !stringValue && !isNumber(initializer)) {
                            context.unsupported(member, "Computed enum initializers are not supported; use numeric or string literals."); continue;
                        }
                        if (value == null) {
                            context.unsupported(member, "An enum member after a string value needs an initializer."); continue;
                        }
                        String access = name + "[" + key + "]";
                        if (stringValue) output.append("  ").append(access).append(" = ").append(value).append(";\n");
                        else output.append("  ").append(name).append("[").append(access).append(" = ")
                                .append(value).append("] = ").append(key).append(";\n");
                        nextValue = stringValue ? null : access + " + 1";
                    }
                }
                output.append("})(").append(name).append(" || (").append(name).append(" = {}));");
                context.replace(node, output.toString());
                return null;
            }
        }.visit(context.program());
    }

    private static boolean isNumber(SingleExpressionContext expression) {
        if (expression instanceof LiteralExpressionContext literal) return literal.literal().numericLiteral() != null;
        if (expression instanceof UnaryMinusExpressionContext unary) return isNumber(unary.singleExpression());
        if (expression instanceof UnaryPlusExpressionContext unary) return isNumber(unary.singleExpression());
        return false;
    }
}
