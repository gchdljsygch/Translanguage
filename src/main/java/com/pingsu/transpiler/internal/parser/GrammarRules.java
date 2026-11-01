package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.List;

/** Grammar capabilities. Implementations share a session, never another concrete parser. */
final class GrammarRules {
    private GrammarRules() { }

    interface Statements {
        List<SyntaxNode> statements(boolean block, boolean directives);
        ParsedNode statement();
        ParsedNode block(boolean functionBody);
    }

    interface Bindings {
        ParsedNode variables(boolean terminate);
        ParsedNode binding();
        ParsedNode bindingIdentifier();
        ParsedNode identifierName();
        ParsedNode propertyName();
        boolean identifierToken(ParsedNode node);
    }

    interface Functions {
        ParsedNode function(boolean expression);
        ParsedNode parameters(boolean constructor);
        List<String> parameterNames(ParsedNode parameters);
        ParsedNode functionBody(ParsedNode parameters, boolean unique, boolean arrow);
        void checkAccessor(String accessor, ParsedNode parameters);
        boolean arrowAhead();
        ParsedNode arrow();
    }

    interface Classes {
        ParsedNode classDeclaration(boolean expression);
    }

    interface Declarations {
        ParsedNode interfaceDeclaration();
        ParsedNode typeAlias();
        ParsedNode ambientNamespace();
        ParsedNode ambient();
        ParsedNode enumDeclaration();
        ParsedNode moduleDeclaration(boolean exporting);
    }

    interface Types {
        ParsedNode annotation();
        ParsedNode typeParameters();
        ParsedNode typeArguments();
        ParsedNode type(int minimum);
        ParsedNode indexSignature();
        ParsedNode objectType();
    }

    interface Expressions {
        ParsedNode expression(int minimum);
        void requireAssignable(ParsedNode value, boolean pattern);
        ParsedNode initializer(String kind);
        ParsedNode template(boolean type);
    }
}
