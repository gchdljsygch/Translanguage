package com.pingsu.transpiler.syntax;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Read-only syntax capability. Nodes and children use original source coordinates. */
public interface SyntaxNode {
    /** Stable descriptive kind, e.g. program, typeAnnotation, identifier. */
    String kind();
    SourceRange range();
    List<? extends SyntaxNode> children();

    /** Iterative preorder traversal. Returning false prunes a visitor's subtree. */
    default void walk(SyntaxVisitor visitor) {
        Objects.requireNonNull(visitor);
        var pending = new ArrayDeque<SyntaxNode>();
        pending.push(this);
        while (!pending.isEmpty()) {
            SyntaxNode node = pending.pop();
            if (!visitor.visit(node)) continue;
            var children = node.children();
            for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
        }
    }
}
