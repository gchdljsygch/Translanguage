package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.compat.Unicode17;
import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.emit.ScopedJavaScriptPrinter;
import com.pingsu.transpiler.internal.js.*;
import java.util.*;

/** DefineField semantics, with native method home objects and isolated initializer scopes. */
public final class ClassFieldsLowering implements LoweringPass {
    @Override public String lower(JsProgram program) {
        if (hasPrivate(program.root())) program = JsProgram.parse(program.sourceName(), RegularLowering.optionalChains(program));
        return new Printer(program).print();
    }
    public static boolean hasPrivate(JsNode node) {
        return node.is("privateIdentifier") || node.children().stream().anyMatch(ClassFieldsLowering::hasPrivate);
    }
    private static final class Printer extends ScopedJavaScriptPrinter {
        private String defineField, privateReference, privateInitialize, privateHas, privateName, privateAccessorReference;
        private final Set<String> accessorStores=new HashSet<>();
        private final Map<JsNode, String> privateStores = new IdentityHashMap<>();
        private final Map<JsNode, List<String>> classStores = new IdentityHashMap<>();
        private final IdentityHashMap<JsNode, String> superInitializers = new IdentityHashMap<>();
        private int fieldCount;
        private final IdentityHashMap<JsNode, String> names = new IdentityHashMap<>();
        private final IdentityHashMap<JsNode, JsNode> parents = new IdentityHashMap<>();
        Printer(JsProgram program) { super(program); index(program.root()); indexPrivate(program.root(), new HashMap<>()); }
        private void index(JsNode node) { for (JsNode child : node.children()) { parents.put(child, node); if (child.is("propertyDeclaration")) fieldCount++; index(child); } }
        @Override public String print() {
            String result = super.print();
            if (privateReference != null) result += "\nfunction " + privateReference + "(receiver, state) {"
                    + "return {get value(){if(!state.has(receiver))throw new TypeError('Invalid private receiver');return state.get(receiver).value;},"
                    + "set value(value){if(!state.has(receiver))throw new TypeError('Invalid private receiver');state.get(receiver).value=value;}};}\n";
            if(privateAccessorReference!=null)result+="\nfunction "+privateAccessorReference+"(receiver,state){return {get value(){if(!state.has(receiver))throw new TypeError('Invalid private receiver');var d=state.get(receiver).value;if(!d.get)throw new TypeError('Private accessor has no getter');return Function.prototype.call.call(d.get,receiver);},"
                    +"set value(value){if(!state.has(receiver))throw new TypeError('Invalid private receiver');var d=state.get(receiver).value;if(!d.set)throw new TypeError('Private accessor has no setter');Function.prototype.call.call(d.set,receiver,value);}};}\n";
            if (privateInitialize != null) result += "\nfunction " + privateInitialize + "(receiver,state,value){"
                    + "if(state.has(receiver))throw new TypeError('Private field already initialized');state.set(receiver,{value:value});}\n";
            if (privateHas != null) result += "\nfunction " + privateHas + "(state,receiver){"
                    + "if(receiver===null||(typeof receiver!=='object'&&typeof receiver!=='function'))throw new TypeError('Private in requires an object');"
                    + "return state.has(receiver); }\n";
            if (privateName != null) result += "\nfunction " + privateName + "(value,name){Object.defineProperty(value,'name',{value:name,configurable:true});return value;}\n";
            if (defineField == null) return result;
            return result + "\nfunction " + defineField + "(receiver, key, value) {\n"
                    + "Object.defineProperty(receiver, key, {value: value, writable: true, enumerable: true, configurable: true});\n}\n";
        }
        private void indexPrivate(JsNode node, Map<String, String> environment) {
            if (node.is("classDeclaration") || node.is("classExpression")) {
                Map<String, String> inner = new HashMap<>(environment);
                List<String> stores = new ArrayList<>();Map<String,String> declared=new HashMap<>();
                for (JsNode member : node.children()) {
                    if (member.children().isEmpty() || !member.child(0).is("privateIdentifier")) continue;
                    if (!member.is("propertyDeclaration")&&!privateAccessor(member)) program.fail(member, "Private methods require PRESERVE; explicit targets lower private fields and accessors");
                    String name=member.child(0).attribute("name"),store=declared.get(name);
                    if(store==null){store=scopes.fresh("private");stores.add(store);declared.put(name,store);}
                    if(privateAccessor(member))accessorStores.add(store);
                    inner.put(name, store);
                }
                classStores.put(node, stores);
                for (JsNode child : node.children()) {
                    boolean member = child.is("propertyDeclaration") || child.is("methodDeclaration")
                            || child.is("constructorDeclaration") || child.is("staticBlock");
                    indexPrivate(child, member ? inner : environment);
                }
                return;
            }
            if (node.is("privateIdentifier")) privateStores.put(node, environment.get(node.attribute("name")));
            for (JsNode child : node.children()) indexPrivate(child, environment);
        }
        private boolean privateMember(JsNode node) {
            return node.is("memberExpression") && node.child(1).is("privateIdentifier");
        }
        private boolean privateAccessor(JsNode node){return node.is("methodDeclaration")&&!node.children().isEmpty()&&node.child(0).is("privateIdentifier")&&!node.attribute("accessor").isEmpty();}
        private String privateRead(JsNode member, String receiver) {
            if(accessorStores.contains(privateStores.get(member.child(1)))){
                if(privateAccessorReference==null)privateAccessorReference=scopes.fresh("privateAccessor");
                return privateAccessorReference+"("+receiver+","+privateStores.get(member.child(1))+").value";
            }
            if (privateReference == null) privateReference = scopes.fresh("privateReference");
            return privateReference + "(" + receiver + "," + privateStores.get(member.child(1)) + ").value";
        }
        private String privateInit(JsNode key, String value) {
            if (privateInitialize == null) privateInitialize = scopes.fresh("privateInitialize");
            return privateInitialize + "(this," + privateStores.get(key) + "," + value + ");\n";
        }
        private String define(String receiver, String key, String value) {
            if (defineField == null) defineField = scopes.fresh("defineField");
            return defineField + "(" + receiver + "," + key + "," + value + ");\n";
        }
        private boolean fields(JsNode node) { return node.children().stream().anyMatch(n -> n.is("propertyDeclaration") || n.is("staticBlock") || privateAccessor(n)); }
        @Override protected String render(JsNode node) {
            if (node.is("binaryExpression") && node.child(0).is("privateIdentifier") && node.attribute("operator").equals("in")) {
                if (privateHas == null) privateHas = scopes.fresh("privateHas");
                return privateHas + "(" + privateStores.get(node.child(0)) + "," + render(node.child(1)) + ")";
            }
            if (node.is("argumentsExpression") || node.is("taggedTemplateExpression")) {
                JsNode callee = unparenthesized(node.child(0));
                if (privateMember(callee)) {
                    String receiver = temporary(), read = privateRead(callee, receiver);
                    String prefix = "(" + receiver + "=" + render(callee.child(0)) + ",";
                    if (node.is("taggedTemplateExpression"))
                        return prefix + "Function.prototype.bind.call(" + read + "," + receiver + "))" + render(node.child(1));
                    String arguments = render(node.child(1));
                    return prefix + "Function.prototype.call.call(" + read + "," + receiver
                            + (arguments.length() > 2 ? "," + arguments.substring(1, arguments.length() - 1) : "") + "))";
                }
            }
            if (node.is("newExpression") && privateMember(unparenthesized(node.child(0))))
                return "new (" + render(node.child(0)) + ")" + (node.children().size() > 1 ? render(node.child(1)) : "()");
            if (privateMember(node)) return privateRead(node, render(node.child(0)));
            if (superInitializers.containsKey(node)) {
                return "(" + super.render(node) + "," + superInitializers.get(node) + "this)";
            }
            if (node.is("objectMember") && node.children().size()==2 && node.child(0).is("computedName")) {
                JsNode value=unparenthesized(node.child(1));
                if(value.is("classExpression")&&fields(value)&&!simpleFields(value)&&className(value).isEmpty()) {
                    String key=temporary();names.put(value,nameOfKey(key));
                    return "[("+key+"=Reflect.ownKeys({["+render(node.child(0).child(0))+"]:0})[0])]:"+render(node.child(1));
                }
            }
            if (node.is("exportDeclaration") && !node.children().isEmpty()) {
                JsNode owner = node.child(0);
                if ((owner.is("classExpression") || owner.is("classDeclaration")) && fields(owner) && !simpleFields(owner)) {
                    String name = className(owner);
                    if (Compat.stripLeading(text(node)).startsWith("export default")) {
                        if (!name.isEmpty()) return "let " + name + "=" + lowerClass(owner, quote(ScopeAnalysis.decode(name))) + "; export {" + name + " as default};";
                        return "export default " + lowerClass(owner, quote("default")) + ";";
                    }
                }
            }
            if ((node.is("classDeclaration") || node.is("classExpression")) && fields(node)) {
                if (simpleFields(node)) return lowerSimpleClass(node);
                String name = className(node);
                String result = lowerClass(node, name.isEmpty() ? inferredName(node) : quote(ScopeAnalysis.decode(name)));
                return node.is("classDeclaration") ? "let " + name + "=" + result + ";" : result;
            }
            return super.render(node);
        }
        private JsNode unparenthesized(JsNode node) { while(node.is("parenthesizedExpression"))node=node.child(0);return node; }
        private String nameOfKey(String key) { return "({["+key+"]:function(){}})["+key+"].name"; }
        private String className(JsNode node) {
            java.util.List<com.pingsu.transpiler.syntax.Token> tokens=program.significantTokens(node);return tokens.size()>1&&!Compat.set("extends","{").contains(tokens.get(1).text())?tokens.get(1).text():"";
        }
        private String inferredName(JsNode node) {
            if(names.containsKey(node))return names.get(node);
            JsNode parent=parents.get(node);
            while(parent!=null&&parent.is("parenthesizedExpression")){node=parent;parent=parents.get(parent);}
            if(parent==null)return quote("");
            if(parent.is("variableDeclaration")&&parent.children().size()==2&&parent.child(0).is("identifier"))return quote(ScopeAnalysis.decode(text(parent.child(0))));
            if(parent.is("assignmentExpression")&&parent.attribute("operator").equals("=")&&parent.child(0).is("identifier"))return quote(ScopeAnalysis.decode(text(parent.child(0))));
            if(parent.is("objectMember")&&parent.children().size()==2&&parent.child(0).is("propertyName")) {
                String key=text(parent.child(0));return key.startsWith("'")||key.startsWith("\"")||Unicode17.isDigit(key.charAt(0))?nameOfKey(key):quote(ScopeAnalysis.decode(key));
            }
            if(parent.is("bindingInitializer")||parent.is("parameterInitializer")) {
                JsNode binding=parents.get(parent);
                if(binding!=null) {
                    JsNode target=binding.child(0);
                    if(binding.is("bindingProperty")&&binding.children().size()>2)target=binding.child(1);
                    if(target.is("identifier")||target.is("propertyName"))return quote(ScopeAnalysis.decode(text(target)));
                }
            }
            if(parent.is("exportDeclaration")&&Compat.stripLeading(text(parent)).startsWith("export default"))return quote("default");
            return quote("");
        }
        // Existing constructors may shadow initializer bindings; generated constructors do
        // not. Neither path may change observations of arguments or new.target.
        // All other initializers retain their separate native method home object below.
        private boolean simpleFields(JsNode owner) {
            boolean defaultConstructor = owner.children().stream().noneMatch(n -> n.is("constructorDeclaration"));
            for (JsNode member : owner.children()) {
                if (member.is("staticBlock") || privateAccessor(member)) return false;
                if (!member.is("propertyDeclaration")) continue;
                if (member.attribute("static").equals("true") || member.child(0).is("computedName") || member.child(0).is("privateIdentifier")) return false;
                if (member.children().size() > 1 && !(defaultConstructor
                        ? defaultConstructorValue(member.child(1)) : closedValue(member.child(1)))) return false;
            }
            return true;
        }
        private boolean defaultConstructorValue(JsNode node) {
            // The generated constructor has no user bindings. Its only new observable
            // environments are arguments and new.target (including captures by arrows).
            if (node.is("newTargetExpression") || node.is("identifier") && ScopeAnalysis.decode(text(node)).equals("arguments")) return false;
            return node.children().stream().allMatch(this::defaultConstructorValue);
        }
        private boolean closedValue(JsNode node) {
            switch (node.kind()) {
                case "numericLiteral":case "bigintLiteral":case "stringLiteral":case "literal":case "regularExpression": return true;
                case "parenthesizedExpression":case "unaryExpression":case "binaryExpression":case "conditionalExpression": return node.children().stream().allMatch(this::closedValue);
                default: return false;
            }
        }
        private String propertyKey(JsNode key) {
            String property = text(key);
            return property.startsWith("'") || property.startsWith("\"") || Unicode17.isDigit(property.charAt(0))
                    ? property : quote(ScopeAnalysis.decode(property));
        }
        private String lowerSimpleClass(JsNode owner) {
            if (scopes.dynamic()) program.fail(owner, "Class field lowering with direct eval or with cannot preserve lexical visibility");
            Map<JsNode, String> members = new IdentityHashMap<>();
            StringBuilder initializers = new StringBuilder();
            List<String> expressions = new ArrayList<>();
            JsNode constructor = null;
            for (JsNode member : owner.children()) {
                if (member.is("constructorDeclaration")) constructor = member;
                if (!member.is("propertyDeclaration")) continue;
                String property = propertyKey(member.child(0));
                String value = "void 0";
                if (member.children().size() > 1) {
                    JsNode initializer = member.child(1), valueNode = unparenthesized(initializer);
                    if (valueNode.is("classExpression") && className(valueNode).isEmpty()) names.put(valueNode, nameOfKey(property));
                    value = render(initializer);
                    if (anonymous(valueNode)) value = "({[" + property + "]:" + value + "})[" + property + "]";
                }
                String definition = define("this", property, value);
                initializers.append(definition);
                expressions.add(definition.substring(0, definition.length() - 2));
                members.put(member, "");
            }
            if (constructor != null) {
                JsNode body = constructor.children().stream().filter(n -> n.is("functionBody")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
                members.put(constructor, initializeConstructor(owner, constructor, body, initializers.toString(), String.join(",", expressions) + ","));
            }
            String result = children(owner, n -> members.containsKey(n) ? members.get(n) : render(n));
            if (constructor == null) {
                String signature = "constructor(){";
                if (owner.attribute("derived").equals("true")) {
                    String args = scopes.fresh("args");
                    signature = "constructor(..." + args + "){super(..." + args + ");";
                }
                int end = result.lastIndexOf('}');
                result = result.substring(0, end) + signature + initializers + "}}";
            }
            return result;
        }
        private String initializeConstructor(JsNode owner, JsNode constructor, JsNode body, String initialization, String expressions) {
            if (owner.attribute("derived").equals("true")) {
                List<JsNode> calls = new ArrayList<>();
                for (JsNode part : constructor.children()) calls.addAll(SuperCallSites.collect(part, JsNode::kind, JsNode::children));
                JsNode direct = body.children().stream().filter(n -> n.is("expressionStatement")
                        && n.child(0).is("argumentsExpression") && n.child(0).child(0).is("superExpression")).findFirst().orElse(null);
                if (calls.size() == 1 && direct != null) {
                    String replacement = spliceBody(body, direct.range().end(), ";" + initialization);
                    return children(constructor, n -> n == body ? replacement : render(n));
                }
                for (JsNode call : calls) superInitializers.put(call, expressions);
                try { return children(constructor, this::render); }
                finally { for (JsNode call : calls) superInitializers.remove(call); }
            }
            int insertion = body.range().start() + 1;
            for (JsNode statement : body.children()) {
                if (statement.is("expressionStatement") && statement.child(0).is("stringLiteral")) insertion = statement.range().end();
                else break;
            }
            String replacement = spliceBody(body, insertion, ";" + initialization);
            return children(constructor, n -> n == body ? replacement : render(n));
        }
        private String lowerClass(JsNode owner, String effectiveName) {
            if (scopes.dynamic()) program.fail(owner, "Class field lowering with direct eval or with cannot preserve lexical visibility");
            String ref = scopes.fresh("class"), marker = scopes.fresh("key"), instanceKey = scopes.fresh("instance"), staticKey = scopes.fresh("static");
            String init = scopes.fresh("initialize"), staticInit = scopes.fresh("initializeStatic");
            List<String> keys = new ArrayList<>();
            StringBuilder instance = new StringBuilder(), statics = new StringBuilder();
            Map<JsNode, String> members = new IdentityHashMap<>();
            List<String> accessorDeclarations=new ArrayList<>();Map<String,String> accessorKeys=new HashMap<>();
            StringBuilder extractAccessors=new StringBuilder(),instanceBrands=new StringBuilder(),staticBrands=new StringBuilder();
            for(JsNode member:owner.children())if(privateAccessor(member)){
                JsNode key=member.child(0);String store=privateStores.get(key),symbol=accessorKeys.get(store);
                if(symbol==null){
                    symbol=scopes.fresh("accessorKey");String descriptor=scopes.fresh("accessorDescriptor");accessorKeys.put(store,symbol);
                    accessorDeclarations.add(symbol+"=Symbol()");accessorDeclarations.add(descriptor);
                    String target=member.attribute("static").equals("true")?ref:ref+".prototype";
                    extractAccessors.append(descriptor).append("=Object.getOwnPropertyDescriptor(").append(target).append(',').append(symbol).append(");delete ").append(target).append('[').append(symbol).append("];\n");
                    (member.attribute("static").equals("true")?staticBrands:instanceBrands).append(privateInit(key,descriptor));
                }
                final String computed="["+symbol+"]";members.put(member,children(member,c->c==key?computed:render(c)));
            }
            JsNode constructor = null;
            for (JsNode member : owner.children()) {
                if (member.is("constructorDeclaration")) constructor = member;
                if (member.is("staticBlock")) { statics.append(render(member.child(0))); members.put(member, ""); }
                if (!member.is("propertyDeclaration")) continue;
                JsNode key = member.child(0);
                String property;
                if (key.is("privateIdentifier")) {
                    String value = member.children().size() > 1 ? render(member.child(1)) : "void 0";
                    if (member.children().size() > 1 && anonymous(unparenthesized(member.child(1)))) {
                        if (privateName == null) privateName = scopes.fresh("privateName");
                        value = privateName + "(" + value + "," + quote("#" + key.attribute("name")) + ")";
                    }
                    (member.attribute("static").equals("true") ? statics : instance).append(privateInit(key, value));
                    members.put(member, ""); continue;
                }
                if (key.is("computedName")) {
                    property = scopes.fresh("property"); keys.add(property);
                    // A computed method occupies the exact original key evaluation position. The
                    // marker is deleted before any initializer can inspect the class or prototype.
                    members.put(member, "static [(" + property + "=Reflect.ownKeys({[" + render(key.child(0)) + "]:0})[0]," + marker + ")](){}\n");
                } else {
                    property = text(key);
                    if (!property.startsWith("'") && !property.startsWith("\"") && !Unicode17.isDigit(property.charAt(0))) property = quote(ScopeAnalysis.decode(property));
                    members.put(member, "");
                }
                if(member.children().size()>1) {
                    JsNode valueNode=unparenthesized(member.child(1));
                    if(valueNode.is("classExpression")&&className(valueNode).isEmpty())names.put(valueNode,nameOfKey(property));
                }
                String value = member.children().size() > 1 ? render(member.child(1)) : "void 0";
                // Native anonymous function/class name inference is retained via an object property.
                if (member.children().size() > 1 && anonymous(member.child(1))) value = "({[" + property + "]:" + value + "})[" + property + "]";
                (member.attribute("static").equals("true") ? statics : instance).append(fieldCount > 1
                        ? define("this", property, value)
                        : "Object.defineProperty(this," + property + ",{value:" + value + ",writable:true,enumerable:true,configurable:true});\n");
            }
            instance.insert(0,instanceBrands);statics.insert(0,staticBrands);
            if (instance.length() > 0 && constructor != null) {
                JsNode body = constructor.children().stream().filter(n -> n.is("functionBody")).findFirst().orElseThrow(java.util.NoSuchElementException::new);
                members.put(constructor, initializeConstructor(owner, constructor, body, init + ".call(this);\n", init + ".call(this),"));
            }
            String classText = children(owner, n -> members.containsKey(n) ? members.get(n) : render(n));
            StringBuilder methods = new StringBuilder();
            if (instance.length() > 0) {
                if (constructor == null) methods.append(owner.attribute("derived").equals("true")
                        ? "constructor(...args){super(...args);" + init + ".call(this);}" : "constructor(){" + init + ".call(this);}");
                methods.append('[').append(instanceKey).append("](){").append(instance).append("}\n");
            }
            if (statics.length() > 0) methods.append("static [").append(staticKey).append("](){").append(statics).append("}\n");
            classText = classText.substring(0, classText.lastIndexOf('}')) + methods + "}";
            List<String> declarations = new ArrayList<>();
            for (String store : classStores.get(owner)) declarations.add(store + "=new WeakMap()");
            if (!keys.isEmpty()) declarations.add(marker + "=Symbol()");
            if (instance.length() > 0) { declarations.add(instanceKey + "=Symbol()"); declarations.add(init); }
            if (statics.length() > 0) { declarations.add(staticKey + "=Symbol()"); declarations.add(staticInit); }
            declarations.addAll(keys);declarations.addAll(accessorDeclarations);
            StringBuilder out = new StringBuilder("(()=>{\nlet ").append(String.join(",", declarations));
            out.append(";\nlet ").append(ref).append('=').append(classText).append(";\n");
            out.append(extractAccessors);
            if (!keys.isEmpty()) out.append("delete ").append(ref).append('[').append(marker).append("];\n");
            if (instance.length() > 0) out.append(init).append('=').append(ref).append(".prototype[").append(instanceKey).append("];delete ").append(ref).append(".prototype[").append(instanceKey).append("];\n");
            if (statics.length() > 0) out.append(staticInit).append('=').append(ref).append('[').append(staticKey).append("];delete ").append(ref).append('[').append(staticKey).append("];\n");
            // Methods/accessors (including computed names) override the native name descriptor.
            if (className(owner).isEmpty()) out.append("if(typeof Object.getOwnPropertyDescriptor(").append(ref).append(",\"name\").value===\"string\")Object.defineProperty(").append(ref).append(",\"name\",{value:").append(effectiveName).append(",configurable:true});\n");
            if (statics.length() > 0) out.append(staticInit).append(".call(").append(ref).append(");\n");
            return out.append("return ").append(ref).append(";})()").toString();
        }
        private boolean anonymous(JsNode node) {
            return node.is("arrowFunction") || node.is("classExpression") && className(node).isEmpty()
                    || node.is("functionExpression") && node.children().stream().noneMatch(n -> n.is("identifier"));
        }
        private String spliceBody(JsNode body, int position, String insertion) {
            StringBuilder result = new StringBuilder(); int cursor = body.range().start(); boolean inserted = false;
            for (JsNode child : body.children()) {
                if (!inserted && position <= child.range().start()) {
                    result.append(program.source(), cursor, position).append(insertion); cursor = position; inserted = true;
                }
                result.append(program.source(), cursor, child.range().start()).append(render(child)); cursor = child.range().end();
            }
            if (!inserted) { result.append(program.source(), cursor, position).append(insertion); cursor = position; }
            return result.append(program.source(), cursor, body.range().end()).toString();
        }
    }
}
