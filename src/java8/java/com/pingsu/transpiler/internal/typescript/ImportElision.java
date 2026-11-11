package com.pingsu.transpiler.internal.typescript;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.TransformationContext;
import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.syntax.SourceRange;
import com.pingsu.transpiler.syntax.SyntaxNode;
import java.util.List;
import java.util.stream.Collectors;

/** Applies import edits as one owner, so explicit and inferred type erasure cannot overlap. */
public final class ImportElision {
    private final TransformationContext context;
    private final RuntimeBindingAnalysis bindings;

    public ImportElision(TransformationContext context) {
        this.context = context;
        boolean hasImports = context.program().children().stream().anyMatch(n -> (n.kind().equals("importDeclaration") || n.kind().equals("importEqualsDeclaration"))
                && !n.children().isEmpty() && !RuntimeBindingAnalysis.attribute(n, "typeOnly").equals("true"));
        bindings = hasImports ? new RuntimeBindingAnalysis(context) : null;
    }

    public boolean usesImport(String name) { return bindings != null && bindings.usesImport(name); }

    /** Returns whether the declaration still marks the output as an ES module. */
    public boolean transform(ParsedNode declaration) {
        if (declaration.attribute("typeOnly").equals("true")) { context.remove(declaration); return false; }
        if (declaration.children().isEmpty()) return true; // Bare side-effect import.
        SyntaxNode defaultBinding = declaration.children().stream().filter(n -> n.kind().equals("identifier")).findFirst().orElse(null);
        SyntaxNode clause = declaration.children().stream().filter(n -> n.kind().equals("moduleItems") || n.kind().equals("namespaceImport")).findFirst().orElse(null);
        boolean keepDefault = defaultBinding != null && bindings.usesImport(RuntimeBindingAnalysis.attribute(defaultBinding, "bindingName"));
        List<? extends SyntaxNode> kept = clause != null && clause.kind().equals("moduleItems")
                ? clause.children().stream().filter(n -> n.kind().equals("moduleSpecifier")
                    && bindings.usesImport(RuntimeBindingAnalysis.attribute(n, "localName"))).collect(Compat.toList()) : Compat.list();
        // An explicitly empty import list is also a request to evaluate the dependency.
        boolean emptyImport = defaultBinding == null && clause != null && clause.kind().equals("moduleItems") && clause.children().isEmpty();
        boolean keepClause = clause != null && (emptyImport || !kept.isEmpty()
                || clause.kind().equals("namespaceImport") && bindings.usesImport(RuntimeBindingAnalysis.attribute(clause, "localName")));
        if (!keepDefault && !keepClause) { context.remove(declaration); return false; }
        if (defaultBinding != null && !keepDefault)
            context.remove(new SourceRange(defaultBinding.range().start(), clause.range().start()));
        if (clause != null && !keepClause)
            context.remove(new SourceRange(defaultBinding.range().end(), clause.range().end()));
        else if (clause != null && clause.kind().equals("moduleItems") && kept.size() != clause.children().size())
            context.replace(clause, "{ " + kept.stream().map(context::slice).collect(Collectors.joining(", ")) + " }");
        return true;
    }
}