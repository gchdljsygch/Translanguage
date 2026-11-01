package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.syntax.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Handwritten recursive-descent statements/types and Pratt expressions. All state is per parse. */
public final class RecursiveDescentParser implements SourceParser {
    @Override public Result parse(String name, String source, Language language) {
        Engine engine = new Engine(name, source, language, false);
        Result result = engine.parse();
        // A module is strict from its first token, even when its first export occurs at the end.
        if (result.successful() && engine.module) result = new Engine(name, source, language, true).parse();
        return result;
    }

    private static final class Failure extends RuntimeException {
        final Diagnostic diagnostic;
        final boolean limit;
        Failure(Diagnostic diagnostic) { this(diagnostic, false); }
        Failure(Diagnostic diagnostic, boolean limit) { super(null, null, false, false); this.diagnostic = diagnostic; this.limit = limit; }
    }
    private static final class Engine {
        private static final int MAX_DEPTH = 128;
        private static final Set<String> RESERVED = Set.of("break", "case", "catch", "class", "const", "continue",
                "debugger", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally",
                "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super", "switch",
                "this", "throw", "true", "try", "typeof", "var", "void", "while", "with");
        private static final Set<String> STRICT_RESERVED = Set.of("implements", "interface", "let", "package",
                "private", "protected", "public", "static", "yield");
        private static final Set<String> MODIFIERS = Set.of("public", "private", "protected", "readonly", "abstract", "declare", "override");
        private final String name, source;
        private final Language language;
        private final boolean moduleCode;
        private final SourceLexer lexer;
        private final List<Token> tokens = new ArrayList<>();
        private Token look, last;
        private TokenReader.Checkpoint beforeLook;
        private TokenReader.Goal lookGoal;
        private int lookTokenCount, depth, functionDepth, loops, switches;
        private long work;
        private final long budget;
        private boolean strict, module, async, generator, ambientContext, allowIn = true;
        private final Set<String> labels = new HashSet<>();
        private final Set<String> iterationLabels = new HashSet<>();
        Engine(String name, String source, Language language, boolean strict) {
            this.name = name; this.source = source; this.language = language; this.strict = strict; this.moduleCode = strict;
            lexer = new SourceLexer(name, source); budget = 4096L + source.length() * 64L;
        }
        Result parse() {
            try {
                List<SyntaxNode> statements = statements(false, true);
                expectEof();
                Program program = new Program(new SourceRange(0, source.length()), statements, tokens);
                validateCoverGrammar(program); return new Result(program, lexer.diagnostics());
            } catch (Failure failure) {
                List<Diagnostic> diagnostics = lexer.diagnostics();
                return new Result(null, diagnostics.isEmpty() ? List.of(failure.diagnostic) : diagnostics);
            }
        }
        private Token peek() { return peek(TokenReader.Goal.DIVISION); }
        private Token peek(TokenReader.Goal goal) {
            if (++work > budget) throw limitFailure(last, "Parser work limit exceeded");
            if (look != null && beforeLook != null && lookGoal != goal && look.text().startsWith("/")) {
                lexer.restore(beforeLook); tokens.subList(lookTokenCount, tokens.size()).clear(); look = null;
            }
            if (look == null) {
                beforeLook = lexer.checkpoint(); lookTokenCount = tokens.size(); lookGoal = goal;
                do { look = lexer.next(goal); if (look.trivia()) tokens.add(look); } while (look.trivia());
                if (look.kind() == TokenKind.ERROR || !lexer.diagnostics().isEmpty()) throw failure(look, "Invalid token");
            }
            return look;
        }
        private Token take() { Token token = look == null ? peek() : look; tokens.add(token); last = token; look = null; return token; }
        private boolean at(String text) { return peek().text().equals(text); }
        private boolean eat(String text) { if (!at(text)) return false; take(); return true; }
        private Token expect(String text) { if (!at(text)) throw failure(peek(), "Expected '" + text + "'"); return take(); }
        private void expectEof() { if (peek().kind() != TokenKind.EOF) throw failure(peek(), "Expected end of input"); take(); }
        private boolean end() { return peek().kind() == TokenKind.EOF; }
        private boolean newline() {
            int start = last == null ? 0 : last.range().end();
            int stop = peek().range().start();
            for (int i = start; i < stop; i++) if ("\r\n\u2028\u2029".indexOf(source.charAt(i)) >= 0) return true;
            return false;
        }
        private Failure failure(Token token, String message) {
            return new Failure(new Diagnostic(name, token == null ? 1 : token.line(), token == null ? 1 : token.column(), "TS_SYNTAX", message));
        }
        private Failure limitFailure(Token token, String message) { return new Failure(failure(token, message).diagnostic, true); }
        private void unsupported(Token token, String message) {
            throw new Failure(new Diagnostic(name, token.line(), token.column(), "TS_UNSUPPORTED", message));
        }
        private void ts() { if (language == Language.JAVASCRIPT) throw failure(peek(), "TypeScript syntax is not allowed in JavaScript"); }
        private int start() { return peek().range().start(); }
        private ParsedNode node(String kind, int start, List<? extends SyntaxNode> children) {
            return new ParsedNode(kind, new SourceRange(start, last == null ? start : Math.max(start, last.range().end())), new ArrayList<>(children), Map.of());
        }
        private ParsedNode node(String kind, int start, SyntaxNode... children) { return node(kind, start, List.of(children)); }
        private ParsedNode leaf(String kind) { int start = start(); take(); return node(kind, start); }
        private String text(SyntaxNode node) { return source.substring(node.range().start(), node.range().end()); }
        private void enter() { if (depth >= MAX_DEPTH) throw limitFailure(peek(), "Syntax nesting limit exceeded (" + MAX_DEPTH + ")"); depth++; }
        private void leave() { depth--; }
        private record Mark(TokenReader.Checkpoint lexer, Token look, Token last, TokenReader.Checkpoint beforeLook,
                            TokenReader.Goal goal, int lookCount, int tokenCount) { }
        private Mark mark() { return new Mark(lexer.checkpoint(), look, last, beforeLook, lookGoal, lookTokenCount, tokens.size()); }
        private void restore(Mark mark) {
            lexer.restore(mark.lexer); look = mark.look; last = mark.last; beforeLook = mark.beforeLook;
            lookGoal = mark.goal; lookTokenCount = mark.lookCount;
            tokens.subList(mark.tokenCount, tokens.size()).clear();
        }
        private String nextText() { Mark mark = mark(); take(); String next = peek().text(); restore(mark); return next; }
        private String spelling(String raw) {
            if (raw.indexOf('\\') < 0) return raw;
            StringBuilder decoded = new StringBuilder();
            for (int i = 0; i < raw.length();) {
                if (raw.charAt(i) != '\\') { decoded.append(raw.charAt(i++)); continue; }
                i += 2;
                if (raw.charAt(i) == '{') { int end = raw.indexOf('}', ++i); decoded.appendCodePoint(Integer.parseInt(raw.substring(i, end), 16)); i = end + 1; }
                else { decoded.append((char) Integer.parseInt(raw.substring(i, i + 4), 16)); i += 4; }
            }
            return decoded.toString();
        }
        private boolean identifier(Token token) {
            return (token.kind() == TokenKind.IDENTIFIER || token.kind() == TokenKind.KEYWORD)
                    && !RESERVED.contains(spelling(token.text())) && (!strict || !STRICT_RESERVED.contains(spelling(token.text())))
                    && !((async || moduleCode) && spelling(token.text()).equals("await")) && !(generator && spelling(token.text()).equals("yield"));
        }
        private ParsedNode bindingIdentifier() {
            Token token = peek();
            if (!identifier(token) || strict && Set.of("eval", "arguments").contains(spelling(token.text()))) throw failure(token, "Invalid binding identifier");
            return leaf("identifier").with("bindingName", spelling(token.text()));
        }
        private ParsedNode identifierName() {
            if (peek().kind() != TokenKind.IDENTIFIER && peek().kind() != TokenKind.KEYWORD) throw failure(peek(), "Expected identifier name");
            return leaf("propertyName");
        }
        private ParsedNode propertyName() {
            int start = start();
            if (eat("[")) { ParsedNode value = expression(1); expect("]"); return node("computedName", start, value); }
            if (at("#")) unsupported(peek(), "Private fields are not supported.");
            if (peek().kind() == TokenKind.IDENTIFIER || peek().kind() == TokenKind.KEYWORD
                    || peek().kind() == TokenKind.STRING || peek().kind() == TokenKind.NUMBER) return leaf("propertyName");
            throw failure(peek(), "Expected property name");
        }
        private void semicolon() {
            if (!eat(";") && !end() && !at("}") && !newline()) throw failure(peek(), "Expected semicolon or line terminator");
        }
        private List<SyntaxNode> statements(boolean block, boolean directives) {
            List<SyntaxNode> result = new ArrayList<>();
            while (!end() && !(block && at("}"))) {
                boolean directive = directives && peek().kind() == TokenKind.STRING;
                ParsedNode statement = statement(); result.add(statement);
                if (directive && statement.kind().equals("expressionStatement") && statement.children().size() == 1
                        && statement.children().get(0).kind().equals("stringLiteral")) {
                    String literal = text(statement.children().get(0));
                    if (literal.equals("\"use strict\"") || literal.equals("'use strict'")) strict = true;
                } else directives = false;
            }
            return result;
        }
        private ParsedNode statement() {
            enter();
            try {
                int start = start();
                switch (peek().text()) {
                    case ";": return leaf("emptyStatement");
                    case "{": return block(false);
                    case "@": unsupported(peek(), "Decorator lowering is not supported."); break;
                    case "interface": ts(); return interfaceDeclaration();
                    case "type": if (identifierAfter()) { ts(); return typeAlias(); } break;
                    case "declare": if (!Set.of("=", ";", "(", ".").contains(nextText())) { ts(); return ambient(); } break;
                    case "namespace", "module": if (ambientContext) return ambientNamespace(); if (!Set.of("=", ";", "(", ".").contains(nextText())) { ts(); unsupported(peek(), "Runtime namespaces are not supported; use ES modules."); } break;
                    case "enum": ts(); return enumDeclaration();
                    case "const": if (nextText().equals("enum")) { ts(); return enumDeclaration(); }
                    case "let", "var": return variables(true);
                    case "abstract": if (nextText().equals("class")) { ts(); return classDeclaration(false); } break;
                    case "class": return classDeclaration(false);
                    case "function": return function(false);
                    case "async": if (nextText().equals("function")) return function(false); break;
                    case "import": if (!Set.of("(", ".").contains(nextText())) return moduleDeclaration(false); break;
                    case "export": return moduleDeclaration(true);
                    case "if": {
                        take(); expect("("); ParsedNode condition = expression(1); expect(")");
                        List<SyntaxNode> children = new ArrayList<>(List.of(condition, statement()));
                        if (eat("else")) children.add(statement()); return node("ifStatement", start, children);
                    }
                    case "for": return forStatement();
                    case "while", "do": return loop();
                    case "switch": return switchStatement();
                    case "try": return tryStatement();
                    case "return", "throw", "break", "continue": return jump();
                    case "debugger": take(); semicolon(); return node("debuggerStatement", start);
                    case "with": {
                        if (strict) throw failure(peek(), "with is forbidden in strict mode");
                        take(); expect("("); ParsedNode object = expression(1); expect(")"); return node("withStatement", start, object, statement());
                    }
                }
                if (identifier(peek()) && nextText().equals(":")) {
                    String label = take().text(); expect(":");
                    if (!labels.add(label)) throw failure(last, "Duplicate label");
                    boolean iteration = Set.of("for", "while", "do").contains(peek().text());
                    if (iteration) iterationLabels.add(label);
                    ParsedNode body = statement(); labels.remove(label); iterationLabels.remove(label);
                    return node("labelledStatement", start, body).with("name", label);
                }
                ParsedNode value = expression(1); semicolon(); return node("expressionStatement", start, value);
            } finally { leave(); }
        }
        private boolean identifierAfter() { Mark mark = mark(); take(); boolean result = identifier(peek()); restore(mark); return result; }
        private ParsedNode block(boolean functionBody) {
            int start = start(); expect("{"); List<SyntaxNode> children = statements(true, functionBody); expect("}");
            return node(functionBody ? "functionBody" : "block", start, children);
        }
        private ParsedNode variables(boolean terminate) {
            int start = start(); String modifier = take().text(); List<SyntaxNode> declarations = new ArrayList<>();
            do {
                int begin = start(); List<SyntaxNode> children = new ArrayList<>(); children.add(binding());
                if (at("!")) { ts(); children.add(leaf("typeModifier")); }
                if (at(":")) children.add(annotation());
                if (eat("=")) children.add(expression(2));
                else if (modifier.equals("const") && !ambientContext && !(at("in") || at("of"))) throw failure(peek(), "const requires an initializer");
                declarations.add(node("variableDeclaration", begin, children));
            } while (eat(","));
            if (terminate) semicolon(); return node("variableStatement", start, declarations);
        }
        private ParsedNode binding() {
            enter();
            try {
                int start = start(); List<SyntaxNode> children = new ArrayList<>();
                if (eat("[")) {
                    while (!at("]")) {
                        if (at(",")) { children.add(leaf("bindingElision")); continue; }
                        int begin = start(); boolean rest = eat("..."); List<SyntaxNode> parts = new ArrayList<>(); parts.add(binding());
                        if (eat("=")) { if (rest) throw failure(last, "Rest binding cannot have a default"); parts.add(initializer("bindingInitializer")); }
                        children.add(node("bindingElement", begin, parts).with("rest", Boolean.toString(rest)));
                        if (!eat(",")) break;
                        if (rest) throw failure(last, "Rest binding must be last");
                    }
                    expect("]"); return node("arrayBinding", start, children);
                }
                if (eat("{")) {
                    while (!at("}")) {
                        int begin = start(); boolean rest = eat("..."); List<SyntaxNode> parts = new ArrayList<>();
                        if (rest) parts.add(bindingIdentifier());
                        else {
                            ParsedNode key = propertyName(); parts.add(key);
                            if (eat(":")) parts.add(binding());
                            else {
                                if (!identifierToken(key)) throw failure(last, "Invalid shorthand binding");
                                parts.set(0, key.with("bindingName", spelling(text(key))));
                            }
                            if (eat("=")) parts.add(initializer("bindingInitializer"));
                        }
                        children.add(node(rest ? "restBinding" : "bindingProperty", begin, parts));
                        if (!eat(",")) break;
                        if (rest) throw failure(last, "Rest binding must be last");
                    }
                    expect("}"); return node("objectBinding", start, children);
                }
                return bindingIdentifier();
            } finally { leave(); }
        }
        private boolean identifierToken(ParsedNode node) { return node.kind().equals("propertyName") && !text(node).startsWith("'") && !text(node).startsWith("\"") && !Character.isDigit(text(node).charAt(0)) && !RESERVED.contains(spelling(text(node))); }
        private ParsedNode forStatement() {
            int start = start(); expect("for"); boolean awaiting = eat("await"); expect("(");
            List<SyntaxNode> children = new ArrayList<>(); boolean oldIn = allowIn; allowIn = false;
            try { if (!at(";")) children.add(Set.of("var", "let", "const").contains(peek().text()) ? variables(false) : expression(1)); }
            finally { allowIn = oldIn; }
            if (at("in") || at("of")) {
                String operator = take().text();
                if (awaiting && !operator.equals("of")) throw failure(last, "for await requires of");
                if (children.isEmpty()) throw failure(last, "Missing loop binding");
                ParsedNode init = (ParsedNode) children.get(0);
                if (init.kind().equals("variableStatement")) {
                    if (init.children().size() != 1) throw failure(last, "Loop requires one binding");
                    var declaration = init.children().get(0);
                    if (declaration.children().stream().skip(1).anyMatch(n -> !Set.of("typeAnnotation", "typeModifier").contains(n.kind())))
                        throw failure(last, "for-in/of binding cannot have an initializer");
                } else requireAssignable(init, true);
                children.add(expression(operator.equals("of") ? 2 : 1));
            } else {
                if (awaiting) throw failure(peek(), "for await requires of");
                expect(";"); if (!at(";")) children.add(expression(1)); expect(";"); if (!at(")")) children.add(expression(1));
            }
            expect(")"); loops++; try { children.add(statement()); } finally { loops--; }
            return node("forStatement", start, children).with("await", Boolean.toString(awaiting));
        }
        private ParsedNode loop() {
            int start = start(); boolean doLoop = eat("do"); List<SyntaxNode> children = new ArrayList<>(); loops++;
            try {
                if (doLoop) children.add(statement());
                expect("while"); expect("("); children.add(expression(1)); expect(")");
                if (!doLoop) children.add(statement()); else eat(";");
            } finally { loops--; }
            return node(doLoop ? "doStatement" : "whileStatement", start, children);
        }
        private ParsedNode switchStatement() {
            int start = start(); take(); expect("("); List<SyntaxNode> children = new ArrayList<>(List.of(expression(1))); expect(")"); expect("{");
            boolean seenDefault = false; switches++;
            try {
                while (!at("}")) {
                    if (eat("case")) children.add(expression(1));
                    else { expect("default"); if (seenDefault) throw failure(last, "Duplicate default clause"); seenDefault = true; }
                    expect(":"); while (!at("case") && !at("default") && !at("}")) children.add(statement());
                }
                expect("}");
            } finally { switches--; }
            return node("switchStatement", start, children);
        }
        private ParsedNode tryStatement() {
            int start = start(); take(); List<SyntaxNode> children = new ArrayList<>(List.of(block(false))); boolean handler = false;
            if (eat("catch")) {
                handler = true;
                if (eat("(")) { children.add(binding()); if (at(":")) children.add(annotation()); expect(")"); }
                children.add(block(false));
            }
            if (eat("finally")) { handler = true; children.add(block(false)); }
            if (!handler) throw failure(peek(), "try requires catch or finally");
            return node("tryStatement", start, children);
        }
        private ParsedNode jump() {
            int start = start(); Token keyword = take(); String kind = keyword.text(); List<SyntaxNode> children = new ArrayList<>();
            if (kind.equals("return") && functionDepth == 0) throw failure(keyword, "return outside function");
            boolean line = newline();
            if (kind.equals("throw") && line) throw failure(peek(), "Line terminator after throw");
            if (kind.equals("break") || kind.equals("continue")) {
                String label = !line && identifier(peek()) ? take().text() : "";
                if (!label.isEmpty() && !(kind.equals("continue") ? iterationLabels : labels).contains(label)) throw failure(keyword, "Unknown jump label");
                if (label.isEmpty() && loops == 0 && (kind.equals("continue") || switches == 0)) throw failure(keyword, "Jump outside loop/switch");
            } else if (!line && !at(";") && !at("}") && !end()) children.add(expression(1));
            else if (kind.equals("throw")) throw failure(peek(), "throw requires an expression");
            semicolon(); return node(kind + "Statement", start, children);
        }
        private ParsedNode function(boolean expression) {
            int start = start(); boolean isAsync = eat("async");
            if (isAsync && newline()) throw failure(peek(), "Line terminator after async");
            expect("function"); boolean isGenerator = eat("*"); List<SyntaxNode> children = new ArrayList<>();
            if (!at("(") && !at("<")) children.add(bindingIdentifier());
            else if (!expression) throw failure(peek(), "Function declaration requires a name");
            boolean oldAsync = async, oldGenerator = generator; async = isAsync; generator = isGenerator;
            try {
                if (at("<")) children.add(typeParameters());
                ParsedNode parameters = parameters(false); children.add(parameters);
                if (at(":")) children.add(annotation());
                if (at("{")) {
                    ParsedNode body = functionBody(parameters, isAsync || isGenerator, false); children.add(body);
                    if (body.attribute("strict").equals("true") && !children.isEmpty() && children.get(0).kind().equals("identifier")) {
                        String functionName = spelling(text(children.get(0)));
                        if (Set.of("eval", "arguments").contains(functionName) || STRICT_RESERVED.contains(functionName)) throw failure(last, "Invalid strict function name");
                    }
                }
                else { ts(); if (expression) throw failure(peek(), "Function expression requires a body"); semicolon(); return node("overloadDeclaration", start, children); }
                return node(expression ? "functionExpression" : "functionDeclaration", start, children).with("async", Boolean.toString(isAsync)).with("generator", Boolean.toString(isGenerator));
            } finally { async = oldAsync; generator = oldGenerator; }
        }
        private ParsedNode parameters(boolean constructor) {
            int start = start(); expect("("); List<SyntaxNode> children = new ArrayList<>();
            while (!at(")")) {
                int begin = start(); List<SyntaxNode> parts = new ArrayList<>(); boolean property = false, rest = eat("...");
                while (Set.of("public", "private", "protected", "readonly", "override").contains(peek().text())) {
                    ts(); property = true; parts.add(leaf("typeModifier"));
                }
                ParsedNode binding;
                if (at("this")) { ts(); binding = leaf("identifier"); }
                else binding = binding();
                parts.add(binding);
                if (at("?")) { ts(); parts.add(leaf("typeModifier")); }
                if (at(":")) parts.add(annotation());
                if (eat("=")) { if (rest) throw failure(last, "Rest parameter cannot have a default"); parts.add(initializer("parameterInitializer")); }
                boolean comma = eat(",");
                if (rest && comma) throw failure(last, "Rest parameter must be last without a trailing comma");
                boolean thisParameter = text(binding).equals("this");
                if (thisParameter && !children.isEmpty()) unsupported(last, "The this parameter must be the first parameter.");
                ParsedNode parameter = node(thisParameter ? "thisParameter" : property ? "parameterProperty" : "parameter", begin, parts)
                        .with("name", text(binding)).with("simple", Boolean.toString(binding.kind().equals("identifier")))
                        .with("rest", Boolean.toString(rest));
                if (property && !constructor) unsupported(last, "Parameter properties are only valid in constructors.");
                children.add(parameter);
                if (!comma) break;
            }
            expect(")"); return node("parameters", start, children);
        }
        private ParsedNode initializer(String kind) {
            int start = start(); return node(kind, start, expression(2));
        }
        private List<String> parameterNames(ParsedNode parameters) {
            List<String> names = new ArrayList<>();
            parameters.walk(node -> {
                if (Set.of("typeAnnotation", "typeParameters", "bindingInitializer", "parameterInitializer").contains(node.kind())) return false;
                if (node instanceof ParsedNode parsed && !parsed.attribute("bindingName").isEmpty()) names.add(parsed.attribute("bindingName"));
                return true;
            });
            return names;
        }
        private ParsedNode functionBody(ParsedNode parameters, boolean unique, boolean arrow) {
            int oldLoops = loops, oldSwitches = switches; boolean oldStrict = strict;
            Set<String> oldLabels = new HashSet<>(labels), oldIterationLabels = new HashSet<>(iterationLabels);
            labels.clear(); iterationLabels.clear(); loops = 0; switches = 0; functionDepth++;
            try {
                ParsedNode body = block(true);
                Set<String> names = new HashSet<>(); boolean simple = parameters.children().stream().allMatch(p -> {
                    ParsedNode parameter = (ParsedNode) p;
                    return parameter.attribute("simple").equals("true") && parameter.attribute("rest").equals("false") && parameter.children().stream().allMatch(n -> Set.of("identifier", "typeAnnotation", "typeModifier").contains(n.kind()));
                });
                if (!oldStrict && strict && !simple) throw failure(last, "use strict directive with non-simple parameters");
                for (String binding : parameterNames(parameters)) {
                    if ((strict || unique || arrow || !simple) && !names.add(binding)) throw failure(last, "Duplicate parameter");
                    if (strict && (Set.of("eval", "arguments").contains(binding) || STRICT_RESERVED.contains(binding)))
                        throw failure(last, "Invalid strict mode parameter");
                }
                return body.with("strict", Boolean.toString(strict));
            } finally {
                functionDepth--; loops = oldLoops; switches = oldSwitches; strict = oldStrict;
                labels.clear(); labels.addAll(oldLabels); iterationLabels.clear(); iterationLabels.addAll(oldIterationLabels);
            }
        }
        private ParsedNode classDeclaration(boolean expression) {
            int start = start(); List<SyntaxNode> children = new ArrayList<>();
            boolean oldStrict = strict; strict = true; try {
            if (at("abstract")) { ts(); children.add(leaf("typeModifier")); }
            expect("class");
            if (!at("{") && !at("extends") && !at("implements") && !at("<")) children.add(bindingIdentifier());
            else if (!expression) throw failure(peek(), "Class declaration requires a name");
            if (at("<")) children.add(typeParameters());
            boolean derived = eat("extends");
            if (derived) children.add(expression(17));
            if (at("implements")) {
                ts(); int begin = start(); take(); List<SyntaxNode> types = new ArrayList<>();
                do { types.add(type(0)); } while (eat(",")); children.add(node("implementsClause", begin, types));
            }
            expect("{");
                boolean constructorSeen = false;
                while (!at("}")) {
                    if (eat(";")) continue;
                    ParsedNode member = classMember(derived);
                    if (member.kind().equals("constructorDeclaration")) {
                        if (constructorSeen) throw failure(last, "Duplicate constructor implementation"); constructorSeen = true;
                    }
                    children.add(member);
                }
                expect("}");
            return node(expression ? "classExpression" : "classDeclaration", start, children).with("derived", Boolean.toString(derived));
            } finally { strict = oldStrict; }
        }
        private ParsedNode classMember(boolean derived) {
            int start = start(); List<SyntaxNode> children = new ArrayList<>(); boolean ambient = false, isStatic = false, isAsync = false;
            if (at("@")) unsupported(peek(), "Decorator lowering is not supported.");
            while (MODIFIERS.contains(peek().text()) || at("static") || at("async")) {
                String next = nextText();
                if (Set.of("(", ":", "=", ";", "?", "!", "}", "<").contains(next)) break;
                if (at("static")) { isStatic = true; take(); }
                else if (at("async")) { isAsync = true; take(); if (newline()) throw failure(peek(), "Line terminator after async"); }
                else { ts(); ambient |= at("abstract") || at("declare"); children.add(leaf("typeModifier")); }
            }
            if (isStatic && at("{")) { children.add(block(false)); return node("staticBlock", start, children); }
            // An index signature begins with [binding : type], not an arbitrary computed key.
            if (at("[")) {
                Mark mark = mark(); take();
                boolean index = identifier(peek()) && nextText().equals(":"); restore(mark);
                if (index) { ts(); children.add(indexSignature()); semicolon(); return node("indexMemberDeclaration", start, children); }
            }
            boolean isGenerator = eat("*"); String accessor = "";
            if ((at("get") || at("set")) && !Set.of("(", ":", "=", ";", "}", "<").contains(nextText())) accessor = take().text();
            ParsedNode key = propertyName(); children.add(key);
            if (at("?") || at("!")) { ts(); children.add(leaf("typeModifier")); }
            boolean constructor = text(key).equals("constructor") && !isStatic && accessor.isEmpty();
            if (at("<")) children.add(typeParameters());
            if (at("(")) {
                boolean oldAsync = async, oldGenerator = generator; async = isAsync; generator = isGenerator;
                try {
                    ParsedNode parameters = parameters(constructor); children.add(parameters);
                    checkAccessor(accessor, parameters);
                    if (at(":")) children.add(annotation());
                    if (!at("{")) { ts(); semicolon(); return node("overloadDeclaration", start, children); }
                    if (ambient) unsupported(peek(), accessor.isEmpty() ? "An abstract/declare method cannot have an implementation." : "Abstract/declare accessors with bodies are not supported.");
                    children.add(functionBody(parameters, true, false));
                    return node(constructor ? "constructorDeclaration" : "methodDeclaration", start, children).with("derived", Boolean.toString(derived)).with("static", Boolean.toString(isStatic)).with("async", Boolean.toString(isAsync)).with("generator", Boolean.toString(isGenerator));
                } finally { async = oldAsync; generator = oldGenerator; }
            }
            if (!accessor.isEmpty() || isGenerator) throw failure(peek(), "Method requires a parameter list");
            if (at(":")) children.add(annotation());
            if (eat("=")) children.add(expression(2));
            semicolon(); return node(ambient ? "ambientDeclaration" : "propertyDeclaration", start, children).with("static", Boolean.toString(isStatic));
        }
        private void checkAccessor(String accessor, ParsedNode parameters) {
            int size = parameters.children().size();
            if (accessor.equals("get") && size != 0 || accessor.equals("set") && (size != 1
                    || ((ParsedNode) parameters.children().get(0)).attribute("rest").equals("true")))
                throw failure(last, "Getter requires zero parameters; setter requires one non-rest parameter");
        }
        private ParsedNode interfaceDeclaration() {
            int start = start(); expect("interface"); List<SyntaxNode> children = new ArrayList<>(List.of(bindingIdentifier()));
            if (at("<")) children.add(typeParameters());
            if (eat("extends")) do { children.add(type(1)); } while (eat(","));
            children.add(objectType()); eat(";"); return node("interfaceDeclaration", start, children);
        }
        private ParsedNode typeAlias() {
            int start = start(); expect("type"); List<SyntaxNode> children = new ArrayList<>(List.of(bindingIdentifier()));
            if (at("<")) children.add(typeParameters()); expect("="); children.add(type(0)); semicolon();
            return node("typeAliasDeclaration", start, children);
        }
        private ParsedNode ambientNamespace() {
            int start = start(); take();
            if (peek().kind() == TokenKind.STRING) take(); else { bindingIdentifier(); while (eat(".")) bindingIdentifier(); }
            expect("{"); List<SyntaxNode> children = new ArrayList<>();
            while (!at("}")) { eat("export"); children.add(statement()); }
            expect("}"); return node("ambientDeclaration", start, children);
        }
        private ParsedNode ambient() {
            int start = start(); expect("declare"); List<SyntaxNode> children = new ArrayList<>();
            boolean oldAmbient = ambientContext; ambientContext = true; try {
            if (eat("namespace") || eat("module")) {
                if (peek().kind() == TokenKind.STRING) take(); else { bindingIdentifier(); while (eat(".")) bindingIdentifier(); }
                expect("{");
                while (!at("}")) { eat("export"); children.add(statement()); }
                expect("}");
            } else if (Set.of("var", "let", "const").contains(peek().text()) && !nextText().equals("enum")) {
                // Ambient const declarations legitimately have no runtime initializer.
                int begin = start(); take();
                do { children.add(binding()); if (at(":")) children.add(annotation()); if (eat("=")) children.add(expression(2)); } while (eat(","));
                semicolon(); children = new ArrayList<>(List.of(node("variableStatement", begin, children)));
            } else children.add(statement());
            return node("ambientDeclaration", start, children);
            } finally { ambientContext = oldAmbient; }
        }
        private ParsedNode enumDeclaration() {
            int start = start(); eat("const"); expect("enum"); String enumName = text(bindingIdentifier()); expect("{");
            List<SyntaxNode> members = new ArrayList<>();
            while (!at("}")) {
                int begin = start(); ParsedNode key = propertyName(); List<SyntaxNode> parts = new ArrayList<>(List.of(key));
                if (eat("=")) parts.add(expression(2));
                members.add(node("enumMember", begin, parts)); if (!eat(",")) break;
            }
            expect("}"); return node("enumDeclaration", start, members).with("name", enumName);
        }
        private ParsedNode moduleDeclaration(boolean exporting) {
            int start = start(); take(); module = true;
            if (depth != 1) throw failure(last, "Module declarations must be at top level");
            List<SyntaxNode> children = new ArrayList<>(); boolean typeOnly = false, hasDefault = false; String namespaceExport = "";
            if (exporting && eat("default")) {
                if (at("class") || at("abstract")) children.add(classDeclaration(true));
                else if (at("function") || at("async") && nextText().equals("function")) children.add(function(true));
                else { children.add(expression(2)); semicolon(); }
                return node("exportDeclaration", start, children);
            }
            if (at("type") && (nextText().equals("{") || nextText().equals("*") || !exporting && !Set.of(",", "from").contains(nextText()))) {
                ts(); take(); typeOnly = true;
            }
            if (exporting && !at("{") && !at("*")) {
                children.add(statement()); return node("exportDeclaration", start, children);
            }
            if (!exporting && peek().kind() == TokenKind.STRING) { take(); semicolon(); return node("importDeclaration", start); }
            if (!at("{") && !at("*")) {
                children.add(bindingIdentifier()); hasDefault = true;
                if (!at("from")) expect(",");
            }
            if (at("{")) {
                int begin = start(); take(); List<SyntaxNode> specifiers = new ArrayList<>();
                while (!at("}")) {
                    int specStart = start(); boolean type = at("type") && !Set.of("as", ",", "}").contains(nextText());
                    if (type) { ts(); take(); }
                    propertyName(); if (eat("as")) { if (exporting) propertyName(); else bindingIdentifier(); }
                    specifiers.add(node(type ? "typeSpecifier" : "moduleSpecifier", specStart));
                    if (!eat(",")) break;
                }
                expect("}"); children.add(node("moduleItems", begin, specifiers));
            } else if (eat("*")) { if (eat("as")) { var name = bindingIdentifier(); if (exporting) namespaceExport = text(name); } }
            if (!exporting || at("from")) { expect("from"); if (peek().kind() != TokenKind.STRING) throw failure(peek(), "Expected module string"); take(); }
            semicolon();
            return node(exporting ? "exportDeclaration" : "importDeclaration", start, children)
                    .with("typeOnly", Boolean.toString(typeOnly)).with("defaultBinding", Boolean.toString(hasDefault))
                    .with("namespaceExport", namespaceExport);
        }
        private ParsedNode annotation() { ts(); int start = start(); expect(":"); return node("typeAnnotation", start, type(0)); }
        private void greater() {
            Token token = peek(); if (!token.text().startsWith(">")) throw failure(token, "Expected '>'");
            if (token.text().length() == 1) { take(); return; }
            Token head = new Token(TokenKind.PUNCTUATION, new SourceRange(token.range().start(), token.range().start() + 1), ">", token.line(), token.column());
            tokens.add(head); last = head;
            look = new Token(TokenKind.PUNCTUATION, new SourceRange(token.range().start() + 1, token.range().end()), token.text().substring(1), token.line(), token.column() + 1);
            beforeLook = null;
        }
        private ParsedNode typeParameters() {
            ts(); int start = start(); expect("<"); List<SyntaxNode> children = new ArrayList<>();
            do {
                eat("const"); eat("in"); eat("out"); children.add(bindingIdentifier());
                if (eat("extends")) children.add(type(1)); if (eat("=")) children.add(type(0));
                if (!eat(",")) break;
            } while (!peek().text().startsWith(">"));
            greater(); return node("typeParameters", start, children);
        }
        private ParsedNode typeArguments() {
            ts(); int start = start(); expect("<"); List<SyntaxNode> children = new ArrayList<>();
            do { children.add(type(0)); if (!eat(",")) break; } while (!peek().text().startsWith(">"));
            greater(); return node("typeArguments", start, children);
        }
        /** Conditional (0), union (1), intersection (2), prefix/postfix (3) type grammar. */
        private ParsedNode type(int minimum) {
            enter();
            try {
                int start = start(); List<SyntaxNode> children = new ArrayList<>();
                if (eat("|") || eat("&")) children.add(type(3));
                else if (Set.of("keyof", "readonly", "unique", "infer", "typeof", "asserts").contains(peek().text())) {
                    String operator = take().text(); children.add(type(3));
                    if (operator.equals("infer") && eat("extends")) children.add(type(1));
                } else if (at("{")) children.add(objectType());
                else if (eat("[")) {
                    while (!at("]")) {
                        eat("...");
                        if (identifier(peek()) && (nextText().equals(":") || nextText().equals("?"))) { take(); eat("?"); expect(":"); }
                        children.add(type(0)); eat("?"); if (!eat(",")) break;
                    }
                    expect("]");
                } else if (at("(") || at("<") || at("new") || at("abstract") && nextText().equals("new")) {
                    Mark mark = mark(); boolean functionType = false;
                    try {
                        eat("abstract"); eat("new"); if (at("<")) typeParameters(); typeSignatureParameters(); expect("=>"); functionType = true;
                    } catch (Failure failure) { if (failure.limit) throw failure; if (!failure.diagnostic.code().equals("TS_SYNTAX")) throw failure; }
                    restore(mark);
                    if (functionType) {
                        eat("abstract"); eat("new"); if (at("<")) children.add(typeParameters());
                        children.add(typeSignatureParameters()); expect("=>"); children.add(type(0));
                    } else { expect("("); children.add(type(0)); expect(")"); }
                } else if (at("`")) children.add(template(true));
                else {
                    if (at("-") || at("+")) take();
                    Token token = peek();
                    if (!(token.kind() == TokenKind.IDENTIFIER || token.kind() == TokenKind.KEYWORD || token.kind() == TokenKind.STRING
                            || token.kind() == TokenKind.NUMBER || token.kind() == TokenKind.BIGINT)) throw failure(token, "Expected type");
                    take();
                    if (token.text().equals("import") && eat("(")) { if (peek().kind() != TokenKind.STRING) throw failure(peek(), "Expected import type string"); take(); expect(")"); }
                }
                while (true) {
                    if (eat(".")) { identifierName(); }
                    else if (at("<")) {
                        Mark mark = mark();
                        try { children.add(typeArguments()); }
                        catch (Failure failure) { if (failure.limit) throw failure; restore(mark); break; }
                    }
                    else if (at("[") && !newline()) { take(); if (!at("]")) children.add(type(0)); expect("]"); }
                    else break;
                }
                if (minimum <= 3 && eat("is")) children.add(type(0));
                if (minimum <= 2) while (eat("&")) children.add(type(3));
                if (minimum <= 1) while (eat("|")) children.add(type(2));
                if (minimum == 0 && eat("extends")) { children.add(type(1)); expect("?"); children.add(type(0)); expect(":"); children.add(type(0)); }
                return node("type", start, children);
            } finally { leave(); }
        }
        private ParsedNode typeSignatureParameters() {
            int start = start(); expect("("); List<SyntaxNode> children = new ArrayList<>();
            while (!at(")")) {
                eat("..."); if (at("this")) take(); else children.add(binding()); eat("?");
                if (at(":")) children.add(annotation()); if (!eat(",")) break;
            }
            expect(")"); return node("typeSignatureParameters", start, children);
        }
        private ParsedNode indexSignature() {
            int start = start(); expect("["); bindingIdentifier();
            List<SyntaxNode> children = new ArrayList<>();
            if (eat("in")) { children.add(type(0)); if (eat("as")) children.add(type(0)); }
            else { expect(":"); children.add(type(0)); }
            expect("]"); if (at("+") || at("-")) take(); eat("?"); expect(":"); children.add(type(0));
            return node("indexSignature", start, children);
        }
        private ParsedNode objectType() {
            int start = start(); expect("{"); List<SyntaxNode> children = new ArrayList<>();
            while (!at("}")) {
                if (eat(";") || eat(",")) continue;
                if (at("+") || at("-")) take();
                if (at("readonly") && !Set.of(":", "?", "(").contains(nextText())) take();
                if (at("[")) children.add(indexSignature());
                else {
                    if (at("new") && Set.of("(", "<").contains(nextText())) take();
                    else if (!at("(") && !at("<")) { propertyName(); eat("?"); }
                    if (at("<")) children.add(typeParameters());
                    if (at("(")) children.add(typeSignatureParameters());
                    if (at(":")) children.add(annotation());
                }
                if (!eat(";") && !eat(",") && !at("}") && !newline()) throw failure(peek(), "Expected type member separator");
            }
            expect("}"); return node("objectType", start, children);
        }
        private ParsedNode expression(int minimum) {
            enter();
            try {
                ParsedNode left = prefix();
                while (true) {
                    int start = left.range().start(); String operator = peek().text();
                    if (operator.equals(".") || operator.equals("?.")) {
                        take(); List<SyntaxNode> children = new ArrayList<>(List.of(left));
                        if (operator.equals("?.") && at("(")) children.add(arguments());
                        else if (operator.equals("?.") && eat("[")) { children.add(expression(1)); expect("]"); }
                        else { if (at("#")) unsupported(peek(), "Private fields are not supported."); children.add(identifierName()); }
                        left = node(operator.equals("?.") ? "optionalMemberExpression" : "memberExpression", start, children); continue;
                    }
                    if (eat("[")) { ParsedNode key = expression(1); expect("]"); left = node("memberExpression", start, left, key); continue; }
                    if (at("(")) { left = node("argumentsExpression", start, left, arguments()); continue; }
                    if (at("`")) { left = node("taggedTemplateExpression", start, left, template(false)); continue; }
                    if (at("<") && language == Language.TYPESCRIPT && !newline()) {
                        Mark mark = mark(); ParsedNode arguments = null;
                        try {
                            arguments = typeArguments();
                            if (!(at("(") || at("`") || minimum == 17 && (at("{") || at("implements")))) arguments = null;
                        } catch (Failure failure) { if (failure.limit) throw failure; if (!failure.diagnostic.code().equals("TS_SYNTAX")) throw failure; }
                        if (arguments != null) { left = node("instantiationExpression", start, left, arguments); continue; }
                        restore(mark);
                    }
                    if (at("!") && !newline()) { ts(); ParsedNode assertion = leaf("typeModifier"); left = node("nonNullExpression", start, left, assertion); continue; }
                    if ((at("++") || at("--")) && !newline()) {
                        requireAssignable(left, false); take(); left = node("updateExpression", start, left); continue;
                    }
                    if ((at("as") || at("satisfies")) && minimum <= 11 && !newline()) {
                        ts(); int begin = start(); take(); ParsedNode erased = node("typeAssertion", begin, type(1));
                        left = node("assertionExpression", start, left, erased); continue;
                    }
                    if (at("?") && minimum <= 3) {
                        take(); ParsedNode yes = expression(2); expect(":"); ParsedNode no = expression(2);
                        left = node("conditionalExpression", start, left, yes, no); continue;
                    }
                    int precedence = precedence(operator);
                    if (precedence < minimum || operator.equals("in") && !allowIn) break;
                    take();
                    if (precedence == 2) requireAssignable(left, operator.equals("="));
                    if (operator.equals("**") && left.kind().equals("unaryExpression")) throw failure(last, "Unary expression before exponentiation requires parentheses");
                    ParsedNode right = expression(precedence == 2 || operator.equals("**") ? precedence : precedence + 1);
                    if (Set.of("??", "&&", "||").contains(operator) && (mixedNullish(left, operator) || mixedNullish(right, operator)))
                        throw failure(last, "Nullish and logical operators require parentheses when mixed");
                    left = node(precedence == 2 ? "assignmentExpression" : "binaryExpression", start, left, right).with("operator", operator);
                }
                return left;
            } finally { leave(); }
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
        private void requireAssignable(ParsedNode value, boolean pattern) {
            if (value.kind().equals("parenthesizedExpression") || value.kind().equals("nonNullExpression")) {
                requireAssignable((ParsedNode) value.children().get(0), pattern); return;
            }
            if (value.kind().equals("identifier")) {
                if (strict && Set.of("eval", "arguments").contains(text(value))) throw failure(last, "Invalid strict mode assignment");
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
                        if (item != value.children().get(value.children().size() - 1) || value.attribute("trailingComma").equals("true")) throw failure(last, "Rest element must be last");
                        element = (ParsedNode) element.children().get(0);
                    }
                    assignmentElement(element);
                }
                return;
            }
            if (pattern && value.kind().equals("objectLiteral")) {
                for (SyntaxNode member : value.children()) {
                    ParsedNode property = (ParsedNode) member;
                    if (property.children().size() > 2) throw failure(last, "Method cannot be an assignment target");
                    if (property.attribute("spread").equals("true")) {
                        if (member != value.children().get(value.children().size() - 1) || value.attribute("trailingComma").equals("true")) throw failure(last, "Rest property must be last");
                        requireAssignable((ParsedNode) member.children().get(0), false);
                    } else if (member.children().size() == 1 || property.attribute("shorthandDefault").equals("true")) {
                        String binding = spelling(text(member.children().get(0)));
                        if (strict && Set.of("eval", "arguments").contains(binding)) throw failure(last, "Invalid strict assignment");
                    } else assignmentElement((ParsedNode) member.children().get(1));
                }
                return;
            }
            throw failure(last, "Invalid assignment target");
        }
        private void assignmentElement(ParsedNode element) {
            if (element.kind().equals("assignmentExpression") && element.attribute("operator").equals("="))
                requireAssignable((ParsedNode) element.children().get(0), true);
            else requireAssignable(element, true);
        }
        private ParsedNode prefix() {
            Token token = peek(TokenReader.Goal.REGULAR_EXPRESSION); int start = token.range().start();
            if ((at("(") || at("<") || at("async") || identifier(token)) && arrowAhead()) return arrow();
            token = peek(TokenReader.Goal.REGULAR_EXPRESSION);
            switch (token.kind()) {
                case NUMBER, BIGINT: {
                    if (strict && token.kind() == TokenKind.NUMBER && token.text().length() > 1 && token.text().charAt(0) == '0' && Character.isDigit(token.text().charAt(1)))
                        throw failure(token, "Legacy numeric literal in strict mode");
                    return leaf(token.kind() == TokenKind.NUMBER ? "numericLiteral" : "bigintLiteral");
                }
                case STRING: {
                    if (strict && legacyStringEscape(token.text())) throw failure(token, "Legacy escape in strict mode");
                    return leaf("stringLiteral");
                }
                case REGULAR_EXPRESSION: take(); return node("regularExpression", start);
                default: break;
            }
            String value = token.text();
            switch (value) {
                case "function": return function(true);
                case "async": if (nextText().equals("function")) return function(true); break;
                case "class": return classDeclaration(true);
                case "[": return array();
                case "{": return object();
                case "`": return template(false);
                case "(": {
                    take(); boolean oldIn = allowIn; allowIn = true; ParsedNode inner;
                    try { inner = expression(1); } finally { allowIn = oldIn; }
                    expect(")"); return node("parenthesizedExpression", start, inner);
                }
                case "<": {
                    ts(); take(); ParsedNode type = type(0); greater(); ParsedNode erased = node("typeAssertion", start, type);
                    return node("assertionExpression", start, erased, expression(15));
                }
                case "!", "~", "+", "-", "typeof", "void", "delete", "++", "--", "await": {
                    if (value.equals("await") && functionDepth > 0 && !async) throw failure(token, "await outside async function");
                    take(); ParsedNode operand = expression(15);
                    if (value.equals("delete") && strict && operand.kind().equals("identifier")) throw failure(token, "Deleting an identifier in strict mode");
                    if (value.equals("++") || value.equals("--")) requireAssignable(operand, false);
                    return node("unaryExpression", start, operand).with("operator", value);
                }
                case "yield": {
                    if (!generator) break;
                    take(); List<SyntaxNode> children = new ArrayList<>();
                    if (!newline() && !Set.of(";", "}", ")", "]", ",").contains(peek().text()) && !end()) { eat("*"); children.add(expression(2)); }
                    return node("yieldExpression", start, children);
                }
                case "new": {
                    take(); if (eat(".")) { expect("target"); return node("newTargetExpression", start); }
                    return newExpression(start);
                }
                case "import": {
                    take(); if (eat(".")) { expect("meta"); module = true; return node("importMetaExpression", start); }
                    return node("importExpression", start, arguments());
                }
                case "this": return leaf("thisExpression");
                case "super": return leaf("superExpression");
                case "true", "false", "null": return leaf("literal");
                case "@": unsupported(token, "Decorator lowering is not supported."); break;
                case "#": unsupported(token, "Private fields are not supported."); break;
            }
            if (identifier(token)) {
                if (token.kind() == TokenKind.KEYWORD && token.text().contains("\\")) throw failure(token, "Escaped reserved keyword");
                return leaf("identifier");
            }
            throw failure(token, "Expected expression");
        }
        private ParsedNode newExpression(int start) {
            enter(); try {
            ParsedNode callee = prefix();
            while (true) {
                int begin = callee.range().start();
                if (eat(".")) callee = node("memberExpression", begin, callee, identifierName());
                else if (eat("[")) { ParsedNode key = expression(1); expect("]"); callee = node("memberExpression", begin, callee, key); }
                else if (at("<") && language == Language.TYPESCRIPT) {
                    Mark mark = mark(); ParsedNode types;
                    try { types = typeArguments(); }
                    catch (Failure failure) { if (failure.limit) throw failure; restore(mark); break; }
                    callee = node("instantiationExpression", begin, callee, types);
                } else break;
            }
            if (at("?.")) throw failure(peek(), "Optional chain cannot be a new constructor");
            return at("(") ? node("newExpression", start, callee, arguments()) : node("newExpression", start, callee);
            } finally { leave(); }
        }
        private boolean legacyStringEscape(String text) {
            for (int i = 1; i < text.length() - 1; i++) if (text.charAt(i) == '\\') {
                char next = text.charAt(++i);
                if (next >= '1' && next <= '9' || next == '0' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) return true;
            }
            return false;
        }
        private boolean arrowAhead() {
            Mark mark = mark();
            try {
                boolean asynchronous = at("async") && !nextText().equals("=>");
                if (asynchronous) { take(); if (newline()) return false; }
                if (at("<")) typeParameters();
                if (at("(")) parameters(false); else bindingIdentifier();
                if (at(":")) annotation();
                return !newline() && at("=>");
            } catch (Failure failure) { if (failure.limit) throw failure; return false; }
            finally { restore(mark); }
        }
        private ParsedNode arrow() {
            int start = start(); List<SyntaxNode> children = new ArrayList<>();
            boolean isAsync = at("async") && !nextText().equals("=>"); if (isAsync) take();
            boolean oldAsync = async, oldGenerator = generator; async = isAsync; generator = false;
            try {
                if (at("<")) children.add(typeParameters());
                ParsedNode parameters;
                if (at("(")) parameters = parameters(false);
                else {
                    int begin = start(); ParsedNode id = bindingIdentifier();
                    ParsedNode p = node("parameter", begin, id).with("name", text(id)).with("simple", "true").with("rest", "false");
                    parameters = node("parameters", begin, p);
                }
                children.add(parameters); if (at(":")) children.add(annotation()); expect("=>");
                Set<String> names = new HashSet<>();
                for (String binding : parameterNames(parameters)) if (!names.add(binding)) throw failure(last, "Duplicate arrow parameter");
                if (at("{")) children.add(functionBody(parameters, true, true));
                else { functionDepth++; try { children.add(expression(2)); } finally { functionDepth--; } }
                return node("arrowFunction", start, children).with("async", Boolean.toString(isAsync));
            } finally { async = oldAsync; generator = oldGenerator; }
        }
        private ParsedNode arguments() {
            int start = start(); expect("("); List<SyntaxNode> children = new ArrayList<>();
            while (!at(")")) { eat("..."); children.add(expression(2)); if (!eat(",")) break; }
            expect(")"); return node("arguments", start, children);
        }
        private ParsedNode array() {
            int start = start(); expect("["); List<SyntaxNode> children = new ArrayList<>(); boolean trailingComma = false;
            while (!at("]")) {
                if (eat(",")) continue;
                int begin = start(); boolean spread = eat("..."); ParsedNode value = expression(2);
                children.add(spread ? node("spreadElement", begin, value) : value);
                if (!eat(",")) break; trailingComma = at("]");
            }
            expect("]"); return node("arrayLiteral", start, children).with("trailingComma", Boolean.toString(trailingComma));
        }
        private ParsedNode object() {
            int start = start(); expect("{"); List<SyntaxNode> children = new ArrayList<>(); boolean trailingComma = false;
            while (!at("}")) {
                int begin = start(); List<SyntaxNode> parts = new ArrayList<>(); boolean shorthandDefault = false; boolean spread = eat("..."); boolean memberAsync = false, memberGenerator = false;
                if (spread) parts.add(expression(2));
                else {
                    boolean isAsync = at("async") && !Set.of("(", ":", ",", "}", "=", "<").contains(nextText());
                    memberAsync = isAsync; if (isAsync) take(); boolean isGenerator = eat("*"); memberGenerator = isGenerator;
                    String accessor = "";
                    if ((at("get") || at("set")) && !Set.of("(", ":", ",", "}", "=", "<").contains(nextText())) accessor = take().text();
                    ParsedNode key = propertyName(); parts.add(key);
                    if (eat(":")) parts.add(expression(2));
                    else if (eat("=")) { if (!identifierToken(key)) throw failure(last, "Default requires a shorthand identifier"); shorthandDefault = true; parts.add(expression(2)); }
                    else if (at("(") || at("<")) {
                        boolean oldAsync = async, oldGenerator = generator; async = isAsync; generator = isGenerator;
                        try {
                            if (at("<")) parts.add(typeParameters()); ParsedNode parameters = parameters(false); parts.add(parameters); checkAccessor(accessor, parameters);
                            if (at(":")) parts.add(annotation()); parts.add(functionBody(parameters, true, false));
                        } finally { async = oldAsync; generator = oldGenerator; }
                    } else if (!identifierToken(key)) throw failure(peek(), "Expected property value");
                }
                children.add(node("objectMember", begin, parts).with("shorthandDefault", Boolean.toString(shorthandDefault)).with("spread", Boolean.toString(spread)).with("async", Boolean.toString(memberAsync)).with("generator", Boolean.toString(memberGenerator)));
                if (!eat(",")) break; trailingComma = at("}");
            }
            expect("}"); return node("objectLiteral", start, children).with("trailingComma", Boolean.toString(trailingComma));
        }
        private void validateCoverGrammar(Program program) {
            record Visit(SyntaxNode node, boolean pattern) { }
            var pending = new java.util.ArrayDeque<Visit>(); pending.push(new Visit(program, false));
            while (!pending.isEmpty()) {
                Visit visit = pending.pop(); SyntaxNode node = visit.node();
                if (node.kind().equals("type") || node.kind().equals("objectType")) continue;
                boolean defaultProperty = node instanceof ParsedNode p && p.attribute("shorthandDefault").equals("true");
                if (defaultProperty && !visit.pattern()) {
                    var location = new com.pingsu.transpiler.internal.source.SourceText(source).location(node.range().start());
                    throw new Failure(new Diagnostic(name, location.line(), location.column(), "TS_SYNTAX", "Shorthand default is only valid in an assignment pattern"));
                }
                for (int i = 0; i < node.children().size(); i++) {
                    boolean pattern = visit.pattern() && !defaultProperty;
                    if (node.kind().equals("assignmentExpression")) pattern = i == 0;
                    pending.push(new Visit(node.children().get(i), pattern));
                }
            }
        }
        private ParsedNode template(boolean type) {
            int start = start(); expect("`"); List<SyntaxNode> children = new ArrayList<>();
            while (!at("`")) {
                if (eat("${")) { children.add(type ? type(0) : expression(1)); expect("}"); }
                else { if (peek().kind() != TokenKind.TEMPLATE) throw failure(peek(), "Expected template text"); take(); }
            }
            expect("`"); return node(type ? "templateType" : "templateLiteral", start, children);
        }
    }
}
