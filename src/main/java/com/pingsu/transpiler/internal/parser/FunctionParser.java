package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.parser.AbstractParseSession.Mark;
import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.pingsu.transpiler.internal.parser.IdentifierRules.STRICT_RESERVED;
import static com.pingsu.transpiler.internal.parser.IdentifierRules.spelling;

/** Functions, parameters, arrows and function-scope validation. */
final class FunctionParser implements GrammarRules.Functions {
    private final AbstractParseSession context;
    private final GrammarRules.Statements statements;
    private final GrammarRules.Bindings bindings;
    private final GrammarRules.Types types;
    private final GrammarRules.Expressions expressions;

    FunctionParser(AbstractParseSession context,
            GrammarRules.Statements statements,
            GrammarRules.Bindings bindings,
            GrammarRules.Types types,
            GrammarRules.Expressions expressions) {
        this.context = context;
        this.statements = statements;
        this.bindings = bindings;
        this.types = types;
        this.expressions = expressions;
    }

    @Override public ParsedNode function(boolean expression) {
        int start = context.start(); boolean isAsync = context.eat("async");
        if (isAsync && context.newline()) throw context.failure(context.peek(), "Line terminator after async");
        context.expect("function"); boolean isGenerator = context.eat("*"); List<SyntaxNode> children = new ArrayList<>();
        if (!context.at("(") && !context.at("<")) children.add(bindings.bindingIdentifier());
        else if (!expression) throw context.failure(context.peek(), "Function declaration requires a name");
        boolean oldAsync = context.grammar.async, oldGenerator = context.grammar.generator; context.grammar.async = isAsync; context.grammar.generator = isGenerator;
        try {
            if (context.at("<")) children.add(types.typeParameters());
            ParsedNode parameters = parameters(false); children.add(parameters);
            if (context.at(":")) children.add(types.annotation());
            if (context.at("{")) {
                ParsedNode body = functionBody(parameters, isAsync || isGenerator, false); children.add(body);
                if (body.attribute("strict").equals("true") && !children.isEmpty() && children.get(0).kind().equals("identifier")) {
                    String functionName = spelling(context.text(children.get(0)));
                    if (Set.of("eval", "arguments").contains(functionName) || STRICT_RESERVED.contains(functionName)) throw context.failure(context.last(), "Invalid strict function name");
                }
            }
            else { context.ts(); if (expression) throw context.failure(context.peek(), "Function expression requires a body"); context.semicolon(); return context.node("overloadDeclaration", start, children); }
            return context.node(expression ? "functionExpression" : "functionDeclaration", start, children).with("async", Boolean.toString(isAsync)).with("generator", Boolean.toString(isGenerator));
        } finally { context.grammar.async = oldAsync; context.grammar.generator = oldGenerator; }
    }

    @Override public ParsedNode parameters(boolean constructor) {
        int start = context.start(); context.expect("("); List<SyntaxNode> children = new ArrayList<>();
        while (!context.at(")")) {
            int begin = context.start(); List<SyntaxNode> parts = new ArrayList<>(); boolean property = false, rest = context.eat("...");
            while (Set.of("public", "private", "protected", "readonly", "override").contains(context.peek().text())) {
                context.ts(); property = true; parts.add(context.leaf("typeModifier"));
            }
            ParsedNode binding;
            if (context.at("this")) { context.ts(); binding = context.leaf("identifier"); }
            else binding = bindings.binding();
            parts.add(binding);
            if (context.at("?")) { context.ts(); parts.add(context.leaf("typeModifier")); }
            if (context.at(":")) parts.add(types.annotation());
            if (context.eat("=")) { if (rest) throw context.failure(context.last(), "Rest parameter cannot have a default"); parts.add(expressions.initializer("parameterInitializer")); }
            boolean comma = context.eat(",");
            if (rest && comma) throw context.failure(context.last(), "Rest parameter must be last without a trailing comma");
            boolean thisParameter = context.text(binding).equals("this");
            if (thisParameter && !children.isEmpty()) context.unsupported(context.last(), "The this parameter must be the first parameter.");
            ParsedNode parameter = context.node(thisParameter ? "thisParameter" : property ? "parameterProperty" : "parameter", begin, parts)
                    .with("name", context.text(binding)).with("simple", Boolean.toString(binding.kind().equals("identifier")))
                    .with("rest", Boolean.toString(rest));
            if (property && !constructor) context.unsupported(context.last(), "Parameter properties are only valid in constructors.");
            children.add(parameter);
            if (!comma) break;
        }
        context.expect(")"); return context.node("parameters", start, children);
    }

    @Override public List<String> parameterNames(ParsedNode parameters) {
        List<String> names = new ArrayList<>();
        parameters.walk(node -> {
            if (Set.of("typeAnnotation", "typeParameters", "bindingInitializer", "parameterInitializer").contains(node.kind())) return false;
            if (node instanceof ParsedNode parsed && !parsed.attribute("bindingName").isEmpty()) names.add(parsed.attribute("bindingName"));
            return true;
        });
        return names;
    }

    @Override public ParsedNode functionBody(ParsedNode parameters, boolean unique, boolean arrow) {
        int oldLoops = context.grammar.loops, oldSwitches = context.grammar.switches; boolean oldStrict = context.grammar.strict;
        Set<String> oldLabels = new HashSet<>(context.grammar.labels), oldIterationLabels = new HashSet<>(context.grammar.iterationLabels);
        context.grammar.labels.clear(); context.grammar.iterationLabels.clear(); context.grammar.loops = 0; context.grammar.switches = 0; context.grammar.functionDepth++;
        try {
            ParsedNode body = statements.block(true);
            Set<String> names = new HashSet<>(); boolean simple = parameters.children().stream().allMatch(p -> {
                ParsedNode parameter = (ParsedNode) p;
                return parameter.attribute("simple").equals("true") && parameter.attribute("rest").equals("false") && parameter.children().stream().allMatch(n -> Set.of("identifier", "typeAnnotation", "typeModifier").contains(n.kind()));
            });
            if (!oldStrict && context.grammar.strict && !simple) throw context.failure(context.last(), "use strict directive with non-simple parameters");
            for (String binding : parameterNames(parameters)) {
                if ((context.grammar.strict || unique || arrow || !simple) && !names.add(binding)) throw context.failure(context.last(), "Duplicate parameter");
                if (context.grammar.strict && (Set.of("eval", "arguments").contains(binding) || STRICT_RESERVED.contains(binding)))
                    throw context.failure(context.last(), "Invalid strict mode parameter");
            }
            return body.with("strict", Boolean.toString(context.grammar.strict));
        } finally {
            context.grammar.functionDepth--; context.grammar.loops = oldLoops; context.grammar.switches = oldSwitches; context.grammar.strict = oldStrict;
            context.grammar.labels.clear(); context.grammar.labels.addAll(oldLabels); context.grammar.iterationLabels.clear(); context.grammar.iterationLabels.addAll(oldIterationLabels);
        }
    }

    @Override public void checkAccessor(String accessor, ParsedNode parameters) {
        int size = parameters.children().size();
        if (accessor.equals("get") && size != 0 || accessor.equals("set") && (size != 1
                || ((ParsedNode) parameters.children().get(0)).attribute("rest").equals("true")))
            throw context.failure(context.last(), "Getter requires zero parameters; setter requires one non-rest parameter");
    }

    @Override public boolean arrowAhead() {
        Mark mark = context.mark();
        try {
            boolean asynchronous = context.at("async") && !context.nextText().equals("=>");
            if (asynchronous) { context.take(); if (context.newline()) return false; }
            if (context.at("<")) types.typeParameters();
            if (context.at("(")) parameters(false); else bindings.bindingIdentifier();
            if (context.at(":")) types.annotation();
            return !context.newline() && context.at("=>");
        } catch (ParseFailure failure) { if (failure.limit) throw failure; return false; }
        finally { context.restore(mark); }
    }

    @Override public ParsedNode arrow() {
        int start = context.start(); List<SyntaxNode> children = new ArrayList<>();
        boolean isAsync = context.at("async") && !context.nextText().equals("=>"); if (isAsync) context.take();
        boolean oldAsync = context.grammar.async, oldGenerator = context.grammar.generator; context.grammar.async = isAsync; context.grammar.generator = false;
        try {
            if (context.at("<")) children.add(types.typeParameters());
            ParsedNode parameters;
            if (context.at("(")) parameters = parameters(false);
            else {
                int begin = context.start(); ParsedNode id = bindings.bindingIdentifier();
                ParsedNode p = context.node("parameter", begin, id).with("name", context.text(id)).with("simple", "true").with("rest", "false");
                parameters = context.node("parameters", begin, p);
            }
            children.add(parameters); if (context.at(":")) children.add(types.annotation()); context.expect("=>");
            Set<String> names = new HashSet<>();
            for (String binding : parameterNames(parameters)) if (!names.add(binding)) throw context.failure(context.last(), "Duplicate arrow parameter");
            if (context.at("{")) children.add(functionBody(parameters, true, true));
            else { context.grammar.functionDepth++; try { children.add(expressions.expression(2)); } finally { context.grammar.functionDepth--; } }
            return context.node("arrowFunction", start, children).with("async", Boolean.toString(isAsync));
        } finally { context.grammar.async = oldAsync; context.grammar.generator = oldGenerator; }
    }
}
