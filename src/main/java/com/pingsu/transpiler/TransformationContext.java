package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.source.SourceText;
import com.pingsu.transpiler.syntax.Program;
import com.pingsu.transpiler.syntax.SourceRange;
import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.transpiler.syntax.Token;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-compilation syntax, source edits and diagnostics. All public types belong to this library.
 * Offsets are UTF-16 half-open ranges in the original input after removal of a leading BOM.
 * Nodes and tokens are immutable. Edits never change their coordinates.
 *
 * <p>Overlapping replacements (including containment) and insertions strictly inside a replaced
 * range throw immediately. Boundary insertions are allowed; insertions at the same offset retain
 * registration order. A diagnostic prevents output and subsequent passes.</p>
 */
public final class TransformationContext {
    private final String sourceName;
    private final JavaScriptTarget target;
    private final SourceText source;
    private final Program program;
    private final SourceEdits edits;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    TransformationContext(String sourceName, String source, Program program, JavaScriptTarget target) {
        this.sourceName = sourceName; this.target = target;
        this.source = new SourceText(source);
        this.program = program; this.edits = new SourceEdits(source);
    }
    public String sourceName() { return sourceName; }
    public String source() { return source.text(); }
    public JavaScriptTarget target() { return target; }
    public Program program() { return program; }
    public List<Token> tokens() { return program.tokens(); }
    public String slice(SourceRange range) { return source.slice(range); }
    public String slice(SyntaxNode node) { return slice(node.range()); }
    public String slice(Token token) { return slice(token.range()); }
    public void remove(SyntaxNode node) { remove(node.range()); }
    public void remove(Token token) { remove(token.range()); }
    public void remove(SourceRange range) { replace(range, ""); }
    public void replace(SyntaxNode node, String text) { replace(node.range(), text); }
    public void replace(Token token, String text) { replace(token.range(), text); }
    public void replace(Token first, Token last, String text) {
        replace(new SourceRange(first.range().start(), last.range().end()), text);
    }
    public void replace(SourceRange range, String text) { edits.replace(range, text); }
    public void insert(int offset, String text) { replace(SourceRange.at(offset), text); }
    public void insertAfter(Token token, String text) { insert(token.range().end(), text); }
    public void append(String text) { edits.append(text); }
    public void unsupported(SyntaxNode node, String message) { error(node.range(), "TS_UNSUPPORTED", message); }
    public void error(Token token, String code, String message) { error(token.range(), code, message); }
    public void error(SourceRange range, String code, String message) {
        source.checkRange(range);
        var location = source.location(range.start());
        diagnostics.add(new Diagnostic(sourceName, location.line(), location.column(), code, message));
    }
    public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }

    String render() { return edits.render(); }
    String render(SourceRange range) { return edits.render(range); }
    void reportDiagnostics(List<Diagnostic> errors) { diagnostics.addAll(errors); }
}
