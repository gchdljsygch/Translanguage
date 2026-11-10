package com.pingsu.transpiler.internal.js;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.*;

/** Lexical/function scope tree plus compilation-local, conservative capture avoidance. */
public final class ScopeAnalysis {
    public enum Kind { PROGRAM, FUNCTION, BLOCK, CLASS }
    public static final class Scope {
        private final Scope parent;
        private final Kind kind;
        private final Set<String> bindings = new LinkedHashSet<>();
        private final Set<String> references = new LinkedHashSet<>();
        Scope(Scope parent, Kind kind) { this.parent = parent; this.kind = kind; }
        public Scope parent() { return parent; }
        public Kind kind() { return kind; }
        public Set<String> bindings() { return Collections.unmodifiableSet(bindings); }
        public Set<String> references() { return Collections.unmodifiableSet(references); }
        public Scope resolve(String name) { return bindings.contains(name) ? this : parent == null ? null : parent.resolve(name); }
    }
    private final Set<String> names = new HashSet<>();
    private final IdentityHashMap<JsNode, Scope> scopes = new IdentityHashMap<>();
    private int serial;
    private boolean dynamic;
    public ScopeAnalysis(JsProgram program) {
        for (com.pingsu.transpiler.syntax.Token token : program.tokens()) if (token.kind() == TokenKind.IDENTIFIER || token.kind() == TokenKind.KEYWORD) names.add(decode(token.text()));
        visit(program.root(), new Scope(null, Kind.PROGRAM), program, "");
    }
    private void visit(JsNode node, Scope scope, JsProgram program, String declaration) {
        if (node.is("functionDeclaration") || node.is("classDeclaration")) {
            if (!node.children().isEmpty() && node.child(0).is("identifier")) scope.bindings.add(decode(program.text(node.child(0))));
        }
        if (node.is("namespaceDeclaration")) scope.bindings.add(decode(node.attribute("name")));
        if (node.is("enumDeclaration")) scope.bindings.add(decode(node.attribute("name")));
        Scope enclosing = scope;
        if (Compat.set("functionDeclaration", "functionExpression", "arrowFunction", "methodDeclaration", "constructorDeclaration").contains(node.kind())
                || node.is("objectMember") && node.children().stream().anyMatch(n -> n.is("functionBody"))) scope = new Scope(scope, Kind.FUNCTION);
        else if (node.is("classDeclaration") || node.is("classExpression")) scope = new Scope(scope, Kind.CLASS);
        else if (node.is("namespaceDeclaration")) scope = new Scope(scope, Kind.FUNCTION);
        // Parameter defaults cannot see body var/function declarations.
        else if (node.is("functionBody") || node.is("staticBlock")) scope = new Scope(scope, Kind.FUNCTION);
        else if (node.is("block") || node.is("forStatement") || node.is("switchStatement")) scope = new Scope(scope, Kind.BLOCK);
        if (scope != enclosing && scope.kind == Kind.FUNCTION && !node.is("arrowFunction") && !node.is("functionBody") && !node.is("staticBlock"))
            scope.bindings.add("arguments");
        scopes.put(node, scope);
        if (!node.attribute("bindingName").isEmpty()) {
            Scope owner=scope;
            if(declaration.equals("var"))while(owner.parent!=null&&owner.kind!=Kind.FUNCTION&&owner.kind!=Kind.PROGRAM)owner=owner.parent;
            owner.bindings.add(node.attribute("bindingName"));
        }
        else if (node.is("identifier")) scope.references.add(decode(program.text(node)));
        if (node.is("withStatement") || node.is("argumentsExpression") && unwrapped(node.child(0)).is("identifier") && decode(program.text(unwrapped(node.child(0)))).equals("eval")) dynamic = true;
        Scope catchScope = null;
        for (int i=0;i<node.children().size();i++) {
            JsNode child=node.child(i);String kind=declaration;
            if(node.is("variableStatement"))kind=program.significantTokens(node).get(0).text();
            if(node.is("variableDeclaration")&&i>0||node.is("functionBody")||node.is("parameters"))kind="";
            Scope childScope = scope;
            if ((node.is("methodDeclaration") || node.is("objectMember")) && child.is("computedName")) childScope = enclosing;
            if (node.is("switchStatement") && i == 0) childScope = enclosing;
            // The parser stores catch bindings and their block consecutively in tryStatement.
            if (node.is("tryStatement") && !child.is("block")) {
                if (catchScope == null) catchScope = new Scope(scope, Kind.BLOCK);
                childScope = catchScope;
            } else if (node.is("tryStatement") && catchScope != null) {
                childScope = catchScope; catchScope = null;
            }
            visit(child,childScope,program,kind);
        }
    }
    private JsNode unwrapped(JsNode node) { while(node.is("parenthesizedExpression"))node=node.child(0);return node; }
    public Scope scope(JsNode node) { return scopes.get(node); }
    public boolean dynamic() { return dynamic; }
    public String fresh(String purpose) {
        String candidate;
        do { candidate = "$tl$" + purpose + "$" + serial++; } while (!names.add(candidate));
        return candidate;
    }
    public static String decode(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '\\') { out.append(raw.charAt(i++)); continue; }
            i += 2;
            if (raw.charAt(i) == '{') { int end = raw.indexOf('}', ++i); out.appendCodePoint(Integer.parseInt(raw.substring(i, end), 16)); i = end + 1; }
            else { out.append((char) Integer.parseInt(raw.substring(i, i + 4), 16)); i += 4; }
        }
        return out.toString();
    }
}
