async function* g(){try{yield await 1;yield* [2]}finally{console.log("close")}} async function f(){for await(const x of g()){console.log(x);break}}f();
