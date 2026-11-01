class A{m(){return 3}}class B extends A{async m(){const f=async()=>await super.m();return await f()}}new B().m().then(console.log);
