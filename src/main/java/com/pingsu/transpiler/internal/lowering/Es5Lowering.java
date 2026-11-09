package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.emit.ScopedJavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import com.pingsu.transpiler.syntax.Token;
import java.math.BigInteger;
import java.util.*;

/** ES5 syntax lowering. Each pass reparses its result before handing it to the next capability. */
public final class Es5Lowering implements LoweringPass {
    @Override public String lower(JsProgram program) {
        String syntax = new Printer(program).print();
        JsProgram regular = JsProgram.parse(program.sourceName(), syntax);
        String expressions = new RegularLowering(true).lower(regular);
        String finalSyntax = new Printer(JsProgram.parse(program.sourceName(), expressions)).print();
        return new Es5LexicalLowering().lower(JsProgram.parse(program.sourceName(), finalSyntax));
    }

    private static final class Printer extends ScopedJavaScriptPrinter {
        private final Map<JsNode, JsNode> parents = new IdentityHashMap<>();
        private final Map<String, String> helpers = new LinkedHashMap<>();
        private FunctionContext function;
        private ClassContext home;
        private boolean constructor;
        private String receiver;
        private static final class FunctionContext {
            final boolean arrow;
            final FunctionContext parent;
            final String thisName, argumentsName;
            boolean usesThis, usesArguments, lazyThis;
            FunctionContext(boolean arrow, FunctionContext parent, ScopeAnalysis scopes) {
                this.arrow=arrow;this.parent=parent;thisName=scopes.fresh("this");argumentsName=scopes.fresh("arguments");
            }
        }
        private static final class ClassContext {
            final String name, base, instance;
            final boolean derived;
            boolean statik;
            String fields;
            ClassContext(String name, String base, String instance, boolean derived) {
                this.name=name;this.base=base;this.instance=instance;this.derived=derived;
            }
        }
        Printer(JsProgram program) { super(program); index(program.root()); }
        private void index(JsNode node) { for (JsNode child:node.children()) {parents.put(child,node);index(child);} }
        private String helper(String purpose, String body) {
            String name=helpers.get(purpose);
            if(name==null){name=scopes.fresh(purpose);helpers.put(purpose,name);helpers.put(name,"function "+name+body);}
            return name;
        }
        @Override public String print() {
            if(scopes.dynamic())program.fail(program.root(),"ES5 lowering with direct eval or with is not supported");
            String result=super.print();
            for(Map.Entry<String,String> entry:helpers.entrySet()) if(entry.getValue().startsWith("function ")) result+="\n"+entry.getValue();
            return result;
        }
        private String apply(String callable,String owner,String args) {return "Function.prototype.apply.call("+callable+","+owner+","+args+")";}
        private String define(String object,String key,String value) {
            return "Object.defineProperty("+object+","+key+",{value:"+value+",writable:true,enumerable:true,configurable:true})";
        }
        private String array(String value) { return array(value, "void 0"); }
        private String array(String value, String limit) {
            String name=helper("array", "(value,limit){var result=[],iterator,step,method,done=false;if(value==null)throw new TypeError('Not iterable');"
                +"method=typeof Symbol!=='undefined'&&Symbol.iterator&&value[Symbol.iterator];"
                +"if(method){iterator=Function.prototype.call.call(method,value);try{while(limit===void 0||result.length<limit){step=iterator.next();if(step.done){done=true;break;}result.push(step.value);}}finally{if(!done&&iterator.return!=null)Function.prototype.call.call(iterator.return,iterator);}return result;}"
                +"if(Object.prototype.toString.call(value)==='[object Array]')return Array.prototype.slice.call(value,0,limit);"
                +"if(typeof value==='string'){for(var i=0;i<value.length;i++){var c=value.charCodeAt(i);if(c>=55296&&c<=56319&&i+1<value.length&&value.charCodeAt(i+1)>=56320&&value.charCodeAt(i+1)<=57343)result.push(value.charAt(i)+value.charAt(++i));else result.push(value.charAt(i));}return result;}"
                +"throw new TypeError('Not iterable');}");
            return name+"("+value+","+limit+")";
        }
        private String propertyKey(String value){String h=helper("key","(value){return typeof value==='symbol'?value:String(value);}");return h+"("+value+")";}
        private String key(JsNode node) {
            if(node.is("computedName"))return propertyKey(render(node.child(0)));
            String raw=text(node);
            return raw.startsWith("'")||raw.startsWith("\"")||Character.isDigit(raw.charAt(0))?literal(raw):quote(ScopeAnalysis.decode(raw));
        }
        private String literal(String raw) {
            if(raw.startsWith("'")||raw.startsWith("\""))return escape(StringLiterals.decode(raw));
            String number=raw.replace("_","");
            if(number.startsWith("0b")||number.startsWith("0B"))return new BigInteger(number.substring(2),2).toString();
            if(number.startsWith("0o")||number.startsWith("0O"))return new BigInteger(number.substring(2),8).toString();
            return number;
        }
        private String escape(String value) {
            StringBuilder out=new StringBuilder("\"");
            for(int i=0;i<value.length();i++) {char c=value.charAt(i);if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32||c=='\u2028'||c=='\u2029')out.append(String.format("\\u%04x",(int)c));else out.append(c);}
            return out.append('"').toString();
        }
        private boolean function(JsNode n) { return n.is("functionDeclaration")||n.is("functionExpression")||n.is("arrowFunction"); }
        private JsNode part(JsNode n,String kind) {for(JsNode c:n.children())if(c.is(kind))return c;return null;}
        @Override protected String render(JsNode n) {
            if(function(n))return function(n,null,false);
            if(n.is("classDeclaration")||n.is("classExpression"))return clazz(n);
            if(n.is("thisExpression"))return thisValue();
            if(n.is("returnStatement")&&constructor&&home!=null&&home.derived&&(function==null||!function.arrow)){String finish=helper("returnThis","(value,instance){if(value!==null&&(typeof value==='object'||typeof value==='function'))return value;if(value!==void 0)throw new TypeError('Derived constructor returned a primitive');if(instance===void 0)throw new ReferenceError('Super constructor has not been called');return instance;}");return "return "+finish+"("+(n.children().isEmpty()?"void 0":render(n.child(0)))+","+home.instance+");";}
            if(n.is("identifier")&&text(n).equals("arguments")&&function!=null&&function.arrow) {
                if(scopes.scope(n).resolve("arguments")==scopes.scope(functionOwner(n)))return super.render(n);
                FunctionContext owner=function;while(owner.arrow&&owner.parent!=null)owner=owner.parent;
                if(owner.arrow)program.fail(n,"ES5 cannot capture top-level arguments in an arrow");
                owner.usesArguments=true;return owner.argumentsName;
            }
            if((n.is("identifier")||n.is("propertyName"))&&text(n).contains("\\u{")&&!text(n).startsWith("'")&&!text(n).startsWith("\""))return ScopeAnalysis.decode(text(n));
            if(n.is("stringLiteral")||n.is("numericLiteral")||n.is("propertyName")&&(text(n).startsWith("'")||text(n).startsWith("\"")||Character.isDigit(text(n).charAt(0))))return literal(text(n));
            if(n.is("templateLiteral"))return template(n);
            if(n.is("objectLiteral"))return object(n);
            if(n.is("arrayLiteral")&&n.children().stream().anyMatch(c->c.is("spreadElement")))return spreadArray(n);
            if(n.is("argumentsExpression")&&n.child(0).is("superExpression"))return superCall(n);
            if(n.is("argumentsExpression")&&n.child(0).is("memberExpression")&&n.child(0).child(0).is("superExpression"))return apply(superMember(n.child(0)),thisValue(),arguments(n.child(1)));
            if(n.is("memberExpression")&&n.child(0).is("superExpression")) {
                JsNode p=parents.get(n);
                if(p!=null&&(p.is("assignmentExpression")&&p.child(0)==n||p.is("updateExpression")))program.fail(n,"ES5 super property writes are not supported");
                return superMember(n);
            }
            if(n.is("argumentsExpression")&&hasSpread(n.child(1))&&!optionalChain(n.child(0))) {
                JsNode callee=unwrap(n.child(0));String args=arguments(n.child(1));
                if(callee.is("memberExpression")){String owner=temporary();String property=callee.child(1).is("propertyName")?key(callee.child(1)):render(callee.child(1));return "("+owner+"="+render(callee.child(0))+","+apply(owner+"["+property+"]",owner,args)+")";}
                return apply(render(n.child(0)),"void 0",args);
            }
            if(n.is("newExpression")&&n.children().size()>1&&hasSpread(n.child(1)))return "new (Function.prototype.bind.apply("+render(n.child(0))+",[null].concat("+arguments(n.child(1))+")))()";
            if(n.is("variableStatement"))return variables(n);
            if(n.is("assignmentExpression")&&(unwrap(n.child(0)).is("objectLiteral")||unwrap(n.child(0)).is("arrayLiteral")))program.fail(n,"ES5 destructuring assignment is not supported; use a binding declaration");
            if(n.is("forStatement"))return loop(n,"");
            if(n.is("labelledStatement")){JsNode loop=n;String labels="";while(loop.is("labelledStatement")){labels+=loop.attribute("name")+":";loop=loop.child(0);}if(loop.is("forStatement"))return loop(loop,labels);}
            if(n.is("tryStatement"))for(JsNode child:n.children())if(child.is("objectBinding")||child.is("arrayBinding"))program.fail(child,"ES5 destructured catch bindings are not supported");
            return super.render(n);
        }
        private boolean optionalChain(JsNode n){while(n.is("memberExpression")||n.is("argumentsExpression")||n.is("optionalMemberExpression")||n.is("parenthesizedExpression")){if(n.is("optionalMemberExpression"))return true;n=n.child(0);}return false;}
        private JsNode functionOwner(JsNode n){for(JsNode p=parents.get(n);p!=null;p=parents.get(p))if(function(p)||part(p,"parameters")!=null)return p;return program.root();}
        private JsNode unwrap(JsNode n){while(n.is("parenthesizedExpression"))n=n.child(0);return n;}
        private String thisValue() {
            if(function!=null&&function.arrow){FunctionContext owner=function;while(owner.arrow&&owner.parent!=null)owner=owner.parent;owner.usesThis=true;return owner.thisName+(owner.lazyThis?"()":"");}
            return receiver==null?"this":receiver;
        }
        private String function(JsNode n,String name,boolean method) {
            JsNode params=part(n,"parameters"),body=n.child(n.children().size()-1);
            FunctionContext old=function,current=new FunctionContext(n.is("arrowFunction"),old,scopes);function=current;
            ClassContext oldHome=home;boolean oldConstructor=constructor;String oldReceiver=receiver;
            current.lazyThis=method&&n.is("constructorDeclaration")&&oldReceiver!=null;
            if(!current.arrow&&!method){home=null;constructor=false;receiver=null;}
            List<String> signature=new ArrayList<>();StringBuilder prefix=new StringBuilder();boolean optional=false;
            boolean nonSimple=params.children().stream().anyMatch(p->!p.child(0).is("identifier")||p.attribute("rest").equals("true")||part(p,"parameterInitializer")!=null);
            if(nonSimple)checkParameterEnvironment(params,body);
            for(int i=0;i<params.children().size();i++){
                JsNode p=params.child(i),binding=p.child(0),init=part(p,"parameterInitializer");
                boolean rest=p.attribute("rest").equals("true");optional|=rest||init!=null;
                String value;
                if(nonSimple){
                    value=scopes.fresh("parameter");
                    if(!optional)signature.add(scopes.fresh("argument"));
                    prefix.append("var ").append(value).append('=').append(rest?"Array.prototype.slice.call(arguments,"+i+")":"arguments["+i+"]").append(';');
                    if(init!=null)prefix.append("if(").append(value).append("===void 0)").append(value).append('=').append(render(init.child(0))).append(';');
                    List<String> declarations=new ArrayList<>();binding(binding,value,declarations);prefix.append("let ").append(String.join(",",declarations)).append(';');
                }else signature.add(render(binding));
            }
            String rendered=body.is("functionBody")?super.render(body):"{return "+render(body)+";}";
            if(current.usesThis)prefix.insert(0,"var "+current.thisName+"="+(current.lazyThis?"function(){return "+oldReceiver+";}":"this")+";");
            if(current.usesArguments)prefix.insert(0,"var "+current.argumentsName+"=arguments;");
            if(method&&home!=null)prefix.insert(0,"'use strict';");
            rendered=insert(body,rendered,prefix.toString());
            String functionName=name;
            if(functionName==null){JsNode id=n.is("arrowFunction")?null:part(n,"identifier");functionName=id==null?"":render(id);}
            boolean nonConstructible=current.arrow||method&&!n.is("constructorDeclaration")&&!(n.is("objectMember")&&!accessor(n).isEmpty());
            if(nonConstructible){functionName=scopes.fresh("callable");rendered=insert(body,rendered,"if(this instanceof "+functionName+")throw new TypeError('Function is not a constructor');");}
            String result="function "+functionName+"("+String.join(",",signature)+")"+rendered;
            function=old;home=oldHome;constructor=oldConstructor;receiver=oldReceiver;
            if(current.arrow){
                // An ES5 bound function preserves the arrow's receiver. Lexical arguments use the enclosing alias.
                result="("+result+").bind(this)";
                if(old==null&&current.usesThis)result="(function("+current.thisName+"){return "+result+";})(this)";
            }
            return n.is("functionDeclaration")?result:method?result:"("+result+")";
        }
        private void checkParameterEnvironment(JsNode parameters,JsNode body) {
            Set<String> names=new HashSet<>();collectBodyBindings(body,names);
            for(JsNode parameter:parameters.children()){
                List<String> parameterNames=new ArrayList<>();bindingNames(parameter.child(0),parameterNames);
                for(String name:parameterNames)if(names.contains(name))program.fail(parameter,"ES5 non-simple parameter redeclared in the function body requires a separate parameter environment");
                for(JsNode c:parameter.children())if(c.is("parameterInitializer"))checkDefaultReferences(c,names);
            }
        }
        private void collectBodyBindings(JsNode n,Set<String> names){
            if(n.is("functionDeclaration")){JsNode id=part(n,"identifier");if(id!=null)names.add(ScopeAnalysis.decode(text(id)));return;}
            if(function(n))return;
            if(!n.attribute("bindingName").isEmpty())names.add(n.attribute("bindingName"));
            for(JsNode child:n.children())collectBodyBindings(child,names);
        }
        private void checkDefaultReferences(JsNode n,Set<String> names){
            if(n.is("identifier")&&names.contains(ScopeAnalysis.decode(text(n))))program.fail(n,"ES5 parameter defaults referencing a name declared in the function body require a separate parameter environment");
            for(JsNode c:n.children())checkDefaultReferences(c,names);
        }
        private String insert(JsNode body,String rendered,String prefix) {
            if(prefix.isEmpty())return rendered;
            int offset=1;
            if(body.is("functionBody"))for(JsNode statement:body.children()) {
                if(!statement.is("expressionStatement")||!statement.child(0).is("stringLiteral"))break;
                // Rendered directives may have normalized escapes, so find their closing semicolon structurally.
                String directive=render(statement);offset=rendered.indexOf(directive,offset)+directive.length();
            }
            return rendered.substring(0,offset)+(offset==1?"":";")+prefix+rendered.substring(offset);
        }
        private String template(JsNode n) {
            StringBuilder result=new StringBuilder("(\"\"");int cursor=n.range().start()+1;
            for(JsNode expression:n.children()){
                int begin=program.source().lastIndexOf("${",expression.range().start());
                result.append('+').append(escape(StringLiterals.decode("\""+program.source().substring(cursor,begin).replace("\r\n","\n").replace('\r','\n')+"\"")));
                result.append(".concat(").append(render(expression)).append(')');
                int end=expression.range().end();while(program.source().charAt(end)!='}')end++;cursor=end+1;
            }
            result.append('+').append(escape(StringLiterals.decode("\""+program.source().substring(cursor,n.range().end()-1).replace("\r\n","\n").replace('\r','\n')+"\"")));
            return result.append(')').toString();
        }
        private String object(JsNode n) {
            boolean computed=n.children().stream().anyMatch(c->c.child(0).is("computedName")||c.attribute("spread").equals("true"));
            if(!computed){List<String> members=new ArrayList<>();for(JsNode m:n.children()){
                JsNode params=part(m,"parameters");String property=key(m.child(0));
                if(params!=null){String accessor=accessor(m);if(accessor.isEmpty())members.add(property+":"+function(m,"",true));else{String fn=function(m,"",true);members.add(accessor+" "+property+fn.substring(fn.indexOf('(')));}}
                else members.add(property+":"+(m.children().size()==1?ScopeAnalysis.decode(text(m.child(0))):render(m.child(1))));
            }return "{"+String.join(",",members)+"}";}
            String value=temporary();StringBuilder out=new StringBuilder("(").append(value).append("={}");
            for(JsNode m:n.children()) {
                if(m.attribute("spread").equals("true")){
                    String spread=helper("spread","(target,source){if(source==null)return target;var keys=Object.keys(Object(source));if(typeof Object.getOwnPropertySymbols==='function')keys=keys.concat(Object.getOwnPropertySymbols(source));for(var i=0;i<keys.length;i++){var k=keys[i];if(Object.prototype.propertyIsEnumerable.call(source,k))Object.defineProperty(target,k,{value:source[k],writable:true,enumerable:true,configurable:true});}return target;}");
                    out.append(',').append(spread).append('(').append(value).append(',').append(render(m.child(0))).append(')');continue;
                }
                String property=key(m.child(0)),accessor=accessor(m);
                if(!m.child(0).is("computedName")&&property.equals(quote("__proto__"))&&m.children().size()==2)program.fail(m,"ES5 object prototype setters mixed with computed/spread properties are not supported");
                if(!accessor.isEmpty())out.append(',').append(accessorDefinition(value,property,accessor,function(m,"",false),true));
                else out.append(',').append(define(value,property,part(m,"parameters")!=null?function(m,"",true):m.children().size()==1?ScopeAnalysis.decode(text(m.child(0))):render(m.child(1))));
            }
            return out.append(',').append(value).append(')').toString();
        }
        private String accessor(JsNode member) {
            for(Token t:program.significantTokens(member)){if(t.range().start()>=member.child(0).range().start())break;if(t.text().equals("get")||t.text().equals("set"))return t.text();}return "";
        }
        private String accessorDefinition(String object,String key,String accessor,String value,boolean enumerable){
            String h=helper("accessor","(object,key,kind,value,enumerable){var old=Object.getOwnPropertyDescriptor(object,key),d={configurable:true,enumerable:enumerable};if(old&&!Object.prototype.hasOwnProperty.call(old,'value')){d.get=old.get;d.set=old.set;}d[kind]=value;Object.defineProperty(object,key,d);}");
            return h+"("+object+","+key+","+quote(accessor)+","+value+","+enumerable+")";
        }
        private boolean spreadBetween(int start,int end){for(Token t:program.tokens())if(t.range().start()>=start&&t.range().end()<=end&&!t.trivia()&&t.text().equals("..."))return true;return false;}
        private boolean hasSpread(JsNode args){int cursor=args.range().start();for(JsNode c:args.children()){if(spreadBetween(cursor,c.range().start()))return true;cursor=c.range().end();}return false;}
        private String arguments(JsNode args){List<String> groups=new ArrayList<>();int cursor=args.range().start();for(JsNode c:args.children()){boolean spread=spreadBetween(cursor,c.range().start());groups.add(spread?array(render(c)):"["+render(c)+"]");cursor=c.range().end();}return "[].concat("+String.join(",",groups)+")";}
        private String spreadArray(JsNode n){List<String> groups=new ArrayList<>();int cursor=n.range().start()+1;for(JsNode c:n.children()){
            int commas=0;for(Token t:program.tokens())if(t.range().start()>=cursor&&t.range().end()<=c.range().start()&&t.text().equals(","))commas++;
            int holes=commas-(cursor==n.range().start()+1?0:1);if(holes>0)groups.add("new Array("+holes+")");
            groups.add(c.is("spreadElement")?array(render(c.child(0))):"["+render(c)+"]");cursor=c.range().end();
        }int commas=0;for(Token t:program.tokens())if(t.range().start()>=cursor&&t.range().end()<n.range().end()&&t.text().equals(","))commas++;if(commas>1)groups.add("new Array("+(commas-1)+")");return "[].concat("+String.join(",",groups)+")";}
        private String variables(JsNode n) {
            String kind=program.significantTokens(n).get(0).text();List<String> declarations=new ArrayList<>();
            for(JsNode d:n.children()){
                if(d.child(0).is("identifier")){declarations.add(render(d.child(0))+(d.children().size()>1?"="+render(d.child(1)):""));continue;}
                if(d.children().size()<2)program.fail(d,"ES5 loop patterns require a for-of loop");
                String value=scopes.fresh("binding");declarations.add(value+"="+render(d.child(1)));binding(d.child(0),value,declarations);
            }
            return kind+" "+String.join(",",declarations)+(text(n).trim().endsWith(";")?";":"");
        }
        private void binding(JsNode n,String value,List<String> declarations) {
            if(n.is("identifier")||n.is("propertyName")){declarations.add(ScopeAnalysis.decode(text(n))+"="+value);return;}
            if(n.is("arrayBinding")){
                String values=scopes.fresh("items");boolean rest=n.children().stream().anyMatch(c->c.attribute("rest").equals("true"));declarations.add(values+"="+array(value,rest?"void 0":Integer.toString(n.children().size())));int i=0;
                for(JsNode element:n.children()){if(element.is("bindingElision")){i++;continue;}String read=element.attribute("rest").equals("true")?values+".slice("+i+")":values+"["+i+"]";bindDefault(element,element.child(0),read,declarations);i++;}return;
            }
            if(n.is("objectBinding")) {
                String checked=scopes.fresh("object");String check=helper("object","(value){if(value==null)throw new TypeError('Cannot destructure null or undefined');return value;}");declarations.add(checked+"="+check+"("+value+")");List<String> excluded=new ArrayList<>();
                for(JsNode property:n.children()) {
                    if(property.is("restBinding")){
                        String rest=helper("rest","(source,excluded){var target={},keys=Object.keys(Object(source));if(typeof Object.getOwnPropertySymbols==='function')keys=keys.concat(Object.getOwnPropertySymbols(source));for(var i=0;i<keys.length;i++){var k=keys[i];if(excluded.indexOf(k)<0&&Object.prototype.propertyIsEnumerable.call(source,k))Object.defineProperty(target,k,{value:source[k],enumerable:true,writable:true,configurable:true});}return target;}");binding(property.child(0),rest+"("+checked+",["+String.join(",",excluded)+"])",declarations);continue;
                    }
                    JsNode name=property.child(0);String k=key(name);if(name.is("computedName")){String cached=scopes.fresh("key");declarations.add(cached+"="+k);k=cached;}excluded.add(k);
                    JsNode target=property.children().size()>1&&!property.child(1).is("bindingInitializer")?property.child(1):name;
                    bindDefault(property,target,checked+"["+k+"]",declarations);
                }return;
            }
            program.fail(n,"Unsupported ES5 binding: "+n.kind());
        }
        private void bindDefault(JsNode owner,JsNode target,String value,List<String> declarations){JsNode init=part(owner,"bindingInitializer");if(init!=null){String cached=scopes.fresh("default");declarations.add(cached+"="+value);value=cached+"===void 0?"+render(init.child(0))+":"+cached;}binding(target,value,declarations);}
        private String loop(JsNode n,String labels){
            if(n.children().size()!=3)return labels+super.render(n);
            JsNode left=n.child(0),right=n.child(1),body=n.child(2);String between=program.source().substring(left.range().end(),right.range().start()).trim();
            if(!between.equals("of"))return labels+super.render(n);
            String iterator=scopes.fresh("iterator"),step=scopes.fresh("step"),done=scopes.fresh("done"),error=scopes.fresh("error"),failed=scopes.fresh("failed"),caught=scopes.fresh("caught"),close=scopes.fresh("close");
            String iter=helper("iterator","(value){var method=typeof Symbol!=='undefined'&&Symbol.iterator&&value!=null&&value[Symbol.iterator];if(method)return Function.prototype.call.call(method,value);var values="+array("value")+",i=0;return {next:function(){return i<values.length?{value:values[i++],done:false}:{done:true};}};}");
            String initialize,tdz="";
            if(left.is("variableStatement")&&!program.significantTokens(left).get(0).text().equals("var")){List<String> names=new ArrayList<>();bindingNames(left.child(0).child(0),names);tdz="let "+String.join(",",names)+";";}
            if(left.is("variableStatement")){List<String> declarations=new ArrayList<>();binding(left.child(0).child(0),step+".value",declarations);initialize=program.significantTokens(left).get(0).text()+" "+String.join(",",declarations)+";";}else initialize=render(left)+"="+step+".value;";
            return "{var "+iterator+"="+iter+"("+render(right)+"),"+step+","+done+"=true,"+failed+"=false,"+error+";"+tdz+"try{"+labels+"for(;!("+done+"=("+step+"="+iterator+".next()).done);"+done+"=true){"+initialize+render(body)+"}}catch("+caught+"){"+failed+"=true;"+error+"="+caught+";}finally{try{if(!"+done+"){var "+close+"="+iterator+".return;if("+close+"!=null)Function.prototype.call.call("+close+","+iterator+");}}finally{if("+failed+")throw "+error+";}}}";
        }
        private void bindingNames(JsNode n,List<String> names){if(!n.attribute("bindingName").isEmpty())names.add(n.attribute("bindingName"));else if(!n.is("bindingInitializer"))for(JsNode c:n.children())bindingNames(c,names);}
        private String superMember(JsNode n){if(home==null)program.fail(n,"ES5 super requires a class method");String object=home.statik?home.base:home.base+".prototype";String k=n.child(1).is("propertyName")?key(n.child(1)):render(n.child(1));String get=helper("superGet","(base,key,receiver){while(base){var d=Object.getOwnPropertyDescriptor(base,key);if(d)return d.get?Function.prototype.call.call(d.get,receiver):d.value;base=Object.getPrototypeOf(base);}}");return get+"("+object+","+k+","+thisValue()+")";}
        private String construct(String base,String args,String target,String fallback){
            String h=helper("construct","(base,args,target,fallback){if(typeof Reflect!=='undefined'&&typeof Reflect.construct==='function')return Reflect.construct(base,args,target);if(typeof base!=='function')throw new TypeError('Invalid superclass');if(Function.prototype.toString.call(base).indexOf('[native code]')>=0)throw new TypeError('Native inheritance requires Reflect.construct');var value=Function.prototype.apply.call(base,fallback,args);return value!==null&&(typeof value==='object'||typeof value==='function')?value:fallback;}");
            return h+"("+base+","+args+","+target+","+fallback+")";
        }
        private String superCall(JsNode n){if(home==null||!constructor)program.fail(n,"ES5 super() requires a derived constructor");String value=temporary();String init=helper("superInit","(current,value,fallback){if(current!==void 0)throw new ReferenceError('Super constructor already called');return value!==null&&(typeof value==='object'||typeof value==='function')?value:fallback;}");return "("+value+"="+construct(home.base,arguments(n.child(1)),"this.constructor","this")+","+home.instance+"="+init+"("+home.instance+","+value+",this),"+home.fields+".call("+home.instance+"),"+home.instance+")";}
        private String inferredName(JsNode n){JsNode p=parents.get(n);while(p!=null&&p.is("parenthesizedExpression")){n=p;p=parents.get(p);}if(p!=null&&(p.is("variableDeclaration")||p.is("assignmentExpression"))&&p.child(0).is("identifier"))return ScopeAnalysis.decode(text(p.child(0)));return "";}
        private String clazz(JsNode n){
            boolean named=!n.children().isEmpty()&&n.child(0).is("identifier");String name=named?ScopeAnalysis.decode(text(n.child(0))):scopes.fresh("Class");boolean derived=n.attribute("derived").equals("true");int start=named?1:0;
            String base=derived?render(n.child(start++)):"null";ClassContext previous=home,current=new ClassContext(name,scopes.fresh("base"),scopes.fresh("instance"),derived);home=current;
            boolean oldConstructor=constructor;String oldReceiver=receiver;
            FunctionContext fieldContext=new FunctionContext(false,null,scopes);
            List<String> instanceFields=new ArrayList<>(),statics=new ArrayList<>(),methods=new ArrayList<>(),keys=new ArrayList<>(),keyValues=new ArrayList<>();JsNode ctor=null;
            for(int i=start;i<n.children().size();i++){
                JsNode member=n.child(i);if(member.is("constructorDeclaration")){ctor=member;continue;}
                if(member.is("staticBlock"))program.fail(member,"ES5 class static blocks are not supported");
                current.statik=member.attribute("static").equals("true");constructor=false;receiver=null;
                home=previous;String property=key(member.child(0));home=current;
                if(member.child(0).is("computedName")){String cached=scopes.fresh("key");keys.add(cached);keyValues.add(property);property=cached;}
                String target=current.statik?name:name+".prototype";
                if(member.is("propertyDeclaration")){
                    FunctionContext enclosing=function;function=current.statik?new FunctionContext(false,null,scopes):fieldContext;
                    String value=member.children().size()>1?render(member.child(1)):"void 0";
                    String captures=function.usesThis?"var "+function.thisName+"=this;":"";function=enclosing;
                    String definition=define("this",property,value)+";";
                    if(current.statik)statics.add("(function(){"+captures+definition+"}).call("+name+");");else instanceFields.add(definition);
                }else{
                    String fn=function(member,"",true),accessor=accessor(member);
                    methods.add(accessor.isEmpty()?"Object.defineProperty("+target+","+property+",{value:"+fn+",writable:true,configurable:true});":accessorDefinition(target,property,accessor,fn,false)+";");
                }
            }
            String fields=scopes.fresh("fields");current.fields=fields;String fieldFunction="function "+fields+"(){"+(fieldContext.usesThis?"var "+fieldContext.thisName+"=this;":"")+String.join("",instanceFields)+"}";
            constructor=true;current.statik=false;
            String read=helper("this","(value){if(value===void 0)throw new ReferenceError('Super constructor has not been called');return value;}");
            receiver=derived?read+"("+current.instance+")":null;
            String functionCode;
            if(ctor==null)functionCode="function "+name+"(){"+(derived?"var "+current.instance+"="+construct(current.base,"arguments","this.constructor","this")+";if("+current.instance+"===null||(typeof "+current.instance+"!=='object'&&typeof "+current.instance+"!=='function'))"+current.instance+"=this;":"")+fields+".call("+(derived?current.instance:"this")+");"+(derived?"return "+current.instance+";":"")+"}";
            else {
                functionCode=function(ctor,name,true);
                int brace=functionCode.indexOf('{');
                functionCode=functionCode.substring(0,brace+1)+(derived?"var "+current.instance+";":fields+".call(this);")+functionCode.substring(brace+1);
                if(derived){functionCode=functionCode.substring(0,functionCode.length()-1)+"return "+read+"("+current.instance+");}";}
            }
            int brace=functionCode.indexOf('{');functionCode=functionCode.substring(0,brace+1)+"'use strict';if(!(this instanceof "+name+"))throw new TypeError('Class constructor requires new');"+functionCode.substring(brace+1);
            String inheritance="";
            if(derived)inheritance="if("+current.base+"!==null&&typeof "+current.base+"!=='function')throw new TypeError('Invalid superclass');"+name+".prototype=Object.create("+current.base+"===null?null:"+current.base+".prototype);Object.defineProperty("+name+".prototype,'constructor',{value:"+name+",writable:true,configurable:true});if("+current.base+"!==null){if(Object.setPrototypeOf)Object.setPrototypeOf("+name+","+current.base+");else Object.getOwnPropertyNames("+current.base+").forEach(function(k){if(k!=='prototype'&&k!=='name'&&k!=='length'&&k!=='caller'&&k!=='arguments')Object.defineProperty("+name+",k,{configurable:true,get:function(){return "+current.base+"[k];},set:function(v){Object.defineProperty(this,k,{value:v,writable:true,enumerable:true,configurable:true});}});});}";
            String inferred=named?name:inferredName(n);
            String naming="Object.defineProperty("+name+",'name',{value:"+quote(inferred)+",configurable:true});";
            String result="(function("+current.base+(keys.isEmpty()?"":","+String.join(",",keys))+"){'use strict';"+fieldFunction+functionCode+inheritance+naming+String.join("",methods)+String.join("",statics)+"return "+name+";})("+base+(keyValues.isEmpty()?"":","+String.join(",",keyValues))+")";
            home=previous;constructor=oldConstructor;receiver=oldReceiver;
            return n.is("classDeclaration")?"let "+name+"="+result+";":result;
        }
    }
}
