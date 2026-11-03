package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.compat.Compat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Finds calls belonging to a constructor's lexical super binding, including arrows. */
public final class SuperCallSites {
    private SuperCallSites() { }

    public static <N> List<N> collect(N root, Function<N, String> kind,
                                     Function<N, ? extends List<? extends N>> children) {
        List<N> calls = new ArrayList<>();
        visit(root, kind, children, calls);
        return calls;
    }

    private static <N> void visit(N node, Function<N, String> kind,
                                  Function<N, ? extends List<? extends N>> children, List<N> calls) {
        String type = kind.apply(node);
        List<? extends N> parts = children.apply(node);
        if (Compat.set("functionDeclaration", "functionExpression", "typeAnnotation", "typeAssertion",
                "typeParameters", "typeArguments", "ambientDeclaration").contains(type)) return;
        if (type.equals("classDeclaration") || type.equals("classExpression")) {
            // Heritage and computed member keys still run in the enclosing lexical scope.
            for (N child : parts) {
                String childKind = kind.apply(child);
                if (Compat.set("methodDeclaration", "constructorDeclaration", "propertyDeclaration").contains(childKind)) {
                    for (N part : children.apply(child)) if (kind.apply(part).equals("computedName")) visit(part, kind, children, calls);
                } else if (!childKind.equals("staticBlock")) visit(child, kind, children, calls);
            }
            return;
        }
        if (type.equals("objectMember") && parts.stream().anyMatch(n -> kind.apply(n).equals("functionBody"))) {
            for (N child : parts) if (kind.apply(child).equals("computedName")) visit(child, kind, children, calls);
            return;
        }
        if (type.equals("argumentsExpression") && kind.apply(parts.get(0)).equals("superExpression")) calls.add(node);
        for (N child : parts) visit(child, kind, children, calls);
    }
}
