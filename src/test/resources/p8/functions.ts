function* g(a=1){yield a;yield* [2]} const o={*g(){yield 3},get x(){return 4},set x(v){},'default':5};
const f=function named(x:number){return x}; const arrow=(x:number)=>({v:x}); const one=x=>x+1;
console.log([...g()].join(','),o.g().next().value,o.x,f(5),arrow(6).v,one(6));
