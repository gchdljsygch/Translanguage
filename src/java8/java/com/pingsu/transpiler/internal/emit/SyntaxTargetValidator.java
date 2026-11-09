package com.pingsu.transpiler.internal.emit;

import com.pingsu.transpiler.internal.compat.Unicode17;
import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.JavaScriptTarget;
import com.pingsu.transpiler.internal.js.*;
import java.util.Set;

/** Feature checks on the reparsed artifact, including regex token syntax and flags. */
public final class SyntaxTargetValidator implements TargetValidator {
    @Override public void validate(JsProgram program, JavaScriptTarget target) { visit(program, program.root(), target, 0, true); }
    public void validateInput(JsProgram program, JavaScriptTarget target) { visit(program, program.root(), target, 0, false); }
    private void visit(JsProgram program, JsNode node, JavaScriptTarget target, int functions, boolean output) {
        if (target == JavaScriptTarget.ES5) Es5SyntaxValidator.validate(program, node, output);
        boolean function = Compat.set("functionDeclaration", "functionExpression", "arrowFunction", "methodDeclaration", "constructorDeclaration").contains(node.kind())
                || node.is("objectMember") && node.children().stream().anyMatch(c -> c.is("functionBody"));
        int nested = functions + (function ? 1 : 0);
        if (functions == 0 && (node.is("unaryExpression") && node.attribute("operator").equals("await") || node.attribute("await").equals("true"))) program.fail(node, "Top-level await is outside the supported explicit targets");
        if (output && (node.is("propertyDeclaration") || node.is("staticBlock"))) program.fail(node, "Class fields/static blocks remain in the printed artifact");
        if (node.is("regularExpression")) regex(program, node, target);
        if(node.is("taggedTemplateExpression")&&!node.child(0).is("parenthesizedExpression")) {
            JsNode base=node.child(0);while(Compat.set("optionalMemberExpression","memberExpression","argumentsExpression").contains(base.kind())){if(base.is("optionalMemberExpression"))program.fail(node,"An optional chain cannot be an unparenthesized template tag");base=base.child(0);}
        }
        if (target == JavaScriptTarget.ES6 || target == JavaScriptTarget.ES5) {
            if (output && !node.attribute("namespaceExport").isEmpty()) program.fail(node, "Namespace re-export remains in " + target + " output");
            if (Compat.set("bigintLiteral", "importExpression", "importMetaExpression").contains(node.kind())) program.fail(node, target + " cannot represent " + node.kind());
            if (output && (node.is("optionalMemberExpression") || node.is("restBinding") || node.attribute("spread").equals("true")
                    || Compat.set("??", "**", "**=", "&&=", "||=", "??=").contains(node.attribute("operator"))
                    || node.attribute("async").equals("true") || node.attribute("await").equals("true")
                    || node.is("unaryExpression") && node.attribute("operator").equals("await"))) program.fail(node, target + " feature remains after lowering: " + node.kind());
            if (output && (node.is("parameters") || node.is("arguments"))) {
                java.util.List<com.pingsu.transpiler.syntax.Token> tokens = program.significantTokens(node);
                if (tokens.size() > 1 && tokens.get(tokens.size()-2).text().equals(",")) program.fail(node, "Trailing parameter/call comma remains in " + target + " output");
            }
            if (output && node.is("tryStatement")) {
                java.util.List<com.pingsu.transpiler.syntax.Token> tokens = program.significantTokens(node);
                for (int i=0;i+1<tokens.size();i++) if (tokens.get(i).text().equals("catch") && tokens.get(i+1).text().equals("{")) program.fail(node, "Optional catch binding remains in " + target + " output");
            }
        }
        if(output&&(target==JavaScriptTarget.ES6 || target==JavaScriptTarget.ES5)&&(node.is("numericLiteral")||node.is("bigintLiteral")||node.is("propertyName")&&Unicode17.isDigit(program.text(node).charAt(0)))&&program.text(node).contains("_"))program.fail(node,"Numeric separator remains in " + target + " output");
        for (JsNode child : node.children()) visit(program, child, target, nested, output);
    }
    private void regex(JsProgram program, JsNode node, JavaScriptTarget target) {
        String literal = program.text(node); int slash = literal.lastIndexOf('/'); String flags = literal.substring(slash+1);
        if (flags.indexOf('d') >= 0 || flags.indexOf('v') >= 0 || (target == JavaScriptTarget.ES6 || target == JavaScriptTarget.ES5) && flags.indexOf('s') >= 0) program.fail(node, "Regular expression flags exceed " + target);
        boolean escaped = false, characterClass = false; int groups=0;
        java.util.HashSet<java.lang.String> names=new java.util.HashSet<String>();
        for (int i=1;i<slash;i++) {
            char c = literal.charAt(i);
            if (escaped) {
                if ((target==JavaScriptTarget.ES6 || target==JavaScriptTarget.ES5) && ((c=='p'||c=='P') && flags.indexOf('u')>=0 || c=='k' && i+1<slash && literal.charAt(i+1)=='<')) program.fail(node, "Regular expression feature exceeds " + target);
                escaped=false; continue;
            }
            if(c=='\\'){escaped=true;continue;} if(c=='[')characterClass=true; if(c==']')characterClass=false;
            if(!characterClass&&c=='(') {
                groups++;
                if(literal.startsWith("(?",i)) {
                    if(i+2>=slash||":=!<".indexOf(literal.charAt(i+2))<0)program.fail(node,"Regular expression group syntax exceeds the target");
                    if(literal.startsWith("(?<",i)) {
                        if((target==JavaScriptTarget.ES6 || target==JavaScriptTarget.ES5))program.fail(node,"Regular expression lookbehind/named groups exceed " + target);
                        if(i+3<slash&&literal.charAt(i+3)!='='&&literal.charAt(i+3)!='!') {
                            int end=literal.indexOf('>',i+3);if(end<0||end>=slash||!names.add(literal.substring(i+3,end)))program.fail(node,"Invalid or duplicate regular expression group name");
                        }
                    }
                }
            }
            if(!characterClass&&c==')'&&--groups<0)program.fail(node,"Unbalanced regular expression group");
        }
        if(groups!=0||characterClass)program.fail(node,"Unclosed regular expression group or character class");
    }
}
