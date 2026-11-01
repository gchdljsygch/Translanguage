package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.parser.AbstractParseSession.Mark;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** TypeScript conditional, union, intersection and structural types. */
final class TypeParser implements GrammarRules.Types {
    private final AbstractParseSession context;
    private final GrammarRules.Bindings bindings;
    private final GrammarRules.Expressions expressions;

    TypeParser(AbstractParseSession context,
            GrammarRules.Bindings bindings,
            GrammarRules.Expressions expressions) {
        this.context = context;
        this.bindings = bindings;
        this.expressions = expressions;
    }

    @Override public ParsedNode annotation() { context.ts(); int start = context.start(); context.expect(":"); return context.node("typeAnnotation", start, type(0)); }

    @Override public ParsedNode typeParameters() {
        context.ts(); int start = context.start(); context.expect("<"); List<SyntaxNode> children = new ArrayList<>();
        do {
            context.eat("const"); context.eat("in"); context.eat("out"); children.add(bindings.bindingIdentifier());
            if (context.eat("extends")) children.add(type(1)); if (context.eat("=")) children.add(type(0));
            if (!context.eat(",")) break;
        } while (!context.peek().text().startsWith(">"));
        context.greater(); return context.node("typeParameters", start, children);
    }

    @Override public ParsedNode typeArguments() {
        context.ts(); int start = context.start(); context.expect("<"); List<SyntaxNode> children = new ArrayList<>();
        do { children.add(type(0)); if (!context.eat(",")) break; } while (!context.peek().text().startsWith(">"));
        context.greater(); return context.node("typeArguments", start, children);
    }
    /** Conditional (0), union (1), intersection (2), prefix/postfix (3) type grammar. */

    @Override public ParsedNode type(int minimum) {
        context.enter();
        try {
            int start = context.start(); List<SyntaxNode> children = new ArrayList<>();
            if (context.eat("|") || context.eat("&")) children.add(type(3));
            else if (Set.of("keyof", "readonly", "unique", "infer", "typeof", "asserts").contains(context.peek().text())) {
                String operator = context.take().text(); children.add(type(3));
                if (operator.equals("infer") && context.eat("extends")) children.add(type(1));
            } else if (context.at("{")) children.add(objectType());
            else if (context.eat("[")) {
                while (!context.at("]")) {
                    context.eat("...");
                    if (context.identifier(context.peek()) && (context.nextText().equals(":") || context.nextText().equals("?"))) { context.take(); context.eat("?"); context.expect(":"); }
                    children.add(type(0)); context.eat("?"); if (!context.eat(",")) break;
                }
                context.expect("]");
            } else if (context.at("(") || context.at("<") || context.at("new") || context.at("abstract") && context.nextText().equals("new")) {
                Mark mark = context.mark(); boolean functionType = false;
                try {
                    context.eat("abstract"); context.eat("new"); if (context.at("<")) typeParameters(); typeSignatureParameters(); context.expect("=>"); functionType = true;
                } catch (ParseFailure failure) { if (failure.limit) throw failure; if (!failure.diagnostic.code().equals("TS_SYNTAX")) throw failure; }
                context.restore(mark);
                if (functionType) {
                    context.eat("abstract"); context.eat("new"); if (context.at("<")) children.add(typeParameters());
                    children.add(typeSignatureParameters()); context.expect("=>"); children.add(type(0));
                } else { context.expect("("); children.add(type(0)); context.expect(")"); }
            } else if (context.at("`")) children.add(expressions.template(true));
            else {
                if (context.at("-") || context.at("+")) context.take();
                Token token = context.peek();
                if (!(token.kind() == TokenKind.IDENTIFIER || token.kind() == TokenKind.KEYWORD || token.kind() == TokenKind.STRING
                        || token.kind() == TokenKind.NUMBER || token.kind() == TokenKind.BIGINT)) throw context.failure(token, "Expected type");
                context.take();
                if (token.text().equals("import") && context.eat("(")) { if (context.peek().kind() != TokenKind.STRING) throw context.failure(context.peek(), "Expected import type string"); context.take(); context.expect(")"); }
            }
            while (true) {
                if (context.eat(".")) { bindings.identifierName(); }
                else if (context.at("<")) {
                    Mark mark = context.mark();
                    try { children.add(typeArguments()); }
                    catch (ParseFailure failure) { if (failure.limit) throw failure; context.restore(mark); break; }
                }
                else if (context.at("[") && !context.newline()) { context.take(); if (!context.at("]")) children.add(type(0)); context.expect("]"); }
                else break;
            }
            if (minimum <= 3 && context.eat("is")) children.add(type(0));
            if (minimum <= 2) while (context.eat("&")) children.add(type(3));
            if (minimum <= 1) while (context.eat("|")) children.add(type(2));
            if (minimum == 0 && context.eat("extends")) { children.add(type(1)); context.expect("?"); children.add(type(0)); context.expect(":"); children.add(type(0)); }
            return context.node("type", start, children);
        } finally { context.leave(); }
    }

    private ParsedNode typeSignatureParameters() {
        int start = context.start(); context.expect("("); List<SyntaxNode> children = new ArrayList<>();
        while (!context.at(")")) {
            context.eat("..."); if (context.at("this")) context.take(); else children.add(bindings.binding()); context.eat("?");
            if (context.at(":")) children.add(annotation()); if (!context.eat(",")) break;
        }
        context.expect(")"); return context.node("typeSignatureParameters", start, children);
    }

    @Override public ParsedNode indexSignature() {
        int start = context.start(); context.expect("["); bindings.bindingIdentifier();
        List<SyntaxNode> children = new ArrayList<>();
        if (context.eat("in")) { children.add(type(0)); if (context.eat("as")) children.add(type(0)); }
        else { context.expect(":"); children.add(type(0)); }
        context.expect("]"); if (context.at("+") || context.at("-")) context.take(); context.eat("?"); context.expect(":"); children.add(type(0));
        return context.node("indexSignature", start, children);
    }

    @Override public ParsedNode objectType() {
        int start = context.start(); context.expect("{"); List<SyntaxNode> children = new ArrayList<>();
        while (!context.at("}")) {
            if (context.eat(";") || context.eat(",")) continue;
            if (context.at("+") || context.at("-")) context.take();
            if (context.at("readonly") && !Set.of(":", "?", "(").contains(context.nextText())) context.take();
            if (context.at("[")) children.add(indexSignature());
            else {
                if (context.at("new") && Set.of("(", "<").contains(context.nextText())) context.take();
                else if (!context.at("(") && !context.at("<")) { bindings.propertyName(); context.eat("?"); }
                if (context.at("<")) children.add(typeParameters());
                if (context.at("(")) children.add(typeSignatureParameters());
                if (context.at(":")) children.add(annotation());
            }
            if (!context.eat(";") && !context.eat(",") && !context.at("}") && !context.newline()) throw context.failure(context.peek(), "Expected type member separator");
        }
        context.expect("}"); return context.node("objectType", start, children);
    }
}
