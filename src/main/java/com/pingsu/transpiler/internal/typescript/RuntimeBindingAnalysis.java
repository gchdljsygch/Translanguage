package com.pingsu.transpiler.internal.typescript;

import com.pingsu.transpiler.TransformationContext;
import com.pingsu.transpiler.internal.js.ScopeAnalysis;
import com.pingsu.transpiler.internal.js.StringLiterals;
import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.*;

/** File-local value bindings. References are resolved only after all declarations are collected. */
public final class RuntimeBindingAnalysis {
    private static final Set<String> FUNCTIONS = Set.of("functionDeclaration", "functionExpression",
            "arrowFunction", "methodDeclaration", "constructorDeclaration");
    private static final class NamespaceBindings {
        final Set<String> exports = new HashSet<>();
        final List<Scope> bodies = new ArrayList<>();
        final Map<String, NamespaceBindings> children = new HashMap<>();
    }
    private static final class Scope {
        final Scope parent;
        final boolean varBoundary;
        final Set<String> bindings = new HashSet<>();
        final Map<String, Scope> enums = new HashMap<>();
        final Map<String, NamespaceBindings> namespaces = new HashMap<>();
        Map<String, NamespaceBindings> exportedNamespaces = namespaces;
        Scope(Scope parent, boolean varBoundary) { this.parent = parent; this.varBoundary = varBoundary; }
        Scope resolve(String name) {
            for (Scope scope = this; scope != null; scope = scope.parent)
                if (scope.bindings.contains(name)) return scope;
            return null;
        }
        Scope varOwner() {
            Scope scope = this;
            while (!scope.varBoundary) scope = scope.parent;
            return scope;
        }
    }
    private record Reference(Scope scope, String name) { }
    private final TransformationContext context;
    private final List<Reference> references = new ArrayList<>();
    private final List<Scope> evalScopes = new ArrayList<>();
    private final Set<String> importedReferences = new HashSet<>();

    public RuntimeBindingAnalysis(TransformationContext context) {
        this.context = context;
        visit(context.program(), new Scope(null, true), false);
        for (Reference reference : references)
            if (reference.scope.resolve(reference.name) == null) importedReferences.add(reference.name);
    }

    /** Imports are the remaining outer bindings after local runtime declarations have been resolved. */
    public boolean usesImport(String name) {
        return importedReferences.contains(name) || evalScopes.stream()
                .anyMatch(scope -> scope.resolve("eval") == null && scope.resolve(name) == null);
    }

    private void visit(SyntaxNode node, Scope scope, boolean varBinding) {
        String kind = node.kind();
        for (String name : context.references(node)) reference(scope, name);
        if (kind.equals("jsxTag")) {
            String name = attribute(node, "name");
            if (!name.isEmpty() && !JsxNames.intrinsic(name)) reference(scope, name.split("\\.")[0]);
            return;
        }
        if (kind.equals("jsxTypeArguments")) return;
        if (TypeOnlySyntax.erased(kind) || kind.equals("importDeclaration") || kind.equals("typeSpecifier")) return;
        if (kind.equals("importEqualsDeclaration")) {
            if (!attribute(node,"typeOnly").equals("true")) visit(node.children().get(1),scope,false);
            return;
        }
        if (kind.equals("exportDeclaration")) {
            if (attribute(node, "typeOnly").equals("true")) return;
            if (!attribute(node, "moduleSource").isEmpty()) return; // Re-exports do not read local bindings.
            for (SyntaxNode child : node.children()) {
                // Named default declarations also introduce a binding outside the expression-shaped node.
                if (child.kind().equals("classExpression") || child.kind().equals("functionExpression")) bindName(child, scope);
                if (child.kind().equals("moduleItems")) {
                    for (SyntaxNode item : child.children()) if (item.kind().equals("moduleSpecifier"))
                        reference(scope, attribute(item, "localName"));
                } else visit(child, scope, false);
            }
            return;
        }
        if (kind.equals("namespaceExport") && node.children().get(0).kind().equals("namespaceDeclaration")) {
            visitNamespace(node.children().get(0), scope, true); return;
        }
        if (kind.equals("namespaceDeclaration")) { visitNamespace(node, scope, false); return; }
        if (kind.equals("enumDeclaration")) {
            String name = ScopeAnalysis.decode(attribute(node, "name"));
            scope.bindings.add(name);
            Scope owner = scope;
            Scope members = scope.enums.computeIfAbsent(name, ignored -> new Scope(owner, false));
            // Merged enums share their member environment, including references before declarations.
            for (SyntaxNode member : node.children()) {
                String raw = context.slice(member.children().get(0));
                members.bindings.add(raw.startsWith("\"") || raw.startsWith("'")
                        ? StringLiterals.decode(raw) : ScopeAnalysis.decode(raw));
            }
            for (SyntaxNode member : node.children())
                for (int i = 1; i < member.children().size(); i++) visit(member.children().get(i), members, false);
            return;
        }
        if (kind.equals("classDeclaration") || kind.equals("functionDeclaration")) bindName(node, scope);
        Scope enclosing = scope;
        if (FUNCTIONS.contains(kind)) {
            for (SyntaxNode c : node.children()) {
                if (c.kind().equals("decorator")) visit(c, enclosing, false);
                if (c.kind().equals("parameters")) for (SyntaxNode p : c.children())
                    for (SyntaxNode part : p.children()) if (part.kind().equals("decorator")) visit(part, enclosing, false);
            }
        }
        boolean objectMethod = kind.equals("objectMember") && node.children().stream().anyMatch(n -> n.kind().equals("functionBody"));
        if (FUNCTIONS.contains(kind) || objectMethod) {
            scope = new Scope(scope, true);
            if (!kind.equals("arrowFunction")) scope.bindings.add("arguments");
        } else if (kind.equals("functionBody") || kind.equals("staticBlock")) scope = new Scope(scope, true);
        else if (Set.of("block", "forStatement", "switchStatement", "classDeclaration", "classExpression").contains(kind))
            scope = new Scope(scope, false);

        if (scope != enclosing) varBinding = false;
        String binding = attribute(node, "bindingName");
        if (!binding.isEmpty()) (varBinding ? scope.varOwner() : scope).bindings.add(binding);
        else if (kind.equals("identifier")) reference(scope, ScopeAnalysis.decode(context.slice(node)));

        if (kind.equals("objectMember") && !node.children().isEmpty()
                && node.children().get(0).kind().equals("propertyName")
                && (node.children().size() == 1 || attribute(node, "shorthandDefault").equals("true")))
            reference(scope, ScopeAnalysis.decode(context.slice(node.children().get(0))));
        if (kind.equals("argumentsExpression") && isEval(node.children().get(0))) evalScopes.add(scope);

        Scope catchScope = null;
        for (int i = 0; i < node.children().size(); i++) {
            SyntaxNode child = node.children().get(i);
            if (child.kind().equals("decorator") && (FUNCTIONS.contains(kind) || kind.equals("parameter") || kind.equals("parameterProperty"))) continue;
            Scope childScope = scope;
            if ((kind.equals("methodDeclaration") || objectMethod) && child.kind().equals("computedName")) childScope = enclosing;
            if (kind.equals("switchStatement") && i == 0) childScope = enclosing;
            if (kind.equals("tryStatement") && !TypeOnlySyntax.erased(child.kind())) {
                if (!child.kind().equals("block")) {
                    if (catchScope == null) catchScope = new Scope(scope, false);
                    childScope = catchScope;
                } else if (catchScope != null) { childScope = catchScope; catchScope = null; }
            }
            boolean childVar = varBinding;
            if (kind.equals("variableStatement")) childVar = context.source().startsWith("var", node.range().start());
            if (kind.equals("variableDeclaration") && i > 0 || kind.equals("functionBody") || kind.equals("parameters")
                    || FUNCTIONS.contains(kind) || objectMethod || kind.equals("bindingInitializer")) childVar = false;
            visit(child, childScope, childVar);
        }
    }
    private void visitNamespace(SyntaxNode node, Scope scope, boolean exported) {
        String name = ScopeAnalysis.decode(attribute(node, "name")); scope.bindings.add(name);
        NamespaceBindings group = (exported ? scope.exportedNamespaces : scope.namespaces)
                .computeIfAbsent(name, ignored -> new NamespaceBindings());
        Scope members = new Scope(scope, true); members.exportedNamespaces = group.children; group.bodies.add(members);
        for (SyntaxNode child : node.children()) if (child.kind().equals("namespaceExport"))
            exportedNames(child.children().get(0), group.exports);
        for (Scope body : group.bodies) body.bindings.addAll(group.exports);
        for (SyntaxNode child : node.children()) visit(child, members, false);
    }
    private void exportedNames(SyntaxNode node, Set<String> result) {
        if (TypeOnlySyntax.erased(node.kind())) return;
        if (node.kind().equals("decoratedDeclaration")) { exportedNames(node.children().get(node.children().size() - 1), result); return; }
        if (node.kind().equals("namespaceDeclaration") || node.kind().equals("enumDeclaration")) {
            result.add(ScopeAnalysis.decode(attribute(node, "name"))); return;
        }
        if (node.kind().equals("variableStatement")) {
            for (SyntaxNode variable : node.children()) bindingNames(variable.children().get(0), result);
        } else for (SyntaxNode child : node.children()) if (!attribute(child, "bindingName").isEmpty()) result.add(attribute(child, "bindingName"));
    }
    private void bindingNames(SyntaxNode node, Set<String> result) {
        if (node.kind().equals("bindingInitializer") || node.kind().equals("computedName")) return;
        if (!attribute(node, "bindingName").isEmpty()) result.add(attribute(node, "bindingName"));
        else for (SyntaxNode child : node.children()) bindingNames(child, result);
    }
    private void bindName(SyntaxNode node, Scope scope) {
        node.children().stream().filter(n -> !attribute(n, "bindingName").isEmpty()).findFirst()
                .ifPresent(name -> scope.bindings.add(attribute(name, "bindingName")));
    }
    private boolean isEval(SyntaxNode node) {
        while (Set.of("parenthesizedExpression", "assertionExpression", "nonNullExpression", "instantiationExpression").contains(node.kind())) {
            node = node.children().stream().filter(n -> !TypeOnlySyntax.erased(n.kind())).findFirst().orElseThrow();
        }
        return node.kind().equals("identifier") && ScopeAnalysis.decode(context.slice(node)).equals("eval");
    }
    private void reference(Scope scope, String name) { if (!name.isEmpty()) references.add(new Reference(scope, name)); }
    static String attribute(SyntaxNode node, String key) { return node instanceof ParsedNode parsed ? parsed.attribute(key) : ""; }
}