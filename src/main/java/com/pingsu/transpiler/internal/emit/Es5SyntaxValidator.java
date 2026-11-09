package com.pingsu.transpiler.internal.emit;

import com.pingsu.transpiler.internal.js.*;
import com.pingsu.transpiler.syntax.Token;

/** ES5-specific input boundaries and final-artifact checks, shared by both JVM artifacts. */
final class Es5SyntaxValidator {
    static void validate(JsProgram program, JsNode node, boolean output) {
        if(node.attribute("async").equals("true")||node.attribute("generator").equals("true")||node.is("yieldExpression"))program.fail(node,"ES5 async functions and generators are not supported");
        if(node.is("importDeclaration")||node.is("exportDeclaration")||node.is("importExpression")||node.is("importMetaExpression"))program.fail(node,"ES5 scripts cannot contain ES modules; bundle modules before selecting ES5");
        if(node.is("taggedTemplateExpression")||node.is("newTargetExpression"))program.fail(node,"ES5 cannot lower "+node.kind());
        if(node.is("superExpression")&&output)program.fail(node,"super remains in ES5 output");
        if(output){
            if(node.is("classDeclaration")||node.is("classExpression")||node.is("arrowFunction")||node.is("templateLiteral")||node.is("computedName")||node.is("spreadElement")||node.is("objectBinding")||node.is("arrayBinding")||node.is("restBinding"))program.fail(node,"Modern syntax remains in ES5 output: "+node.kind());
            if(node.is("variableStatement")&&!program.significantTokens(node).get(0).text().equals("var"))program.fail(node,"Block-scoped declaration remains in ES5 output");
            if(node.is("parameter")&&(node.attribute("rest").equals("true")||node.children().size()!=1))program.fail(node,"Non-simple parameter remains in ES5 output");
            if(node.is("objectMember")){
                if(node.children().size()==1)program.fail(node,"Shorthand property remains in ES5 output");
                if(node.children().stream().anyMatch(c->c.is("parameters"))){String first=program.significantTokens(node).get(0).text();if(!first.equals("get")&&!first.equals("set"))program.fail(node,"Concise method remains in ES5 output");}
            }
            if(node.is("arguments"))for(Token token:program.significantTokens(node))if(token.text().equals("..."))program.fail(node,"Argument spread remains in ES5 output");
            if(node.is("forStatement")&&node.children().size()==3){String gap=program.source().substring(node.child(0).range().end(),node.child(1).range().start()).trim();if(gap.equals("of"))program.fail(node,"for-of remains in ES5 output");}
            if(node.is("stringLiteral")||node.is("propertyName")||node.is("identifier")){
                String text=program.text(node);for(int i=0;i<text.length();i++)if(text.charAt(i)=='\\'){if(text.startsWith("\\u{",i))program.fail(node,"Unicode code-point escape remains in ES5 output");i++;}
            }
            if(node.is("numericLiteral")||node.is("propertyName")){String text=program.text(node);if(text.matches("0[bBoO].*"))program.fail(node,"Binary/octal literal remains in ES5 output");}
        }
        if(node.is("regularExpression")){String literal=program.text(node),flags=literal.substring(literal.lastIndexOf('/')+1);for(int i=0;i<flags.length();i++)if("gim".indexOf(flags.charAt(i))<0)program.fail(node,"Regular expression flags exceed ES5");}
        if(node.is("identifier")||node.is("propertyName")){
            String raw=program.text(node);
            if(!raw.startsWith("'")&&!raw.startsWith("\"")&&!Character.isDigit(raw.charAt(0))){String name=ScopeAnalysis.decode(raw);for(int i=0;i<name.length();i++)if(Character.isSurrogate(name.charAt(i)))program.fail(node,"ES5 identifier names cannot contain supplementary Unicode characters; use a quoted property name");}
        }
        if(node.is("program")&&program.source().startsWith("#!"))program.fail(node,"Hashbang syntax exceeds ES5");
    }
}
