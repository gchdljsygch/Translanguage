package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.parser.AbstractParseSession.Mark;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Statement dispatch, directives, blocks and control flow. */
final class StatementParser implements GrammarRules.Statements {
    private final AbstractParseSession context;
    private final GrammarRules.Bindings bindings;
    private final GrammarRules.Functions functions;
    private final GrammarRules.Classes classes;
    private final GrammarRules.Declarations declarations;
    private final GrammarRules.Types types;
    private final GrammarRules.Expressions expressions;

    StatementParser(AbstractParseSession context,
            GrammarRules.Bindings bindings,
            GrammarRules.Functions functions,
            GrammarRules.Classes classes,
            GrammarRules.Declarations declarations,
            GrammarRules.Types types,
            GrammarRules.Expressions expressions) {
        this.context = context;
        this.bindings = bindings;
        this.functions = functions;
        this.classes = classes;
        this.declarations = declarations;
        this.types = types;
        this.expressions = expressions;
    }

    @Override public List<SyntaxNode> statements(boolean block, boolean directives) {
        List<SyntaxNode> result = new ArrayList<>();
        while (!context.end() && !(block && context.at("}"))) {
            boolean directive = directives && context.peek().kind() == TokenKind.STRING;
            ParsedNode statement = statement(); result.add(statement);
            if (directive && statement.kind().equals("expressionStatement") && statement.children().size() == 1
                    && statement.children().get(0).kind().equals("stringLiteral")) {
                String literal = context.text(statement.children().get(0));
                if (literal.equals("\"use strict\"") || literal.equals("'use strict'")) context.grammar.strict = true;
            } else directives = false;
        }
        return result;
    }

    @Override public ParsedNode statement() {
        context.enter();
        try {
            int start = context.start();
            switch (context.peek().text()) {
                case ";": return context.leaf("emptyStatement");
                case "{": return block(false);
                case "@": {
                    List<SyntaxNode> decorators = DecoratorParser.parse(context, expressions, types);
                    if (!(context.at("class") || context.at("abstract") || context.at("export"))) throw context.failure(context.peek(), "Decorators require a class declaration");
                    ParsedNode declaration = context.at("export") ? declarations.moduleDeclaration(true) : statement();
                    ParsedNode target = declaration;
                    if (target.kind().equals("namespaceExport")) {
                        ParsedNode exported = (ParsedNode) target.children().get(0);
                        if (!(exported.kind().equals("classDeclaration") || exported.kind().equals("classExpression")))
                            throw context.failure(context.last(), "Decorators require a class declaration");
                        decorators.add(exported);
                        return context.node("namespaceExport", start, context.node("decoratedDeclaration", start, decorators));
                    }
                    if (target.kind().equals("exportDeclaration") && target.children().size() == 1) target = (ParsedNode) target.children().get(0);
                    if (!(target.kind().equals("classDeclaration") || target.kind().equals("classExpression") || target.kind().equals("decoratedDeclaration")))
                        throw context.failure(context.last(), "Decorators require a class declaration");
                    decorators.add(declaration); return context.node("decoratedDeclaration", start, decorators);
                }
                case "interface": context.ts(); return declarations.interfaceDeclaration();
                case "type": if (identifierAfter()) { context.ts(); return declarations.typeAlias(); } break;
                case "declare": if (!Set.of("=", ";", "(", ".").contains(context.nextText())) { context.ts(); return declarations.ambient(); } break;
                case "namespace", "module": if (context.grammar.ambientContext) return declarations.ambientNamespace(); if (!Set.of("=", ";", "(", ".").contains(context.nextText())) { context.ts(); return declarations.namespaceDeclaration(); } break;
                case "enum": context.ts(); return declarations.enumDeclaration();
                case "const": if (context.nextText().equals("enum")) { context.ts(); return declarations.enumDeclaration(); }
                case "let", "var": return bindings.variables(true);
                case "using": if (resourceAhead()) return bindings.variables(true); break;
                case "await": if (context.nextText().equals("using")) return bindings.variables(true); break;
                case "abstract": if (context.nextText().equals("class")) { context.ts(); return classes.classDeclaration(false); } break;
                case "class": return classes.classDeclaration(false);
                case "function": return functions.function(false);
                case "async": if (context.nextText().equals("function")) return functions.function(false); break;
                case "import": if (!Set.of("(", ".").contains(context.nextText())) return declarations.moduleDeclaration(false); break;
                case "export": return declarations.moduleDeclaration(true);
                case "if": {
                    context.take(); context.expect("("); ParsedNode condition = expressions.expression(1); context.expect(")");
                    List<SyntaxNode> children = new ArrayList<>(List.of(condition, statement()));
                    if (context.eat("else")) children.add(statement()); return context.node("ifStatement", start, children);
                }
                case "for": return forStatement();
                case "while", "do": return loop();
                case "switch": return switchStatement();
                case "try": return tryStatement();
                case "return", "throw", "break", "continue": return jump();
                case "debugger": context.take(); context.semicolon(); return context.node("debuggerStatement", start);
                case "with": {
                    if (context.grammar.strict) throw context.failure(context.peek(), "with is forbidden in strict mode");
                    context.take(); context.expect("("); ParsedNode object = expressions.expression(1); context.expect(")"); return context.node("withStatement", start, object, statement());
                }
            }
            if (context.identifier(context.peek()) && context.nextText().equals(":")) {
                String label = context.take().text(); context.expect(":");
                if (!context.grammar.labels.add(label)) throw context.failure(context.last(), "Duplicate label");
                boolean iteration = Set.of("for", "while", "do").contains(context.peek().text());
                if (iteration) context.grammar.iterationLabels.add(label);
                ParsedNode body = statement(); context.grammar.labels.remove(label); context.grammar.iterationLabels.remove(label);
                return context.node("labelledStatement", start, body).with("name", label);
            }
            ParsedNode value = expressions.expression(1); context.semicolon(); return context.node("expressionStatement", start, value);
        } finally { context.leave(); }
    }

    private boolean resourceAhead() { Mark mark=context.mark();context.take();boolean result=!context.newline()&&context.identifier(context.peek());context.restore(mark);return result; }
    private boolean identifierAfter() { Mark mark = context.mark(); context.take(); boolean result = context.identifier(context.peek()); context.restore(mark); return result; }

    @Override public ParsedNode block(boolean functionBody) {
        int start = context.start(); context.expect("{"); List<SyntaxNode> children = statements(true, functionBody); context.expect("}");
        return context.node(functionBody ? "functionBody" : "block", start, children);
    }

    private ParsedNode forStatement() {
        int start = context.start(); context.expect("for"); boolean awaiting = context.eat("await"); context.expect("(");
        List<SyntaxNode> children = new ArrayList<>(); boolean oldIn = context.grammar.allowIn; context.grammar.allowIn = false;
        try { if (!context.at(";")) children.add((Set.of("var", "let", "const", "using").contains(context.peek().text()) || context.at("await") && context.nextText().equals("using")) ? bindings.variables(false) : expressions.expression(1)); }
        finally { context.grammar.allowIn = oldIn; }
        if (context.at("in") || context.at("of")) {
            String operator = context.take().text();
            if (awaiting && !operator.equals("of")) throw context.failure(context.last(), "for await requires of");
            if (children.isEmpty()) throw context.failure(context.last(), "Missing loop binding");
            ParsedNode init = (ParsedNode) children.get(0);
            if (init.kind().equals("variableStatement")) {
                if (init.children().size() != 1) throw context.failure(context.last(), "Loop requires one binding");
                var declaration = init.children().get(0);
                if (declaration.children().stream().skip(1).anyMatch(n -> !Set.of("typeAnnotation", "typeModifier").contains(n.kind())))
                    throw context.failure(context.last(), "for-in/of binding cannot have an initializer");
            } else expressions.requireAssignable(init, true);
            children.add(expressions.expression(operator.equals("of") ? 2 : 1));
        } else {
            if (awaiting) throw context.failure(context.peek(), "for await requires of");
            context.expect(";"); if (!context.at(";")) children.add(expressions.expression(1)); context.expect(";"); if (!context.at(")")) children.add(expressions.expression(1));
        }
        context.expect(")"); context.grammar.loops++; try { children.add(statement()); } finally { context.grammar.loops--; }
        return context.node("forStatement", start, children).with("await", Boolean.toString(awaiting));
    }

    private ParsedNode loop() {
        int start = context.start(); boolean doLoop = context.eat("do"); List<SyntaxNode> children = new ArrayList<>(); context.grammar.loops++;
        try {
            if (doLoop) children.add(statement());
            context.expect("while"); context.expect("("); children.add(expressions.expression(1)); context.expect(")");
            if (!doLoop) children.add(statement()); else context.eat(";");
        } finally { context.grammar.loops--; }
        return context.node(doLoop ? "doStatement" : "whileStatement", start, children);
    }

    private ParsedNode switchStatement() {
        int start = context.start(); context.take(); context.expect("("); List<SyntaxNode> children = new ArrayList<>(List.of(expressions.expression(1))); context.expect(")"); context.expect("{");
        boolean seenDefault = false; context.grammar.switches++;
        try {
            while (!context.at("}")) {
                if (context.eat("case")) children.add(expressions.expression(1));
                else { context.expect("default"); if (seenDefault) throw context.failure(context.last(), "Duplicate default clause"); seenDefault = true; }
                context.expect(":"); while (!context.at("case") && !context.at("default") && !context.at("}")) children.add(statement());
            }
            context.expect("}");
        } finally { context.grammar.switches--; }
        return context.node("switchStatement", start, children);
    }

    private ParsedNode tryStatement() {
        int start = context.start(); context.take(); List<SyntaxNode> children = new ArrayList<>(List.of(block(false))); boolean handler = false;
        if (context.eat("catch")) {
            handler = true;
            if (context.eat("(")) { children.add(bindings.binding()); if (context.at(":")) children.add(types.annotation()); context.expect(")"); }
            children.add(block(false));
        }
        if (context.eat("finally")) { handler = true; children.add(block(false)); }
        if (!handler) throw context.failure(context.peek(), "try requires catch or finally");
        return context.node("tryStatement", start, children);
    }

    private ParsedNode jump() {
        int start = context.start(); Token keyword = context.take(); String kind = keyword.text(); List<SyntaxNode> children = new ArrayList<>();
        if (kind.equals("return") && context.grammar.functionDepth == 0) throw context.failure(keyword, "return outside function");
        boolean line = context.newline();
        if (kind.equals("throw") && line) throw context.failure(context.peek(), "Line terminator after throw");
        if (kind.equals("break") || kind.equals("continue")) {
            String label = !line && context.identifier(context.peek()) ? context.take().text() : "";
            if (!label.isEmpty() && !(kind.equals("continue") ? context.grammar.iterationLabels : context.grammar.labels).contains(label)) throw context.failure(keyword, "Unknown jump label");
            if (label.isEmpty() && context.grammar.loops == 0 && (kind.equals("continue") || context.grammar.switches == 0)) throw context.failure(keyword, "Jump outside loop/switch");
        } else if (!line && !context.at(";") && !context.at("}") && !context.end()) children.add(expressions.expression(1));
        else if (kind.equals("throw")) throw context.failure(context.peek(), "throw requires an expression");
        context.semicolon(); return context.node(kind + "Statement", start, children);
    }
}
