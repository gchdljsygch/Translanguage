type T<X> = X extends {id:infer U} ? U : never;
type M<X> = {-readonly [K in keyof X as K]-?: X[K]};
type Tuple = [name:string,age?:number,...rest:boolean[]]; type Temp = `p${string}`;
function isString(x:unknown):x is string{return typeof x==='string'}
function assertString(x:unknown):asserts x is string{}
console.log(isString('x'));
