package com.pingsu.transpiler;

import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps DefineField semantics when the pinned backend lowers public fields to assignments.
 * Source positions identify only statements synthesized from original field declarations.
 * User-written assignments, including parameter properties, retain assignment semantics.
 */
final class ClassFieldSemantics {
    private static final DiagnosticType UNSUPPORTED = DiagnosticType.error(
            "TRANSLANGUAGE_CLASS_FIELD", "Cannot safely lower class fields: {0}");
    private record Position(String source, int offset) {
        static Position of(Node node) { return new Position(node.getSourceFileName(), node.getSourceOffset()); }
    }
    private final Compiler compiler;
    private final Map<Position, Node> fields = new HashMap<>();
    private final Map<Position, String> classNames = new HashMap<>();
    private final Set<String> originalMethods = new HashSet<>();
    private final Set<String> staticHelpers = new HashSet<>();
    private final Set<String> names = new HashSet<>();
    private int nextClass;

    ClassFieldSemantics(Compiler compiler) { this.compiler = compiler; }

    void capture(Node externs, Node root) { capture(root); separateClassExports(root); }

    private void capture(Node node) {
        if (node.isName()) names.add(node.getString());
        if (node.isMemberFieldDef() || node.isComputedFieldDef()) {
            fields.put(Position.of(node), node.cloneNode());
        }
        if (node.isMemberFunctionDef()) originalMethods.add(node.getString());
        if (node.isClass()) {
            validateConstructor(node);
            String name = node.getFirstChild().isName() ? node.getFirstChild().getString() : "";
            if (name.isEmpty()) {
                Node parent = node.getParent();
                if (parent.isName() || parent.isStringKey()) name = parent.getString();
                else if (parent.isAssign() && parent.getFirstChild().isName()) name = parent.getFirstChild().getString();
                else if (parent.isExport()) name = "default";
            }
            classNames.put(Position.of(node), name);
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) capture(child);
    }

    private void separateClassExports(Node node) {
        for (Node child = node.getFirstChild(); child != null;) {
            Node next = child.getNext();
            separateClassExports(child);
            child = next;
        }
        if (!node.isExport() || !node.getFirstChild().isClass()) return;
        Node owner = node.getFirstChild().detach();
        String local = owner.getFirstChild().isName() ? owner.getFirstChild().getString() : "";
        if (local.isEmpty()) {
            do { local = "$translanguage$default$" + nextClass++; } while (!names.add(local));
            owner.getFirstChild().replaceWith(IR.name(local).srcref(owner));
        }
        String exported = node.getBooleanProp(Node.EXPORT_DEFAULT) ? "default" : local;
        Node spec = new Node(Token.EXPORT_SPEC, IR.name(local), IR.name(exported));
        Node export = IR.export(new Node(Token.EXPORT_SPECS, spec)).srcrefTreeIfMissing(node);
        node.replaceWith(owner);
        export.insertAfter(owner);
        compiler.reportChangeToEnclosingScope(owner);
    }

    private void validateConstructor(Node owner) {
        if (owner.getSecondChild().isEmpty()) return;
        Node members = owner.getLastChild();
        boolean instanceFields = false;
        Node constructor = null;
        for (Node member = members.getFirstChild(); member != null; member = member.getNext()) {
            if ((member.isMemberFieldDef() || member.isComputedFieldDef()) && !member.isStaticMember()) instanceFields = true;
            if (member.isMemberFunctionDef() && member.getString().equals("constructor")) constructor = member.getFirstChild();
        }
        if (!instanceFields || constructor == null) return;
        // The backend inserts fields after the first direct super() statement. Complex control
        // flow must not silently initialize this before super() or on a path that returns an object.
        Node direct = null;
        for (Node statement = constructor.getLastChild().getFirstChild(); statement != null; statement = statement.getNext()) {
            if (statement.isExprResult() && statement.getFirstChild().isCall()
                    && statement.getFirstFirstChild().isSuper()) { direct = statement.getFirstChild(); break; }
        }
        if (direct == null || countSuperCalls(constructor.getLastChild()) != 1) {
            compiler.report(JSError.make(owner, UNSUPPORTED,
                    "derived constructors require one direct super() statement"));
        }
    }

    private int countSuperCalls(Node node) {
        if (node.isClass()) return 0;
        int count = node.isCall() && node.getFirstChild().isSuper() ? 1 : 0;
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) count += countSuperCalls(child);
        return count;
    }

    void restore(Node root) {
        rewrite(root);
        rewriteHelperCalls(root);
        for (Node field : fields.values()) {
            compiler.report(JSError.make(field, UNSUPPORTED, "field initialization was not emitted by the backend"));
        }
    }

    private void restoreClassName(Node owner) {
        String original = classNames.remove(Position.of(owner));
        if (original == null) return;
        String emitted = owner.getFirstChild().isName() ? owner.getFirstChild().getString() : "";
        String effective = emitted.isEmpty() && owner.getParent().isName() ? owner.getParent().getString() : emitted;
        if (original.equals(effective)) return;
        Node statement = owner;
        Node reference;
        if (owner.getParent().isName()) {
            reference = IR.name(owner.getParent().getString());
            statement = owner.getParent().getParent();
        } else if (!emitted.isEmpty()) {
            reference = IR.name(emitted);
        } else {
            return; // Unextracted anonymous expressions retain native name inference.
        }
        if (statement.getParent().isExport()) statement = statement.getParent();
        if (!statement.getParent().isBlock() && !statement.getParent().isScript()
                && !statement.getParent().isModuleBody()) return;
        Node definition = IR.exprResult(IR.call(IR.getprop(IR.name("Object"), "defineProperty"), reference,
                IR.string("name"), IR.objectlit(IR.stringKey("value", IR.string(original)),
                        IR.stringKey("configurable", IR.trueNode())))).srcrefTreeIfMissing(owner);
        definition.insertAfter(statement);
        compiler.reportChangeToEnclosingScope(definition);
    }

    // A temporary static method provides the correct home object for `super` in a field
    // initializer. Extract its function before running initializers, so reflection sees
    // only the user's own static members, then invoke it with the original receiver.
    private void extractStaticHelpers(Node owner) {
        Node statement = owner;
        String reference;
        if (owner.getParent().isName()) {
            reference = owner.getParent().getString();
            statement = owner.getParent().getParent();
        } else if (owner.getFirstChild().isName()) {
            reference = owner.getFirstChild().getString();
        } else return;
        if (statement.getParent().isExport()) statement = statement.getParent();
        if (!statement.getParent().isBlock() && !statement.getParent().isScript()
                && !statement.getParent().isModuleBody()) return;
        for (Node member = owner.getLastChild().getFirstChild(); member != null; member = member.getNext()) {
            if (!member.isMemberFunctionDef() || !member.isStaticMember()) continue;
            String name = member.getString();
            if (!name.startsWith("$jscomp$static$init$") || originalMethods.contains(name)) continue;
            staticHelpers.add(name);
            Node save = IR.constNode(IR.name(name), IR.getprop(IR.name(reference), name)).srcrefTreeIfMissing(owner);
            save.insertAfter(statement);
            Node remove = IR.exprResult(IR.delprop(IR.getprop(IR.name(reference), name))).srcrefTreeIfMissing(owner);
            remove.insertAfter(save);
            statement = remove;
            compiler.reportChangeToEnclosingScope(remove);
        }
    }

    private void rewriteHelperCalls(Node node) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) rewriteHelperCalls(child);
        if (!node.isCall() || !node.getFirstChild().isGetProp()) return;
        Node callee = node.getFirstChild();
        if (!staticHelpers.contains(callee.getString())) return;
        Node receiver = callee.getFirstChild().detach();
        String name = callee.getString();
        callee.replaceWith(IR.getprop(IR.name(name), "call").srcrefTreeIfMissing(node));
        receiver.insertAfter(node.getFirstChild());
        compiler.reportChangeToEnclosingScope(node);
    }

    private void rewrite(Node node) {
        for (Node child = node.getFirstChild(); child != null;) {
            Node next = child.getNext();
            rewrite(child);
            child = next;
        }
        if (node.isClass()) {
            extractStaticHelpers(node);
            restoreClassName(node);
        }
        if (!node.isExprResult() || !fields.containsKey(Position.of(node))) return;
        Node expression = node.getFirstChild();
        Node member = expression.isAssign() ? expression.getFirstChild() : expression;
        if (!member.isGetProp() && !member.isGetElem()) return;
        Node value = expression.isAssign() ? expression.getLastChild().detach() : IR.voidNode(IR.number(0));
        Node key = member.isGetProp() ? IR.string(member.getString()) : member.getLastChild().detach();
        Node receiver = member.getFirstChild().detach();
        Node descriptor = IR.objectlit(IR.stringKey("value", value), IR.stringKey("writable", IR.trueNode()),
                IR.stringKey("enumerable", IR.trueNode()), IR.stringKey("configurable", IR.trueNode()));
        Node call = IR.call(IR.getprop(IR.name("Object"), "defineProperty"), receiver, key, descriptor).srcrefTreeIfMissing(node);
        expression.replaceWith(call);
        fields.remove(Position.of(node));
        compiler.reportChangeToEnclosingScope(node);
    }
}
