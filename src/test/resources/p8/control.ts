let n=0; ; debugger; outer: for(let i=0;i<4;i++){if(i===1)continue outer;if(i===3)break; n+=i;}
let j=0;while(j<2){j++} do{j--}while(j>1);for(const k in {a:1})n++;for(const v of [2])n+=v;
switch(n){case 0:n=9;break;case 5:n++;break;default:n=0;}
try{throw 2}catch(e:unknown){n+=e as number}finally{n++;}console.log(n,j);
