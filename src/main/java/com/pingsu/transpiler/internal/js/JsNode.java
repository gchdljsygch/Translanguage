package com.pingsu.transpiler.internal.js;

import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.syntax.*;
import java.util.List;
import java.util.Map;

/** Immutable JavaScript tree; source ranges retain trivia and all expression boundaries. */
public record JsNode(String kind, SourceRange range, List<JsNode> children, Map<String, String> attributes) {
    public JsNode { children = List.copyOf(children); attributes = Map.copyOf(attributes); }
    public static JsNode from(SyntaxNode node) {
        return new JsNode(node.kind(), node.range(), node.children().stream().map(JsNode::from).toList(),
                node instanceof ParsedNode parsed ? parsed.attributes() : Map.of());
    }
    public String attribute(String name) { return attributes.getOrDefault(name, ""); }
    public JsNode child(int index) { return children.get(index); }
    public boolean is(String name) { return kind.equals(name); }
}
