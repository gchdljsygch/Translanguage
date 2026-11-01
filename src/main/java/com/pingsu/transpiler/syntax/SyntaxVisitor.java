package com.pingsu.transpiler.syntax;

/** A syntax traversal capability, independent of the parser implementation. */
@FunctionalInterface
public interface SyntaxVisitor {
    /** Returns true to visit this node's children, or false to prune the subtree. */
    boolean visit(SyntaxNode node);
}
