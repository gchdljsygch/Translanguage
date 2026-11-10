package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.List;

/** Wires mutually recursive grammar capabilities for one parse session. */
final class RecursiveDescentGrammar implements
        GrammarRules.Statements, GrammarRules.Bindings, GrammarRules.Functions, GrammarRules.Classes, GrammarRules.Declarations, GrammarRules.Types, GrammarRules.Expressions {
    private final GrammarRules.Statements statements;
    private final GrammarRules.Bindings bindings;
    private final GrammarRules.Functions functions;
    private final GrammarRules.Classes classes;
    private final GrammarRules.Declarations declarations;
    private final GrammarRules.Types types;
    private final GrammarRules.Expressions expressions;

    RecursiveDescentGrammar(AbstractParseSession context) {
        statements = new StatementParser(context, this, this, this, this, this, this);
        bindings = new BindingParser(context, this, this);
        functions = new FunctionParser(context, this, this, this, this);
        classes = new ClassParser(context, this, this, this, this, this);
        declarations = new DeclarationParser(context, this, this, this, this, this, this);
        types = new TypeParser(context, this, this);
        expressions = new ExpressionParser(context, this, this, this, this);
    }

    @Override public List<SyntaxNode> statements(boolean block, boolean directives) { return statements.statements(block, directives); }
    @Override public ParsedNode statement() { return statements.statement(); }
    @Override public ParsedNode block(boolean functionBody) { return statements.block(functionBody); }

    @Override public ParsedNode variables(boolean terminate) { return bindings.variables(terminate); }
    @Override public ParsedNode binding() { return bindings.binding(); }
    @Override public ParsedNode bindingIdentifier() { return bindings.bindingIdentifier(); }
    @Override public ParsedNode identifierName() { return bindings.identifierName(); }
    @Override public ParsedNode propertyName() { return bindings.propertyName(); }
    @Override public boolean identifierToken(ParsedNode node) { return bindings.identifierToken(node); }

    @Override public ParsedNode function(boolean expression) { return functions.function(expression); }
    @Override public ParsedNode parameters(boolean constructor) { return functions.parameters(constructor); }
    @Override public List<String> parameterNames(ParsedNode parameters) { return functions.parameterNames(parameters); }
    @Override public ParsedNode functionBody(ParsedNode parameters, boolean unique, boolean arrow) { return functions.functionBody(parameters, unique, arrow); }
    @Override public void checkAccessor(String accessor, ParsedNode parameters) { functions.checkAccessor(accessor, parameters); }
    @Override public boolean arrowAhead() { return functions.arrowAhead(); }
    @Override public ParsedNode arrow() { return functions.arrow(); }

    @Override public ParsedNode classDeclaration(boolean expression) { return classes.classDeclaration(expression); }

    @Override public ParsedNode interfaceDeclaration() { return declarations.interfaceDeclaration(); }
    @Override public ParsedNode typeAlias() { return declarations.typeAlias(); }
    @Override public ParsedNode ambientNamespace() { return declarations.ambientNamespace(); }
    @Override public ParsedNode namespaceDeclaration() { return declarations.namespaceDeclaration(); }
    @Override public ParsedNode ambient() { return declarations.ambient(); }
    @Override public ParsedNode enumDeclaration() { return declarations.enumDeclaration(); }
    @Override public ParsedNode moduleDeclaration(boolean exporting) { return declarations.moduleDeclaration(exporting); }

    @Override public ParsedNode annotation() { return types.annotation(); }
    @Override public ParsedNode typeParameters() { return types.typeParameters(); }
    @Override public ParsedNode typeArguments() { return types.typeArguments(); }
    @Override public ParsedNode type(int minimum) { return types.type(minimum); }
    @Override public ParsedNode indexSignature() { return types.indexSignature(); }
    @Override public ParsedNode objectType() { return types.objectType(); }

    @Override public ParsedNode expression(int minimum) { return expressions.expression(minimum); }
    @Override public void requireAssignable(ParsedNode value, boolean pattern) { expressions.requireAssignable(value, pattern); }
    @Override public ParsedNode initializer(String kind) { return expressions.initializer(kind); }
    @Override public ParsedNode template(boolean type) { return expressions.template(type); }
}
