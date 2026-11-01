async function f(x=2){try{return await Promise.resolve(x)}finally{await 0}} f().then(console.log);
