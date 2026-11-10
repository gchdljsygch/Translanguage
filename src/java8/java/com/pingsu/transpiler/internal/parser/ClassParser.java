package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.parser.AbstractParseSession.Mark;
import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Class declarations, expressions and members. */
final class ClassParser implements GrammarRules.Classes {
    private final AbstractParseSession context;
    private final GrammarRules.Statements statements;
    private final GrammarRules.Bindings bindings;
    private final GrammarRules.Functions functions;
    private final GrammarRules.Types types;
    private final GrammarRules.Expressions expressions;
    private static final Set<String> MODIFIERS = Compat.set("public", "private", "protected", "readonly", "abstract", "declare", "override");

    ClassParser(AbstractParseSession context,
            GrammarRules.Statements statements,
            GrammarRules.Bindings bindings,
            GrammarRules.Functions functions,
            GrammarRules.Types types,
            GrammarRules.Expressions expressions) {
        this.context = context;
        this.statements = statements;
        this.bindings = bindings;
        this.functions = functions;
        this.types = types;
        this.expressions = expressions;
    }

    @Override public ParsedNode classDeclaration(boolean expression) {
        int start = context.start(); List<SyntaxNode> children = new ArrayList<>();
        children.addAll(DecoratorParser.parse(context, expressions, types));
        boolean oldStrict = context.grammar.strict; context.grammar.strict = true; try {
        if (context.at("abstract")) { context.ts(); children.add(context.leaf("typeModifier")); }
        context.expect("class");
        if (!context.at("{") && !context.at("extends") && !context.at("implements") && !context.at("<")) children.add(bindings.bindingIdentifier());
        else if (!expression) throw context.failure(context.peek(), "Class declaration requires a name");
        if (context.at("<")) children.add(types.typeParameters());
        boolean derived = context.eat("extends");
        if (derived) children.add(expressions.expression(17));
        if (context.at("implements")) {
            context.ts(); int begin = context.start(); context.take(); List<SyntaxNode> implementedTypes = new ArrayList<>();
            do { implementedTypes.add(types.type(0)); } while (context.eat(",")); children.add(context.node("implementsClause", begin, implementedTypes));
        }
        context.expect("{");
            boolean constructorSeen = false;
            while (!context.at("}")) {
                if (context.eat(";")) continue;
                ParsedNode member = classMember(derived);
                if (member.kind().equals("constructorDeclaration")) {
                    if (constructorSeen) throw context.failure(context.last(), "Duplicate constructor implementation"); constructorSeen = true;
                }
                children.add(member);
            }
            context.expect("}");
        return context.node(expression ? "classExpression" : "classDeclaration", start, children).with("derived", Boolean.toString(derived));
        } finally { context.grammar.strict = oldStrict; }
    }

    private ParsedNode classMember(boolean derived) {
        int start = context.start(); List<SyntaxNode> children = new ArrayList<>(); boolean ambient = false, isStatic = false, isAsync = false;
        children.addAll(DecoratorParser.parse(context, expressions, types));
        while (MODIFIERS.contains(context.peek().text()) || context.at("static") || context.at("async")) {
            String next = context.nextText();
            if (Compat.set("(", ":", "=", ";", "?", "!", "}", "<").contains(next)) break;
            if (context.at("static")) { isStatic = true; context.take(); }
            else if (context.at("async")) { isAsync = true; context.take(); if (context.newline()) throw context.failure(context.peek(), "Line terminator after async"); }
            else { context.ts(); ambient |= context.at("abstract") || context.at("declare"); children.add(context.leaf("typeModifier")); }
        }
        if (isStatic && context.at("{")) { children.add(statements.block(false)); return context.node("staticBlock", start, children); }
        // An index signature begins with [binding : type], not an arbitrary computed key.
        if (context.at("[")) {
            Mark mark = context.mark(); context.take();
            boolean index = context.identifier(context.peek()) && context.nextText().equals(":"); context.restore(mark);
            if (index) { context.ts(); children.add(types.indexSignature()); context.semicolon(); return context.node("indexMemberDeclaration", start, children); }
        }
        boolean autoAccessor = context.at("accessor") && !Compat.set("(", ":", "=", ";", "}").contains(context.nextText());
        if (autoAccessor) children.add(context.leaf("accessorModifier"));
        boolean isGenerator = context.eat("*"); String accessor = "";
        if ((context.at("get") || context.at("set")) && !Compat.set("(", ":", "=", ";", "}", "<").contains(context.nextText())) accessor = context.take().text();
        ParsedNode key = context.at("#") ? PrivateNames.parse(context) : bindings.propertyName(); children.add(key);
        if (context.at("?") || context.at("!")) { context.ts(); children.add(context.leaf("typeModifier")); }
        boolean constructor = context.text(key).equals("constructor") && !isStatic && accessor.isEmpty();
        if (context.at("<")) children.add(types.typeParameters());
        if (context.at("(")) {
            boolean oldAsync = context.grammar.async, oldGenerator = context.grammar.generator; context.grammar.async = isAsync; context.grammar.generator = isGenerator;
            try {
                ParsedNode parameters = functions.parameters(constructor); children.add(parameters);
                functions.checkAccessor(accessor, parameters);
                if (context.at(":")) children.add(types.annotation());
                if (!context.at("{")) { context.ts(); context.semicolon(); return context.node("overloadDeclaration", start, children); }
                if (ambient) context.unsupported(context.peek(), accessor.isEmpty() ? "An abstract/declare method cannot have an implementation." : "Abstract/declare accessors with bodies are not supported.");
                children.add(functions.functionBody(parameters, true, false));
                return context.node(constructor ? "constructorDeclaration" : "methodDeclaration", start, children).with("derived", Boolean.toString(derived)).with("static", Boolean.toString(isStatic)).with("async", Boolean.toString(isAsync)).with("generator", Boolean.toString(isGenerator)).with("accessor", accessor);
            } finally { context.grammar.async = oldAsync; context.grammar.generator = oldGenerator; }
        }
        if (!accessor.isEmpty() || isGenerator) throw context.failure(context.peek(), "Method requires a parameter list");
        if (context.at(":")) children.add(types.annotation());
        if (context.eat("=")) children.add(expressions.expression(2));
        context.semicolon(); return context.node(ambient ? "ambientDeclaration" : "propertyDeclaration", start, children).with("static", Boolean.toString(isStatic)).with("autoAccessor", Boolean.toString(autoAccessor));
    }
}
