class A{set x(v){this.saved=v}} class B extends A{constructor(public x:number,private y=2){super()} sum(){return this.saved+this.y}} console.log(new B(3).sum());
