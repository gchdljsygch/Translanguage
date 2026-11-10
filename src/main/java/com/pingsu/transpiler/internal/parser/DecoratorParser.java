package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;

/** Decorator expressions deliberately stop before the following declaration or computed key. */
final class DecoratorParser {
    private DecoratorParser() { }
    static List<SyntaxNode> parse(AbstractParseSession context, GrammarRules.Expressions expressions, GrammarRules.Types types) {
        List<SyntaxNode> decorators = new ArrayList<>();
        while (context.at("@")) {
            context.ts(); int start = context.start(); context.take(); int begin = context.start();
            List<SyntaxNode> parts = new ArrayList<>();
            if (context.eat("(")) { parts.add(expressions.expression(1)); context.expect(")"); }
            else {
                if (!context.identifier(context.peek())) throw context.failure(context.peek(), "Expected decorator expression");
                parts.add(context.leaf("identifier"));
                while (context.eat(".")) {
                    if (context.peek().kind() != TokenKind.IDENTIFIER && context.peek().kind() != TokenKind.KEYWORD) throw context.failure(context.peek(), "Expected decorator member");
                    parts.add(context.leaf("propertyName"));
                }
                if (context.at("<")) parts.add(types.typeArguments());
                if (context.eat("(")) {
                    while (!context.at(")")) { context.eat("..."); parts.add(expressions.expression(2)); if (!context.eat(",")) break; }
                    context.expect(")");
                }
            }
            decorators.add(context.node("decorator", start, context.node("decoratorExpression", begin, parts)));
        }
        return decorators;
    }
}
