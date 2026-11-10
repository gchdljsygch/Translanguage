package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.compat.Compat;
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
    private SourceEdits edits;
    private JsxLoweringPass jsxPass;
    private DecoratorMode decoratorMode = DecoratorMode.STANDARD;
    void jsxPass(JsxLoweringPass pass) { jsxPass = pass; }
    JsxLoweringPass jsxPass() { return jsxPass == null ? new JsxLoweringPass() : jsxPass; }
    void decoratorMode(DecoratorMode mode) { decoratorMode = mode; }
    DecoratorMode decoratorMode() { return decoratorMode; }
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final java.util.Map<SyntaxNode, List<String>> runtimeReferences = new java.util.IdentityHashMap<>();
    /** Records a generated value reference for scope-aware import retention. */
    public void reference(SyntaxNode node, String name) {
        runtimeReferences.computeIfAbsent(node, ignored -> new ArrayList<>()).add(java.util.Objects.requireNonNull(name));
    }
    /** Generated value references associated with an original syntax node. */
    public List<String> references(SyntaxNode node) {
        List<String> names = runtimeReferences.get(node);
        return names == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(names);
    }
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
        com.pingsu.transpiler.internal.source.SourceText.Location location = source.location(range.start());
        diagnostics.add(new Diagnostic(sourceName, location.line(), location.column(), code, message));
    }
    public List<Diagnostic> diagnostics() { return Compat.copyList(diagnostics); }

    /** Internal phase boundary: materialize prior edits before replacing a complete compilation. */
    void rewrite(String source) {
        edits = new SourceEdits(this.source.text());
        edits.replace(program.range(), source);
    }
    String render() { return edits.render(); }
    String render(SourceRange range) { return edits.render(range); }
    void reportDiagnostics(List<Diagnostic> errors) { diagnostics.addAll(errors); }
}
