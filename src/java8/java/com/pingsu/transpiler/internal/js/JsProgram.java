package com.pingsu.transpiler.internal.js;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.parser.*;
import com.pingsu.transpiler.syntax.*;
import com.pingsu.transpiler.Diagnostic;
import java.util.List;

/** A parsed JS artifact, independent of the TypeScript compilation tree. */
public final class JsProgram {
        private final String sourceName;
        private final String source;
        private final JsNode root;
        private final List<Token> tokens;
        public JsProgram(String sourceName, String source, JsNode root, List<Token> tokens) { tokens = Compat.copyList(tokens);
            this.sourceName = sourceName;
            this.source = source;
            this.root = root;
            this.tokens = tokens;
        }
        public String sourceName() { return sourceName; }
        public String source() { return source; }
        public JsNode root() { return root; }
        public List<Token> tokens() { return tokens; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof JsProgram)) return false;
            JsProgram value = (JsProgram) other;
            return java.util.Objects.equals(sourceName, value.sourceName) && java.util.Objects.equals(source, value.source) && java.util.Objects.equals(root, value.root) && java.util.Objects.equals(tokens, value.tokens);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(sourceName);
            hash = 31 * hash + java.util.Objects.hashCode(source);
            hash = 31 * hash + java.util.Objects.hashCode(root);
            hash = 31 * hash + java.util.Objects.hashCode(tokens);
            return hash;
        }
        @Override public String toString() { return "JsProgram[sourceName=" + sourceName + ", source=" + source + ", root=" + root + ", tokens=" + tokens + "]"; }


    public static JsProgram parse(String name, String source) {
        com.pingsu.transpiler.internal.parser.SourceParser.Result result = new RecursiveDescentParser().parse(name, source, SourceParser.Language.JAVASCRIPT);
        if (!result.successful()) throw new TargetFailure(result.diagnostics().stream().map(d ->
                new Diagnostic(d.sourceName(), d.line(), d.column(), "JS_TARGET", d.message())).collect(Compat.toList()));
        return new JsProgram(name, source, JsNode.from(result.program()), result.program().tokens());
    }
    public List<Token> significantTokens(JsNode node) {
        int low=0,high=tokens.size();
        while(low<high){int middle=(low+high)>>>1;if(tokens.get(middle).range().start()<node.range().start())low=middle+1;else high=middle;}
        java.util.ArrayList<com.pingsu.transpiler.syntax.Token> result=new java.util.ArrayList<Token>();
        for(int i=low;i<tokens.size()&&tokens.get(i).range().end()<=node.range().end();i++){Token token=tokens.get(i);if(!token.trivia()&&token.kind()!=TokenKind.EOF)result.add(token);}
        return result;
    }
    public String text(JsNode node) { return source.substring(node.range().start(), node.range().end()); }
    public void fail(JsNode node, String message) {
        com.pingsu.transpiler.internal.source.SourceText.Location location = new com.pingsu.transpiler.internal.source.SourceText(source).location(node.range().start());
        throw new TargetFailure(Compat.list(new Diagnostic(sourceName, location.line(), location.column(), "JS_TARGET", message)));
    }

    }
