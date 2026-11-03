package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.emit.JavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import java.util.*;

/**
 * Retains native destructuring evaluation using a per-pattern read adapter. Ordinary property
 * reads use the original receiver. A fresh Symbol requests CopyDataProperties at the rest slot.
 * Iterator adapters defer value access and forward IteratorClose to the original iterator.
 */
public final class ObjectRestLowering implements LoweringPass {
    @Override public String lower(JsProgram program) { return new Printer(program).print(); }
    private static final class Pattern {
        private final String source;
        private final String plan;
        private Pattern(String source, String plan) {
            this.source = source;
            this.plan = plan;
        }
        public String source() { return source; }
        public String plan() { return plan; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Pattern)) return false;
            Pattern value = (Pattern) other;
            return java.util.Objects.equals(source, value.source) && java.util.Objects.equals(plan, value.plan);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(source);
            hash = 31 * hash + java.util.Objects.hashCode(plan);
            return hash;
        }
        @Override public String toString() { return "Pattern[source=" + source + ", plan=" + plan + "]"; }

    }
    private static final class Printer extends JavaScriptPrinter {
        private final ScopeAnalysis scopes;
        private String helper;
        private boolean arrayAdapter, recoverSource;
        private GeneratorContext generatorContext;
        private final class GeneratorContext {
            final String arguments=scopes.fresh("arguments"),get=scopes.fresh("superGet"),set=scopes.fresh("superSet");
            final List<String> temps=new ArrayList<>(); boolean reads,writes;
            String temp(){String name=scopes.fresh("reference");temps.add(name);return name;}
        }
        Printer(JsProgram program) { super(program); scopes = new ScopeAnalysis(program); }
        private String helper() {
            if (scopes.dynamic()) program.fail(program.root(), "Object rest lowering with direct eval or with cannot preserve helper visibility");
            if (helper == null) helper = scopes.fresh("destructure"); return helper;
        }
        private boolean hasRest(JsNode node) {
            if (node.is("restBinding") || node.is("objectMember") && node.attribute("spread").equals("true")) return true;
            if(node.is("bindingInitializer")||node.is("parameterInitializer"))return false;
            if(node.is("assignmentExpression")&&node.attribute("operator").equals("="))return hasRest(node.child(0));
            return node.children().stream().anyMatch(this::hasRest);
        }
        @Override public String print() { String result = super.print(); return helper == null ? result : result + "\n" + definition(); }
        @Override protected String render(JsNode node) {
            if(generatorContext!=null) {
                if(isFunction(node)&&!node.is("arrowFunction")||node.is("classDeclaration")||node.is("classExpression")) {
                    GeneratorContext previous=generatorContext;generatorContext=null;
                    try{return render(node);}finally{generatorContext=previous;}
                }
                String replacement=generatorReference(node);if(replacement!=null)return replacement;
            }
            if (node.is("variableDeclaration") && hasRest(node.child(0)) && node.children().size() > 1) {
                Pattern pattern = pattern(node.child(0));
                return pattern.source() + "=" + wrap(render(node.child(1)), pattern.plan());
            }
            if (node.is("assignmentExpression") && node.attribute("operator").equals("=") && hasRest(node.child(0))) {
                recoverSource = true;
                Pattern pattern = pattern(node.child(0));
                return helper() + "((" + pattern.source() + "=" + wrap(render(node.child(1)), pattern.plan()) + "))";
            }
            if (node.is("forStatement") && !node.children().isEmpty()) {
                JsNode head = node.child(0);
                if (head.is("variableStatement") && head.children().size() == 1 && head.child(0).children().size() == 1 && hasRest(head.child(0).child(0))) {
                    JsNode binding = head.child(0).child(0), body = node.child(node.children().size()-1); Pattern pattern = pattern(binding);
                    String temp = scopes.fresh("iteration"), modifier = program.significantTokens(head).get(0).text();
                    String prefix = modifier + " " + pattern.source() + "=" + wrap(temp, pattern.plan()) + ";";
                    return children(node, n -> n == head ? modifier + " " + temp : n == body ? "{" + prefix + render(n) + "}" : render(n));
                }
                if (!head.is("variableStatement") && hasRest(head) && node.children().size() == 3) {
                    JsNode body = node.child(2); Pattern pattern = pattern(head); String temp = scopes.fresh("iteration");
                    return children(node, n -> n == head ? "const " + temp : n == body ? "{(" + pattern.source() + "=" + wrap(temp,pattern.plan()) + ");" + render(n) + "}" : render(n));
                }
            }
            if (node.is("tryStatement")) {
                List<JsNode> parts = node.children();
                for (int i=1;i+1<parts.size();i++) if ((parts.get(i).is("objectBinding") || parts.get(i).is("arrayBinding")) && hasRest(parts.get(i))) {
                    JsNode binding = parts.get(i), body = parts.get(i+1); Pattern pattern = pattern(binding); String temp = scopes.fresh("caught");
                    return children(node, n -> n == binding ? temp : n == body ? "{let " + pattern.source() + "=" + wrap(temp, pattern.plan()) + ";" + render(n) + "}" : render(n));
                }
            }
            if (isFunction(node)) return function(node);
            return super.render(node);
        }
        private boolean isFunction(JsNode node) {
            return Compat.set("functionDeclaration","functionExpression","arrowFunction","methodDeclaration","constructorDeclaration").contains(node.kind())
                    || node.is("objectMember") && node.children().stream().anyMatch(n -> n.is("parameters"));
        }
        private String function(JsNode node) {
            JsNode parameters = node.children().stream().filter(n -> n.is("parameters")).findFirst().orElse(null);
            if (parameters == null || parameters.children().stream().noneMatch(p -> hasRest(p.child(0)))) return super.render(node);
            JsNode body = node.child(node.children().size()-1);
            StringBuilder prelude = new StringBuilder(); List<String> incoming = new ArrayList<>();
            for (JsNode parameter : parameters.children()) {
                JsNode binding = parameter.child(0); Pattern pattern = pattern(binding); String argument = scopes.fresh("argument");
                JsNode defaultValue = parameter.children().stream().filter(n -> n.is("parameterInitializer")).findFirst().orElse(null);
                incoming.add((parameter.attribute("rest").equals("true") ? "..." : "") + argument + (defaultValue == null ? "" : "=void 0"));
                String value = defaultValue == null ? argument : "(" + argument + "===void 0?" + render(defaultValue) + ":" + argument + ")";
                prelude.append("let ").append(pattern.source()).append('=').append(wrap(value, pattern.plan())).append(';');
            }
            // Parameter evaluation precedes the body lexical environment. A nested arrow keeps
            // lexical this, arguments, new.target and super while isolating body declarations.
            // Generator bodies cannot be moved into arrows; preserve them with a block prelude.
            boolean generator = node.attribute("generator").equals("true");
            String renderedBody = generator ? "" : render(body);
            Set<String> parameterNames=new LinkedHashSet<>();for(JsNode parameter:parameters.children())bindingNames(parameter.child(0),parameterNames);
            Set<String> bodyVars=new LinkedHashSet<>();bodyVariables(body,bodyVars);bodyVars.retainAll(parameterNames);
            String copies=String.join(",",bodyVars);
            String newBody;
            if (generator) {
                GeneratorContext context=new GeneratorContext(),previous=generatorContext;generatorContext=context;
                String generatorBody;try{generatorBody=render(body);}finally{generatorContext=previous;}
                String captured=scopes.fresh("parameters");StringBuilder setup=new StringBuilder();
                for(String name:bodyVars)setup.append("var ").append(name).append('=').append(captured).append('.').append(name).append(';');
                if(!context.temps.isEmpty())setup.append("var ").append(String.join(",",context.temps)).append(';');
                generatorBody="{"+setup+generatorBody.substring(1);
                String superBindings=(context.reads?"const "+context.get+"=key=>super[key];":"")+(context.writes?"const "+context.set+"=(key,value)=>super[key]=value;":"");
                newBody="{if(new.target)throw new TypeError('Generator is not a constructor');"+prelude+superBindings+"const "+captured+"={"+copies+"},"+context.arguments+"=arguments;return (function*()"+generatorBody+").apply(this,arguments);}";
            }
            else if (body.is("functionBody")) newBody = "{" + prelude + "return ((" + copies + ")=>" + renderedBody + ")(" + copies + ");}";
            else newBody = "{" + prelude + "return " + renderedBody + ";}";
            String result=children(node, n -> n == parameters ? "(" + String.join(",",incoming) + ")" : n == body ? newBody : render(n));
            if(generator) {
                com.pingsu.transpiler.syntax.Token star=program.significantTokens(node).stream().filter(t->t.text().equals("*")&&t.range().start()<parameters.range().start()).findFirst().orElseThrow(java.util.NoSuchElementException::new);
                int offset=star.range().start()-node.range().start();result=result.substring(0,offset)+result.substring(offset+1);
            }
            return result;
        }
        private JsNode superMember(JsNode node) {
            while(node.is("parenthesizedExpression"))node=node.child(0);
            return node.is("memberExpression")&&node.child(0).is("superExpression")?node:null;
        }
        private String superKey(JsNode member) { return member.child(1).is("propertyName")?quote(ScopeAnalysis.decode(text(member.child(1)))):render(member.child(1)); }
        private String generatorReference(JsNode node) {
            GeneratorContext context=generatorContext;
            if(node.is("identifier")&&ScopeAnalysis.decode(text(node)).equals("arguments")&&node.attribute("bindingName").isEmpty())return context.arguments;
            if(node.is("objectMember")&&node.children().size()==1&&text(node.child(0)).equals("arguments"))return "arguments:"+context.arguments;
            if((node.is("argumentsExpression")||node.is("optionalMemberExpression")&&node.child(1).is("arguments"))&&superMember(node.child(0))!=null) {
                context.reads=true;String key=superKey(superMember(node.child(0))),args=render(node.child(1));
                String arguments="["+args.substring(1,args.length()-1)+"]",read=context.get+"("+key+")";
                if(node.is("optionalMemberExpression")){String value=context.temp();return "("+value+"="+read+","+value+"===null||"+value+"===void 0?void 0:Reflect.apply("+value+",this,"+arguments+"))";}
                return "Reflect.apply("+read+",this,"+arguments+")";
            }
            if(node.is("assignmentExpression")&&superMember(node.child(0))!=null) {
                String key=superKey(superMember(node.child(0))),right=render(node.child(1)),operator=node.attribute("operator");context.writes=true;
                if(operator.equals("="))return context.set+"("+key+","+right+")";
                context.reads=true;String cached=context.temp(),value=context.temp();String prefix=cached+"="+key+","+value+"="+context.get+"("+cached+"),";
                String write=context.set+"("+cached+","+right+")";
                String action; switch(operator){case "??=": action = value+"===null||"+value+"===void 0?"+write+":"+value; break;case "&&=": action = value+"&&"+write; break;case "||=": action = value+"||"+write; break;default: action = context.set+"("+cached+",("+value+operator.substring(0,operator.length()-1)+"("+right+")))"; break;}
                return "("+prefix+action+")";
            }
            if((node.is("unaryExpression")&&Compat.set("++","--").contains(node.attribute("operator"))||node.is("updateExpression"))&&superMember(node.child(0))!=null) {
                context.reads=true;context.writes=true;String key=context.temp(),value=context.temp(),result=context.temp();String op=node.is("updateExpression")?text(node).substring(text(node).length()-2):node.attribute("operator");
                return "("+key+"="+superKey(superMember(node.child(0)))+","+value+"="+context.get+"("+key+"),"+result+"="+(node.is("updateExpression")?value+op:op+value)+","+context.set+"("+key+","+value+"),"+result+")";
            }
            if(superMember(node)!=null){context.reads=true;return context.get+"("+superKey(superMember(node))+")";}
            return null;
        }
        private void bindingNames(JsNode node,Set<String> result) {
            if(!node.attribute("bindingName").isEmpty())result.add(node.attribute("bindingName"));
            if(node.is("bindingInitializer"))return;for(JsNode child:node.children())bindingNames(child,result);
        }
        private void bodyVariables(JsNode node,Set<String> result) {
            if(isFunction(node)||node.is("classExpression")||node.is("classDeclaration"))return;
            if(node.is("variableStatement")&&program.significantTokens(node).get(0).text().equals("var"))for(JsNode declaration:node.children())bindingNames(declaration.child(0),result);
            for(JsNode child:node.children())bodyVariables(child,result);
        }
        private String wrap(String expression, String plan) { return plan.equals("null") ? expression : helper() + "(" + expression + "," + plan + ")"; }
        private Pattern pattern(JsNode node) {
            if (node.is("parenthesizedExpression")) { Pattern inner = pattern(node.child(0)); return new Pattern(inner.source(),inner.plan()); }
            if (!hasRest(node)) return new Pattern(render(node), "null");
            if (node.is("objectBinding") || node.is("objectLiteral")) {
                List<String> members = new ArrayList<>(), plans = new ArrayList<>();
                for (JsNode member : node.children()) {
                    if (member.is("restBinding") || member.attribute("spread").equals("true")) {
                        members.add("[" + helper() + ".key]:" + render(member.child(0))); continue;
                    }
                    JsNode key = member.child(0), target = key, defaultValue = null;
                    if (member.children().size() > 1) {
                        if (member.child(1).is("bindingInitializer") || member.attribute("shorthandDefault").equals("true")) defaultValue = member.child(1);
                        else target = member.child(1);
                    }
                    if (member.children().size() > 2) defaultValue = member.child(2);
                    if (target.is("assignmentExpression") && target.attribute("operator").equals("=")) { defaultValue = target.child(1); target = target.child(0); }
                    Pattern nested = pattern(target); plans.add(nested.plan());
                    members.add(render(key) + ":" + nested.source() + (defaultValue == null ? "" : "=" + wrap(render(defaultValue),nested.plan())));
                }
                return new Pattern("{" + String.join(",", members) + "}", "['o',[" + String.join(",", plans) + "]]");
            }
            if (node.is("arrayBinding") || node.is("arrayLiteral")) {
                arrayAdapter = true;
                List<String> elements = new ArrayList<>(), plans = new ArrayList<>(); int tail = -1; int cursor = node.range().start()+1;
                for (JsNode element : node.children()) {
                    if (element.is("bindingElision")) { elements.add(""); plans.add("null"); cursor = element.range().end(); continue; }
                    // Expression array ASTs retain holes in the original token gaps.
                    if (node.is("arrayLiteral")) {
                        long commas = program.tokens().stream().filter(t -> t.text().equals(",") && t.range().start() >= cursorValue(node, element) && t.range().end() <= element.range().start()).count();
                        int holes = (int) commas - (elements.isEmpty() ? 0 : 1);
                        for (int i=0;i<holes;i++) { elements.add(""); plans.add("null"); }
                    }
                    boolean rest = element.attribute("rest").equals("true") || element.is("spreadElement");
                    JsNode target = element.is("bindingElement") || element.is("spreadElement") ? element.child(0) : element;
                    JsNode defaultValue = element.is("bindingElement") && element.children().size()>1 ? element.child(1) : null;
                    if (target.is("assignmentExpression") && target.attribute("operator").equals("=")) { defaultValue=target.child(1); target=target.child(0); }
                    Pattern nested = pattern(target);
                    if (rest && !nested.plan().equals("null")) { tail = elements.size(); rest = false; }
                    elements.add((rest ? "..." : "") + nested.source() + (defaultValue == null ? "" : "=" + wrap(render(defaultValue),nested.plan())));
                    plans.add(nested.plan()); cursor = element.range().end();
                }
                if (!node.children().isEmpty() && node.child(node.children().size()-1).is("bindingElision")) elements.add("");
                if (node.is("arrayLiteral") && !node.children().isEmpty()) {
                    int end = node.child(node.children().size()-1).range().end();
                    long trailing = program.tokens().stream().filter(t -> t.text().equals(",") && t.range().start()>=end && t.range().end()<node.range().end()).count();
                    if(trailing>1) for(int i=0;i<trailing;i++) { elements.add(""); plans.add("null"); }
                }
                return new Pattern("[" + String.join(",",elements) + "]", "['a',[" + String.join(",",plans) + "]," + tail + "]");
            }
            return new Pattern(render(node),"null");
        }
        private int cursorValue(JsNode array, JsNode element) {
            int index = array.children().indexOf(element); return index == 0 ? array.range().start()+1 : array.child(index-1).range().end();
        }
        private String definition() {
            return "function " + helper + "(source,plan){\n" +
                (recoverSource ? "if(arguments.length===1)return " + helper + ".sources.get(source);\n" : "") +
                "if(source===null||source===void 0)throw new TypeError('Cannot destructure null or undefined');\n" +
                "if(!" + helper + ".key){" + helper + ".key=Symbol();" + (recoverSource ? helper + ".sources=new WeakMap();" : "") + "}\n" +
                "var object=Object(source),index=0,excluded=[],result;\n" +
                "function nested(value,child){return !child||value===void 0?value:" + helper + "(value,child);}\n" +
                (arrayAdapter ? "if(plan[0]==='o')" : "") + "result=new Proxy({}, {get:function(unused,key){\n" +
                "if(key===" + helper + ".key){var rest={};Reflect.ownKeys(object).forEach(function(k){if(excluded.indexOf(k)<0&&Object.prototype.propertyIsEnumerable.call(object,k))Object.defineProperty(rest,k,{value:object[k],writable:true,enumerable:true,configurable:true});});return rest;}\n" +
                "excluded.push(key);return nested(Reflect.get(object,key,source),plan[1][index++]);}});\n" +
                (arrayAdapter ? "else result={[Symbol.iterator]:function(){var iterator=source[Symbol.iterator](),next=iterator.next,finished=false;return {next:function(){var slot=index++;if(slot===plan[2]){var tail=[];while(true){var item=Reflect.apply(next,iterator,[]);if(Object(item)!==item)throw new TypeError('Iterator result is not an object');if(item.done){finished=true;break;}tail.push(item.value);}return {done:false,value:nested(tail,plan[1][slot])};}var step=Reflect.apply(next,iterator,[]);if(Object(step)!==step)throw new TypeError('Iterator result is not an object');return new Proxy({},{get:function(target,key){var value=Reflect.get(step,key,step);return key==='value'?nested(value,plan[1][slot]):value;}});},get return(){if(finished)return void 0;var close=iterator.return;return close==null?void 0:function(){return Reflect.apply(close,iterator,[]);};}};}};\n" : "") +
                (recoverSource ? helper + ".sources.set(result,source);" : "") + "return result;\n}";
        }
    }
}
