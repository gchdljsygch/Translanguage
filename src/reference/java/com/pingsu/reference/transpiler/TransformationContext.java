package com.pingsu.reference.transpiler;

import com.pingsu.reference.antlr.TypeScriptParser;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import java.util.ArrayList;
import java.util.List;

/**
 * Read/write view over one parse: the parse tree, its tokens, source edits, and diagnostics.
 *
 * <p>A pass reports unsupported syntax through {@link #unsupported(ParserRuleContext, String)};
 * any diagnostic aborts the remaining passes and the result carries no JavaScript.</p>
 */
public final class TransformationContext {
    private final String sourceName;
    private final JavaScriptTarget target;
    private final TypeScriptParser.ProgramContext program;
    private final CommonTokenStream tokens;
    private final SourceEdits edits;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    TransformationContext(String sourceName, String source, TypeScriptParser.ProgramContext program,
                          CommonTokenStream tokens, JavaScriptTarget target) {
        this.sourceName = sourceName;
        this.target = target;
        this.program = program;
        this.tokens = tokens;
        this.edits = new SourceEdits(source);
    }

    /** Returns the requested syntax target, available to custom transformation passes. */
    public JavaScriptTarget target() { return target; }

    /** Returns the root parse tree of the program. */
    public TypeScriptParser.ProgramContext program() { return program; }

    /** Returns the filled token stream the tree was parsed from. */
    public CommonTokenStream tokens() { return tokens; }

    /** Deletes the source range covered by {@code node}. */
    public void remove(ParserRuleContext node) { replace(node, ""); }

    /** Deletes a single token, including surrounding trivia only where the ranges require it. */
    public void remove(Token token) { replace(token, token, ""); }

    /** Replaces the source range covered by {@code node} with {@code text}. */
    public void replace(ParserRuleContext node, String text) { replace(node.start, node.stop, text); }

    /** Replaces the range from {@code first} through {@code last} with {@code text}. */
    public void replace(Token first, Token last, String text) { edits.replace(first, last, text); }

    /** Appends text after the end of the original source. */
    public void append(String text) { edits.append(text); }

    /** Inserts text immediately after {@code token}. */
    public void insertAfter(Token token, String text) { edits.insertAfter(token, text); }

    /** Reports {@code TS_UNSUPPORTED} at the start of {@code node}. */
    public void unsupported(ParserRuleContext node, String message) {
        error(node.start, "TS_UNSUPPORTED", message);
    }

    /** Reports a diagnostic at the location of {@code token}. */
    public void error(Token token, String code, String message) {
        diagnostics.add(new Diagnostic(sourceName, token.getLine(), token.getCharPositionInLine() + 1, code, message));
    }

    /** Returns an immutable snapshot of diagnostics reported so far. */
    public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }
    String render() { return edits.render(); }
}
