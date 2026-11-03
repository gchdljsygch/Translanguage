package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.syntax.*;
import java.util.List;
import java.util.Map;

/** Immutable parser node. Attributes describe syntax; transformations own all edit decisions. */
public final class ParsedNode implements SyntaxNode {
        private final String kind;
        private final SourceRange range;
        private final List<SyntaxNode> children;
        private final Map<String, String> attributes;
        public ParsedNode(String kind, SourceRange range, List<SyntaxNode> children,
                         Map<String, String> attributes) {
        children = Compat.copyList(children);
        attributes = Compat.copyMap(attributes);
        for (SyntaxNode child : children)
            if (!range.contains(child.range())) throw new IllegalArgumentException("Child outside " + kind);

            this.kind = kind;
            this.range = range;
            this.children = children;
            this.attributes = attributes;
        }
        public String kind() { return kind; }
        public SourceRange range() { return range; }
        public List<SyntaxNode> children() { return children; }
        public Map<String, String> attributes() { return attributes; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ParsedNode)) return false;
            ParsedNode value = (ParsedNode) other;
            return java.util.Objects.equals(kind, value.kind) && java.util.Objects.equals(range, value.range) && java.util.Objects.equals(children, value.children) && java.util.Objects.equals(attributes, value.attributes);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(kind);
            hash = 31 * hash + java.util.Objects.hashCode(range);
            hash = 31 * hash + java.util.Objects.hashCode(children);
            hash = 31 * hash + java.util.Objects.hashCode(attributes);
            return hash;
        }
        @Override public String toString() { return "ParsedNode[kind=" + kind + ", range=" + range + ", children=" + children + ", attributes=" + attributes + "]"; }


    public String attribute(String name) { return attributes.getOrDefault(name, ""); }
    public ParsedNode with(String name, String value) {
        java.util.HashMap<java.lang.String,java.lang.String> values = new java.util.HashMap<>(attributes); values.put(name, value);
        return new ParsedNode(kind, range, children, values);
    }

    }
