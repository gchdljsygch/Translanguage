package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.emit.JavaScriptPrinter;
import com.pingsu.transpiler.internal.js.JsNode;
import com.pingsu.transpiler.internal.js.JsProgram;
import com.pingsu.transpiler.internal.js.ScopeAnalysis;
import com.pingsu.transpiler.internal.js.StringLiterals;
import com.pingsu.transpiler.internal.lowering.SuperCallSites;
import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.internal.parser.RecursiveDescentParser;
import com.pingsu.transpiler.internal.parser.SourceParser;
import com.pingsu.transpiler.syntax.SourceRange;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Materializes preceding type/JSX edits, then lowers decorator runtime semantics. */
public final class DecoratorLoweringPass implements TransformationPass {
    private final DecoratorMode mode;
    public DecoratorLoweringPass() { this(DecoratorMode.STANDARD); }
    public DecoratorLoweringPass(DecoratorMode mode) { this.mode = Objects.requireNonNull(mode); }
    void configure(TransformationContext context) { context.decoratorMode(mode); }
    @Override public void transform(TransformationContext context) {
        boolean[] found = {false};
        context.program().walk(node -> { if (node.kind().equals("decorator") || node.kind().equals("accessorModifier")) found[0] = true; return true; });
        if (!found[0]) return;
        String source = context.render();
        SourceParser.Result parsed = new RecursiveDescentParser().parse(context.sourceName(), source, SourceParser.Language.TYPESCRIPT);
        if (!parsed.successful()) { context.reportDiagnostics(parsed.diagnostics()); return; }
        TransformationContext phase = new TransformationContext(context.sourceName(), source, parsed.program(), context.target());
        phase.jsxPass(context.jsxPass());
        Engine engine = new Engine(phase, mode);
        String result = engine.render(parsed.program(), Collections.emptyList());
        if (!phase.diagnostics().isEmpty()) context.reportDiagnostics(phase.diagnostics());
        else {
            String output = engine.finish(result);
            SourceParser.Result checked = new RecursiveDescentParser().parse(context.sourceName(), output, SourceParser.Language.JAVASCRIPT);
            if (!checked.successful()) context.reportDiagnostics(checked.diagnostics());
            else context.rewrite(output);
        }
    }

    private static final class Member {
        SyntaxNode node, key, initializer, parameters;
        final List<SyntaxNode> decorators = new ArrayList<>();
        String name, kind, array, initializers, extras;
        boolean isStatic;
    }
    /** One compilation owns generated names, helper requirements and all mutable lowering state. */
    private static final class Engine {
        final TransformationContext context;
        final DecoratorMode mode;
        final ScopeAnalysis names;
        final String keyHelper, decorate, parameter, run;
        boolean used, keys, parameters;
        final java.util.Map<SyntaxNode, SyntaxNode> parents = new java.util.IdentityHashMap<>();
        Engine(TransformationContext context, DecoratorMode mode) {
            this.context = context; this.mode = mode;
            context.program().walk(n -> { for (SyntaxNode c : n.children()) parents.put(c, n); return true; });
            names = new ScopeAnalysis(new JsProgram(context.sourceName(), context.source(), JsNode.from(context.program()), context.tokens()));
            keyHelper = names.fresh("decoratorKey"); decorate = names.fresh("decorate");
            parameter = names.fresh("parameterDecorator"); run = names.fresh("runInitializers");
        }
        String text(SyntaxNode node) { return context.slice(node); }
        String attribute(SyntaxNode node, String key) { return node instanceof ParsedNode ? ((ParsedNode) node).attribute(key) : ""; }
        List<SyntaxNode> decorators(SyntaxNode node) {
            List<SyntaxNode> result = new ArrayList<>();
            for (SyntaxNode child : node.children()) if (child.kind().equals("decorator")) result.add(child);
            return result;
        }
        SyntaxNode child(SyntaxNode node, String kind) {
            for (SyntaxNode child : node.children()) if (child.kind().equals(kind)) return child;
            return null;
        }
        SyntaxNode classIdentifier(SyntaxNode node) {
            for (SyntaxNode c : node.children()) if (!attribute(c, "bindingName").isEmpty()) return c;
            return null;
        }
        String inferredName(SyntaxNode node) {
            SyntaxNode parent = parents.get(node);
            while (parent != null && (parent.kind().equals("parenthesizedExpression") || parent.kind().equals("decoratedDeclaration"))) parent = parents.get(parent);
            if (parent == null) return "";
            if (parent.kind().equals("exportDeclaration") && text(parent).contains("default")) return "default";
            if ((parent.kind().equals("variableDeclaration") || parent.kind().equals("assignmentExpression")) && parent.children().get(0).kind().equals("identifier")) return text(parent.children().get(0));
            return "";
        }
        boolean isClass(SyntaxNode node) { return node.kind().equals("classDeclaration") || node.kind().equals("classExpression"); }
        String render(SyntaxNode node, List<SyntaxNode> pending) {
            if (node.kind().equals("decoratedDeclaration")) {
                List<SyntaxNode> all = new ArrayList<>(pending); all.addAll(decorators(node));
                return render(node.children().get(node.children().size() - 1), all);
            }
            if (node.kind().equals("exportDeclaration")) {
                SyntaxNode declaration = null;
                for (SyntaxNode c : node.children()) if (isClass(c) || c.kind().equals("decoratedDeclaration")) declaration = c;
                if (declaration != null) {
                    String prefix = context.source().substring(node.range().start(), declaration.range().start());
                    boolean defaultExport = prefix.contains("default");
                    if (isClass(declaration) && defaultExport && needsLowering(declaration, pending)) {
                        SyntaxNode id = classIdentifier(declaration);
                        String value = lower(declaration, pending, true);
                        return id == null ? "export default " + value + ";" : "let " + text(id) + " = " + value + "; export { " + text(id) + " as default };";
                    }
                    return prefix + render(declaration, pending);
                }
            }
            if (isClass(node) && needsLowering(node, pending)) return lower(node, pending, node.kind().equals("classExpression"));
            if (node.kind().equals("decorator")) {
                context.unsupported(node, "Decorators are only valid on classes and their members or legacy parameters."); return "";
            }
            StringBuilder result = new StringBuilder(); int cursor = node.range().start();
            for (SyntaxNode c : node.children()) {
                result.append(context.source(), cursor, c.range().start()).append(render(c, Collections.emptyList())); cursor = c.range().end();
            }
            return result.append(context.source(), cursor, node.range().end()).toString();
        }
        boolean needsLowering(SyntaxNode node, List<SyntaxNode> pending) {
            if (!pending.isEmpty() || !decorators(node).isEmpty()) return true;
            for (SyntaxNode member : node.children()) {
                if (!decorators(member).isEmpty() || child(member, "accessorModifier") != null) return true;
                SyntaxNode list = child(member, "parameters");
                if (list != null) for (SyntaxNode p : list.children()) if (!decorators(p).isEmpty()) return true;
            }
            return false;
        }
        Member member(SyntaxNode node) {
            Member member = new Member(); member.node = node; member.decorators.addAll(decorators(node));
            member.key = child(node, "propertyName"); if (member.key == null) member.key = child(node, "computedName");
            if (member.key == null) member.key = child(node, "privateIdentifier");
            if (member.key != null && member.key.kind().equals("privateIdentifier") && !member.decorators.isEmpty())
                context.unsupported(node, "Decorators on private members are not yet supported");
            member.isStatic = attribute(node, "static").equals("true");
            member.parameters = child(node, "parameters");
            member.kind = node.kind().equals("propertyDeclaration") ? "field" : attribute(node, "accessor");
            if (member.kind.isEmpty()) member.kind = "method";
            if (member.kind.equals("get")) member.kind = "getter";
            if (member.kind.equals("set")) member.kind = "setter";
            if (node.kind().equals("propertyDeclaration")) for (SyntaxNode c : node.children())
                if (c != member.key && !c.kind().equals("decorator") && !c.kind().equals("accessorModifier")) member.initializer = c;
            return member;
        }
        String expression(SyntaxNode decorator) {
            String expression = text(decorator.children().get(0));
            TranspileResult result = new TypeScriptTranspiler(JavaScriptTarget.PRESERVE,
                    java.util.Arrays.asList(context.jsxPass(), new TypeErasurePass(), new EnumLoweringPass(), new DecoratorLoweringPass(mode)))
                    .transpile(context.sourceName(), "(" + expression + ");");
            if (!result.successful()) { context.unsupported(decorator, "Invalid decorator expression: " + result.diagnostics().get(0).message()); return "void 0"; }
            String output = result.javaScript().trim();
            SourceParser.Result parsed = new RecursiveDescentParser().parse(context.sourceName(), output, SourceParser.Language.JAVASCRIPT);
            if (parsed.successful() && parsed.program().children().size() > 1) return "(() => { return " + output + "\n})()";
            return output.substring(0, output.length() - 1);
        }
        String array(List<SyntaxNode> decorators, SyntaxNode parametersNode) {
            List<String> values = new ArrayList<>();
            for (SyntaxNode decorator : decorators) values.add(expression(decorator));
            if (parametersNode != null) {
                int index = 0;
                for (SyntaxNode p : parametersNode.children()) {
                    for (SyntaxNode decorator : decorators(p)) {
                        if (mode == DecoratorMode.STANDARD) context.unsupported(decorator, "Parameter decorators require DecoratorMode.LEGACY.");
                        parameters = true; values.add(parameter + "(" + index + ", " + expression(decorator) + ")");
                    }
                    index++;
                }
            }
            return "[" + String.join(", ", values) + "]";
        }
        String lower(SyntaxNode node, List<SyntaxNode> pending, boolean expression) {
            used = true;
            if (names.dynamic()) { context.unsupported(node, "Decorator lowering cannot preserve direct eval/with scope visibility."); return text(node); }
            List<SyntaxNode> classDecorators = new ArrayList<>(pending); classDecorators.addAll(decorators(node));
            List<Member> members = new ArrayList<>(); SyntaxNode constructor = null;
            for (SyntaxNode c : node.children()) {
                if (c.kind().equals("constructorDeclaration")) constructor = c;
                if (c.kind().equals("propertyDeclaration") || c.kind().equals("methodDeclaration")) members.add(member(c));
                if (child(c, "accessorModifier") != null) context.unsupported(c, "Auto-accessor declarations are not yet supported; decorate an explicit getter/setter or field.");
            }
            if (constructor != null && !decorators(constructor).isEmpty()) context.unsupported(constructor, "Decorate the class, not its constructor declaration.");
            String className = classIdentifier(node) == null ? "" : text(classIdentifier(node));
            String displayName = className.isEmpty() ? inferredName(node) : className;
            String value = names.fresh("decoratedClass");
            String classArray = array(classDecorators, constructor == null ? null : child(constructor, "parameters"));
            SourceEdits edits = new SourceEdits(context.source());
            for (SyntaxNode d : decorators(node)) edits.replace(d.range(), "");
            for (SyntaxNode c : node.children()) {
                if (c.kind().equals("decorator")) continue;
                if (!c.kind().equals("propertyDeclaration") && !c.kind().equals("methodDeclaration") && !c.kind().equals("constructorDeclaration")) {
                    String rendered = render(c, Collections.emptyList());
                    if (!rendered.equals(text(c))) edits.replace(c.range(), rendered);
                }
            }
            int open = classOpen(node);
            StringBuilder declarations = new StringBuilder(), after = new StringBuilder();
            if (mode == DecoratorMode.LEGACY) {
                if (!className.isEmpty()) {
                    declarations.append("let ").append(className).append(";\n");
                    edits.replace(classIdentifier(node).range(), "");
                }
                edits.replace(SourceRange.at(open), "\nstatic { " + (className.isEmpty() ? "" : className + " = this; ")
                        + "if (typeof Object.getOwnPropertyDescriptor(this, 'name').value === 'string') Object.defineProperty(this, 'name', {value:" + JavaScriptPrinter.quote(displayName) + ", configurable:true}); }\n");
                for (Member member : members) {
                    boolean decorated = !member.decorators.isEmpty() || hasParameterDecorators(member.parameters);
                    String key = null;
                    if (decorated) {
                        member.name = memberName(member, declarations);
                        if (member.key.kind().equals("computedName")) key = "[" + member.name + " = " + keyHelper + "(" + render(member.key.children().get(0), Collections.emptyList()) + ")]";
                    }
                    edits.replace(member.node.range(), renderMember(member, key, null));
                }
                for (boolean isStatic : new boolean[]{false, true}) for (Member member : members) {
                    if (member.name == null || member.isStatic != isStatic) continue;
                    after.append(decorate).append('(').append(array(member.decorators, member.parameters)).append(", ")
                            .append(value).append(isStatic ? "" : ".prototype").append(", ").append(member.name)
                            .append(", ").append(member.kind.equals("field") ? 1 : 2).append(");\n");
                }
                if (!classArray.equals("[]")) after.append(value).append(" = ").append(decorate).append('(').append(classArray).append(", ").append(value).append(", void 0, 0);\n");
                if (!className.isEmpty()) after.append(className).append(" = ").append(value).append(";\n");
                if (constructor != null) edits.replace(constructor.range(), renderConstructor(constructor, null));
            } else {
                standard(node, members, constructor, edits, declarations, after, value, classArray, className, displayName, open);
            }
            String original = edits.render(node.range());
            // Decorated class expressions are isolated per evaluation, including their initializer queues.
            String result = "(() => {\n" + declarations + "let " + value + " = " + original + ";\n" + after + "return " + value + ";\n})()";
            return expression ? result : "let " + className + " = " + result + ";";
        }
        boolean hasParameterDecorators(SyntaxNode parametersNode) {
            if (parametersNode == null) return false;
            for (SyntaxNode p : parametersNode.children()) if (!decorators(p).isEmpty()) return true;
            return false;
        }
        int classOpen(SyntaxNode node) {
            int boundary = node.range().start();
            for (SyntaxNode c : node.children()) {
                if (c.kind().equals("propertyDeclaration") || c.kind().equals("methodDeclaration") || c.kind().equals("constructorDeclaration") || c.kind().equals("staticBlock")) break;
                boundary = c.range().end();
            }
            for (Token token : context.tokens()) if (token.range().start() >= boundary && token.range().end() <= node.range().end() && token.text().equals("{")) return token.range().end();
            throw new IllegalStateException("Missing class body");
        }
        String memberName(Member member, StringBuilder declarations) {
            if (member.key.kind().equals("computedName")) { keys = true; String key = names.fresh("decoratorName"); declarations.append("let ").append(key).append(";\n"); return key; }
            String raw = text(member.key);
            if (raw.startsWith("'") || raw.startsWith("\"")) return JavaScriptPrinter.quote(StringLiterals.decode(raw));
            if (Character.isDigit(raw.charAt(0))) return "String(" + raw + ")";
            return JavaScriptPrinter.quote(ScopeAnalysis.decode(raw));
        }
        String renderMember(Member member, String key, String initializer) {
            StringBuilder result = new StringBuilder(); int cursor = member.node.range().start();
            for (SyntaxNode c : member.node.children()) {
                result.append(context.source(), cursor, c.range().start());
                if (c.kind().equals("decorator")) { /* decorator expressions are moved to their evaluation phase */ }
                else if (c == member.key && key != null) result.append(key);
                else if (c == member.initializer && initializer != null) result.append(initializer);
                else if (c.kind().equals("parameters")) result.append(renderParameters(c));
                else result.append(render(c, Collections.emptyList()));
                cursor = c.range().end();
            }
            String tail = context.source().substring(cursor, member.node.range().end());
            if (initializer != null && member.initializer == null) {
                result.append(" = ").append(initializer);
                // ASI fields need an explicit delimiter after adding an initializer.
                if (!tail.contains(";")) tail += ";";
            }
            return result.append(tail).toString();
        }
        String renderParameters(SyntaxNode node) {
            SourceEdits edits = new SourceEdits(context.source());
            for (SyntaxNode p : node.children()) for (SyntaxNode c : p.children()) {
                if (c.kind().equals("decorator")) edits.replace(c.range(), "");
                else { String result = render(c, Collections.emptyList()); if (!result.equals(text(c))) edits.replace(c.range(), result); }
            }
            return edits.render(node.range());
        }
        String renderConstructor(SyntaxNode node, String initialize) {
            SourceEdits edits = new SourceEdits(context.source()); SyntaxNode body = child(node, "functionBody");
            SyntaxNode parametersNode = child(node, "parameters"); edits.replace(parametersNode.range(), renderParameters(parametersNode));
            // Render nested decorated classes before injecting constructor initialization.
            String bodyText = render(body, Collections.emptyList());
            if (initialize == null || initialize.isEmpty()) { edits.replace(body.range(), bodyText); return edits.render(node.range()); }
            if (!attribute(node, "derived").equals("true")) {
                int position = body.range().start() + 1;
                for (SyntaxNode statement : body.children()) {
                    if (statement.kind().equals("expressionStatement") && child(statement, "stringLiteral") != null) position = statement.range().end(); else break;
                }
                for (SyntaxNode statement : body.children()) edits.replace(statement.range(), render(statement, Collections.emptyList()));
                edits.replace(SourceRange.at(position), initialize);
            } else {
                List<SyntaxNode> calls = SuperCallSites.collect(body, SyntaxNode::kind, SyntaxNode::children);
                body.walk(n -> {
                    if (n != body && (isClass(n) || n.kind().equals("decoratedDeclaration"))) {
                        edits.replace(n.range(), render(n, Collections.emptyList())); return false;
                    }
                    if (calls.contains(n)) {
                        edits.replace(SourceRange.at(n.range().start()), "(");
                        edits.replace(SourceRange.at(n.range().end()), ", " + initialize.replaceAll(";\\s*$", "") + ", this)");
                    }
                    return true;
                });
            }
            return edits.render(node.range());
        }
        void standard(SyntaxNode node, List<Member> members, SyntaxNode constructor, SourceEdits edits,
                      StringBuilder declarations, StringBuilder after, String value, String classArray, String className, String displayName, int open) {
            String instanceExtras = names.fresh("instanceInitializers"), staticExtras = names.fresh("staticInitializers"), classExtras = names.fresh("classInitializers");
            String classDecs = names.fresh("classDecorators"), replacement = names.fresh("classReplacement"), metadata = names.fresh("metadata");
            declarations.append("let ").append(classDecs).append(" = ").append(classArray).append(", ").append(replacement)
                    .append(", ").append(metadata).append(", ").append(instanceExtras).append(" = [], ").append(staticExtras).append(" = [], ").append(classExtras).append(" = [];\n");
            List<Member> decorated = new ArrayList<>();
            for (Member member : members) {
                if (hasParameterDecorators(member.parameters)) array(Collections.emptyList(), member.parameters);
                if (member.decorators.isEmpty()) continue;
                decorated.add(member); member.name = memberName(member, declarations);
                member.array = names.fresh("memberDecorators"); member.initializers = names.fresh("fieldInitializers"); member.extras = names.fresh("fieldExtras");
                declarations.append("let ").append(member.array).append(", ").append(member.initializers).append(" = [], ").append(member.extras).append(" = [];\n");
            }
            if (!className.isEmpty()) {
                declarations.append("let ").append(className).append(";\n");
                edits.replace(classIdentifier(node).range(), "");
            }
            StringBuilder setup = new StringBuilder("\nstatic {\n");
            setup.append("if (typeof Object.getOwnPropertyDescriptor(this, 'name').value === 'string') Object.defineProperty(this, 'name', {value:")
                    .append(JavaScriptPrinter.quote(displayName)).append(", configurable:true});\n");
            setup.append(metadata).append(" = typeof Symbol === 'function' && Symbol.metadata ? Object.create(" + (attribute(node, "derived").equals("true") ? "Object.getPrototypeOf(this)[Symbol.metadata] || null" : "null") + ") : void 0;\n");
            decorated.sort(Comparator.comparingInt(m -> (m.kind.equals("field") ? 2 : 0) + (m.isStatic ? 0 : 1)));
            for (Member member : decorated) {
                String extras = member.kind.equals("field") ? member.extras : member.isStatic ? staticExtras : instanceExtras;
                setup.append(decorate).append('(').append(member.array).append(", this").append(member.isStatic ? "" : ".prototype")
                        .append(", ").append(member.name).append(", ").append(JavaScriptPrinter.quote(member.kind)).append(", ").append(member.isStatic)
                        .append(", ").append(member.initializers).append(", ").append(extras).append(", ").append(metadata).append(");\n");
            }
            setup.append(replacement).append(" = ").append(decorate).append('(').append(classDecs).append(", this, ").append(JavaScriptPrinter.quote(displayName))
                    .append(", 'class', false, [], ").append(classExtras).append(", ").append(metadata).append(");\n")
                    .append("if (").append(metadata).append(") Object.defineProperty(").append(replacement).append(", Symbol.metadata, {configurable:true, enumerable:true, writable:true, value:").append(metadata).append("});\n")
                    .append(run).append("(this, ").append(staticExtras).append(");\n}\n");
            if (!className.isEmpty()) {
                int position = setup.indexOf("if (" + metadata + ")");
                setup.insert(position, className + " = " + replacement + ";\n");
            }
            edits.replace(SourceRange.at(open), setup.toString());
            String previous = instanceExtras;
            for (Member member : members) {
                String key = null;
                if (member.array != null) {
                    String keyValue = member.key.kind().equals("computedName") ? member.name + " = " + keyHelper + "(" + render(member.key.children().get(0), Collections.emptyList()) + ")" : member.name;
                    key = "[(" + member.array + " = " + array(member.decorators, null) + ", " + keyValue + ")]";
                }
                String initializer = null;
                if (member.kind.equals("field")) {
                    String original = member.initializer == null ? "void 0" : render(member.initializer, Collections.emptyList());
                    if (member.array != null) original = run + "(this, " + member.initializers + ", " + original + ")";
                    if (!member.isStatic && previous != null) original = "(" + run + "(this, " + previous + "), " + original + ")";
                    initializer = original;
                    if (!member.isStatic) previous = member.array == null ? null : member.extras;
                }
                String rendered = renderMember(member, key, initializer);
                if (member.isStatic && member.kind.equals("field") && member.array != null) rendered += "\nstatic { " + run + "(this, " + member.extras + "); }\n";
                edits.replace(member.node.range(), rendered);
            }
            String initialize = previous == null ? "" : run + "(this, " + previous + ");";
            if (constructor != null) edits.replace(constructor.range(), renderConstructor(constructor, initialize));
            else if (!initialize.isEmpty()) {
                boolean derived = attribute(node, "derived").equals("true");
                String args = names.fresh("constructorArgs");
                edits.replace(SourceRange.at(node.range().end() - 1), derived ? "\nconstructor(..." + args + ") { super(..." + args + "); " + initialize + " }\n" : "\nconstructor() { " + initialize + " }\n");
            }
            after.append(value).append(" = ").append(replacement).append(";\n").append(run).append('(').append(value).append(", ").append(classExtras).append(");\n");
        }
        String finish(String source) {
            if (!used) return source;
            String helpers = (keys ? DecoratorHelpers.KEY.replace("KEY", keyHelper) : "")
                    + (mode == DecoratorMode.LEGACY ? DecoratorHelpers.LEGACY.replace("LEGACY", decorate) : DecoratorHelpers.STANDARD.replace("STANDARD", decorate) + DecoratorHelpers.RUN.replace("RUN", run))
                    + (parameters ? DecoratorHelpers.PARAM.replace("PARAM", parameter) : "");
            // Hoisted function declarations preserve directives and module imports at their original positions.
            return source + "\n" + helpers;
        }
    }
}
