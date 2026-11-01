const sym=Symbol();const {a,...r}={a:1,b:2,[sym]:3};let x;({b:x}=r);function f({b,...rest},[v,...tail]){return b+rest[sym]+v+tail.length}console.log(x,f(r,[4,5]));
