package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.compat.Unicode17;
import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.emit.ScopedJavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.*;

/** ES2015 lowering, with references captured once and temporaries local to each invocation. */
public final class RegularLowering implements LoweringPass {
    private final boolean es5;
    public RegularLowering() { this(false); }
    public RegularLowering(boolean es5) { this.es5 = es5; }
    @Override public String lower(JsProgram program) { return new Printer(program, es5).print(); }
    private static final class Printer extends ScopedJavaScriptPrinter {
        private final String apply;
        Printer(JsProgram program, boolean es5) { super(program); apply = es5 ? "Function.prototype.apply.call(" : "Reflect.apply("; }
        private String temp() { return temporary(); }
        @Override protected String render(JsNode node) {
            if (node.is("exportDeclaration") && !node.attribute("namespaceExport").isEmpty()) {
                if (scopes.dynamic()) program.fail(node, "Namespace export lowering cannot expose a binding to direct eval");
                String binding = scopes.fresh("namespace");
                String module = program.significantTokens(node).stream()
                        .filter(t -> t.kind() == TokenKind.STRING).findFirst().orElseThrow(java.util.NoSuchElementException::new).text();
                return "import * as " + binding + " from " + module + ";export {" + binding + " as "
                        + node.attribute("namespaceExport") + "};";
            }
            if (node.is("numericLiteral") || node.is("bigintLiteral") || node.is("propertyName") && Unicode17.isDigit(text(node).charAt(0))) return text(node).replace("_", "");
            if (node.is("parameters") || node.is("arguments")) return trimTrailingComma(node, super.render(node));
            if (node.is("tryStatement")) return catchBinding(node);
            if (node.is("objectLiteral") && node.children().stream().anyMatch(n -> n.attribute("spread").equals("true"))) return spread(node);
            if (node.is("unaryExpression") && node.attribute("operator").equals("delete") && chainHasOptional(unwrap(node.child(0)))) return chain(unwrap(node.child(0)), true);
            if(node.is("taggedTemplateExpression")&&chainHasOptional(unwrap(node.child(0)))) {
                String receiver=temp();
                return apply + "Function.prototype.bind,"+chain(unwrap(node.child(0)),false,receiver)+",["+receiver+"])"+render(node.child(1));
            }
            if (node.is("argumentsExpression") && node.child(0).is("parenthesizedExpression")) {
                JsNode inner = unwrap(node.child(0));
                if (chainHasOptional(inner)) {
                    String receiver = temp(), args = render(node.child(1));
                    return apply + chain(inner,false,receiver) + "," + receiver + ",[" + args.substring(1,args.length()-1) + "])";
                }
            }
            if (chainHasOptional(node)) return chain(node, false);
            if (node.is("binaryExpression")) {
                String operator = node.attribute("operator");
                if (operator.equals("**")) return "Math.pow(" + render(node.child(0)) + "," + render(node.child(1)) + ")";
                if (operator.equals("??")) {
                    String value = temp();
                    return "(" + value + "=" + render(node.child(0)) + "," + nullish(value) + "?" + render(node.child(1)) + ":" + value + ")";
                }
            }
            if (node.is("assignmentExpression") && Compat.set("**=", "&&=", "||=", "??=").contains(node.attribute("operator"))) {
                ExpressionReference ref = reference(node.child(0)); String right = render(node.child(1)); String value = temp();
                String action ; switch (node.attribute("operator")) {
                    case "**=": action = ref.write("Math.pow(" + value + "," + right + ")"); break;
                    case "&&=": action = value + "&&(" + ref.write(right) + ")"; break;
                    case "||=": action = value + "||(" + ref.write(right) + ")"; break;
                    default: action = nullish(value) + "?(" + ref.write(right) + "):" + value; break;
                }
                return "(" + ref.setup() + value + "=" + ref.read() + "," + action + ")";
            }
            return super.render(node);
        }
        private String nullish(String value) { return value + "===null||" + value + "===void 0"; }
        private ExpressionReference reference(JsNode node) {
            if (node.is("parenthesizedExpression")) return reference(node.child(0));
            if (node.is("identifier")) return new ExpressionReference("", render(node), "void 0");
            if (!node.is("memberExpression")) { program.fail(node, "Cannot capture assignment reference"); }
            String receiver, prefix;
            if (node.child(0).is("superExpression")) { receiver = "super"; prefix = ""; }
            else { receiver = temp(); prefix = receiver + "=" + render(node.child(0)) + ","; }
            JsNode key = node.child(1); String target;
            if (key.is("propertyName")) target = receiver + "." + render(key);
            else { String name = temp(); prefix += name + "=" + render(key) + ","; target = receiver + "[" + name + "]"; }
            return new ExpressionReference(prefix, target, receiver.equals("super") ? "this" : receiver);
        }
        private boolean chainNode(JsNode node) { return node.is("memberExpression") || node.is("optionalMemberExpression") || node.is("argumentsExpression"); }
        private boolean chainHasOptional(JsNode node) {
            while (chainNode(node)) { if (node.is("optionalMemberExpression")) return true; node = node.child(0); }
            return false;
        }
        private JsNode unwrap(JsNode node) { while(node.is("parenthesizedExpression")) node=node.child(0);return node; }
        private String chain(JsNode node, boolean deleting) { return chain(node,deleting,null); }
        private String chain(JsNode node, boolean deleting, String capture) {
            List<JsNode> steps = new ArrayList<>(); JsNode base = node;
            while (chainNode(base)) { steps.add(base); base = base.child(0); }
            Collections.reverse(steps);
            String value = render(base), receiver = "void 0", setup = "";
            // A parenthesized member still carries its Reference into an optional call.
            JsNode unwrapped = base;
            while (unwrapped.is("parenthesizedExpression")) unwrapped = unwrapped.child(0);
            if (!steps.isEmpty() && steps.get(0).child(1).is("arguments") && unwrapped.is("memberExpression")) {
                ExpressionReference ref = reference(unwrapped); value = ref.read(); receiver = ref.receiver(); setup = ref.setup();
            }
            if (!steps.isEmpty() && steps.get(0).child(1).is("arguments") && chainHasOptional(unwrapped)) {
                receiver = temp(); value = chain(unwrapped,false,receiver);
            }
            return "(" + setup + chainStep(steps, 0, value, receiver, deleting,capture) + ")";
        }
        private String chainStep(List<JsNode> steps, int index, String value, String receiver, boolean deleting, String capture) {
            if (index == steps.size()) return deleting ? "delete " + value : capture == null ? value : "(" + capture + "=" + receiver + "," + value + ")";
            JsNode step = steps.get(index), part = step.child(1); boolean optional = step.is("optionalMemberExpression");
            if (part.is("arguments")) {
                String arguments = render(part); String call;
                if (optional) {
                    String method = temp(); call = apply + method + "," + receiver + ",[" + arguments.substring(1, arguments.length()-1) + "])";
                    return method + "=" + value + "," + nullish(method) + "?" + (deleting ? "true" : "void 0") + ":(" + chainStep(steps, index+1, call, "void 0", deleting,capture) + ")";
                }
                call = value + arguments;
                return chainStep(steps, index+1, call, "void 0", deleting,capture);
            }
            String base = value.equals("super") ? "super" : temp();
            String prefix = base.equals("super") ? "" : base + "=" + value + ",";
            String access = base + (part.is("propertyName") ? "." + render(part) : "[" + render(part) + "]");
            String continuation = chainStep(steps, index+1, access, base.equals("super") ? "this" : base, deleting,capture);
            return prefix + (optional ? nullish(base) + "?" + (deleting ? "true" : "void 0") + ":(" + continuation + ")" : continuation);
        }
        private String trimTrailingComma(JsNode node, String rendered) {
            // Token-level removal leaves strings, regexes and comments untouched.
            java.util.List<com.pingsu.transpiler.syntax.Token> tokens = program.tokens().stream().filter(t -> !t.trivia() && t.range().start() >= node.range().start() && t.range().end() <= node.range().end()).collect(Compat.toList());
            if (tokens.size() < 2 || !tokens.get(tokens.size()-2).text().equals(",")) return rendered;
            int originalEnd = node.range().end(); int comma = tokens.get(tokens.size()-2).range().start();
            int offset = rendered.length() - (originalEnd - comma);
            return rendered.substring(0, offset) + rendered.substring(offset+1);
        }
        private String catchBinding(JsNode node) {
            Map<JsNode,String> rendered = new IdentityHashMap<>();
            for (JsNode child : node.children()) rendered.put(child,render(child));
            String result = children(node, rendered::get);
            // The catch keyword is outside child ranges; replace by a structural source splice.
            for (com.pingsu.transpiler.syntax.Token token : program.tokens()) {
                if (!token.text().equals("catch") || token.range().start() < node.range().start() || token.range().end() > node.range().end()) continue;
                if (node.children().stream().anyMatch(c -> c.range().contains(token.range()))) continue;
                JsNode block = node.children().stream().filter(c -> c.range().start() >= token.range().end()).findFirst().orElse(null);
                if (block != null && block.is("block")) {
                    int before = token.range().end() - node.range().start();
                    // preceding children may have changed width
                    for (JsNode child : node.children()) if (child.range().end() <= token.range().start()) before += rendered.get(child).length() - child.range().length();
                    return result.substring(0,before) + "(" + scopes.fresh("caught") + ")" + result.substring(before);
                }
            }
            return result;
        }
        private String spread(JsNode node) {
            String result = "{}"; List<String> group = new ArrayList<>(); boolean first = true;
            for (JsNode member : node.children()) {
                if (member.attribute("spread").equals("true")) {
                    if (!group.isEmpty()) { String object = "{" + String.join(",",group) + "}"; result = first ? object : "Object.assign(" + result + "," + object + ")"; group.clear(); first = false; }
                    result = "Object.assign(" + result + "," + render(member.child(0)) + ")"; first = false;
                } else group.add(render(member));
            }
            if (!group.isEmpty()) result = "Object.assign(" + result + ",{" + String.join(",", group) + "})";
            return "(" + result + ")";
        }
    }
}
