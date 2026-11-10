package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.emit.JavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import com.pingsu.transpiler.internal.parser.*;
import java.util.*;

/** Lowers internal TypeScript namespaces after type/enum edits have been materialized. */
public final class NamespaceLoweringPass implements TransformationPass {
    @Override public void transform(TransformationContext context) {
        boolean[] found = {false};
        context.program().walk(n -> { if (n.kind().equals("namespaceDeclaration")) found[0] = true; return true; });
        if (!found[0]) return;
        String source = context.render();
        SourceParser.Result parsed = new RecursiveDescentParser().parse(context.sourceName(), source, SourceParser.Language.TYPESCRIPT);
        if (!parsed.successful()) { context.reportDiagnostics(parsed.diagnostics()); return; }
        JsProgram program = new JsProgram(context.sourceName(), source, JsNode.from(parsed.program()), parsed.program().tokens());
        Printer printer = new Printer(program);
        if (printer.scopes.dynamic() && printer.namespaces.values().stream().anyMatch(n -> n.runtime)) { context.unsupported(context.program(), "Namespace lowering with direct eval or with cannot preserve lexical bindings"); return; }
        String output = printer.print();
        SourceParser.Result checked = new RecursiveDescentParser().parse(context.sourceName(), output, SourceParser.Language.TYPESCRIPT);
        if (!checked.successful()) context.reportDiagnostics(checked.diagnostics());
        else context.rewrite(output);
    }

    private static final class Group {
        final Set<String> exports = new LinkedHashSet<>();
        boolean exported;
    }
    private static final class Namespace {
        final Group group;
        final String receiver;
        final Set<String> variables = new HashSet<>();
        boolean runtime;
        Namespace(Group group, String receiver) { this.group = group; this.receiver = receiver; }
    }
    private static final class Printer extends JavaScriptPrinter {
        final ScopeAnalysis scopes;
        final Map<JsNode, Namespace> namespaces = new IdentityHashMap<>();
        final Map<ScopeAnalysis.Scope, Namespace> environments = new IdentityHashMap<>();
        final Map<Object, Map<String, Group>> groups = new IdentityHashMap<>();
        final Map<JsNode, JsNode> parents = new IdentityHashMap<>();
        final Map<ScopeAnalysis.Scope, Set<String>> lexical = new IdentityHashMap<>();
        final Map<ScopeAnalysis.Scope, Set<String>> declared = new IdentityHashMap<>();
        Printer(JsProgram program) {
            super(program); scopes = new ScopeAnalysis(program); collect(program.root());
            indexLexical(program.root());
        }
        private void bindings(JsNode node, Set<String> result) {
            if (!node.attribute("bindingName").isEmpty()) { result.add(node.attribute("bindingName")); return; }
            if (node.is("bindingInitializer") || node.is("parameterInitializer") || node.is("computedName")) return;
            for (JsNode child : node.children()) bindings(child, result);
        }
        private String declaredName(JsNode node) {
            if (node.is("decoratedDeclaration")) return declaredName(node.child(node.children().size() - 1));
            if (node.is("namespaceDeclaration") || node.is("enumDeclaration")) return ScopeAnalysis.decode(node.attribute("name"));
            for (JsNode child : node.children()) if (!child.attribute("bindingName").isEmpty()) return child.attribute("bindingName");
            return "";
        }
        private void collect(JsNode node) {
            if (node.is("namespaceDeclaration")) {
                ScopeAnalysis.Scope parent = scopes.scope(node).parent();
                Namespace enclosing = environments.get(parent);
                Object container = enclosing != null && parents.get(node).is("namespaceExport") ? enclosing.group : parent;
                Group group = groups.computeIfAbsent(container, k -> new LinkedHashMap<>())
                        .computeIfAbsent(declaredName(node), k -> new Group());
                Namespace namespace = new Namespace(group, scopes.fresh("namespace"));
                namespaces.put(node, namespace); environments.put(scopes.scope(node), namespace);
                for (JsNode statement : node.children()) {
                    boolean exported = statement.is("namespaceExport");
                    JsNode declaration = exported ? statement.child(0) : statement;
                    Set<String> names = new HashSet<>();
                    if (declaration.is("variableStatement")) {
                        for (JsNode variable : declaration.children()) bindings(variable.child(0), names);
                        if (exported) namespace.variables.addAll(names);
                    } else if (!declaredName(declaration).isEmpty()) names.add(declaredName(declaration));
                    if (exported) group.exports.addAll(names);
                    if (!declaration.is("emptyStatement") && !declaration.is("namespaceDeclaration")) namespace.runtime = true;
                }
            }
            for (JsNode child : node.children()) { parents.put(child, node); collect(child); }
            if (node.is("namespaceDeclaration")) for (JsNode statement : node.children()) {
                JsNode declaration = statement.is("namespaceExport") ? statement.child(0) : statement;
                if (declaration.is("namespaceDeclaration") && namespaces.get(declaration).runtime) namespaces.get(node).runtime = true;
            }
        }
        private void indexLexical(JsNode node) {
            if (node.is("classDeclaration") || node.is("functionDeclaration"))
                lexical.computeIfAbsent(scopes.scope(node).parent(), k -> new HashSet<>()).add(declaredName(node));
            if (node.is("variableStatement") && !program.significantTokens(node).get(0).text().equals("var"))
                for (JsNode variable : node.children()) bindings(variable.child(0), lexical.computeIfAbsent(scopes.scope(node), k -> new HashSet<>()));
            for (JsNode child : node.children()) indexLexical(child);
        }
        private String reference(JsNode node, String name) {
            ScopeAnalysis.Scope scope = scopes.scope(node), resolved = scope.resolve(name);
            for (ScopeAnalysis.Scope current = scope; current != null; current = current.parent()) {
                Namespace ns = environments.get(current);
                if (ns != null && (ns.variables.contains(name) || ns.group.exports.contains(name) && !current.bindings().contains(name)))
                    return ns.receiver + "[" + quote(name) + "]";
                if (current == resolved) return null;
            }
            return null;
        }
        @Override protected String render(JsNode node) {
            if (node.is("exportDeclaration") && node.children().size() == 1 && node.child(0).is("namespaceDeclaration"))
                return namespace(node.child(0), true, null);
            if (node.is("namespaceDeclaration")) return namespace(node, false, null);
            if (node.is("decoratedDeclaration") && parents.get(node) != null && parents.get(node).is("namespaceExport")) {
                StringBuilder result = new StringBuilder();
                for (JsNode child : node.children()) result.append(render(child)).append('\n');
                return result.toString();
            }
            if (node.is("namespaceExport")) {
                Namespace ns = environments.get(scopes.scope(node));
                JsNode declaration = node.child(0);
                if (declaration.is("namespaceDeclaration")) return namespace(declaration, false, ns.receiver);
                if (declaration.is("variableStatement")) {
                    StringBuilder result = new StringBuilder();
                    for (JsNode variable : declaration.children()) {
                        JsNode binding = variable.child(0);
                        if (variable.children().size() == 1) continue;
                        result.append('(').append(binding(binding, ns)).append(" = ").append(render(variable.child(1))).append(");\n");
                    }
                    return result.toString();
                }
                String name = declaredName(declaration);
                return render(declaration) + (name.isEmpty() ? "" : ";" + ns.receiver + "[" + quote(name) + "] = " + name + ";");
            }
            if (node.is("identifier") && node.attribute("bindingName").isEmpty()) {
                String replacement = reference(node, ScopeAnalysis.decode(text(node)));
                if (replacement != null) return replacement;
            }
            if (node.is("objectMember") && node.child(0).is("propertyName")
                    && (node.children().size() == 1 || node.attribute("shorthandDefault").equals("true"))) {
                String replacement = reference(node.child(0), ScopeAnalysis.decode(text(node.child(0))));
                if (replacement != null) return text(node.child(0)) + ":" + replacement
                        + (node.children().size() > 1 ? "=" + render(node.child(1)) : "");
            }
            return super.render(node);
        }
        private String binding(JsNode node, Namespace ns) {
            if (!node.attribute("bindingName").isEmpty()) {
                String access = ns.receiver + "[" + quote(node.attribute("bindingName")) + "]";
                return node.is("propertyName") ? text(node) + ":" + access : access;
            }
            if (node.is("bindingInitializer") || node.is("computedName")) return render(node);
            return children(node, child -> binding(child, ns));
        }
        private String namespace(JsNode node, boolean exporting, String owner) {
            Namespace ns = namespaces.get(node);
            if (!ns.runtime) return exporting ? "export {};" : "";
            String name = node.attribute("name"), decoded = ScopeAnalysis.decode(name);
            String property = owner == null ? name : owner + "[" + quote(decoded) + "]";
            ScopeAnalysis.Scope parent = scopes.scope(node).parent();
            boolean existing = lexical.containsKey(parent) && lexical.get(parent).contains(decoded);
            StringBuilder result = new StringBuilder();
            boolean first = declared.computeIfAbsent(parent, k -> new HashSet<>()).add(decoded);
            if (first && !existing) {
                result.append(exporting ? "export var " : "var ").append(name).append(";\n");
                ns.group.exported = exporting;
            } else if (exporting && !ns.group.exported && !alreadyExported(parent, decoded)) {
                result.append("export {").append(name).append("};\n"); ns.group.exported = true;
            }
            result.append("(function (").append(ns.receiver).append(") {\n");
            for (JsNode statement : node.children()) result.append(render(statement)).append('\n');
            result.append("})(");
            if (owner != null) result.append(name).append(" = ");
            result.append(property).append(" || (").append(property).append(" = {}));");
            return result.toString();
        }
        private boolean alreadyExported(ScopeAnalysis.Scope scope, String name) {
            for (Map.Entry<JsNode, JsNode> entry : parents.entrySet())
                if (entry.getValue().is("exportDeclaration") && !entry.getKey().is("namespaceDeclaration")
                        && declaredName(entry.getKey()).equals(name)
                        && (scopes.scope(entry.getKey()) == scope || scopes.scope(entry.getKey()).parent() == scope)) return true;
            return false;
        }
    }
}