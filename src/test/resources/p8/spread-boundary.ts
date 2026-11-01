const source=JSON.parse('{"__proto__":{"marker":7},"x":1}');const result={...source};
console.log(Object.getPrototypeOf(result)===Object.prototype,Object.prototype.hasOwnProperty.call(result,'__proto__'),result.marker);
let calls=0;Object.defineProperty(Object.prototype,'probe',{set(v){calls++},configurable:true});
const copy={...{probe:3}};delete Object.prototype.probe;
console.log(calls,Object.prototype.hasOwnProperty.call(copy,'probe'),copy.probe);
