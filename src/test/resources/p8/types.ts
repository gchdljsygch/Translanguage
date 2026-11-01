type Pair = [number,string]; type Arr = number[];
type Obj = {readonly p?: string; (x:number):string; new(x:number):Date; [k:string]:unknown; m<T>(x:T):T};
type Fn = <T extends object>(x:T,...r:T[])=>T;
type Ctor = new<T>(x:T)=>Box<T>; type Q = typeof Math.PI;
type U = (number|string)&unknown; type Ref = A.B<number>;
interface Base<T> {value:T} interface I<T extends object=object> extends Base<T>,Other {readonly value:T}
declare function ambient(this:unknown,x?:number):void;
function f<T extends number>(x:T,y?:number,...r:number[]):number {return x+(y||0)+r.length}
const value: number = (<number>f<number>(2,3,4))! as number; console.log(value);
