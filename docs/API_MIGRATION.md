# 2.0 扩展 API 迁移

当前正式版本为 `2.0.0`。本文描述已接入生产流程的公共 API 变更；P8 发行验收已通过，证据与复现见 [发行验收](RELEASE_ACCEPTANCE.md)。生产前端已切换到自有解析器，语法树由解析器直接生成，临时适配器已移除。全部目标（含 ES6 异步）只需 JDK 17+ 与主 JAR。

| 1.x API | 当前 API | 迁移方式 |
| --- | --- | --- |
| `context.program(): ProgramContext` | `syntax.Program` | 使用 `kind() / range() / children()`，用 `walk(SyntaxVisitor)` 前序遍历；返回 false 可剪枝 |
| `context.tokens(): CommonTokenStream` | `List<syntax.Token>` | 不可变列表，包含 trivia 和末尾 EOF；没有生成器 token 编号 |
| `ParserRuleContext` 编辑 | `SyntaxNode / SourceRange` 编辑 | `remove(node)`、`replace(node, text)`、`slice(node)` |
| ANTLR `Token` 编辑 | `syntax.Token` 编辑 | `remove(token)`、`replace(token, text)`、`replace(first, last, text)` |
| `tokens.getText(node)` | `context.slice(node)` | 返回原文，保留节点内部 trivia |
| ANTLR 码点偏移 | `SourceRange(start, end)` | UTF-16 半开区间；和 Java 字符串索引一致；不能切开代理对 |
| token 行列 | `token.line()/column()` | 从 1 开始、列按码点计算，CRLF 视为一个换行，同时支持 CR、LF、U+2028、U+2029 |
| `unsupported(ParserRuleContext, ...)` | `unsupported(SyntaxNode, ...)` | 诊断在节点起点 |
| 插入 | `insert(offset, text) / insertAfter(token, text) / append(text)` | 均基于原始输入坐标 |

输入开头的 BOM 在会话创建前移除，节点、token、切片和编辑都使用去掉 BOM 的源码。`program.range()` 覆盖完整源码；`context.source()` 和 `sourceName()` 可读取该输入和诊断名称。语法树结构不含解析器对象，不应强转成 ANTLR 上下文。

重叠替换、包含替换以及替换区间严格内部的插入立即抛出 `IllegalStateException`，没有隐式合并或覆盖。边界插入允许；同位置插入按登记顺序，起点插入先于替换，终点插入在替换之后。越界和切开代理对抛出 `IllegalArgumentException`。失败编辑不会加入编辑集合。

`AbstractCompilation` 现在拒绝跳跃转换和重入执行。错误结果统一清空输出，包括自定义 emitter 返回的带诊断结果；异常仍向调用方抛出，会话状态变为 FAILED。配置的 passes/emitter 在调用间共享，扩展者负责并发安全；每次调用的语法视图、编辑、诊断和词法状态独立。

以下完整示例已由 `compileStandaloneConsumer` 使用 JDK 17、仅主 JAR 编译，并覆盖自定义 pass/emitter、失败结果与重复调用：

- [StandaloneConsumer.java](../src/consumerTest/java/consumer/StandaloneConsumer.java)
- `verifyExtensionApi` 运行其中的公共模型分支，当前通过。
- `verifyStandaloneConsumer` / `verifyStandaloneConsumerNewerJdk` 运行全部转换，JDK 17/25 仅主 JAR 均通过，包含异步生成器、for-await 与扩展追加异步源码。

默认装配已替换为公共 `DefaultJavaScriptEmitter`。原来显式 `new ClosureJavaScriptEmitter()` 的调用方应改用 `new DefaultJavaScriptEmitter()` 或接口注入。旧具体类和 ClassFieldSemantics 已从生产源码、主 JAR、源码包及文档包移除；只在隔离的测试参考源集保留。显式目标的空白格式与 Closure 不同；`JS_TARGET` 坐标使用自有中间 JS 定位，PRESERVE 及 TS 诊断坐标契约保持。直接使用 `com.pingsu.antlr.*` 的旧扩展不属于已完成迁移的公共接口。2.0.0 已在本地发行仓库完成三制品及消费验收，未上传公共仓库。

P2/P3 新增 `verifyPreserveConsumer` / `verifyPreserveConsumerNewerJdk`：仅主 JAR，在 JDK 17/25 运行默认 PRESERVE、错误契约、类型擦除、枚举、参数属性和自定义 pass/emitter；两项及 `auditFrontendIsolation` 均已接入 `check`。

自有 parser 使用独立语句/类型语法和 Pratt 表达式，支持 JS 模式禁止 TS 节点；最大语法深度为 128，工作预算为 `4096 + 64 × UTF-16 输入长度`。越界返回 `TS_SYNTAX` 和空输出，不抛出栈溢出。诊断文字由新前端定义；冻结基线仍逐项比较诊断码、源码名及精确行列。

P4/P5 新增 `verifyRegularConsumer` / `verifyRegularConsumerNewerJdk`，已接入 `check`。它们覆盖字段、常规 ES6 和原生 ES2021 异步；完整消费任务另含 ES6 异步，已在 P6/P7 通过。`auditConsumerDependencies` 与完整消费任务已接入 `check` 和所有 Maven 发布任务。直接 eval/with 与需要新增词法绑定的降级组合会显式拒绝，参见 README。

异步降级使用 ES6 generator、Promise 与自有迭代请求队列；直接 eval/with 会被显式拒绝。函数与异步迭代器的原型/构造器反射形态不作等价承诺；标准 Symbol.asyncIterator 不存在时，仅同一输出内通过私有 Symbol 互通，详见 README。
