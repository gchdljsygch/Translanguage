package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.pingsu.transpiler.internal.parser.IdentifierRules.spelling;

/** Type declarations, ambient declarations, enums and ES modules. */
final class DeclarationParser implements GrammarRules.Declarations {
    private final AbstractParseSession context;
    private final GrammarRules.Statements statements;
    private final GrammarRules.Bindings bindings;
    private final GrammarRules.Functions functions;
    private final GrammarRules.Classes classes;
    private final GrammarRules.Types types;
    private final GrammarRules.Expressions expressions;

    DeclarationParser(AbstractParseSession context,
            GrammarRules.Statements statements,
            GrammarRules.Bindings bindings,
            GrammarRules.Functions functions,
            GrammarRules.Classes classes,
            GrammarRules.Types types,
            GrammarRules.Expressions expressions) {
        this.context = context;
        this.statements = statements;
        this.bindings = bindings;
        this.functions = functions;
        this.classes = classes;
        this.types = types;
        this.expressions = expressions;
    }

    @Override public ParsedNode interfaceDeclaration() {
        int start = context.start(); context.expect("interface"); List<SyntaxNode> children = new ArrayList<>(Compat.list(bindings.bindingIdentifier()));
        if (context.at("<")) children.add(types.typeParameters());
        if (context.eat("extends")) do { children.add(types.type(1)); } while (context.eat(","));
        children.add(types.objectType()); context.eat(";"); return context.node("interfaceDeclaration", start, children);
    }

    @Override public ParsedNode typeAlias() {
        int start = context.start(); context.expect("type"); List<SyntaxNode> children = new ArrayList<>(Compat.list(bindings.bindingIdentifier()));
        if (context.at("<")) children.add(types.typeParameters()); context.expect("="); children.add(types.type(0)); context.semicolon();
        return context.node("typeAliasDeclaration", start, children);
    }

    @Override public ParsedNode namespaceDeclaration() {
        int start = context.start(); context.take();
        return namespacePath(start);
    }

    private ParsedNode namespacePath(int start) {
        context.enter(); try {
        String name = context.text(bindings.bindingIdentifier());
        List<SyntaxNode> children = new ArrayList<>();
        if (context.eat(".")) {
            int nested = context.start();
            children.add(context.node("namespaceExport", nested, namespacePath(nested)));
        } else {
            context.expect("{");
            int previous = context.grammar.namespaceBodyDepth;
            context.grammar.namespaceBodyDepth = context.depth() + 1;
            try { children.addAll(statements.statements(true, false)); }
            finally { context.grammar.namespaceBodyDepth = previous; }
            context.expect("}");
        }
        return context.node("namespaceDeclaration", start, children).with("name", name);
        } finally { context.leave(); }
    }

    @Override public ParsedNode ambientNamespace() {
        int start = context.start(); context.take();
        if (context.peek().kind() == TokenKind.STRING) context.take(); else { bindings.bindingIdentifier(); while (context.eat(".")) bindings.bindingIdentifier(); }
        context.expect("{"); List<SyntaxNode> children = new ArrayList<>();
        while (!context.at("}")) children.add(ambientStatement());
        context.expect("}"); return context.node("ambientDeclaration", start, children);
    }

    private ParsedNode ambientStatement() {
        int start=context.start();
        if(context.eat("export")&&context.eat("=")) {
            ParsedNode value=expressions.expression(2);context.semicolon();return context.node("exportAssignment",start,value);
        }
        return statements.statement();
    }

    @Override public ParsedNode ambient() {
        int start = context.start(); context.expect("declare"); List<SyntaxNode> children = new ArrayList<>();
        boolean oldAmbient = context.grammar.ambientContext; context.grammar.ambientContext = true; try {
        if (context.eat("global")) {
            context.expect("{"); children.addAll(statements.statements(true, false)); context.expect("}");
        } else if (context.eat("namespace") || context.eat("module")) {
            if (context.peek().kind() == TokenKind.STRING) context.take(); else { bindings.bindingIdentifier(); while (context.eat(".")) bindings.bindingIdentifier(); }
            context.expect("{");
            while (!context.at("}")) children.add(ambientStatement());
            context.expect("}");
        } else if (Compat.set("var", "let", "const").contains(context.peek().text()) && !context.nextText().equals("enum")) {
            // Ambient const declarations legitimately have no runtime initializer.
            int begin = context.start(); context.take();
            do { children.add(bindings.binding()); if (context.at(":")) children.add(types.annotation()); if (context.eat("=")) children.add(expressions.expression(2)); } while (context.eat(","));
            context.semicolon(); children = new ArrayList<>(Compat.list(context.node("variableStatement", begin, children)));
        } else children.add(statements.statement());
        return context.node("ambientDeclaration", start, children);
        } finally { context.grammar.ambientContext = oldAmbient; }
    }

    @Override public ParsedNode enumDeclaration() {
        int start = context.start(); context.eat("const"); context.expect("enum"); String enumName = context.text(bindings.bindingIdentifier()); context.expect("{");
        List<SyntaxNode> members = new ArrayList<>();
        while (!context.at("}")) {
            int begin = context.start(); ParsedNode key = bindings.propertyName(); List<SyntaxNode> parts = new ArrayList<>(Compat.list(key));
            if (context.eat("=")) parts.add(expressions.expression(2));
            members.add(context.node("enumMember", begin, parts)); if (!context.eat(",")) break;
        }
        context.expect("}"); return context.node("enumDeclaration", start, members).with("name", enumName);
    }

    @Override public ParsedNode moduleDeclaration(boolean exporting) {
        int start = context.start(); context.take();
        if (context.grammar.namespaceBodyDepth == context.depth()) {
            if (!exporting || context.at("default") || context.at("{") || context.at("*"))
                throw context.failure(context.last(), "Namespaces only allow exported declarations, not ES module imports/exports");
            ParsedNode declaration = statements.statement();
            String kind = declaration.kind();
            if (!(kind.endsWith("Declaration") || kind.equals("variableStatement") || kind.equals("decoratedDeclaration")))
                throw context.failure(context.last(), "Expected an exported namespace declaration");
            return context.node("namespaceExport", start, declaration);
        }
        if (!context.grammar.ambientContext) context.grammar.module = true;
        if (context.depth() != 1 && !context.grammar.ambientContext) throw context.failure(context.last(), "Module declarations must be at top level");
        List<SyntaxNode> children = new ArrayList<>(); boolean typeOnly = false, hasDefault = false; String namespaceExport = "";
        if (exporting && context.eat("=")) {
            context.ts(); children.add(expressions.expression(2)); context.semicolon();
            return context.node("exportAssignment", start, children);
        }
        if (exporting && context.eat("as")) {
            context.ts(); context.expect("namespace"); children.add(bindings.bindingIdentifier()); context.semicolon();
            return context.node("ambientDeclaration", start, children);
        }
        if (exporting && context.eat("default")) {
            if (context.at("class") || context.at("abstract") || context.at("@")) children.add(classes.classDeclaration(true));
            else if (context.at("function") || context.at("async") && context.nextText().equals("function")) children.add(functions.function(true));
            else { children.add(expressions.expression(2)); context.semicolon(); }
            return context.node("exportDeclaration", start, children);
        }
        if (context.at("type") && (context.nextText().equals("{") || context.nextText().equals("*") || !exporting && !Compat.set(",", "from", "=").contains(context.nextText()))) {
            context.ts(); context.take(); typeOnly = true;
        }
        if (!exporting && context.identifier(context.peek()) && context.nextText().equals("=")) {
            context.ts(); children.add(bindings.bindingIdentifier()); context.expect("=");
            if (context.eat("require")) {
                context.expect("(");
                if (context.peek().kind() != TokenKind.STRING) throw context.failure(context.peek(), "Expected module string");
                children.add(context.leaf("stringLiteral")); context.expect(")");
            } else {
                ParsedNode reference = bindings.bindingIdentifier().with("bindingName", "");
                while (context.eat(".")) reference = context.node("memberExpression", reference.range().start(), reference, bindings.identifierName());
                children.add(reference);
            }
            context.semicolon(); return context.node("importEqualsDeclaration", start, children).with("typeOnly", Boolean.toString(typeOnly));
        }
        if (exporting && !context.at("{") && !context.at("*")) {
            children.add(statements.statement()); return context.node("exportDeclaration", start, children);
        }
        if (!exporting && context.peek().kind() == TokenKind.STRING) { context.take(); context.semicolon(); return context.node("importDeclaration", start); }
        if (!context.at("{") && !context.at("*")) {
            children.add(bindings.bindingIdentifier()); hasDefault = true;
            if (!context.at("from")) context.expect(",");
        }
        if (context.at("{")) {
            int begin = context.start(); context.take(); List<SyntaxNode> specifiers = new ArrayList<>();
            while (!context.at("}")) {
                int specStart = context.start(); boolean type = context.at("type") && !Compat.set("as", ",", "}").contains(context.nextText());
                if (type) { context.ts(); context.take(); }
                ParsedNode original = bindings.propertyName();
                String localName = context.text(original);
                if (context.eat("as")) {
                    if (exporting) bindings.propertyName();
                    else localName = context.text(bindings.bindingIdentifier());
                }
                specifiers.add(context.node(type ? "typeSpecifier" : "moduleSpecifier", specStart)
                        .with("localName", localName.startsWith("\"") || localName.startsWith("'") ? "" : spelling(localName)));
                if (!context.eat(",")) break;
            }
            context.expect("}"); children.add(context.node("moduleItems", begin, specifiers));
        } else if (context.at("*")) {
            int begin = context.start(); context.take();
            if (context.eat("as")) {
                com.pingsu.transpiler.internal.parser.ParsedNode name = bindings.bindingIdentifier();
                if (exporting) namespaceExport = context.text(name);
                else children.add(context.node("namespaceImport", begin, name).with("localName", spelling(context.text(name))));
            }
        }
        String moduleSource = "";
        if (!exporting || context.at("from")) {
            context.expect("from");
            if (context.peek().kind() != TokenKind.STRING) throw context.failure(context.peek(), "Expected module string");
            moduleSource = context.take().text();
        }
        context.semicolon();
        return context.node(exporting ? "exportDeclaration" : "importDeclaration", start, children)
                .with("typeOnly", Boolean.toString(typeOnly)).with("defaultBinding", Boolean.toString(hasDefault))
                .with("namespaceExport", namespaceExport).with("moduleSource", moduleSource);
    }
}
