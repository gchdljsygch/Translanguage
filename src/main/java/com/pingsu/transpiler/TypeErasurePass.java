package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.syntax.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Type erasure and parameter properties over the library's immutable syntax tree. */
public final class TypeErasurePass implements TransformationPass {
    private static final Set<String> DECLARATIONS = Set.of("interfaceDeclaration", "typeAliasDeclaration", "ambientDeclaration", "overloadDeclaration", "indexMemberDeclaration");
    private static final Set<String> ERASED = Set.of("typeAnnotation", "typeParameters", "typeArguments", "implementsClause", "typeModifier", "typeAssertion", "thisParameter");
    @Override public void transform(TransformationContext context) {
        boolean[] erasedModule = {false};
        context.program().walk(node -> {
            if (node instanceof ParsedNode parsed) {
                if (node.kind().equals("importDeclaration") || node.kind().equals("exportDeclaration")) {
                    SyntaxNode items = node.children().stream().filter(n -> n.kind().equals("moduleItems")).findFirst().orElse(null);
                    boolean allTypes = items != null && !items.children().isEmpty()
                            && items.children().stream().allMatch(n -> n.kind().equals("typeSpecifier"))
                            && !parsed.attribute("defaultBinding").equals("true");
                    if (parsed.attribute("typeOnly").equals("true") || allTypes
                            || node.children().stream().anyMatch(n -> DECLARATIONS.contains(n.kind()))) {
                        context.remove(node); erasedModule[0] = true; return false;
                    }
                }
                if (node.kind().equals("moduleItems")) {
                    if (node.children().stream().anyMatch(n -> n.kind().equals("typeSpecifier"))) {
                        context.replace(node, "{ " + node.children().stream().filter(n -> !n.kind().equals("typeSpecifier"))
                                .map(context::slice).collect(Collectors.joining(", ")) + " }");
                    }
                    return false;
                }
                if (node.kind().equals("constructorDeclaration")) parameterProperties(context, parsed);
            }
            if (DECLARATIONS.contains(node.kind()) || ERASED.contains(node.kind())) { context.remove(node); return false; }
            return !node.kind().equals("enumDeclaration");
        });
        if (erasedModule[0]) context.append("\nexport {};\n");
    }
    private static void parameterProperties(TransformationContext context, ParsedNode constructor) {
        SyntaxNode parameters = constructor.children().stream().filter(n -> n.kind().equals("parameters")).findFirst().orElseThrow();
        SyntaxNode body = constructor.children().stream().filter(n -> n.kind().equals("functionBody")).findFirst().orElseThrow();
        List<String> assignments = new ArrayList<>();
        for (SyntaxNode node : parameters.children()) {
            if (!node.kind().equals("parameterProperty")) continue;
            ParsedNode parameter = (ParsedNode) node;
            if (!parameter.attribute("simple").equals("true") || parameter.attribute("rest").equals("true")) {
                context.unsupported(node, "A parameter property must have a simple identifier."); continue;
            }
            String name = parameter.attribute("name"); assignments.add("this." + name + " = " + name + ";");
        }
        if (assignments.isEmpty()) return;
        if (constructor.attribute("derived").equals("true")) {
            List<? extends SyntaxNode> directCalls = body.children().stream().filter(TypeErasurePass::directSuperCall).toList();
            int[] calls = {0};
            body.walk(n -> {
                if (Set.of("functionExpression", "functionDeclaration", "arrowFunction", "classDeclaration", "classExpression").contains(n.kind())) return false;
                if (n.kind().equals("argumentsExpression") && n.children().get(0).kind().equals("superExpression")) calls[0]++;
                return true;
            });
            if (directCalls.size() != 1 || calls[0] != 1) {
                context.unsupported(constructor, "Derived parameter properties require a direct super() statement in the constructor."); return;
            }
            context.insert(directCalls.get(0).range().end(), ";\n" + String.join("\n", assignments) + "\n");
        } else {
            context.insert(body.range().start() + 1, "\n" + String.join("\n", assignments) + "\n");
        }
    }
    private static boolean directSuperCall(SyntaxNode node) {
        if (!node.kind().equals("expressionStatement") || node.children().size() != 1) return false;
        SyntaxNode expression = node.children().get(0);
        return expression.kind().equals("argumentsExpression") && expression.children().get(0).kind().equals("superExpression");
    }
}
