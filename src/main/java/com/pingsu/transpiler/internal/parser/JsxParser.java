package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;

/** JSX lexical boundaries are explicit; embedded expressions use the ordinary grammar. */
final class JsxParser {
    private final AbstractParseSession context;
    private final GrammarRules.Expressions expressions;
    private final GrammarRules.Types types;
    JsxParser(AbstractParseSession context, GrammarRules.Expressions expressions, GrammarRules.Types types) {
        this.context = context; this.expressions = expressions; this.types = types;
    }
    private Token peek() { return context.peek(TokenReader.Goal.JSX_TAG); }
    private boolean at(String value) { return peek().text().equals(value); }
    private boolean eat(String value) { if (!at(value)) return false; context.take(); return true; }
    private void expect(String value) { if (!eat(value)) throw context.failure(peek(), "Expected '" + value + "' in JSX"); }
    ParsedNode element() {
        context.enter();
        try {
            int start = peek().range().start(); expect("<");
            List<SyntaxNode> children = new ArrayList<>();
            String name = "";
            if (!at(">")) {
                int begin = peek().range().start(); name = name(true);
                children.add(context.node("jsxTag", begin).with("name", name));
                if (at("<")) { int typeStart = peek().range().start(); children.add(context.node("jsxTypeArguments", typeStart, types.typeArguments())); }
                while (!at(">") && !at("/")) {
                    begin = peek().range().start();
                    if (eat("{")) {
                        context.expect("..."); ParsedNode spread = expressions.expression(2); context.expect("}");
                        children.add(context.node("jsxSpreadAttribute", begin, spread));
                    } else {
                        String attribute = name(false); List<SyntaxNode> values = new ArrayList<>();
                        if (eat("=")) {
                            if (peek().kind() == TokenKind.STRING) { int s = peek().range().start(); context.take(); values.add(context.node("jsxString", s)); }
                            else if (eat("{")) { values.add(expressions.expression(2)); context.expect("}"); }
                            else if (at("<")) values.add(element());
                            else throw context.failure(peek(), "Expected JSX attribute value");
                        }
                        children.add(context.node("jsxAttribute", begin, values).with("name", attribute));
                    }
                }
                if (eat("/")) { expect(">"); return context.node("jsxElement", start, children).with("name", name); }
            }
            expect(">");
            while (true) {
                Token token = context.peek(TokenReader.Goal.JSX_TEXT);
                if (token.kind() == TokenKind.EOF) throw context.failure(token, "Unterminated JSX element");
                int begin = token.range().start();
                if (token.text().equals("<")) {
                    AbstractParseSession.Mark mark = context.mark(); context.take();
                    if (eat("/")) {
                        String closing = at(">") ? "" : name(true); expect(">");
                        if (!name.equals(closing)) throw context.failure(token, "JSX closing tag does not match '" + name + "'");
                        break;
                    }
                    context.restore(mark); children.add(element());
                } else if (token.text().equals("{")) {
                    context.take(); List<SyntaxNode> values = new ArrayList<>();
                    boolean spread = context.eat("...");
                    if (spread || !context.at("}")) values.add(expressions.expression(2));
                    context.expect("}"); children.add(context.node(spread ? "jsxSpreadChild" : "jsxExpression", begin, values));
                } else { context.take(); children.add(context.node("jsxText", begin)); }
            }
            return context.node("jsxElement", start, children).with("name", name);
        } finally { context.leave(); }
    }
    private String name(boolean member) {
        Token first = peek();
        if (first.kind() != TokenKind.IDENTIFIER && first.kind() != TokenKind.KEYWORD) throw context.failure(first, "Expected JSX name");
        if (first.text().indexOf('\\') >= 0) throw context.failure(first, "Escapes are not permitted in JSX names");
        StringBuilder result = new StringBuilder(context.take().text());
        boolean namespace = false, dotted = false;
        while (at("-") || at(":") || member && at(".")) {
            int previousEnd = context.last().range().end();
            Token separator = context.take();
            if (separator.text().equals(":")) {
                if (namespace || dotted) throw context.failure(separator, "Invalid JSX namespace"); namespace = true;
            }
            if (separator.text().equals(".")) {
                if (namespace || result.indexOf("-") >= 0) throw context.failure(separator, "Invalid JSX member name"); dotted = true;
            }
            Token part = peek();
            if (part.kind() != TokenKind.IDENTIFIER && part.kind() != TokenKind.KEYWORD) throw context.failure(part, "Expected JSX name segment");
            if (separator.range().start() != previousEnd || part.range().start() != separator.range().end()) throw context.failure(part, "Whitespace in JSX name");
            if (dotted && part.text().indexOf('-') >= 0) throw context.failure(part, "Invalid JSX member name");
            result.append(separator.text()).append(context.take().text());
        }
        return result.toString();
    }
}
