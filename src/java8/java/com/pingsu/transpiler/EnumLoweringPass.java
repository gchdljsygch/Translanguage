package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.compat.Unicode17;
import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.emit.JavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import com.pingsu.transpiler.internal.parser.*;
import com.pingsu.transpiler.syntax.*;
import java.util.*;

/** Emits sequential runtime enum evaluation, including const enums. */
public final class EnumLoweringPass implements TransformationPass {
    private enum Constant { NUMBER, STRING, COMPUTED }
    private static final class Binding {
        private final Map<String, String> keys;
        private final Map<String, Constant> constants;
        private Binding(Map<String, String> keys, Map<String, Constant> constants) {
            this.keys = keys;
            this.constants = constants;
        }
        public Map<String, String> keys() { return keys; }
        public Map<String, Constant> constants() { return constants; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Binding)) return false;
            Binding value = (Binding) other;
            return java.util.Objects.equals(keys, value.keys) && java.util.Objects.equals(constants, value.constants);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(keys);
            hash = 31 * hash + java.util.Objects.hashCode(constants);
            return hash;
        }
        @Override public String toString() { return "Binding[keys=" + keys + ", constants=" + constants + "]"; }

        Binding() { this(new LinkedHashMap<>(), new HashMap<>()); }

    }

    @Override public void transform(TransformationContext context) {
        JsProgram program = new JsProgram(context.sourceName(), context.source(), JsNode.from(context.program()), context.tokens());
        ScopeAnalysis names = new ScopeAnalysis(program);
        Map<Integer, Binding> bindings = new HashMap<>();
        collectBindings(program.root(), program, names, new IdentityHashMap<>(), bindings);
        transform(context, names, bindings);
    }

    private void transform(TransformationContext context, ScopeAnalysis names, Map<Integer, Binding> bindings) {
        context.program().walk(node -> {
            if (Compat.set("ambientDeclaration", "interfaceDeclaration", "typeAliasDeclaration").contains(node.kind())) return false;
            if (!node.kind().equals("enumDeclaration")) return true;
            lower(context, node, names, bindings);
            return false;
        });
    }

    private static void collectBindings(JsNode node, JsProgram program, ScopeAnalysis scopes,
                                        Map<ScopeAnalysis.Scope, Map<String, Binding>> environments,
                                        Map<Integer, Binding> declarations) {
        if (node.is("ambientDeclaration")) return;
        if (node.is("enumDeclaration")) {
            Binding binding = environments.computeIfAbsent(scopes.scope(node), ignored -> new HashMap<>())
                    .computeIfAbsent(ScopeAnalysis.decode(node.attribute("name")), ignored -> new Binding());
            declarations.put(node.range().start(), binding);
            for (JsNode member : node.children()) {
                JsNode key = member.child(0);
                String raw = program.text(key);
                if (!key.is("propertyName") || Unicode17.isDigit(raw.charAt(0))) continue;
                boolean quoted = raw.startsWith("\"") || raw.startsWith("'");
                String decoded = quoted ? StringLiterals.decode(raw) : ScopeAnalysis.decode(raw);
                binding.keys().put(decoded, quoted ? raw : JavaScriptPrinter.quote(decoded));
            }
        }
        for (JsNode child : node.children()) collectBindings(child, program, scopes, environments, declarations);
    }

    private void lower(TransformationContext context, SyntaxNode node, ScopeAnalysis names, Map<Integer, Binding> bindings) {
        boolean literals = node.children().stream().allMatch(m -> m.children().size() == 1 || literal(m.children().get(1)));
        String name = ((ParsedNode) node).attribute("name"), receiver = literals ? name : names.fresh("enum");
        Binding binding = bindings.get(node.range().start());
        Map<String, String> keys = binding.keys();
        for (SyntaxNode member : node.children()) {
            SyntaxNode property = member.children().get(0);
            String raw = context.slice(property);
            boolean quoted = raw.startsWith("\"") || raw.startsWith("'");
            if (!property.kind().equals("propertyName") || !quoted && Unicode17.isDigit(raw.charAt(0))) {
                context.unsupported(member, "Enum member names must be identifiers or string literals."); return;
            }
            String decoded = quoted ? StringLiterals.decode(raw) : ScopeAnalysis.decode(raw);
            keys.put(decoded, quoted ? raw : JavaScriptPrinter.quote(decoded));
        }
        StringBuilder output = new StringBuilder("var " + name + ";\n" + (literals
                ? "(function (" + receiver + ") {\n" : "((" + receiver + ") => {\n"));
        Map<String, Constant> constants = binding.constants();
        String nextValue = "0";
        for (SyntaxNode member : node.children()) {
            String raw = context.slice(member.children().get(0));
            String memberName = raw.startsWith("\"") || raw.startsWith("'") ? StringLiterals.decode(raw) : ScopeAnalysis.decode(raw);
            String key = keys.get(memberName), access = receiver + "[" + key + "]";
            SyntaxNode initializer = member.children().size() == 1 ? null : member.children().get(1);
            Constant constant = Constant.NUMBER;
            String value = nextValue;
            if (initializer != null && literal(initializer)) {
                value = context.slice(initializer);
                constant = initializer.kind().equals("stringLiteral") ? Constant.STRING : Constant.NUMBER;
            } else if (initializer != null) {
                // Use an isolated edit set for typed callbacks and nested classes/enums.
                Program subtree = new Program(context.program().range(), Compat.list(initializer), context.tokens());
                TransformationContext nested = new TransformationContext(context.sourceName(), context.source(), subtree, context.target());
                new TypeErasurePass().transform(nested);
                if (nested.diagnostics().isEmpty()) transform(nested, names, bindings);
                if (!nested.diagnostics().isEmpty()) {
                    context.reportDiagnostics(nested.diagnostics());
                    return;
                }
                String source = "(" + nested.render(initializer.range()) + ");";
                com.pingsu.transpiler.internal.parser.SourceParser.Result parsed = new RecursiveDescentParser().parse(context.sourceName(), source, SourceParser.Language.JAVASCRIPT);
                if (!parsed.successful()) {
                    context.unsupported(initializer, "Enum initializers must be synchronous expressions: " + parsed.diagnostics().get(0).message()); return;
                }
                JsProgram expression = new JsProgram(context.sourceName(), source, JsNode.from(parsed.program()), parsed.program().tokens());
                InitializerPrinter printer = new InitializerPrinter(expression, receiver, keys);
                if (printer.scopes.dynamic()) {
                    context.unsupported(initializer, "Computed enum initializers with direct eval or with cannot preserve member bindings."); return;
                }
                JsNode root = expression.root().child(0).child(0).child(0);
                if (suspends(root)) {
                    context.unsupported(initializer, "Enum initializers cannot suspend with await or yield."); return;
                }
                constant = printer.constant(root, ScopeAnalysis.decode(name), constants);
                value = printer.render(root);
            }
            if (value == null) {
                context.unsupported(member, "An enum member after a string or computed value needs an initializer."); return;
            }
            if (constant == Constant.STRING) output.append("  ").append(access).append(" = ").append(value).append(";\n");
            else if (constant == Constant.NUMBER) output.append("  ").append(receiver).append("[").append(access).append(" = ").append(value).append("] = ").append(key).append(";\n");
            else {
                String evaluated = names.fresh("enumValue");
                output.append("  const ").append(evaluated).append(" = ").append(access).append(" = ").append(value).append(";\n")
                        .append("  if (typeof ").append(evaluated).append(" === \"number\") ").append(receiver).append("[").append(evaluated).append("] = ").append(key).append(";\n");
            }
            constants.put(memberName, constant);
            nextValue = constant == Constant.NUMBER ? access + " + 1" : null;
        }
        output.append("})(").append(name).append(" || (").append(name).append(" = {}));");
        context.replace(node, output.toString());
    }

    private static boolean suspends(JsNode node) {
        if (Compat.set("functionExpression", "functionDeclaration", "arrowFunction", "methodDeclaration", "constructorDeclaration").contains(node.kind())) return false;
        if (node.is("objectMember") && node.children().stream().anyMatch(c -> c.is("functionBody")))
            return node.child(0).is("computedName") && suspends(node.child(0));
        return node.is("yieldExpression") || node.is("unaryExpression") && node.attribute("operator").equals("await")
                || node.children().stream().anyMatch(EnumLoweringPass::suspends);
    }

    private static boolean literal(SyntaxNode node) {
        if (node.kind().equals("numericLiteral") || node.kind().equals("stringLiteral")) return true;
        return node.kind().equals("unaryExpression") && Compat.set("+", "-").contains(((ParsedNode) node).attribute("operator"))
                && literal(node.children().get(0)) && !node.children().get(0).kind().equals("stringLiteral");
    }

    private static final class InitializerPrinter extends JavaScriptPrinter {
        private final ScopeAnalysis scopes;
        private final String receiver;
        private final Map<String, String> keys;
        InitializerPrinter(JsProgram program, String receiver, Map<String, String> keys) {
            super(program); this.scopes = new ScopeAnalysis(program); this.receiver = receiver; this.keys = keys;
        }
        private String reference(JsNode node) {
            String name = ScopeAnalysis.decode(text(node));
            return keys.containsKey(name) && scopes.scope(node).resolve(name) == null ? receiver + "[" + keys.get(name) + "]" : null;
        }
        @Override protected String render(JsNode node) {
            if (node.is("identifier") && node.attribute("bindingName").isEmpty()) {
                String value = reference(node); if (value != null) return value;
            }
            if (node.is("objectMember") && node.children().size() == 1 && node.child(0).is("propertyName")) {
                String value = reference(node.child(0)); if (value != null) return text(node.child(0)) + ":" + value;
            }
            if (node.is("objectMember") && node.attribute("shorthandDefault").equals("true")) {
                String value = reference(node.child(0));
                if (value != null) return text(node.child(0)) + ":" + value + "=" + render(node.child(1));
            }
            return super.render(node);
        }
        private Constant constant(JsNode node, String enumName, Map<String, Constant> values) {
            if (node.is("numericLiteral")) return Constant.NUMBER;
            if (node.is("stringLiteral")) return Constant.STRING;
            if (node.is("parenthesizedExpression")) return constant(node.child(0), enumName, values);
            if (node.is("identifier")) return values.getOrDefault(ScopeAnalysis.decode(text(node)), Constant.COMPUTED);
            if (node.is("memberExpression") && node.child(0).is("identifier") && ScopeAnalysis.decode(text(node.child(0))).equals(enumName)) {
                JsNode key = node.child(1);
                if (key.is("propertyName") || key.is("stringLiteral")) return values.getOrDefault(key.is("stringLiteral")
                        ? StringLiterals.decode(text(key)) : ScopeAnalysis.decode(text(key)), Constant.COMPUTED);
            }
            String operator = node.attribute("operator");
            if (node.is("unaryExpression") && Compat.set("+", "-", "~").contains(operator)
                    && constant(node.child(0), enumName, values) == Constant.NUMBER) return Constant.NUMBER;
            if (node.is("binaryExpression") && Compat.set("+", "-", "*", "/", "%", "**", "<<", ">>", ">>>", "&", "|", "^").contains(operator)) {
                Constant left = constant(node.child(0), enumName, values), right = constant(node.child(1), enumName, values);
                if (left == Constant.NUMBER && right == Constant.NUMBER) return Constant.NUMBER;
                if (operator.equals("+") && left != Constant.COMPUTED && right != Constant.COMPUTED) return Constant.STRING;
            }
            return Constant.COMPUTED;
        }
    }
}