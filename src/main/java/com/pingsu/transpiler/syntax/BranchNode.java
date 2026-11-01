package com.pingsu.transpiler.syntax;

import java.util.List;
import java.util.Objects;

/** Immutable nonterminal node. Kind names describe syntax rather than Java implementation classes. */
public record BranchNode(String kind, SourceRange range, List<SyntaxNode> children) implements SyntaxNode {
    public BranchNode {
        Objects.requireNonNull(kind); Objects.requireNonNull(range);
        if (kind.isBlank()) throw new IllegalArgumentException("Node kind must not be blank");
        children = List.copyOf(children);
        for (SyntaxNode child : children)
            if (!range.contains(child.range())) throw new IllegalArgumentException("Child outside parent source range");
    }
}
