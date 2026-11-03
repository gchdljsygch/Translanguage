package com.pingsu.transpiler.syntax;

import com.pingsu.transpiler.internal.compat.Compat;
import java.util.List;
import java.util.Objects;

/** Immutable nonterminal node. Kind names describe syntax rather than Java implementation classes. */
public final class BranchNode implements SyntaxNode {
        private final String kind;
        private final SourceRange range;
        private final List<SyntaxNode> children;
        public BranchNode(String kind, SourceRange range, List<SyntaxNode> children) {
        Objects.requireNonNull(kind); Objects.requireNonNull(range);
        if (Compat.isBlank(kind)) throw new IllegalArgumentException("Node kind must not be blank");
        children = Compat.copyList(children);
        for (SyntaxNode child : children)
            if (!range.contains(child.range())) throw new IllegalArgumentException("Child outside parent source range");

            this.kind = kind;
            this.range = range;
            this.children = children;
        }
        public String kind() { return kind; }
        public SourceRange range() { return range; }
        public List<SyntaxNode> children() { return children; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BranchNode)) return false;
            BranchNode value = (BranchNode) other;
            return java.util.Objects.equals(kind, value.kind) && java.util.Objects.equals(range, value.range) && java.util.Objects.equals(children, value.children);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(kind);
            hash = 31 * hash + java.util.Objects.hashCode(range);
            hash = 31 * hash + java.util.Objects.hashCode(children);
            return hash;
        }
        @Override public String toString() { return "BranchNode[kind=" + kind + ", range=" + range + ", children=" + children + "]"; }



    }
