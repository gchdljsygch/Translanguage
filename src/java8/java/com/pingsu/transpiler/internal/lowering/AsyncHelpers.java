package com.pingsu.transpiler.internal.lowering;

import com.pingsu.transpiler.internal.js.ScopeAnalysis;
import java.util.*;

/** Project-authored, ES2015-only helpers. Dependencies are emitted once in deterministic order. */
final class AsyncHelpers {
    enum Kind { CHECK, KEY, ITERATOR, RUN, GENERATOR }
    private final ScopeAnalysis scopes;
    private final Map<Kind,String> names=new EnumMap<>(Kind.class);
    AsyncHelpers(ScopeAnalysis scopes){this.scopes=scopes;}
    String use(Kind kind){
        if(kind==Kind.ITERATOR){use(Kind.CHECK);use(Kind.KEY);}
        if(kind==Kind.GENERATOR){use(Kind.CHECK);use(Kind.ITERATOR);}
        return names.computeIfAbsent(kind,k->scopes.fresh(k.name().toLowerCase(Locale.ROOT)));
    }
    String definitions(){
        StringBuilder out=new StringBuilder();
        for(Kind kind:Kind.values())if(names.containsKey(kind)){
            String definition; switch(kind){case CHECK: definition = CHECK; break;case KEY: definition = KEY; break;case ITERATOR: definition = ITERATOR; break;case RUN: definition = RUN; break;case GENERATOR: definition = GENERATOR; break;default: throw new AssertionError("Unknown enum value");}
            for(java.util.Map.Entry<com.pingsu.transpiler.internal.lowering.AsyncHelpers.Kind,java.lang.String> entry:names.entrySet())definition=definition.replace("@"+entry.getKey()+"@",entry.getValue());
            out.append('\n').append(definition);
        }
        return out.toString();
    }
    private static final String CHECK="function @CHECK@(value){\n" +
            "  if(Object(value)!==value)throw new TypeError('Iterator result is not an object');\n" +
            "  return value;\n" +
            "}\n" +
            "";
    private static final String KEY="function @KEY@(){return Symbol.asyncIterator||(@KEY@.value||(@KEY@.value=Symbol('asyncIterator')));}\n" +
            "";
    private static final String ITERATOR="function @ITERATOR@(value){\n" +
            "  var method=value[@KEY@()],sync=method==null;\n" +
            "  if(sync)method=value[Symbol.iterator];\n" +
            "  var iterator=@CHECK@(Reflect.apply(method,value,[])),next=iterator.next;\n" +
            "  function call(kind,args){\n" +
            "    function invoke(){\n" +
            "      var fn=kind==='next'?next:iterator[kind];\n" +
            "      if(fn==null&&kind!=='next'){\n" +
            "        if(!sync)return {missing:true};\n" +
            "        if(kind==='return')return {result:Promise.resolve({value:args[0],done:true})};\n" +
            "        var close=iterator.return;\n" +
            "        if(close!=null)@CHECK@(Reflect.apply(close,iterator,[]));\n" +
            "        throw new TypeError('Delegate has no throw');\n" +
            "      }\n" +
            "      var result=Reflect.apply(fn,iterator,args);\n" +
            "      if(!sync)return {result:result};\n" +
            "      @CHECK@(result);\n" +
            "      var done=!!result.done,item=result.value;\n" +
            "      return {result:Promise.resolve(item).then(function(v){return {value:v,done:done};},function(error){\n" +
            "        if(!done&&kind!=='return'){try{var close=iterator.return;if(close!=null)@CHECK@(Reflect.apply(close,iterator,[]));}catch(ignored){}}\n" +
            "        throw error;\n" +
            "      })};\n" +
            "    }\n" +
            "    if(!sync)return invoke();\n" +
            "    try{return invoke();}catch(error){return {result:Promise.reject(error)};}\n" +
            "  }\n" +
            "  return {iterator:iterator,sync:sync,call:call,\n" +
            "    next:function(){var result=call('next',[]);if(result.missing)throw new TypeError('Iterator has no next');return result.result;},\n" +
            "    close:function(){var result=call('return',[]);return result.missing?(sync?[Promise.resolve({value:void 0,done:true})]:void 0):[result.result];}\n" +
            "  };\n" +
            "}\n" +
            "";
    private static final String RUN="function @RUN@(factory){\n" +
            "  return new Promise(function(resolve,reject){\n" +
            "    var iterator;\n" +
            "    function resume(kind,value){\n" +
            "      var step;\n" +
            "      try{step=iterator[kind](value);}catch(error){reject(error);return;}\n" +
            "      if(step.done){resolve(step.value);return;}\n" +
            "      Promise.resolve(step.value).then(function(value){resume('next',value);},function(error){resume('throw',error);});\n" +
            "    }\n" +
            "    try{iterator=factory();}catch(error){reject(error);return;}\n" +
            "    resume('next');\n" +
            "  });\n" +
            "}\n" +
            "";
    // Tokens are private to the continuation: 0 awaits, 1 publishes a resolved yield, 2 delegates.
    // Only published yields release the head request. Awaiting always retains queue ownership.
    private static final String GENERATOR="function @GENERATOR@(iterator){\n" +
            "  var queue=[],busy=false,draining=false,started=false,completed=false,delegate;\n" +
            "  function finish(ok,value,done){\n" +
            "    var request=queue.shift();\n" +
            "    if(ok)request.resolve({value:value,done:done});else request.reject(value);\n" +
            "    busy=false;drain();\n" +
            "  }\n" +
            "  function fail(error){completed=true;delegate=void 0;finish(false,error);}\n" +
            "  function resume(kind,value){\n" +
            "    var step;\n" +
            "    try{started=true;step=iterator[kind](value);}catch(error){fail(error);return;}\n" +
            "    if(step.done){completed=true;finish(true,step.value,true);return;}\n" +
            "    var token=step.value;\n" +
            "    if(token[0]===0){Promise.resolve(token[1]).then(function(v){resume('next',v);},function(e){resume('throw',e);});return;}\n" +
            "    if(token[0]===1){finish(true,token[1],false);return;}\n" +
            "    try{delegate=@ITERATOR@(token[1]);}catch(error){resume('throw',error);return;}\n" +
            "    delegated('next',void 0);\n" +
            "  }\n" +
            "  function delegated(kind,value){\n" +
            "    var call;\n" +
            "    try{call=delegate.call(kind,[value]);}catch(error){delegate=void 0;resume('throw',error);return;}\n" +
            "    if(call.missing){\n" +
            "      if(kind==='return'){delegate=void 0;resume('return',value);return;}\n" +
            "      if(kind==='throw'){\n" +
            "        var closing;\n" +
            "        try{closing=delegate.close();}catch(error){delegate=void 0;resume('throw',error);return;}\n" +
            "        delegate=void 0;\n" +
            "        if(closing!==void 0){Promise.resolve(closing[0]).then(function(result){try{@CHECK@(result);}catch(error){resume('throw',error);return;}resume('throw',new TypeError('Delegate has no throw'));},function(error){resume('throw',error);});return;}\n" +
            "      }\n" +
            "      delegate=void 0;resume('throw',new TypeError('Delegate has no method'));return;\n" +
            "    }\n" +
            "    Promise.resolve(call.result).then(function(result){\n" +
            "      var done,item;\n" +
            "      try{@CHECK@(result);done=!!result.done;item=result.value;}catch(error){delegate=void 0;resume('throw',error);return;}\n" +
            "      if(done){\n" +
            "        delegate=void 0;\n" +
            "        if(kind==='return')resume('return',item);\n" +
            "        else resume('next',item);\n" +
            "      }else finish(true,item,false);\n" +
            "    },function(error){delegate=void 0;resume('throw',error);});\n" +
            "  }\n" +
            "  function dispatch(kind,value){if(delegate)delegated(kind,value);else resume(kind,value);}\n" +
            "  function drain(){\n" +
            "    if(busy||draining)return;\n" +
            "    draining=true;\n" +
            "    try{while(!busy&&queue.length)start();}finally{draining=false;}\n" +
            "  }\n" +
            "  function start(){\n" +
            "    busy=true;var request=queue[0],kind=request.kind,value=request.value;\n" +
            "    if(!started&&kind!=='next')completed=true;\n" +
            "    if(completed){\n" +
            "      if(kind==='throw'){finish(false,value);return;}\n" +
            "      if(kind==='return'){Promise.resolve(value).then(function(v){finish(true,v,true);},function(e){finish(false,e);});return;}\n" +
            "      finish(true,void 0,true);return;\n" +
            "    }\n" +
            "    if(kind==='return')Promise.resolve(value).then(function(v){dispatch('return',v);},function(e){dispatch('throw',e);});\n" +
            "    else dispatch(kind,value);\n" +
            "  }\n" +
            "  function enqueue(receiver,kind,value){if(receiver!==result)return Promise.reject(new TypeError('Incompatible async iterator receiver'));return new Promise(function(resolve,reject){queue.push({kind:kind,value:value,resolve:resolve,reject:reject});drain();});}\n" +
            "  var result={next:function(value){return enqueue(this,'next',value);},throw:function(value){return enqueue(this,'throw',value);},return:function(value){return enqueue(this,'return',value);}};\n" +
            "  Object.defineProperty(result,@KEY@(),{value:function(){return this;},configurable:true,writable:true});\n" +
            "  return result;\n" +
            "}\n" +
            "";
}
