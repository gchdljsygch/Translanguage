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
            String definition=switch(kind){case CHECK->CHECK;case KEY->KEY;case ITERATOR->ITERATOR;case RUN->RUN;case GENERATOR->GENERATOR;};
            for(var entry:names.entrySet())definition=definition.replace("@"+entry.getKey()+"@",entry.getValue());
            out.append('\n').append(definition);
        }
        return out.toString();
    }
    private static final String CHECK="""
        function @CHECK@(value){
          if(Object(value)!==value)throw new TypeError('Iterator result is not an object');
          return value;
        }
        """;
    private static final String KEY="""
        function @KEY@(){return Symbol.asyncIterator||(@KEY@.value||(@KEY@.value=Symbol('asyncIterator')));}
        """;
    private static final String ITERATOR="""
        function @ITERATOR@(value){
          var method=value[@KEY@()],sync=method==null;
          if(sync)method=value[Symbol.iterator];
          var iterator=@CHECK@(Reflect.apply(method,value,[])),next=iterator.next;
          function call(kind,args){
            function invoke(){
              var fn=kind==='next'?next:iterator[kind];
              if(fn==null&&kind!=='next'){
                if(!sync)return {missing:true};
                if(kind==='return')return {result:Promise.resolve({value:args[0],done:true})};
                var close=iterator.return;
                if(close!=null)@CHECK@(Reflect.apply(close,iterator,[]));
                throw new TypeError('Delegate has no throw');
              }
              var result=Reflect.apply(fn,iterator,args);
              if(!sync)return {result:result};
              @CHECK@(result);
              var done=!!result.done,item=result.value;
              return {result:Promise.resolve(item).then(function(v){return {value:v,done:done};},function(error){
                if(!done&&kind!=='return'){try{var close=iterator.return;if(close!=null)@CHECK@(Reflect.apply(close,iterator,[]));}catch(ignored){}}
                throw error;
              })};
            }
            if(!sync)return invoke();
            try{return invoke();}catch(error){return {result:Promise.reject(error)};}
          }
          return {iterator:iterator,sync:sync,call:call,
            next:function(){var result=call('next',[]);if(result.missing)throw new TypeError('Iterator has no next');return result.result;},
            close:function(){var result=call('return',[]);return result.missing?(sync?[Promise.resolve({value:void 0,done:true})]:void 0):[result.result];}
          };
        }
        """;
    private static final String RUN="""
        function @RUN@(factory){
          return new Promise(function(resolve,reject){
            var iterator;
            function resume(kind,value){
              var step;
              try{step=iterator[kind](value);}catch(error){reject(error);return;}
              if(step.done){resolve(step.value);return;}
              Promise.resolve(step.value).then(function(value){resume('next',value);},function(error){resume('throw',error);});
            }
            try{iterator=factory();}catch(error){reject(error);return;}
            resume('next');
          });
        }
        """;
    // Tokens are private to the continuation: 0 awaits, 1 publishes a resolved yield, 2 delegates.
    // Only published yields release the head request. Awaiting always retains queue ownership.
    private static final String GENERATOR="""
        function @GENERATOR@(iterator){
          var queue=[],busy=false,draining=false,started=false,completed=false,delegate;
          function finish(ok,value,done){
            var request=queue.shift();
            if(ok)request.resolve({value:value,done:done});else request.reject(value);
            busy=false;drain();
          }
          function fail(error){completed=true;delegate=void 0;finish(false,error);}
          function resume(kind,value){
            var step;
            try{started=true;step=iterator[kind](value);}catch(error){fail(error);return;}
            if(step.done){completed=true;finish(true,step.value,true);return;}
            var token=step.value;
            if(token[0]===0){Promise.resolve(token[1]).then(function(v){resume('next',v);},function(e){resume('throw',e);});return;}
            if(token[0]===1){finish(true,token[1],false);return;}
            try{delegate=@ITERATOR@(token[1]);}catch(error){resume('throw',error);return;}
            delegated('next',void 0);
          }
          function delegated(kind,value){
            var call;
            try{call=delegate.call(kind,[value]);}catch(error){delegate=void 0;resume('throw',error);return;}
            if(call.missing){
              if(kind==='return'){delegate=void 0;resume('return',value);return;}
              if(kind==='throw'){
                var closing;
                try{closing=delegate.close();}catch(error){delegate=void 0;resume('throw',error);return;}
                delegate=void 0;
                if(closing!==void 0){Promise.resolve(closing[0]).then(function(result){try{@CHECK@(result);}catch(error){resume('throw',error);return;}resume('throw',new TypeError('Delegate has no throw'));},function(error){resume('throw',error);});return;}
              }
              delegate=void 0;resume('throw',new TypeError('Delegate has no method'));return;
            }
            Promise.resolve(call.result).then(function(result){
              var done,item;
              try{@CHECK@(result);done=!!result.done;item=result.value;}catch(error){delegate=void 0;resume('throw',error);return;}
              if(done){
                delegate=void 0;
                if(kind==='return')resume('return',item);
                else resume('next',item);
              }else finish(true,item,false);
            },function(error){delegate=void 0;resume('throw',error);});
          }
          function dispatch(kind,value){if(delegate)delegated(kind,value);else resume(kind,value);}
          function drain(){
            if(busy||draining)return;
            draining=true;
            try{while(!busy&&queue.length)start();}finally{draining=false;}
          }
          function start(){
            busy=true;var request=queue[0],kind=request.kind,value=request.value;
            if(!started&&kind!=='next')completed=true;
            if(completed){
              if(kind==='throw'){finish(false,value);return;}
              if(kind==='return'){Promise.resolve(value).then(function(v){finish(true,v,true);},function(e){finish(false,e);});return;}
              finish(true,void 0,true);return;
            }
            if(kind==='return')Promise.resolve(value).then(function(v){dispatch('return',v);},function(e){dispatch('throw',e);});
            else dispatch(kind,value);
          }
          function enqueue(receiver,kind,value){if(receiver!==result)return Promise.reject(new TypeError('Incompatible async iterator receiver'));return new Promise(function(resolve,reject){queue.push({kind:kind,value:value,resolve:resolve,reject:reject});drain();});}
          var result={next:function(value){return enqueue(this,'next',value);},throw:function(value){return enqueue(this,'throw',value);},return:function(value){return enqueue(this,'return',value);}};
          Object.defineProperty(result,@KEY@(),{value:function(){return this;},configurable:true,writable:true});
          return result;
        }
        """;
}
