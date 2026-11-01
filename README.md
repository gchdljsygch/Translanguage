# Translanguage

> **项目定位声明：本项目只以 Java 库（library）的形式使用——把 TypeScript 转成 JavaScript，供其他程序嵌入调用。它不是可直接运行的工具，也不打算成为工具。**
>
> 仓库中不存在 `main` 入口、命令行参数解析、启动脚本、安装目录或发行压缩包。读取源码、决定输出位置、写入文件等 I/O 全部由调用方完成；本库只接收字符串、返回字符串和诊断信息。

实现方式为 **Java + ANTLR 4**：ANTLR 负责 TypeScript 解析与类型擦除，显式选择 ES6 / ES2021 时再使用纯 Java 的 Closure Compiler 输出后端降级 JavaScript 语法。没有调用 `tsc`、Node.js 或外部进程，也没有用正则表达式代替语法解析。

## 作为依赖引入

构建并发布到本地 Maven 仓库：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17-or-newer'
.\gradlew.bat publishToMavenLocal
```

坐标固定为 `com.pingsu:Translanguage:1.0.0`。消费方 Gradle 配置：

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.pingsu:Translanguage:1.0.0'
}
```

`.\gradlew.bat publish` 会把 POM、主 jar、sources jar 和 javadoc jar 写入 `build/repo`，可以直接把该目录当作 Maven 仓库：

```groovy
repositories {
    maven { url = uri('path/to/Translanguage/build/repo') }
    mavenCentral()
}
```

也可以直接依赖 `build/libs/Translanguage-1.0.0.jar`；这种方式不会传递依赖，需要自行把 ANTLR 运行时 `org.antlr:antlr4-runtime:4.13.2` 和输出后端 `com.google.javascript:closure-compiler:v20240317` 加入运行时类路径。使用上面的 Maven 坐标时，两者均自动传递；Closure 不暴露在本库的公开 API 类型中。主 jar 声明了 `Automatic-Module-Name: com.pingsu.transpiler`，消费方需使用 JDK 17 或更高版本。

## 环境与构建

- JDK 17 或更高版本，`JAVA_HOME` 指向该 JDK。项目包含 Gradle Wrapper。
- 测试还需要 PATH 中有 Node.js（已用 Node.js 24 验证）；部分测试会实际执行生成的 `.mjs` 文件。
- 首次构建需从 Maven Central 下载 ANTLR、Closure Compiler，以及仅用于测试的 JUnit 和 Acorn。生成的 parser 在 `build/generated-src/antlr/main`。
- IDEA 中导入 Gradle 工程，Gradle JVM 选择 JDK 17+；语言级别与生成源码由 Gradle 管理。

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

## Java API

```java
Transpiler transpiler = new TypeScriptTranspiler();
TranspileResult result = transpiler.transpile("example.ts", "const value: number = 42;");
if (result.successful()) {
    System.out.println(result.javaScript());
} else {
    result.diagnostics().forEach(System.err::println);
}
```

上述类型位于 `com.pingsu.transpiler`。

- `Transpiler` 定义转换能力；同一个实例可重复使用，每次调用创建独立会话。`TypeScriptTranspiler` 的目标版本在构造时固定，`target()` 可读取配置。
- `TranspileResult` 同时返回 JavaScript 文本与诊断列表；失败时 `javaScript()` 为空字符串。
- `Diagnostic` 的行列从 1 开始，`toString()` 格式为 `文件:行:列: error 错误码: 描述`。
- `AbstractCompilation` 封装会话生命周期：`CREATED → PARSED → TRANSFORMED → COMPLETED`，失败进入 `FAILED`。
- `TransformationPass` 定义转换阶段，`TypeErasurePass` 与 `EnumLoweringPass` 为默认实现。
- `TransformationContext` 提供解析树、token、`target()`、源码编辑与诊断接口。通过 `new TypeScriptTranspiler(List.of(...))` 配置完整的阶段列表；也可使用 `new TypeScriptTranspiler(target, passes)`，在自定义 TS 阶段之后执行目标版本输出。
- `JavaScriptEmitter` 定义输出能力，默认实现为 `ClosureJavaScriptEmitter`。需要扩展输出策略时使用 `new TypeScriptTranspiler(target, passes, emitter)`；自定义 emitter 负责履行目标版本约束。
- 源码编辑以原始 ANTLR token 位置为基准，正确转换 Unicode 码点与 Java UTF-16 偏移；有重叠的扩展编辑会被拒绝，避免生成不可预测的结果。

库本身不读写文件、不读取 `tsconfig.json`、不扫描目录、不监听改动。文件编码、输出位置与写入策略由调用方决定；输入开头的 BOM 会被忽略，进程退出码等约定也属于调用方。

## 选择 ES6 / ES2021 输出

```java
Transpiler es6 = new TypeScriptTranspiler(JavaScriptTarget.ES6);
Transpiler es2021 = new TypeScriptTranspiler(JavaScriptTarget.ES2021);

String source = "class Box { value: number = 1; } const n = new Box()?.value ?? 0;";
TranspileResult result = es6.transpile("box.ts", source);
// 检查 result.successful() 后，由调用方保存 result.javaScript()。
```

| 目标 | 输出行为 |
| --- | --- |
| `PRESERVE`（默认） | 保持原有 API 行为：擦除 TS 类型，保留运行时语法与未修改区域的空白、注释；不约束 ECMAScript 版本。 |
| `ES6` | ECMAScript 2015 语法；降级 async/await、异步迭代、可选链、空值合并、逻辑赋值、幂运算、对象展开/剩余、可选 catch 绑定、数字分隔符和公开类字段。 |
| `ES2021` | 输出语法不超过 ECMAScript 2021；支持 BigInt、可选链、async/await、动态 import 和 import.meta，同时降级公开类字段。 |

显式目标使用输出后端重新打印代码，不保证空白或注释逐字保持，也不保证所有受支持的运算符都原样保留。实例和静态公开类字段通过 `Object.defineProperty` 保留自有属性语义，包括无初值字段；参数属性仍使用赋值语义。ES 模块保持为 ES 模块，不打包、不解析或改写调用方的模块路径。async 等转换需要的辅助函数随输出内联，生成代码无需加载 Closure 或额外的辅助函数包。

目标版本约束的是**语法**，不为业务代码自动补齐 `Promise.any`、`Array.prototype.includes` 等运行时 API；运行环境或调用方负责 polyfill。ES6 对象展开/剩余使用后端的 `Object.assign` 降级策略；对 getter、Proxy、`__proto__` 或属性读取顺序敏感的对象拷贝应使用 ES2021，以保留原生语义。

以下情况返回诊断且不返回 JavaScript：

- ES6 无法保持语义的 BigInt、动态 import、import.meta 和较新的正则表达式特性。
- ES6 / ES2021 下的顶层 await，以及含实例字段但构造函数没有唯一顶层直接 `super()` 语句的复杂派生类。
- 原有 TS 语法边界以外的输入，例如私有 `#` 字段、装饰器等。

输出阶段的诊断码为 `JS_TARGET`，行列基于类型擦除后的中间 JavaScript；最终版本检查的整体诊断定位在 `1:1`。库会重新解析最终输出并校验其语法特性，避免把仍含高版本语法的结果标记为成功。测试还使用独立的 Acorn 按 `ecmaVersion: 2015 / 2021` 验证产物，并通过 Node.js 检查实际执行结果。

## 已实现的转换

- 变量、参数、返回值、属性和 catch 参数的类型注解。
- interface、type 别名、declare 声明、函数/方法重载签名。
- 泛型声明、约束、默认类型、泛型调用、泛型箭头函数、泛型继承与 implements。
- `as`、`satisfies`、尖括号类型断言、非空断言和属性确定赋值断言。
- 可选参数与属性、TypeScript 的访问修饰符、readonly、override、abstract 声明。
- 显式 `import type` / `export type` 及混合导入导出中的 `type` 项。擦除模块标记后补 `export {};` 保留模块语义。
- 数字和字符串字面量初始化的枚举、数字自动递增、数字枚举反向映射。`const enum` 同样生成运行时对象，不内联引用。
- 构造函数参数属性生成 `this.name = name;`；派生类在构造函数顶层的直接 `super()` 语句之后赋值。
- 保留现代 JS 表达式、ES 模块、async/await、解构、剩余参数、模板字符串和控制流。

默认 `PRESERVE` 模式保留未改动源码的空白、换行、注释和字符串内容。被删除的类型区域内的注释会随之删除，枚举与混合导入/导出列表会重新生成局部文本；这不是格式化工具。

## 支持边界

这是一个有明确边界的 TS 转 JS 库，并非 TypeScript 编译器的完整替代品：

- **不做类型检查、跨文件名称解析、模块打包、路径重写、source map 或 ES5 降级。** 默认保留现代 JavaScript 语法，显式目标可输出 ES6 或 ES2021。ES 模块输出需使用 `.mjs` 或 `type: module` 环境。
- 普通导入/导出按原样保留，不根据类型使用情况推断删除；仅用于类型的符号需要显式写 `type`。
- 类型语法覆盖普通引用、对象、数组、元组、联合、交叉、函数类型等。映射类型、条件类型、命名元组等尚未覆盖的类型语法会报解析错误。
- JSX/TSX、运行时 namespace、装饰器、`import =` / `export =`、计算枚举初始化、复杂控制流中的参数属性初始化暂不支持，会报解析或 `TS_UNSUPPORTED` 错误。

## 与旧版命令行入口的关系

早期版本附带过一个基于 `Main` 的命令行入口，现已移除，以免本仓库被当成独立工具使用。如果你需要命令行，请在自己的项目中依赖本库并实现参数解析：`TypeScriptTranspiler` 只接受 `(sourceName, source)` 两个字符串参数，适合在任何 CLI、构建插件或编辑器扩展中调用。

`examples/hello.ts` 仅作为示例输入保留，并由 `ExampleTest` 通过公开 API 转换验证，不会被本库直接执行。

语法来源、版本记录及本地修订见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
