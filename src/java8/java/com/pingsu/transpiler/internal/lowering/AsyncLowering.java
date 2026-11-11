package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.emit.JavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import java.util.*;

/** ES2015 generator continuations preserve native control flow and abrupt completions. */
public final class AsyncLowering implements LoweringPass {
    private final boolean es5;
    public AsyncLowering(){this(false);}
    public AsyncLowering(boolean es5){this.es5=es5;}
    @Override public String lower(JsProgram program) { return new Printer(program,es5).print(); }
    private static final class Printer extends JavaScriptPrinter {
        private final ScopeAnalysis scopes;
        private final AsyncHelpers helpers;
        private Context context;
        private boolean continuation;
        private final String apply;
        private final class Context {
            final boolean generator, arrow;
            final JsNode owner;
            final String superRef = scopes.fresh("super"), lexicalThis = scopes.fresh("this"),
                    lexicalArguments = scopes.fresh("arguments"), lexicalArgumentsType = scopes.fresh("argumentsType"), lexicalTarget = scopes.fresh("target");
            final List<String> temps = new ArrayList<>();
            boolean usesSuper, usesThis, usesArguments, usesArgumentsType, usesTarget;
            Context(JsNode node) { owner=node; generator=node.attribute("generator").equals("true"); arrow=node.is("arrowFunction"); }
            String temp() { String name=scopes.fresh("asyncValue");temps.add(name);return name; }
            String receiver() { if (!arrow) return "this"; usesThis=true;return lexicalThis+"()"; }
            String captures(Context parent) {
                String superValue="key=>Object.defineProperty({},'value',{get:()=>super[key],set:value=>super[key]=value})";
                String thisValue="this",argumentsValue="arguments",argumentsType="typeof arguments",targetValue="new.target";
                if(arrow&&parent!=null){
                    if(usesSuper){parent.usesSuper=true;superValue=parent.superRef;}
                    if(usesThis)thisValue=parent.receiver();
                    if(usesArguments&&parent.arrow&&!localArguments(owner)){parent.usesArguments=true;argumentsValue=parent.lexicalArguments+"()";}
                    if(usesArgumentsType&&parent.arrow&&!localArguments(owner)){parent.usesArgumentsType=true;argumentsType=parent.lexicalArgumentsType+"()";}
                    if(usesTarget){if(parent.arrow){parent.usesTarget=true;targetValue=parent.lexicalTarget+"()";}else targetValue="void 0";}
                }
                return (usesSuper?"const "+superRef+"="+superValue+";":"")
                    +(usesThis?"const "+lexicalThis+"=()=>"+thisValue+";":"")
                    +(usesArguments?"const "+lexicalArguments+"=()=>"+argumentsValue+";":"")
                    +(usesArgumentsType?"const "+lexicalArgumentsType+"=()=>"+argumentsType+";":"")
                    +(usesTarget?"const "+lexicalTarget+"=()=>"+targetValue+";":"");
            }
        }
        Printer(JsProgram program,boolean es5) { super(program);apply=es5?"Function.prototype.apply.call(":"Reflect.apply(";scopes=new ScopeAnalysis(program);helpers=new AsyncHelpers(scopes); }
        @Override public String print() { String result=super.print();return result+helpers.definitions().replace("Reflect.apply(",apply); }
        private boolean function(JsNode n) {
            return Compat.set("functionDeclaration","functionExpression","arrowFunction","methodDeclaration","constructorDeclaration").contains(n.kind())
                ||n.is("objectMember")&&n.children().stream().anyMatch(c->c.is("parameters"));
        }
        @Override protected String render(JsNode n) {
            if (function(n)) {
                if (n.attribute("async").equals("true")) return asynchronous(n);
                if (!n.is("arrowFunction")) {
                    Context old=context;
                    Map<JsNode,String> keys=new IdentityHashMap<>();
                    for(JsNode c:n.children())if(c.is("computedName"))keys.put(c,render(c));
                    context=null;try{return children(n,c->keys.containsKey(c)?keys.get(c):render(c));}finally{context=old;}
                }
                boolean previous=continuation;continuation=false;
                try{
                    JsNode body=n.child(n.children().size()-1);
                    return children(n,c->c==body?localTemporaries(c,true):render(c));
                }finally{continuation=previous;}
            }
            if (n.is("classDeclaration")||n.is("classExpression")) {
                // Methods establish their own home objects; computed keys still use the outer context.
                return super.render(n);
            }
            if (context!=null) {
                if(n.is("parameterInitializer"))return localTemporaries(n,false);
                if(continuation&&n.is("labelledStatement")) {
                    JsNode loop=n;String labels="";
                    while(loop.is("labelledStatement")){labels+=loop.attribute("name")+":";loop=loop.child(0);}
                    if(loop.is("forStatement")&&loop.attribute("await").equals("true"))return iteration(loop,labels);
                }
                if(continuation&&n.is("forStatement")&&n.attribute("await").equals("true"))return iteration(n,"");
                if(continuation&&n.is("unaryExpression")&&n.attribute("operator").equals("await"))return waitFor(render(n.child(0)));
                if(continuation&&n.is("yieldExpression")&&context.generator) {
                    boolean delegate=program.significantTokens(n).size()>1&&program.significantTokens(n).get(1).text().equals("*");
                    String value=n.children().isEmpty()?"void 0":render(n.child(0));
                    if(delegate){helpers.use(AsyncHelpers.Kind.ITERATOR);return "(yield [2,"+value+"])";}
                    return "(yield [1,"+waitFor(value)+"])";
                }
                if(continuation&&n.is("returnStatement")&&context.generator&&!n.children().isEmpty())return "return "+waitFor(render(n.child(0)))+";";
                String reference=reference(n);if(reference!=null)return reference;
            }
            return super.render(n);
        }
        private String localTemporaries(JsNode node,boolean arrowBody){
            if(context==null)return render(node);
            int first=context.temps.size();
            String value=node.is("parameterInitializer")?super.render(node):render(node);
            List<String> local=new ArrayList<>(context.temps.subList(first,context.temps.size()));
            context.temps.subList(first,context.temps.size()).clear();
            if(local.isEmpty())return value;
            String declaration="var "+String.join(",",local)+";";
            if(node.is("functionBody")){
                int insertion=1;
                for(JsNode statement:node.children()){
                    if(!statement.is("expressionStatement")||!statement.child(0).is("stringLiteral"))break;
                    insertion=statement.range().end()-node.range().start();
                }
                return value.substring(0,insertion)+";"+declaration+value.substring(insertion);
            }
            return arrowBody?"{"+declaration+"return "+value+";}":"(()=>{"+declaration+"return "+value+";})()";
        }
        private String waitFor(String value) { return context.generator?"(yield [0,"+value+"])":"(yield ("+value+"))"; }
        private String asynchronous(JsNode n) {
            if(scopes.dynamic())program.fail(n,"Async lowering with direct eval or with cannot preserve binding visibility");
            JsNode params=n.children().stream().filter(c->c.is("parameters")).findFirst().orElseThrow(java.util.NoSuchElementException::new),body=n.child(n.children().size()-1);
            // Property keys are evaluated in the enclosing context, before entering the function.
            Map<JsNode,String> outside=new IdentityHashMap<>();
            for(JsNode c:n.children())if(c!=params&&c!=body)outside.put(c,render(c));
            Context old=context,current=new Context(n);boolean oldContinuation=continuation;context=current;continuation=true;
            String arguments,renderedParams,renderedBody;
            List<String> signature=new ArrayList<>();
            for(JsNode p:params.children()){
                if(p.attribute("rest").equals("true")||p.children().stream().anyMatch(c->c.is("parameterInitializer")))break;
                signature.add(scopes.fresh("parameter"));
            }
            if(current.arrow){String rest=scopes.fresh("rest");arguments="["+String.join(",",signature)+(signature.isEmpty()?"":",")+"..."+rest+"]";signature.add("..."+rest);}
            else arguments="arguments";
            try {
                renderedParams=render(params);
                if(!Compat.stripLeading(renderedParams).startsWith("("))renderedParams="("+renderedParams+")";
                renderedBody=body.is("functionBody")?render(body):"{return "+render(body)+";}";
            } finally {context=old;continuation=oldContinuation;}
            String directives="";
            if(body.is("functionBody"))for(JsNode statement:body.children()){
                if(!statement.is("expressionStatement")||!statement.child(0).is("stringLiteral"))break;
                directives+=text(statement)+";";
            }
            // Temps must follow directives, so strictness remains a property of the original body.
            if(!current.temps.isEmpty()){
                int insertion=1;
                if(body.is("functionBody")){
                    for(JsNode statement:body.children()){
                        if(!statement.is("expressionStatement")||!statement.child(0).is("stringLiteral"))break;
                        insertion=statement.range().end()-body.range().start();
                    }
                }
                renderedBody=renderedBody.substring(0,insertion)+";var "+String.join(",",current.temps)+";"+renderedBody.substring(insertion);
            }
            String receiver=current.arrow?"void 0":"this";
            String iterator=apply+"function*"+renderedParams+renderedBody+","+receiver+","+arguments+")";
            String helper=helpers.use(current.generator?AsyncHelpers.Kind.GENERATOR:AsyncHelpers.Kind.RUN);
            String guard=Compat.set("functionDeclaration","functionExpression").contains(n.kind())?"if(new.target)throw new TypeError('Async function is not a constructor');":"";
            String wrapper="{"+(current.arrow?"":directives)+guard+current.captures(old)+"return "+helper+"("+(current.generator?iterator:"()=>"+iterator)+");}";
            String original=children(n,c->c==params?"("+String.join(",",signature)+")":c==body?wrapper:outside.get(c));
            // Remove only modifier tokens; a property named async and trivia remain intact.
            java.util.List<com.pingsu.transpiler.syntax.Token> tokens=program.significantTokens(n);java.util.ArrayList<com.pingsu.transpiler.syntax.Token> remove=new ArrayList<com.pingsu.transpiler.syntax.Token>();
            for(com.pingsu.transpiler.syntax.Token token:tokens){if(token.range().start()>=params.range().start())break;
                if(token.text().equals("async")&&remove.isEmpty())remove.add(token);
                else if(token.text().equals("*")&&current.generator)remove.add(token);
                // Computed names may contain async or multiplication, so stop at the property/name child.
                if(!n.children().isEmpty()&&n.child(0)!=params&&token.range().start()>=n.child(0).range().start())break;
            }
            for(int i=remove.size()-1;i>=0;i--){com.pingsu.transpiler.syntax.Token token=remove.get(i);int a=token.range().start()-n.range().start();original=original.substring(0,a)+original.substring(a+token.range().length());}
            return original;
        }
        private JsNode superMember(JsNode n) {while(n.is("parenthesizedExpression"))n=n.child(0);return n.is("memberExpression")&&n.child(0).is("superExpression")?n:null;}
        private String superAccess(JsNode n) {context.usesSuper=true;JsNode key=n.child(1);return context.superRef+"("+(key.is("propertyName")?quote(ScopeAnalysis.decode(text(key))):render(key))+").value";}
        private boolean localArguments(JsNode n) {
            com.pingsu.transpiler.internal.js.ScopeAnalysis.Scope binding=scopes.scope(n).resolve("arguments");if(binding==null)return false;
            com.pingsu.transpiler.internal.js.ScopeAnalysis.Scope owner=scopes.scope(context.owner);
            for(com.pingsu.transpiler.internal.js.ScopeAnalysis.Scope s=binding;s!=null;s=s.parent())if(s==owner)return true;
            return false;
        }
        private String reference(JsNode n) {
            if(context.arrow&&n.is("thisExpression"))return context.receiver();
            if(n.is("newTargetExpression")){if(!context.arrow)return "void 0";context.usesTarget=true;return context.lexicalTarget+"()";}
            if(context.arrow&&n.is("unaryExpression")&&n.attribute("operator").equals("typeof")){
                JsNode operand=n.child(0);while(operand.is("parenthesizedExpression"))operand=operand.child(0);
                if(operand.is("identifier")&&ScopeAnalysis.decode(text(operand)).equals("arguments")&&!localArguments(operand)){
                    context.usesArgumentsType=true;return context.lexicalArgumentsType+"()";
                }
            }
            if(context.arrow&&n.is("identifier")&&ScopeAnalysis.decode(text(n)).equals("arguments")&&n.attribute("bindingName").isEmpty()&&!localArguments(n)){
                context.usesArguments=true;return context.lexicalArguments+"()";
            }
            if(context.arrow&&n.is("objectMember")&&n.children().size()==1&&text(n.child(0)).equals("arguments")&&!localArguments(n)){
                context.usesArguments=true;return "arguments:"+context.lexicalArguments+"()";
            }
            if((n.is("argumentsExpression")||n.is("optionalMemberExpression")&&n.child(1).is("arguments"))&&superMember(n.child(0))!=null){
                String value=superAccess(superMember(n.child(0))),args=render(n.child(1));
                String array="["+args.substring(1,args.length()-1)+"]",receiver=context.receiver();
                if(n.is("optionalMemberExpression")){String temp=context.temp();return "("+temp+"="+value+","+temp+"==null?void 0:Reflect.apply("+temp+","+receiver+","+array+"))";}
                return apply+value+","+receiver+","+array+")";
            }
            if(n.is("taggedTemplateExpression")&&superMember(n.child(0))!=null)return apply+"Function.prototype.bind,"+superAccess(superMember(n.child(0)))+",["+context.receiver()+"])"+render(n.child(1));
            if(superMember(n)!=null)return superAccess(superMember(n));
            return null;
        }
        private void bindingNames(JsNode n,Set<String> names){if(!n.attribute("bindingName").isEmpty())names.add(n.attribute("bindingName"));for(JsNode c:n.children())bindingNames(c,names);}
        private String iteration(JsNode n,String labels) {
            String acquire=helpers.use(AsyncHelpers.Kind.ITERATOR),check=helpers.use(AsyncHelpers.Kind.CHECK);
            String it=scopes.fresh("iterator"),step=scopes.fresh("step"),value=scopes.fresh("item"),active=scopes.fresh("active"),failed=scopes.fresh("failed"),error=scopes.fresh("error"),caught=scopes.fresh("caught"),close=scopes.fresh("close");
            JsNode head=n.child(0);String binding=render(head),tdz="";
            if(head.is("variableStatement")){
                java.util.List<com.pingsu.transpiler.syntax.Token> tokens=program.significantTokens(head);
                if(!tokens.get(0).text().equals("var")){Set<String> names=new LinkedHashSet<>();bindingNames(head,names);if(!names.isEmpty())tdz="let "+String.join(",",names)+";";}
                binding+="="+value+";";
            }else binding="("+binding+"="+value+");";
            String next=check+"("+waitFor(it+".next()")+")";
            String setup="const "+it+"="+acquire+"("+render(n.child(1))+");";
            if(!tdz.isEmpty()){
                String headScope=scopes.fresh("head");
                // The loop-head environment stays uninitialized, even in closures saved by the RHS.
                setup="let "+it+";"+headScope+":{"+it+"="+acquire+"("+render(n.child(1))+");break "+headScope+";"+tdz+"}";
            }
            return "{"+setup+"let "+step+","+value+","+active+"=false,"+failed+"=false,"+error+";try{"+labels+
                "for(;("+active+"=false,"+step+"="+next+",!"+step+".done);){"+value+"="+step+".value;"+active+"=true;"+binding+render(n.child(2))+"}}catch("+caught+"){"+failed+"=true;"+error+"="+caught+";}finally{try{if("+active+"){const "+close+"="+it+".close();if("+close+"!==void 0)"+check+"("+waitFor(close+"[0]")+");}}finally{if("+failed+")throw "+error+";}}}";
        }
    }
}
