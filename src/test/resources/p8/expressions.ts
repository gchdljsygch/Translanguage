let x=2;x**=3;x+=1;x-=1;x*=2;x/=2;x%=7;x<<=1;x>>=1;x>>>=0;x|=2;x&=3;x^=1;
const a=[,1,...[2]];const o={a:1,['b']:2, m(v){return this.a+v}};const f=(v)=>v*2;
console.log(x,a.length,o.m?.(3)??0,(null?.x??5),f(3),typeof o,void 0,delete o.b, /x+/gi.test('xx'));
