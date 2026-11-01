# Translanguage

使用 **Java + ANTLR 4** 将 TypeScript 转成现代 JavaScript 的命令行工具，也可作为 Java API 调用。
ANTLR 负责词法与语法分析，Visitor 根据解析树进行类型擦除与运行时语法转换。没有调用 `tsc` 或用正则表达式替代语法解析。

## 环境与运行

- 构建和启动：JDK 17 或更高版本，`JAVA_HOME` 指向该 JDK。项目包含 Gradle Wrapper。
- 测试：还需要 PATH 中有 Node.js（已用 Node.js 24 验证）；测试会实际执行生成的 `.mjs` 文件。
- 首次构建需从 Maven Central 下载 ANTLR 和 JUnit。生成的 parser 在 `build/generated-src/antlr/main`。
- IDEA 中导入 Gradle 工程，Gradle JVM 选择 JDK 17+；语言级别与生成源码由 Gradle 管理。

PowerShell：

```powershell
# 如果终端默认是 Java 8，先把 JAVA_HOME 改成自己安装的 JDK 17+ 路径。
$env:JAVA_HOME = 'C:\path\to\jdk-25'

.\gradlew.bat test
.\gradlew.bat run --args="examples/hello.ts -o build/hello.js"
node build/hello.js
```

示例输出：

```text
你好，ANTLR！
0 Done
```

生成包含依赖和 Windows/Linux 启动脚本的安装目录：

```powershell
.\gradlew.bat installDist
.\build\install\Translanguage\bin\Translanguage.bat examples/hello.ts -o build/hello.js
.\build\install\Translanguage\bin\Translanguage.bat --help
```

使用 `distZip` 生成分发压缩包。安装目录和压缩包包含 README、第三方许可说明与示例。

## 命令行

```text
translanguage <input.ts | -> [-o <output.js | ->]
translanguage --help
```

不指定 `-o` 时输出到标准输出；`-` 表示标准输入/输出。直接使用安装后的启动脚本可以避免 Gradle 日志混入标准输出。

```powershell
'const message: string = "hello"; console.log(message);' |
    .\build\install\Translanguage\bin\Translanguage.bat -
```

文件按 UTF-8 读取和写入；接受输入 BOM；不覆盖输入文件。输出父目录自动创建。只有转换成功才写入文件；转换失败不会破坏已有输出。
包含中文的 PowerShell 管道应先将 `$OutputEncoding` 和 `[Console]::OutputEncoding` 设为 UTF-8。

错误格式为 `文件:行:列: error 错误码: 描述`，行列从 1 开始。退出码：

| 状态码 | 含义 |
| --- | --- |
| 0 | 成功 / 帮助 |
| 1 | 语法错误或不支持的转换 |
| 2 | 命令行参数或文件读写错误 |

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

转换保留未改动源码的空白、换行、注释和字符串内容。被删除的类型区域内的注释会随之删除，枚举与混合导入/导出列表会重新生成局部文本；这不是格式化工具。

## 支持边界

这是一个有明确边界的 TS 转 JS 工具，并非 TypeScript 编译器的完整替代品：

- **不做类型检查、跨文件名称解析、模块打包、路径重写、source map 或 ES5 降级。** 输出使用现代 JavaScript/ES2022 类字段语义。ES 模块输出需使用 `.mjs` 或 `type: module` 环境。
- 普通导入/导出按原样保留，不根据类型使用情况推断删除；仅用于类型的符号需要显式写 `type`。
- 类型语法覆盖普通引用、对象、数组、元组、联合、交叉、函数类型等。映射类型、条件类型、命名元组等尚未覆盖的类型语法会报解析错误。
- JSX/TSX、运行时 namespace、装饰器、`import =` / `export =`、计算枚举初始化、复杂控制流中的参数属性初始化暂不支持，会报解析或 `TS_UNSUPPORTED` 错误。
- 不读取 `tsconfig.json`；不提供目录扫描、监听或增量构建。

## Java API 与扩展

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

- `Transpiler` 定义转换能力；同一个实例可重复使用，每次调用创建独立会话。
- `AbstractCompilation` 封装会话生命周期：`CREATED → PARSED → TRANSFORMED → COMPLETED`，失败进入 `FAILED`。
- `TransformationPass` 定义转换阶段，`TypeErasurePass` 与 `EnumLoweringPass` 为默认实现。
- `TransformationContext` 提供解析树、token、源码编辑与诊断接口。通过 `new TypeScriptTranspiler(List.of(...))` 配置完整的阶段列表。
- 源码编辑以原始 ANTLR token 位置为基准，正确转换 Unicode 码点与 Java UTF-16 偏移；有重叠的扩展编辑会被拒绝，避免生成不可预测的结果。

语法来源、版本记录及本地修订见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
