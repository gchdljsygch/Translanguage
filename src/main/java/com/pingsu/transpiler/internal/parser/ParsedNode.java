package com.pingsu.transpiler.internal.parser;

import com.pingsu.transpiler.syntax.*;
import java.util.List;
import java.util.Map;

/** Immutable parser node. Attributes describe syntax; transformations own all edit decisions. */
public record ParsedNode(String kind, SourceRange range, List<SyntaxNode> children,
                         Map<String, String> attributes) implements SyntaxNode {
    public ParsedNode {
        children = List.copyOf(children);
        attributes = Map.copyOf(attributes);
        for (SyntaxNode child : children)
            if (!range.contains(child.range())) throw new IllegalArgumentException("Child outside " + kind);
    }
    public String attribute(String name) { return attributes.getOrDefault(name, ""); }
    public ParsedNode with(String name, String value) {
        var values = new java.util.HashMap<>(attributes); values.put(name, value);
        return new ParsedNode(kind, range, children, values);
    }
}
