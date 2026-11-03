package com.pingsu.transpiler.internal.js;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.parser.ParsedNode;
import com.pingsu.transpiler.syntax.*;
import java.util.List;
import java.util.Map;

/** Immutable JavaScript tree; source ranges retain trivia and all expression boundaries. */
public final class JsNode {
        private final String kind;
        private final SourceRange range;
        private final List<JsNode> children;
        private final Map<String, String> attributes;
        public JsNode(String kind, SourceRange range, List<JsNode> children, Map<String, String> attributes) { children = Compat.copyList(children); attributes = Compat.copyMap(attributes);
            this.kind = kind;
            this.range = range;
            this.children = children;
            this.attributes = attributes;
        }
        public String kind() { return kind; }
        public SourceRange range() { return range; }
        public List<JsNode> children() { return children; }
        public Map<String, String> attributes() { return attributes; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof JsNode)) return false;
            JsNode value = (JsNode) other;
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
        @Override public String toString() { return "JsNode[kind=" + kind + ", range=" + range + ", children=" + children + ", attributes=" + attributes + "]"; }


    public static JsNode from(SyntaxNode node) {
        return new JsNode(node.kind(), node.range(), node.children().stream().map(JsNode::from).collect(Compat.toList()),
                node instanceof ParsedNode ? ((ParsedNode) node).attributes() : Compat.map());
    }
    public String attribute(String name) { return attributes.getOrDefault(name, ""); }
    public JsNode child(int index) { return children.get(index); }
    public boolean is(String name) { return kind.equals(name); }

    }
