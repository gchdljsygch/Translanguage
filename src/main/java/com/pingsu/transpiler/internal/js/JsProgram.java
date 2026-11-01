package com.pingsu.transpiler.internal.js;

import com.pingsu.transpiler.internal.parser.*;
import com.pingsu.transpiler.syntax.*;
import com.pingsu.transpiler.Diagnostic;
import java.util.List;

/** A parsed JS artifact, independent of the TypeScript compilation tree. */
public record JsProgram(String sourceName, String source, JsNode root, List<Token> tokens) {
    public JsProgram { tokens = List.copyOf(tokens); }
    public static JsProgram parse(String name, String source) {
        var result = new RecursiveDescentParser().parse(name, source, SourceParser.Language.JAVASCRIPT);
        if (!result.successful()) throw new TargetFailure(result.diagnostics().stream().map(d ->
                new Diagnostic(d.sourceName(), d.line(), d.column(), "JS_TARGET", d.message())).toList());
        return new JsProgram(name, source, JsNode.from(result.program()), result.program().tokens());
    }
    public List<Token> significantTokens(JsNode node) {
        int low=0,high=tokens.size();
        while(low<high){int middle=(low+high)>>>1;if(tokens.get(middle).range().start()<node.range().start())low=middle+1;else high=middle;}
        var result=new java.util.ArrayList<Token>();
        for(int i=low;i<tokens.size()&&tokens.get(i).range().end()<=node.range().end();i++){Token token=tokens.get(i);if(!token.trivia()&&token.kind()!=TokenKind.EOF)result.add(token);}
        return result;
    }
    public String text(JsNode node) { return source.substring(node.range().start(), node.range().end()); }
    public void fail(JsNode node, String message) {
        var location = new com.pingsu.transpiler.internal.source.SourceText(source).location(node.range().start());
        throw new TargetFailure(List.of(new Diagnostic(sourceName, location.line(), location.column(), "JS_TARGET", message)));
    }
}
