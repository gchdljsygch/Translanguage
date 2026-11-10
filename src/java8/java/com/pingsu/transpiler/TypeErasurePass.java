package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.internal.lowering.SuperCallSites;
import com.pingsu.transpiler.internal.js.JsNode;
import com.pingsu.transpiler.internal.js.JsProgram;
import com.pingsu.transpiler.internal.js.ScopeAnalysis;
import com.pingsu.transpiler.syntax.*;
import com.pingsu.transpiler.internal.typescript.ImportElision;
import static com.pingsu.transpiler.internal.typescript.TypeOnlySyntax.DECLARATIONS;
import static com.pingsu.transpiler.internal.typescript.TypeOnlySyntax.ERASED;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Type erasure, import elision and parameter properties over the immutable syntax tree. */
public final class TypeErasurePass implements TransformationPass {
    @Override public void transform(TransformationContext context) {
        boolean[] erasedModule = {false}, runtimeModule = {false};
        ImportElision imports = new ImportElision(context);
        context.program().walk(node -> {
            if (node.kind().equals("decorator") || node.kind().equals("jsxTypeArguments")) return false;
            if (node instanceof ParsedNode) {
                ParsedNode parsed = (ParsedNode) node;
                if (node.kind().equals("importDeclaration")) {
                    if (imports.transform(parsed)) runtimeModule[0] = true;
                    else erasedModule[0] = true;
                    return false;
                }
                if (node.kind().equals("exportDeclaration")) {
                    SyntaxNode items = node.children().stream().filter(n -> n.kind().equals("moduleItems")).findFirst().orElse(null);
                    boolean allTypes = items != null && !items.children().isEmpty()
                            && items.children().stream().allMatch(n -> n.kind().equals("typeSpecifier"))
                            && !parsed.attribute("defaultBinding").equals("true");
                    if (parsed.attribute("typeOnly").equals("true") || allTypes
                            || node.children().stream().anyMatch(n -> DECLARATIONS.contains(n.kind()))) {
                        context.remove(node); erasedModule[0] = true; return false;
                    }
                    runtimeModule[0] = true;
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
        if (erasedModule[0] && !runtimeModule[0]) context.append("\nexport {};\n");
    }
    private static void parameterProperties(TransformationContext context, ParsedNode constructor) {
        SyntaxNode parameters = constructor.children().stream().filter(n -> n.kind().equals("parameters")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        SyntaxNode body = constructor.children().stream().filter(n -> n.kind().equals("functionBody")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
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
            List<SyntaxNode> calls = SuperCallSites.collect(body, SyntaxNode::kind, SyntaxNode::children);
            if (!SuperCallSites.collect(parameters, SyntaxNode::kind, SyntaxNode::children).isEmpty()) {
                context.unsupported(constructor, "Parameter properties with super() in parameter initializers are not supported."); return;
            }
            List<? extends SyntaxNode> directCalls = body.children().stream().filter(TypeErasurePass::directSuperCall).collect(Compat.toList());
            if (calls.size() == 1 && directCalls.size() == 1) {
                context.insert(directCalls.get(0).range().end(), ";\n" + String.join("\n", assignments) + "\n");
            } else if (!calls.isEmpty()) {
                ScopeAnalysis scopes = new ScopeAnalysis(new JsProgram(context.sourceName(), context.source(),
                        JsNode.from(context.program()), context.tokens()));
                if (scopes.dynamic()) {
                    context.unsupported(constructor, "Parameter property initialization across control flow with direct eval or with cannot preserve lexical visibility."); return;
                }
                // Capture the constructor's parameter bindings, even when a branch or arrow
                // shadows their names. The arrow does not read this until super succeeds.
                String initialize = scopes.fresh("parameters");
                context.insert(directiveEnd(body), "\nconst " + initialize + " = () => {" + String.join("", assignments) + "};\n");
                for (SyntaxNode call : calls) {
                    context.insert(call.range().start(), "(");
                    context.insert(call.range().end(), ", " + initialize + "(), this)");
                }
            }
        } else {
            context.insert(directiveEnd(body), "\n" + String.join("\n", assignments) + "\n");
        }
    }
    private static int directiveEnd(SyntaxNode body) {
        int position = body.range().start() + 1;
        for (SyntaxNode statement : body.children()) {
            if (statement.kind().equals("expressionStatement") && statement.children().get(0).kind().equals("stringLiteral")) position = statement.range().end();
            else break;
        }
        return position;
    }
    private static boolean directSuperCall(SyntaxNode node) {
        if (!node.kind().equals("expressionStatement") || node.children().size() != 1) return false;
        SyntaxNode expression = node.children().get(0);
        return expression.kind().equals("argumentsExpression") && expression.children().get(0).kind().equals("superExpression");
    }
}
