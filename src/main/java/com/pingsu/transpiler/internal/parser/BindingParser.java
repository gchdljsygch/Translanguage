package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.pingsu.transpiler.internal.parser.IdentifierRules.RESERVED;
import static com.pingsu.transpiler.internal.parser.IdentifierRules.spelling;

/** Variable declarations, destructuring bindings and property names. */
final class BindingParser implements GrammarRules.Bindings {
    private final AbstractParseSession context;
    private final GrammarRules.Types types;
    private final GrammarRules.Expressions expressions;

    BindingParser(AbstractParseSession context,
            GrammarRules.Types types,
            GrammarRules.Expressions expressions) {
        this.context = context;
        this.types = types;
        this.expressions = expressions;
    }

    @Override public ParsedNode variables(boolean terminate) {
        int start = context.start(); String modifier = context.take().text(); List<SyntaxNode> declarations = new ArrayList<>();
        do {
            int begin = context.start(); List<SyntaxNode> children = new ArrayList<>(); children.add(binding());
            if (context.at("!")) { context.ts(); children.add(context.leaf("typeModifier")); }
            if (context.at(":")) children.add(types.annotation());
            if (context.eat("=")) children.add(expressions.expression(2));
            else if (modifier.equals("const") && !context.grammar.ambientContext && !(context.at("in") || context.at("of"))) throw context.failure(context.peek(), "const requires an initializer");
            declarations.add(context.node("variableDeclaration", begin, children));
        } while (context.eat(","));
        if (terminate) context.semicolon(); return context.node("variableStatement", start, declarations);
    }

    @Override public ParsedNode binding() {
        context.enter();
        try {
            int start = context.start(); List<SyntaxNode> children = new ArrayList<>();
            if (context.eat("[")) {
                while (!context.at("]")) {
                    if (context.at(",")) { children.add(context.leaf("bindingElision")); continue; }
                    int begin = context.start(); boolean rest = context.eat("..."); List<SyntaxNode> parts = new ArrayList<>(); parts.add(binding());
                    if (context.eat("=")) { if (rest) throw context.failure(context.last(), "Rest binding cannot have a default"); parts.add(expressions.initializer("bindingInitializer")); }
                    children.add(context.node("bindingElement", begin, parts).with("rest", Boolean.toString(rest)));
                    if (!context.eat(",")) break;
                    if (rest) throw context.failure(context.last(), "Rest binding must be last");
                }
                context.expect("]"); return context.node("arrayBinding", start, children);
            }
            if (context.eat("{")) {
                while (!context.at("}")) {
                    int begin = context.start(); boolean rest = context.eat("..."); List<SyntaxNode> parts = new ArrayList<>();
                    if (rest) parts.add(bindingIdentifier());
                    else {
                        ParsedNode key = propertyName(); parts.add(key);
                        if (context.eat(":")) parts.add(binding());
                        else {
                            if (!identifierToken(key)) throw context.failure(context.last(), "Invalid shorthand binding");
                            parts.set(0, key.with("bindingName", spelling(context.text(key))));
                        }
                        if (context.eat("=")) parts.add(expressions.initializer("bindingInitializer"));
                    }
                    children.add(context.node(rest ? "restBinding" : "bindingProperty", begin, parts));
                    if (!context.eat(",")) break;
                    if (rest) throw context.failure(context.last(), "Rest binding must be last");
                }
                context.expect("}"); return context.node("objectBinding", start, children);
            }
            return bindingIdentifier();
        } finally { context.leave(); }
    }

    @Override public ParsedNode bindingIdentifier() {
        Token token = context.peek();
        if (!context.identifier(token) || context.grammar.strict && Set.of("eval", "arguments").contains(spelling(token.text()))) throw context.failure(token, "Invalid binding identifier");
        return context.leaf("identifier").with("bindingName", spelling(token.text()));
    }

    @Override public ParsedNode identifierName() {
        if (context.peek().kind() != TokenKind.IDENTIFIER && context.peek().kind() != TokenKind.KEYWORD) throw context.failure(context.peek(), "Expected identifier name");
        return context.leaf("propertyName");
    }

    @Override public ParsedNode propertyName() {
        int start = context.start();
        if (context.eat("[")) { ParsedNode value = expressions.expression(1); context.expect("]"); return context.node("computedName", start, value); }
        if (context.at("#")) throw context.failure(context.peek(), "Private names are only allowed in classes");
        if (context.peek().kind() == TokenKind.IDENTIFIER || context.peek().kind() == TokenKind.KEYWORD
                || context.peek().kind() == TokenKind.STRING || context.peek().kind() == TokenKind.NUMBER) return context.leaf("propertyName");
        throw context.failure(context.peek(), "Expected property name");
    }

    @Override public boolean identifierToken(ParsedNode node) { return node.kind().equals("propertyName") && !context.text(node).startsWith("'") && !context.text(node).startsWith("\"") && !Character.isDigit(context.text(node).charAt(0)) && !RESERVED.contains(spelling(context.text(node))); }
}
