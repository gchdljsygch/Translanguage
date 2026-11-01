package com.pingsu.transpiler.internal.emit;

import com.pingsu.transpiler.internal.js.*;
import java.util.*;

/** Owns temporary declaration placement without changing directive or parameter environments. */
public class ScopedJavaScriptPrinter extends JavaScriptPrinter {
    protected final ScopeAnalysis scopes;
    private List<String> temporaries = new ArrayList<>();
    public ScopedJavaScriptPrinter(JsProgram program) { super(program); scopes = new ScopeAnalysis(program); }
    protected String temporary() {
        if(scopes.dynamic()) program.fail(program.root(),"Lowering with direct eval or with cannot preserve temporary visibility");
        String name=scopes.fresh("value");temporaries.add(name);return name;
    }
    @Override protected String render(JsNode node) {
        if(node.is("program")||node.is("functionBody")) return body(node);
        if(node.is("arrowFunction")&&!node.child(node.children().size()-1).is("functionBody")) {
            JsNode expression=node.child(node.children().size()-1);List<String> previous=temporaries;temporaries=new ArrayList<>();
            String value=render(expression),declarations=declarations();temporaries=previous;
            return children(node,n->n==expression?declarations.isEmpty()?value:"{"+declarations+"return "+value+";}":render(n));
        }
        if(node.is("parameterInitializer")) {
            List<String> previous=temporaries;temporaries=new ArrayList<>();String value=super.render(node),declarations=declarations();temporaries=previous;
            return declarations.isEmpty()?value:"(()=>{"+declarations+"return "+value+";})()";
        }
        return super.render(node);
    }
    private String declarations() { return temporaries.isEmpty()?"":"var "+String.join(",",temporaries)+";\n"; }
    private String body(JsNode node) {
        List<String> previous=temporaries;temporaries=new ArrayList<>();Map<JsNode,String> rendered=new IdentityHashMap<>();
        for(JsNode child:node.children())rendered.put(child,render(child));String declarations=declarations();temporaries=previous;
        if(declarations.isEmpty())return children(node,rendered::get);
        int insertion=node.is("program")?0:node.range().start()+1;
        if(node.is("program")&&program.source().startsWith("#!")){int line=program.source().indexOf('\n');insertion=line<0?program.source().length():line+1;}
        for(JsNode child:node.children()) { if(child.is("expressionStatement")&&child.child(0).is("stringLiteral"))insertion=child.range().end();else break; }
        StringBuilder result=new StringBuilder();int cursor=node.range().start();boolean added=false;
        for(JsNode child:node.children()) {
            if(!added&&insertion<=child.range().start()){result.append(program.source(),cursor,insertion).append(';').append(declarations);cursor=insertion;added=true;}
            result.append(program.source(),cursor,child.range().start()).append(rendered.get(child));cursor=child.range().end();
        }
        if(!added){result.append(program.source(),cursor,insertion).append(';').append(declarations);cursor=insertion;}
        return result.append(program.source(),cursor,node.range().end()).toString();
    }
}
