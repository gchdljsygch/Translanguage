package com.pingsu.transpiler.internal.typescript;

import com.pingsu.transpiler.internal.compat.Compat;
import java.util.Set;

/** Syntax removed before runtime binding analysis and JavaScript emission. */
public final class TypeOnlySyntax {
    public static final Set<String> DECLARATIONS = Compat.set("interfaceDeclaration", "typeAliasDeclaration",
            "ambientDeclaration", "overloadDeclaration", "indexMemberDeclaration");
    public static final Set<String> ERASED = Compat.set("typeAnnotation", "typeParameters", "typeArguments",
            "implementsClause", "typeModifier", "typeAssertion", "thisParameter");
    private TypeOnlySyntax() { }
    public static boolean erased(String kind) { return DECLARATIONS.contains(kind) || ERASED.contains(kind); }
}
