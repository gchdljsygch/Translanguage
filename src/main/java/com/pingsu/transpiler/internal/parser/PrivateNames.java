package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.*;
import java.util.*;

/** Private identifiers and their lexically scoped class brands, shared by TS and JS. */
final class PrivateNames {
    private PrivateNames() { }

    static ParsedNode parse(AbstractParseSession context) {
        int start = context.start();
        Token hash = context.expect("#"), name = context.peek();
        if (name.range().start() != hash.range().end()
                || name.kind() != TokenKind.IDENTIFIER && name.kind() != TokenKind.KEYWORD)
            throw context.failure(name, "Expected an identifier immediately after #");
        context.take();
        return context.node("privateIdentifier", start).with("name", IdentifierRules.spelling(name.text()));
    }

    static void validate(AbstractParseSession context, SyntaxNode program) {
        visit(context, program, null, new ArrayDeque<Set<String>>());
    }

    private static String attribute(SyntaxNode node, String key) {
        return node instanceof ParsedNode ? ((ParsedNode) node).attribute(key) : "";
    }
    private static boolean isClass(SyntaxNode node) {
        return node.kind().equals("classDeclaration") || node.kind().equals("classExpression");
    }
    private static SyntaxNode key(SyntaxNode member) {
        for (SyntaxNode child : member.children()) if (child.kind().equals("privateIdentifier")) return child;
        return null;
    }
    private static void visit(AbstractParseSession context, SyntaxNode node, SyntaxNode parent, Deque<Set<String>> environments) {
        if (isClass(node)) {
            Map<String, String> declarations = new HashMap<>();
            for (SyntaxNode member : node.children()) {
                if (!(member.kind().equals("propertyDeclaration") || member.kind().equals("methodDeclaration")
                        || member.kind().equals("ambientDeclaration") || member.kind().equals("overloadDeclaration"))) continue;
                SyntaxNode key = key(member);
                if (key == null) continue;
                String name = attribute(key, "name"), accessor = attribute(member, "accessor");
                if (name.equals("constructor")) throw context.failureAt(key.range().start(), "#constructor is not a valid private name");
                String shape = attribute(member, "static") + ":" + accessor;
                String previous = declarations.get(name);
                if (previous != null && !(previous.equals(attribute(member, "static") + ":get") && accessor.equals("set")
                        || previous.equals(attribute(member, "static") + ":set") && accessor.equals("get")))
                    throw context.failureAt(key.range().start(), "Duplicate private name #" + name);
                declarations.put(name, previous == null ? shape : "paired");
                if (member.kind().equals("ambientDeclaration") || member.kind().equals("overloadDeclaration"))
                    throw context.failureAt(key.range().start(), "Private members require a runtime declaration");
            }
            for (SyntaxNode child : node.children()) {
                boolean member = child.kind().equals("propertyDeclaration") || child.kind().equals("methodDeclaration")
                        || child.kind().equals("constructorDeclaration") || child.kind().equals("staticBlock");
                if (member) environments.push(declarations.keySet());
                visit(context, child, node, environments);
                if (member) environments.pop();
            }
            return;
        }
        if (node.kind().equals("privateIdentifier")) {
            boolean declaration = parent != null && key(parent) == node
                    && (parent.kind().equals("propertyDeclaration") || parent.kind().equals("methodDeclaration"));
            if (declaration) return;
            boolean member = parent != null && (parent.kind().equals("memberExpression") || parent.kind().equals("optionalMemberExpression"))
                    && parent.children().get(1) == node;
            boolean brand = parent != null && parent.kind().equals("binaryExpression") && attribute(parent, "operator").equals("in")
                    && parent.children().get(0) == node;
            if (!member && !brand) throw context.failureAt(node.range().start(), "Invalid private identifier position");
            if (member && parent.children().get(0).kind().equals("superExpression"))
                throw context.failureAt(node.range().start(), "super cannot access a private member");
            String name = attribute(node, "name");
            if (environments.stream().noneMatch(names -> names.contains(name)))
                throw context.failureAt(node.range().start(), "Private name #" + name + " is not declared in an enclosing class");
        }
        if (node.kind().equals("unaryExpression") && attribute(node, "operator").equals("delete")) {
            SyntaxNode operand = node.children().get(0);
            while (operand.kind().equals("parenthesizedExpression") || operand.kind().equals("nonNullExpression")) operand = operand.children().get(0);
            if ((operand.kind().equals("memberExpression") || operand.kind().equals("optionalMemberExpression")) && key(operand) != null)
                throw context.failureAt(node.range().start(), "Private members cannot be deleted");
        }
        for (SyntaxNode child : node.children()) visit(context, child, node, environments);
    }
}