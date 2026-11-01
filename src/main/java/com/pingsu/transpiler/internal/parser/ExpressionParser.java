package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.parser.AbstractParseSession.Mark;
import com.pingsu.transpiler.internal.parser.SourceParser.Language;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.pingsu.transpiler.internal.parser.IdentifierRules.spelling;

/** Pratt expressions, assignment targets, literals and templates. */
final class ExpressionParser implements GrammarRules.Expressions {
    private final AbstractParseSession context;
    private final GrammarRules.Bindings bindings;
    private final GrammarRules.Functions functions;
    private final GrammarRules.Classes classes;
    private final GrammarRules.Types types;

    ExpressionParser(AbstractParseSession context,
            GrammarRules.Bindings bindings,
            GrammarRules.Functions functions,
            GrammarRules.Classes classes,
            GrammarRules.Types types) {
        this.context = context;
        this.bindings = bindings;
        this.functions = functions;
        this.classes = classes;
        this.types = types;
    }

    @Override public ParsedNode expression(int minimum) {
        context.enter();
        try {
            ParsedNode left = prefix();
            while (true) {
                int start = left.range().start(); String operator = context.peek().text();
                if (operator.equals(".") || operator.equals("?.")) {
                    context.take(); List<SyntaxNode> children = new ArrayList<>(List.of(left));
                    if (operator.equals("?.") && context.at("(")) children.add(arguments());
                    else if (operator.equals("?.") && context.eat("[")) { children.add(expression(1)); context.expect("]"); }
                    else { if (context.at("#")) context.unsupported(context.peek(), "Private fields are not supported."); children.add(bindings.identifierName()); }
                    left = context.node(operator.equals("?.") ? "optionalMemberExpression" : "memberExpression", start, children); continue;
                }
                if (context.eat("[")) { ParsedNode key = expression(1); context.expect("]"); left = context.node("memberExpression", start, left, key); continue; }
                if (context.at("(")) { left = context.node("argumentsExpression", start, left, arguments()); continue; }
                if (context.at("`")) { left = context.node("taggedTemplateExpression", start, left, template(false)); continue; }
                if (context.at("<") && context.language() == Language.TYPESCRIPT && !context.newline()) {
                    Mark mark = context.mark(); ParsedNode arguments = null;
                    try {
                        arguments = types.typeArguments();
                        if (!(context.at("(") || context.at("`") || minimum == 17 && (context.at("{") || context.at("implements")))) arguments = null;
                    } catch (ParseFailure failure) { if (failure.limit) throw failure; if (!failure.diagnostic.code().equals("TS_SYNTAX")) throw failure; }
                    if (arguments != null) { left = context.node("instantiationExpression", start, left, arguments); continue; }
                    context.restore(mark);
                }
                if (context.at("!") && !context.newline()) { context.ts(); ParsedNode assertion = context.leaf("typeModifier"); left = context.node("nonNullExpression", start, left, assertion); continue; }
                if ((context.at("++") || context.at("--")) && !context.newline()) {
                    requireAssignable(left, false); context.take(); left = context.node("updateExpression", start, left); continue;
                }
                if ((context.at("as") || context.at("satisfies")) && minimum <= 11 && !context.newline()) {
                    context.ts(); int begin = context.start(); context.take(); ParsedNode erased = context.node("typeAssertion", begin, types.type(1));
                    left = context.node("assertionExpression", start, left, erased); continue;
                }
                if (context.at("?") && minimum <= 3) {
                    context.take(); ParsedNode yes = expression(2); context.expect(":"); ParsedNode no = expression(2);
                    left = context.node("conditionalExpression", start, left, yes, no); continue;
                }
                int precedence = precedence(operator);
                if (precedence < minimum || operator.equals("in") && !context.grammar.allowIn) break;
                context.take();
                if (precedence == 2) requireAssignable(left, operator.equals("="));
                if (operator.equals("**") && left.kind().equals("unaryExpression")) throw context.failure(context.last(), "Unary expression before exponentiation requires parentheses");
                ParsedNode right = expression(precedence == 2 || operator.equals("**") ? precedence : precedence + 1);
                if (Set.of("??", "&&", "||").contains(operator) && (mixedNullish(left, operator) || mixedNullish(right, operator)))
                    throw context.failure(context.last(), "Nullish and logical operators require parentheses when mixed");
                left = context.node(precedence == 2 ? "assignmentExpression" : "binaryExpression", start, left, right).with("operator", operator);
            }
            return left;
        } finally { context.leave(); }
    }

    private boolean mixedNullish(ParsedNode node, String operator) {
        String other = node.attribute("operator");
        return operator.equals("??") ? other.equals("&&") || other.equals("||") : other.equals("??");
    }

    private static int precedence(String operator) {
        return switch (operator) {
            case "," -> 1;
            case "=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=" -> 2;
            case "??", "||" -> 4; case "&&" -> 5; case "|" -> 6; case "^" -> 7; case "&" -> 8;
            case "==", "!=", "===", "!==" -> 9; case "<", ">", "<=", ">=", "in", "instanceof" -> 10;
            case "<<", ">>", ">>>" -> 11; case "+", "-" -> 12; case "*", "/", "%" -> 13; case "**" -> 14;
            default -> -1;
        };
    }

    @Override public void requireAssignable(ParsedNode value, boolean pattern) {
        if (value.kind().equals("parenthesizedExpression") || value.kind().equals("nonNullExpression")) {
            requireAssignable((ParsedNode) value.children().get(0), pattern); return;
        }
        if (value.kind().equals("identifier")) {
            if (context.grammar.strict && Set.of("eval", "arguments").contains(context.text(value))) throw context.failure(context.last(), "Invalid strict mode assignment");
            return;
        }
        if (value.kind().equals("memberExpression")) {
            SyntaxNode base = value.children().get(0);
            while (base.kind().equals("memberExpression")) base = base.children().get(0);
            if (!base.kind().equals("optionalMemberExpression")) return;
        }
        if (pattern && value.kind().equals("arrayLiteral")) {
            for (SyntaxNode item : value.children()) {
                ParsedNode element = (ParsedNode) item;
                if (element.kind().equals("spreadElement")) {
                    if (item != value.children().get(value.children().size() - 1) || value.attribute("trailingComma").equals("true")) throw context.failure(context.last(), "Rest element must be last");
                    element = (ParsedNode) element.children().get(0);
                }
                assignmentElement(element);
            }
            return;
        }
        if (pattern && value.kind().equals("objectLiteral")) {
            for (SyntaxNode member : value.children()) {
                ParsedNode property = (ParsedNode) member;
                if (property.children().size() > 2) throw context.failure(context.last(), "Method cannot be an assignment target");
                if (property.attribute("spread").equals("true")) {
                    if (member != value.children().get(value.children().size() - 1) || value.attribute("trailingComma").equals("true")) throw context.failure(context.last(), "Rest property must be last");
                    requireAssignable((ParsedNode) member.children().get(0), false);
                } else if (member.children().size() == 1 || property.attribute("shorthandDefault").equals("true")) {
                    String binding = spelling(context.text(member.children().get(0)));
                    if (context.grammar.strict && Set.of("eval", "arguments").contains(binding)) throw context.failure(context.last(), "Invalid strict assignment");
                } else assignmentElement((ParsedNode) member.children().get(1));
            }
            return;
        }
        throw context.failure(context.last(), "Invalid assignment target");
    }

    private void assignmentElement(ParsedNode element) {
        if (element.kind().equals("assignmentExpression") && element.attribute("operator").equals("="))
            requireAssignable((ParsedNode) element.children().get(0), true);
        else requireAssignable(element, true);
    }

    private ParsedNode prefix() {
        Token token = context.peek(TokenReader.Goal.REGULAR_EXPRESSION); int start = token.range().start();
        if ((context.at("(") || context.at("<") || context.at("async") || context.identifier(token)) && functions.arrowAhead()) return functions.arrow();
        token = context.peek(TokenReader.Goal.REGULAR_EXPRESSION);
        switch (token.kind()) {
            case NUMBER, BIGINT: {
                if (context.grammar.strict && token.kind() == TokenKind.NUMBER && token.text().length() > 1 && token.text().charAt(0) == '0' && Character.isDigit(token.text().charAt(1)))
                    throw context.failure(token, "Legacy numeric literal in strict mode");
                return context.leaf(token.kind() == TokenKind.NUMBER ? "numericLiteral" : "bigintLiteral");
            }
            case STRING: {
                if (context.grammar.strict && legacyStringEscape(token.text())) throw context.failure(token, "Legacy escape in strict mode");
                return context.leaf("stringLiteral");
            }
            case REGULAR_EXPRESSION: context.take(); return context.node("regularExpression", start);
            default: break;
        }
        String value = token.text();
        switch (value) {
            case "function": return functions.function(true);
            case "async": if (context.nextText().equals("function")) return functions.function(true); break;
            case "class": return classes.classDeclaration(true);
            case "[": return array();
            case "{": return object();
            case "`": return template(false);
            case "(": {
                context.take(); boolean oldIn = context.grammar.allowIn; context.grammar.allowIn = true; ParsedNode inner;
                try { inner = expression(1); } finally { context.grammar.allowIn = oldIn; }
                context.expect(")"); return context.node("parenthesizedExpression", start, inner);
            }
            case "<": {
                context.ts(); context.take(); ParsedNode type = types.type(0); context.greater(); ParsedNode erased = context.node("typeAssertion", start, type);
                return context.node("assertionExpression", start, erased, expression(15));
            }
            case "!", "~", "+", "-", "typeof", "void", "delete", "++", "--", "await": {
                if (value.equals("await") && context.grammar.functionDepth > 0 && !context.grammar.async) throw context.failure(token, "await outside async function");
                context.take(); ParsedNode operand = expression(15);
                if (value.equals("delete") && context.grammar.strict && operand.kind().equals("identifier")) throw context.failure(token, "Deleting an identifier in strict mode");
                if (value.equals("++") || value.equals("--")) requireAssignable(operand, false);
                return context.node("unaryExpression", start, operand).with("operator", value);
            }
            case "yield": {
                if (!context.grammar.generator) break;
                context.take(); List<SyntaxNode> children = new ArrayList<>();
                if (!context.newline() && !Set.of(";", "}", ")", "]", ",").contains(context.peek().text()) && !context.end()) { context.eat("*"); children.add(expression(2)); }
                return context.node("yieldExpression", start, children);
            }
            case "new": {
                context.take(); if (context.eat(".")) { context.expect("target"); return context.node("newTargetExpression", start); }
                return newExpression(start);
            }
            case "import": {
                context.take(); if (context.eat(".")) { context.expect("meta"); context.grammar.module = true; return context.node("importMetaExpression", start); }
                return context.node("importExpression", start, arguments());
            }
            case "this": return context.leaf("thisExpression");
            case "super": return context.leaf("superExpression");
            case "true", "false", "null": return context.leaf("literal");
            case "@": context.unsupported(token, "Decorator lowering is not supported."); break;
            case "#": context.unsupported(token, "Private fields are not supported."); break;
        }
        if (context.identifier(token)) {
            if (token.kind() == TokenKind.KEYWORD && token.text().contains("\\")) throw context.failure(token, "Escaped reserved keyword");
            return context.leaf("identifier");
        }
        throw context.failure(token, "Expected expression");
    }

    private ParsedNode newExpression(int start) {
        context.enter(); try {
        ParsedNode callee = prefix();
        while (true) {
            int begin = callee.range().start();
            if (context.eat(".")) callee = context.node("memberExpression", begin, callee, bindings.identifierName());
            else if (context.eat("[")) { ParsedNode key = expression(1); context.expect("]"); callee = context.node("memberExpression", begin, callee, key); }
            else if (context.at("<") && context.language() == Language.TYPESCRIPT) {
                Mark mark = context.mark(); ParsedNode typeArguments;
                try { typeArguments = types.typeArguments(); }
                catch (ParseFailure failure) { if (failure.limit) throw failure; context.restore(mark); break; }
                callee = context.node("instantiationExpression", begin, callee, typeArguments);
            } else break;
        }
        if (context.at("?.")) throw context.failure(context.peek(), "Optional chain cannot be a new constructor");
        return context.at("(") ? context.node("newExpression", start, callee, arguments()) : context.node("newExpression", start, callee);
        } finally { context.leave(); }
    }

    private boolean legacyStringEscape(String text) {
        for (int i = 1; i < text.length() - 1; i++) if (text.charAt(i) == '\\') {
            char next = text.charAt(++i);
            if (next >= '1' && next <= '9' || next == '0' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) return true;
        }
        return false;
    }

    private ParsedNode arguments() {
        int start = context.start(); context.expect("("); List<SyntaxNode> children = new ArrayList<>();
        while (!context.at(")")) { context.eat("..."); children.add(expression(2)); if (!context.eat(",")) break; }
        context.expect(")"); return context.node("arguments", start, children);
    }

    private ParsedNode array() {
        int start = context.start(); context.expect("["); List<SyntaxNode> children = new ArrayList<>(); boolean trailingComma = false;
        while (!context.at("]")) {
            if (context.eat(",")) continue;
            int begin = context.start(); boolean spread = context.eat("..."); ParsedNode value = expression(2);
            children.add(spread ? context.node("spreadElement", begin, value) : value);
            if (!context.eat(",")) break; trailingComma = context.at("]");
        }
        context.expect("]"); return context.node("arrayLiteral", start, children).with("trailingComma", Boolean.toString(trailingComma));
    }

    private ParsedNode object() {
        int start = context.start(); context.expect("{"); List<SyntaxNode> children = new ArrayList<>(); boolean trailingComma = false;
        while (!context.at("}")) {
            int begin = context.start(); List<SyntaxNode> parts = new ArrayList<>(); boolean shorthandDefault = false; boolean spread = context.eat("..."); boolean memberAsync = false, memberGenerator = false;
            if (spread) parts.add(expression(2));
            else {
                boolean isAsync = context.at("async") && !Set.of("(", ":", ",", "}", "=", "<").contains(context.nextText());
                memberAsync = isAsync; if (isAsync) context.take(); boolean isGenerator = context.eat("*"); memberGenerator = isGenerator;
                String accessor = "";
                if ((context.at("get") || context.at("set")) && !Set.of("(", ":", ",", "}", "=", "<").contains(context.nextText())) accessor = context.take().text();
                ParsedNode key = bindings.propertyName(); parts.add(key);
                if (context.eat(":")) parts.add(expression(2));
                else if (context.eat("=")) { if (!bindings.identifierToken(key)) throw context.failure(context.last(), "Default requires a shorthand identifier"); shorthandDefault = true; parts.add(expression(2)); }
                else if (context.at("(") || context.at("<")) {
                    boolean oldAsync = context.grammar.async, oldGenerator = context.grammar.generator; context.grammar.async = isAsync; context.grammar.generator = isGenerator;
                    try {
                        if (context.at("<")) parts.add(types.typeParameters()); ParsedNode parameters = functions.parameters(false); parts.add(parameters); functions.checkAccessor(accessor, parameters);
                        if (context.at(":")) parts.add(types.annotation()); parts.add(functions.functionBody(parameters, true, false));
                    } finally { context.grammar.async = oldAsync; context.grammar.generator = oldGenerator; }
                } else if (!bindings.identifierToken(key)) throw context.failure(context.peek(), "Expected property value");
            }
            children.add(context.node("objectMember", begin, parts).with("shorthandDefault", Boolean.toString(shorthandDefault)).with("spread", Boolean.toString(spread)).with("async", Boolean.toString(memberAsync)).with("generator", Boolean.toString(memberGenerator)));
            if (!context.eat(",")) break; trailingComma = context.at("}");
        }
        context.expect("}"); return context.node("objectLiteral", start, children).with("trailingComma", Boolean.toString(trailingComma));
    }

    @Override public ParsedNode initializer(String kind) {
        int start = context.start(); return context.node(kind, start, expression(2));
    }

    @Override public ParsedNode template(boolean type) {
        int start = context.start(); context.expect("`"); List<SyntaxNode> children = new ArrayList<>();
        while (!context.at("`")) {
            if (context.eat("${")) { children.add(type ? types.type(0) : expression(1)); context.expect("}"); }
            else { if (context.peek().kind() != TokenKind.TEMPLATE) throw context.failure(context.peek(), "Expected template text"); context.take(); }
        }
        context.expect("`"); return context.node(type ? "templateType" : "templateLiteral", start, children);
    }
}
