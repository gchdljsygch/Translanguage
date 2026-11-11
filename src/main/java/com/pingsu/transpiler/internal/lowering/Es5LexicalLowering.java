package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.emit.JavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import com.pingsu.transpiler.syntax.Token;
import java.util.*;

/** ES5 lexical environments: cells retain TDZ, immutable bindings and per-iteration captures. */
final class Es5LexicalLowering implements LoweringPass {
    @Override public String lower(JsProgram program) { return new Printer(program).print(); }
    private static final class Binding {
        final String cell;
        final boolean constant;
        final ScopeAnalysis.Scope scope;
        Binding(String cell, boolean constant, ScopeAnalysis.Scope scope){this.cell=cell;this.constant=constant;this.scope=scope;}
    }
    private static final class Printer extends JavaScriptPrinter {
        private final ScopeAnalysis scopes;
        private final Map<ScopeAnalysis.Scope,Map<String,Binding>> bindings=new IdentityHashMap<>();
        private final Map<JsNode,JsNode> parents=new IdentityHashMap<>();
        private final String make,init,clone;
        Printer(JsProgram program){super(program);scopes=new ScopeAnalysis(program);make=scopes.fresh("lexical");init=scopes.fresh("initialize");clone=scopes.fresh("iteration");index(program.root());}
        private void index(JsNode node){
            if(node.is("variableStatement")){
                String kind=program.significantTokens(node).get(0).text();
                if(!kind.equals("var"))for(JsNode declaration:node.children()){
                    JsNode name=declaration.child(0);if(!name.is("identifier"))program.fail(name,"Unlowered ES5 lexical binding");
                    ScopeAnalysis.Scope scope=scopes.scope(name);
                    bindings.computeIfAbsent(scope,k->new LinkedHashMap<>()).put(ScopeAnalysis.decode(text(name)),new Binding(scopes.fresh("binding"),kind.equals("const"),scope));
                }
            }
            for(JsNode child:node.children()){parents.put(child,node);index(child);}
        }
        @Override public String print(){
            String result=super.print();if(bindings.isEmpty())return result;
            return result+"\nfunction "+make+"(constant){var initialized=false,value;var cell={initialize:function(v){if(initialized)throw new ReferenceError('Binding already initialized');value=v;initialized=true;return v;}};Object.defineProperty(cell,'v',{get:function(){if(!initialized)throw new ReferenceError('Binding before initialization');return value;},set:function(v){if(!initialized)throw new ReferenceError('Binding before initialization');if(constant)throw new TypeError('Assignment to constant variable');value=v;}});return cell;}"
                +"\nfunction "+init+"(cell,value){return cell.initialize(value);}"
                +"\nfunction "+clone+"(cell){var copy="+make+"(false);copy.initialize(cell.v);return copy;}";
        }
        private Binding binding(JsNode node){String name=ScopeAnalysis.decode(text(node));ScopeAnalysis.Scope scope=scopes.scope(node).resolve(name);Map<String,Binding> values=bindings.get(scope);return values==null?null:values.get(name);}
        private List<Binding> owned(JsNode n){Map<String,Binding> values=bindings.get(scopes.scope(n));return values==null?Collections.emptyList():new ArrayList<>(values.values());}
        private String declarations(JsNode n){List<String> values=new ArrayList<>();for(Binding b:owned(n))values.add(b.cell+"="+make+"("+b.constant+")");return values.isEmpty()?"":"var "+String.join(",",values)+";";}
        @Override protected String render(JsNode n){
            if(n.is("identifier")){
                Binding b=binding(n);if(b==null)return text(n);
                JsNode parent=parents.get(n);
                if(parent!=null&&parent.is("unaryExpression")&&parent.attribute("operator").equals("delete"))program.fail(n,"ES5 delete of a lexical binding is not supported");
                JsNode call=n;while(parents.get(call)!=null&&parents.get(call).is("parenthesizedExpression"))call=parents.get(call);
                JsNode use=parents.get(call);return use!=null&&use.is("argumentsExpression")&&use.child(0)==call?"(0,"+b.cell+".v)":b.cell+".v";
            }
            if(n.is("variableStatement")&&!program.significantTokens(n).get(0).text().equals("var")){
                if(scopes.dynamic())program.fail(n,"ES5 lexical lowering cannot preserve direct eval or with visibility");
                List<String> values=new ArrayList<>();for(JsNode d:n.children()){Binding b=binding(d.child(0));values.add(init+"("+b.cell+","+(d.children().size()>1?render(d.child(1)):"void 0")+")");}
                return String.join(",",values)+(text(n).trim().endsWith(";")?";":"");
            }
            if(n.is("program")||n.is("functionBody")||n.is("block"))return body(n);
            if(n.is("switchStatement")&&!owned(n).isEmpty())return "{"+declarations(n)+super.render(n)+"}";
            if(n.is("forStatement")&&!owned(n).isEmpty())return loop(n);
            if(n.is("functionExpression")){
                Set<Binding> captures=new LinkedHashSet<>();capture(n,n,captures);String rendered=super.render(n);
                if(captures.isEmpty())return rendered;
                List<String> cells=new ArrayList<>();for(Binding b:captures)cells.add(b.cell);
                return "(function("+String.join(",",cells)+"){return "+rendered+";})("+String.join(",",cells)+")";
            }
            if(n.is("functionDeclaration")){
                Set<Binding> captures=new LinkedHashSet<>();capture(n,n,captures);
                if(!captures.isEmpty())for(JsNode p=parents.get(n);p!=null;p=parents.get(p)){
                    if(p.is("forStatement")||p.is("whileStatement")||p.is("doStatement"))program.fail(n,"ES5 function declarations capturing loop bindings require a function expression");
                    if(p.is("functionBody"))break;
                }
            }
            return super.render(n);
        }
        private void capture(JsNode owner,JsNode n,Set<Binding> captured){
            if(n.is("identifier")){Binding b=binding(n);if(b!=null){ScopeAnalysis.Scope s=b.scope;boolean local=false;while(s!=null){if(s==scopes.scope(owner)){local=true;break;}s=s.parent();}if(!local)captured.add(b);}}
            for(JsNode c:n.children())capture(owner,c,captured);
        }
        private String body(JsNode n){
            String declaration=declarations(n);if(declaration.isEmpty())return super.render(n);
            int insertion=n.is("program")?n.range().start():n.range().start()+1;
            for(JsNode statement:n.children()){if(statement.is("expressionStatement")&&statement.child(0).is("stringLiteral"))insertion=statement.range().end();else break;}
            StringBuilder out=new StringBuilder();int cursor=n.range().start();boolean added=false;
            for(JsNode c:n.children()){
                if(!added&&insertion<=c.range().start()){out.append(program.source(),cursor,insertion).append(insertion==n.range().start()||insertion==n.range().start()+1?"":";").append(declaration);cursor=insertion;added=true;}
                out.append(program.source(),cursor,c.range().start()).append(render(c));cursor=c.range().end();
            }
            if(!added){out.append(program.source(),cursor,insertion).append(insertion==n.range().start()||insertion==n.range().start()+1?"":";").append(declaration);cursor=insertion;}
            return out.append(program.source(),cursor,n.range().end()).toString();
        }
        private String loop(JsNode n){
            List<Token> tokens=program.significantTokens(n);List<Token> semicolons=new ArrayList<>();int depth=0;
            for(Token t:tokens){if(t.text().equals("("))depth++;else if(t.text().equals(")")){if(--depth==0)break;}else if(t.text().equals(";")&&depth==1)semicolons.add(t);}
            if(semicolons.size()!=2)program.fail(n,"ES5 lexical for-in bindings are not supported; use var or for-of");
            JsNode initializer=n.child(0);String declaration=declarations(n);String dummy=scopes.fresh("loopInit");
            String start=declaration.substring(0,declaration.length()-1)+","+dummy+"=("+render(initializer)+")";
            StringBuilder copies=new StringBuilder();for(Binding b:owned(n))if(!b.constant)copies.append(b.cell).append('=').append(clone).append('(').append(b.cell).append("),");
            if(copies.length()>0)start=start.substring(0,start.length()-1)+","+copies+"void 0)";
            int insert=semicolons.get(1).range().end(),cursor=n.range().start();StringBuilder out=new StringBuilder();boolean added=false;
            JsNode body=n.child(n.children().size()-1);boolean update=false;for(JsNode c:n.children())if(c!=body&&c.range().start()>=insert)update=true;
            if(!update&&copies.length()>0)copies.setLength(copies.length()-1);
            for(JsNode c:n.children()){
                if(!added&&insert<=c.range().start()){out.append(program.source(),cursor,insert).append(copies);cursor=insert;added=true;}
                out.append(program.source(),cursor,c.range().start()).append(c==initializer?start:render(c));cursor=c.range().end();
            }
            return out.append(program.source(),cursor,n.range().end()).toString();
        }
    }
}
