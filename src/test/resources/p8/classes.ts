interface I{} abstract class A {abstract m():number; static base=2;}
class B extends A implements I { [key:string]:any; value=3; static copy=super.base; m(){return this.value} get x(){return this.value} set x(v:number){this.value=v} *values(){yield this.value} }
const b=new B();b.x=4;console.log(b.m(),B.copy,b.values().next().value);
