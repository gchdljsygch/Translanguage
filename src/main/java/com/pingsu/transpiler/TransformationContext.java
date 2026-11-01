package com.pingsu.transpiler;

import com.pingsu.antlr.TypeScriptParser;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import java.util.ArrayList;
import java.util.List;

public final class TransformationContext {
    private final String sourceName;
    private final TypeScriptParser.ProgramContext program;
    private final CommonTokenStream tokens;
    private final SourceEdits edits;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    TransformationContext(String sourceName, String source, TypeScriptParser.ProgramContext program,
                          CommonTokenStream tokens) {
        this.sourceName = sourceName;
        this.program = program;
        this.tokens = tokens;
        this.edits = new SourceEdits(source);
    }

    public TypeScriptParser.ProgramContext program() { return program; }
    public CommonTokenStream tokens() { return tokens; }
    public void remove(ParserRuleContext node) { replace(node, ""); }
    public void remove(Token token) { replace(token, token, ""); }
    public void replace(ParserRuleContext node, String text) { replace(node.start, node.stop, text); }
    public void replace(Token first, Token last, String text) { edits.replace(first, last, text); }
    public void append(String text) { edits.append(text); }
    public void insertAfter(Token token, String text) { edits.insertAfter(token, text); }
    public void unsupported(ParserRuleContext node, String message) {
        error(node.start, "TS_UNSUPPORTED", message);
    }
    public void error(Token token, String code, String message) {
        diagnostics.add(new Diagnostic(sourceName, token.getLine(), token.getCharPositionInLine() + 1, code, message));
    }
    public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }
    String render() { return edits.render(); }
}
