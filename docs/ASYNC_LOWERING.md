# ES6 异步降级实现与验收

更新：2026-11-02。实现入口为 `internal/lowering/AsyncLowering`，由 `DefaultJavaScriptEmitter` 在字段处理之后、对象剩余和常规表达式降级之前调用。ES2021 保留原生异步语法。所有生成内容（包括 helpers）都经过自有 JS 重解析、目标检查，并在测试中交给 Acorn 2015/2021 和 Node。

## 转换规则

1. 将 async 函数主体移入 ES6 generator，await 表达式变为 generator 暂停点。原生 generator 保留循环、switch、try/catch/finally、break/continue/return 的完成记录；不会手工展开这些控制流为字符串状态标签。
2. 普通 async 函数通过 Promise executor 中的工厂创建 generator，因此默认参数、解构等调用时异常成为 Promise 拒绝。异步生成器直接创建 generator，参数初始化仍同步发生，函数体延迟至首个 next。原参数整体放在内部 generator，保留参数环境与函数体 var 环境的关系。外层签名保留 length；箭头使用剩余参数转发实参。
3. 非箭头函数在内部 generator 中保留 this 和 arguments。箭头通过延迟词法读取保留 this、arguments、typeof arguments 和 new.target，包括派生构造器 super() 前创建、暂停后才读取 this 的情形。普通嵌套函数隔离这些捕获；词法箭头继承它们。
4. super 的 home object 留在原方法中。方法内创建词法访问器，由内部 generator 使用；成员调用保持 receiver，计算 key 表达式只求值一次，ToPropertyKey 仍在实际读写时执行。可选调用临时值按函数、箭头和参数初始化环境分配，避免重入污染或参数看不到函数体变量。
5. 异步生成器内部暂停值分三种：await（0）、已完成等待的普通 yield（1）、yield* 委托（2）。只有向调用方发布 yield 或结束时才完成队首请求；await 保持队首请求的所有权。同步/异步委托遵循各自的值解包协议，异步委托的 value 不额外自动 await。
6. for-await 获取并缓存迭代器和 next 方法；用 try/finally 保护循环体。next/结果读取失败不执行多余关闭；绑定/循环体异常、break、return 和跳出外层标签则执行关闭。原异常优先于关闭异常。普通 continue 不关闭，下一轮恢复前清除 active 标记。
7. for-await 的 RHS 在未初始化的循环头词法环境中求值；即使 RHS 保存了引用该变量的闭包，该环境也保持 TDZ。实际每轮绑定使用新的词法声明。同步迭代结果通过 Promise 解包 value，原生异步迭代结果保留 value。

## Helper 依赖与状态

| helper | 触发条件 | 依赖 | 职责 |
| --- | --- | --- | --- |
| CHECK | 迭代 | 无 | 要求 IteratorResult 为对象 |
| KEY | 迭代协议 | 无 | 使用 Symbol.asyncIterator；不存在时为同一输出懒创建私有 Symbol |
| ITERATOR | for-await / yield* / 异步生成器 | CHECK、KEY | 获取协议、缓存 next、保持 receiver、适配同步 value 与关闭 |
| RUN | 普通 async | 无 | Promise executor、同步启动、thenable 采纳和 next/throw 恢复 |
| GENERATOR | async generator | CHECK、KEY、ITERATOR | 请求队列、await/发布/委托、启动和完成状态、请求 receiver 检查 |

名称由同一次编译的 ScopeAnalysis 分配，避开用户声明、引用和转义标识符；按固定依赖顺序去重内联。helper 定义无顶层初始化副作用。运行仅需目标标准运行时，不加载第三方 helper 包，不复制 Closure 输出。

生成器队列状态包括未启动、执行中（busy）、暂停和完成。每个请求保存操作、值及 Promise 的 resolve/reject；请求完成后按序处理下一项。同步完成大量请求时由 drain 循环推进，避免递归排空导致栈溢出。未启动的 return/throw 不执行函数体；完成后的 next、return、throw 各自遵循对应结果/拒绝协议。

## 独立语义证据

`OwnAsyncBackendTest` 的每个具名输入分别运行原生源码和 ES6/ES2021 产物，比较 stdout/事件轨迹，并检查实际产物语法。覆盖参数、捕获、super、finally、thenable 和微任务、委托、同步 value 拒绝时的 IteratorClose、异常优先级、队列、借用请求方法、TDZ 闭包、嵌套标签，以及 12,000 个排队请求。原有 JT-002/JT-008 和冻结参考记录继续运行。

JDK 17/25 的 StandaloneConsumer 只用主 JAR，覆盖三目标、完整异步链与扩展追加源码；生产依赖、元数据和制品由 auditConsumerDependencies 审计。完整证据位于 `verification/2026-11-02-p6-p7`。

## 明确边界

- 直接 eval/with 与异步降级组合显式返回 JS_TARGET、空输出，避免改变动态作用域可见性。
- 不承诺函数/迭代器原型、构造器、toString 的反射形态一致；不提供业务 API polyfill。
- 缺少 Symbol.asyncIterator 时，私有 key 只保证同一输出的内部生成器/for-await 互通，不安装全局协议。
- P8 的无工具安装、无缓存、操作系统断网、最终性能和正式发行验收仍待完成。


## ES5 状态机后端（2026-11-10）

ES5 现在复用此异步协议转换，并在同步语法和词法绑定处理后调用 `GeneratorLowering`。生成器主体被编译为可恢复的 switch 状态，`GeneratorRuntime` 路由 next/throw/return、委托以及 catch/finally 完成记录；最终不含 `function*`、yield、async 或 await 语法。简单 async 仅要求 Promise，生成的调用使用 ES5 的 Function.prototype.apply.call；异步迭代仍使用宿主 Symbol 协议。

`ResourceManagementLowering` 在异步转换前把 using / await using 转为受保护的资源栈，使异步清理与生成器关闭复用同一套 finally 完成路径。`Es5ExtendedSyntaxTest` 用 Acorn ES5 复核输出，并对暂停、委托、异常、资源释放及模板/类语法组合进行运行验证。
