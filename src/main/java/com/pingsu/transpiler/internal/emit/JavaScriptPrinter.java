package com.pingsu.transpiler.internal.emit;

import com.pingsu.transpiler.internal.js.*;
import java.util.function.Function;

/** Lossless structural printer. Generated expressions are parenthesized by lowering passes. */
public class JavaScriptPrinter {
    protected final JsProgram program;
    public JavaScriptPrinter(JsProgram program) { this.program = program; }
    public String print() { return render(program.root()); }
    protected String render(JsNode node) { return children(node, this::render); }
    protected String children(JsNode node, Function<JsNode, String> printer) {
        StringBuilder output = new StringBuilder(); int cursor = node.range().start();
        for (JsNode child : node.children()) {
            output.append(program.source(), cursor, child.range().start()).append(printer.apply(child));
            cursor = child.range().end();
        }
        return output.append(program.source(), cursor, node.range().end()).toString();
    }
    protected String text(JsNode node) { return program.text(node); }
    public static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
