# Translanguage

**Translanguage** 是一个零运行时依赖的 Java 库，用于将 TypeScript 源码字符串转换为 JavaScript。

> **定位声明**：本项目**仅以 Java 库（library）的形式提供**，供其他 JVM 程序（如构建插件、服务端应用、脚手架或编辑器扩展）嵌入调用。生产 JAR 不包含 `main` 入口、命令行参数解析、启动脚本或分发安装包；源码读取、文件写入与路径管理等 I/O 均由调用方负责。

## 核心特性

- **零第三方依赖**：编译与运行仅需 **JDK 17+** 标准库（`java.base`）与单个主 JAR，无需 Node.js、`tsc`、ANTLR 或 Closure Compiler。
- **纯内存字符串转换**：接收 `(sourceName, source)` 字符串，返回生成的 JavaScript 文本与结构化诊断信息，不触碰文件系统或外部进程。
- **多目标语法输出**：支持保留原运行时语法（`PRESERVE`），或按目标环境降级输出为 `ES2021` / `ES6`（含 `async`/`await`、异步生成器与公开类字段降级）。
- **并发安全与可扩展**：`Transpiler` 实例可跨线程安全复用；内置不可变语法树（AST）与源码区间编辑接口，支持注入自定义 `TransformationPass` 与 `JavaScriptEmitter`。

---

## 环境要求与引入方式

- **运行环境**：JDK 17 或更高版本
- **模块名**：`com.pingsu.transpiler`（`Automatic-Module-Name`）
- **Maven 坐标**：`com.pingsu:Translanguage:2.0.0`

### 1. 发布到本地仓库并引入

在项目根目录使用 JDK 17+ 构建并发布：

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-17-or-newer'
.\gradlew.bat publishToMavenLocal
```

在消费方 Gradle 项目中添加依赖：

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.pingsu:Translanguage:2.0.0'
}
```

### 2. 使用构建目录仓库或单 JAR 引入

执行 `.\gradlew.bat publish` 会将 POM、主 JAR、sources JAR 和 javadoc JAR 发布到 `build/repo`，可直接作为本地 Maven 仓库引用：

```groovy
repositories {
    maven { url = uri('path/to/Translanguage/build/repo') }
}

dependencies {
    implementation 'com.pingsu:Translanguage:2.0.0'
}
```

也可以直接执行 `.\gradlew.bat jar`，将生成的 `build/libs/Translanguage-2.0.0.jar` 加入消费方类路径（无任何传递依赖）。

---

## 快速开始

### 基础转换（默认 `PRESERVE` 模式）

默认构造的 `TypeScriptTranspiler` 会擦除 TypeScript 类型注解与纯类型声明，转换枚举与构造函数参数属性，并保留其余运行时 JavaScript 语法及未改动区域的空白和注释：

```java
import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.TranspileResult;
import com.pingsu.transpiler.Transpiler;
import com.pingsu.transpiler.TypeScriptTranspiler;

public class Example {
    public static void main(String[] args) {
        Transpiler transpiler = new TypeScriptTranspiler();

        String tsSource = """
                export interface User {
                    id: number;
                    name: string;
                }
                const user: User = { id: 1, name: "Alice" };
                console.log(user.name);
                """;

        TranspileResult result = transpiler.transpile("user.ts", tsSource);
        if (result.successful()) {
            System.out.println(result.javaScript());
        } else {
            for (Diagnostic diagnostic : result.diagnostics()) {
                System.err.println(diagnostic);
            }
        }
    }
}
```

### 指定 ECMAScript 目标版本（`ES6` / `ES2021`）

当运行环境对 ECMAScript 语法版本有限制时，可在构造 `TypeScriptTranspiler` 时指定 `JavaScriptTarget`：

```java
import com.pingsu.transpiler.JavaScriptTarget;
import com.pingsu.transpiler.TranspileResult;
import com.pingsu.transpiler.Transpiler;
import com.pingsu.transpiler.TypeScriptTranspiler;

Transpiler es6Transpiler = new TypeScriptTranspiler(JavaScriptTarget.ES6);
Transpiler es2021Transpiler = new TypeScriptTranspiler(JavaScriptTarget.ES2021);

String source = """
        class Box {
            value: number = 1;
            constructor(public label: string) {}
        }
        async function load(box?: Box): Promise<number> {
            return (await Promise.resolve(box?.value)) ?? 0;
        }
        """;

TranspileResult result = es6Transpiler.transpile("box.ts", source);
if (result.successful()) {
    String js = result.javaScript();
    // 由调用方保存或执行生成的 JavaScript
}
```

---

## 输出目标版本对比

| 目标枚举 | 输出语法行为 | 适用场景 |
| --- | --- | --- |
| `JavaScriptTarget.PRESERVE`（默认） | 擦除 TS 类型、省略无运行时引用的导入并转换枚举/参数属性；保留其余运行时 JS 语法与未修改区域的空白、换行和注释。 | 后续接入现代打包器/运行时，或希望尽量保留原始代码排版。 |
| `JavaScriptTarget.ES2021` | 重新打印代码，确保语法不超过 ECMAScript 2021；原生保留 `async`/`await`、可选链、空值合并、BigInt、动态 `import()` 和 `import.meta`，同时降级公开类字段并移除数字分隔符。 | 现代浏览器或 Node.js 环境，需兼容尚未支持 ES2022 类字段的引擎。 |
| `JavaScriptTarget.ES6` | 重新打印代码，确保语法不超过 ECMAScript 2015（ES6）；自动降级 `async`/`await`、异步生成器、`for await...of`、可选链 `?.`、空值合并 `??`、逻辑赋值、幂运算 `**`、对象展开/剩余、可选 `catch` 绑定、数字分隔符、`export * as ns` 以及公开类字段。 | 仅支持 ES6 语法的运行时环境。 |

### 显式目标（`ES6` / `ES2021`）行为说明

- **自包含辅助函数**：`ES6` 异步控制流、类字段定义与对象剩余解构所需的辅助函数（helper）会按需去重内联到输出代码中，生成代码无需引入额外 runtime 包。对象剩余解构仅在需要时生成数组迭代适配器和赋值结果回溯表。
- **精简且可读的输出**：安全的实例字段直接在构造函数中定义，保留原生 `class`；需要独立初始化作用域的字段继续使用语义完整的降级路径，并省略未使用的 Symbol 和初始化变量。最终代码按语法树组织换行、两空格缩进与运算符间距，保持现有换行语义和模板原文。具体边界与体积对比见 [显式目标输出优化](docs/OUTPUT_QUALITY.md)。
- **保留 ES 模块**：经 import elision 保留的 `import` / `export` 仍使用标准 ES 模块语法，不打包、不解析也不改写模块路径；`export * as ns from 'mod'` 在 `ES6` 下会降级为命名空间导入加命名导出，保留实时绑定（live binding）。
- **类字段语义**：实例和静态公开类字段通过 `Object.defineProperty` 降级，保留自有属性语义（含无初始值字段）；构造函数参数属性保持 `this.prop = prop;` 赋值语义。
- **仅降级语法，不注入业务 Polyfill**：目标版本约束的是**语法**，不会为业务代码自动注入 `Promise.any`、`Array.prototype.includes` 等标准库 API 的 polyfill。运行 `ES6` 降级产物需要宿主具备 ES6 标准内置对象（`Promise`、`Symbol`、`Reflect`、`Proxy`、`WeakMap`）。
- **语法复核**：显式目标在生成代码后会重新解析并校验最终 AST，确保不会将残留高版本语法的产物标记为成功。

---

## 核心 API 与错误处理

核心类型均位于 `com.pingsu.transpiler` 与 `com.pingsu.transpiler.syntax` 包下：

| 类型 | 说明 |
| --- | --- |
| `Transpiler` | 转换器接口，核心方法为 `TranspileResult transpile(String sourceName, String source)`。输入开头的 UTF-8 BOM（`\uFEFF`）会自动忽略。 |
| `TypeScriptTranspiler` | `Transpiler` 的标准实现。实例不可变且线程安全，可跨多次调用和多线程并发复用；每次 `transpile` 调用均创建隔离的编译会话。 |
| `TranspileResult` | 转换结果记录类，包含 `boolean successful()`、`String javaScript()` 和 `List<Diagnostic> diagnostics()`。**一旦存在任何诊断错误，`successful()` 为 `false` 且 `javaScript()` 保证为空字符串 `""`。** |
| `Diagnostic` | 结构化诊断记录：`sourceName()`、`line()`（从 1 开始）、`column()`（从 1 开始，按 Unicode 码点计）、`code()`、`message()`。`toString()` 格式为 `文件:行:列: error 错误码: 描述`。 |

### 常见诊断码（`Diagnostic.code()`）

- `TS_LEXER`：词法错误（如未闭合的字符串、模板或非法字符）。
- `TS_SYNTAX`：TypeScript / JavaScript 语法错误，或超出解析深度（128 层）与工作预算限制。
- `TS_UNSUPPORTED`：已知不支持的 TypeScript 运行时特性（见下方[支持边界](#支持边界与已知限制)）。
- `JS_TARGET`：显式目标（`ES6` / `ES2021`）不支持的语法特性（如 `ES6` 下的 BigInt、动态 `import()`、`import.meta`、高版本正则特性，或顶层 `await`）。`JS_TARGET` 的行列坐标基于类型擦除后的中间 JavaScript。

---

## 扩展与自定义管线

当内置转换阶段无法满足定制需求时，可通过 `TransformationPass` 与 `JavaScriptEmitter` 扩展编译管线。1.x 升级用户请先参阅 [2.0 扩展 API 迁移说明](docs/API_MIGRATION.md)。

### 1. 注入自定义 `TransformationPass`

`TransformationContext` 暴露了不可变语法树 `Program`、完整 `Token` 列表（含空白、注释和末尾 `EOF`）以及基于原始源码（去除开头 BOM 后）UTF-16 半开区间 `[start, end)` 的非破坏性编辑接口：

```java
import com.pingsu.transpiler.*;
import com.pingsu.transpiler.syntax.*;
import java.util.List;

TransformationPass bannerPass = context -> {
    // 前序遍历语法树（返回 false 可跳过子树）
    context.program().walk(node -> {
        if (node.kind().equals("debuggerStatement")) {
            context.unsupported(node, "debugger statements are forbidden.");
            return false;
        }
        return true;
    });
    context.append("// Transpiled for target: " + context.target());
};

// 在默认阶段之外组合自定义阶段，并指定输出目标
Transpiler transpiler = new TypeScriptTranspiler(
        JavaScriptTarget.ES2021,
        List.of(new TypeErasurePass(), new EnumLoweringPass(), bannerPass)
);
```

**源码编辑规则（`TransformationContext`）**：
- 所有节点与 token 的坐标始终对应原始输入，不会因其他编辑而偏移。
- 支持 `remove(node/token/range)`、`replace(node/token/range, text)`、`insert(offset, text)`、`insertAfter(token, text)` 和 `append(text)`。
- **冲突检测**：重叠替换、包含替换以及在替换区间严格内部的插入会立即抛出 `IllegalStateException`；越界或切开 UTF-16 代理对会抛出 `IllegalArgumentException`。
- **边界插入顺序**：允许在替换区间起点或终点插入；同位置多次插入按登记顺序输出（起点插入先于替换内容，终点插入在替换内容之后）。

### 2. 自定义 `JavaScriptEmitter`

可通过三参数构造器替换或包装默认输出后端 `DefaultJavaScriptEmitter`：

```java
JavaScriptEmitter defaultEmitter = new DefaultJavaScriptEmitter();
JavaScriptEmitter customEmitter = (sourceName, intermediateJs, target) -> {
    TranspileResult emitted = defaultEmitter.emit(sourceName, intermediateJs, target);
    if (!emitted.successful()) {
        return emitted;
    }
    return new TranspileResult("/* header */ " + emitted.javaScript(), List.of());
};

Transpiler transpiler = new TypeScriptTranspiler(
        JavaScriptTarget.ES6,
        List.of(new TypeErasurePass(), new EnumLoweringPass()),
        customEmitter
);
```

> **并发提示**：传入 `TypeScriptTranspiler` 的 `TransformationPass` 和 `JavaScriptEmitter` 实例会在多次和并发调用间共享，自定义实现请勿在实例字段中保存单次编译的可变状态。

---

## 支持的 TypeScript 转换清单

- **类型注解擦除**：变量、函数参数、`this` 参数、返回值、类属性、对象方法及 `catch` 参数的类型注解。
- **纯类型声明擦除**：`interface`、`type` 别名、`declare` 环境声明、函数/方法/构造器重载签名、索引签名。
- **泛型与断言擦除**：泛型声明、类型约束、默认类型参数、泛型调用、泛型箭头函数、`implements` 子句、`as` / `satisfies` / `<T>` 类型断言、`!` 非空断言与确定赋值断言。
- **类与修饰符**：擦除 `public` / `protected` / `private` / `readonly` / `override` / `abstract`；转换构造函数参数属性；派生类支持分支、循环、短路/条件表达式、返回表达式及词法箭头函数中的 `super()`，按“父类构造 → 实例字段 → 参数属性”的顺序初始化，并保留 `super()` 表达式的返回值和异常行为。
- **模块导入导出与 Import Elision**：所有目标默认擦除 `import type` / `export type` 及混合导入导出中的 `type` 项，并按文件内的词法绑定删除未使用或仅用于类型的普通导入，支持默认、命名、别名及命名空间导入的逐项删减。运行时表达式、本地值导出、计算属性名和对象简写中的引用会保留对应导入；同名参数、局部变量、catch 绑定和枚举成员不会误保留外层导入。显式副作用导入 `import './setup'` 和空列表导入 `import {} from './setup'` 保留；若所有静态导入导出均被擦除，会补充一次 `export {};` 维持 ES 模块作用域。

- **枚举（`enum` / `const enum`）**：支持数字/字符串、算术与位运算、成员引用、函数调用及其他同步表达式的运行时求值；按声明顺序单次求值，数字结果生成反向映射，字符串结果不生成反向映射。同作用域的合并声明可引用已有成员；回调参数、局部变量及属性名不会被误改写。数字常量表达式后的成员可自动递增；字符串或无法静态识别为数字常量的计算成员之后必须显式初始化。`const enum` 同样生成运行时对象，不做跨表达式内联。

```ts
import DefaultType, { User, createUser } from './users';
const user: User = createUser();
```

默认输出仅保留 `import { createUser } from './users';`，并擦除 `user` 的类型注解。若需要执行只提供类型或未使用绑定的模块的副作用，请另写显式副作用导入。此行为属于默认 `TypeErasurePass`；自定义 pass 列表仍严格按调用方配置执行。

---

## 支持边界与已知限制

Translanguage 是一个专注于单文件语法转换的库，**不是**完整的 TypeScript 编译器（`tsc`）替代品：

1. **不做类型检查与跨文件分析**：仅解析并擦除类型语法，不验证类型正确性，不读取 `tsconfig.json`，不解析外部模块路径，也不生成 `.d.ts` 或 Source Map。
2. **Import Elision 的分析边界**：只分析当前文件的语法与运行时绑定，不查询依赖模块中某个导出究竟是类型还是值。普通本地 `export { Foo }` 视为值引用；类型重导出请写 `export type { Foo }` 或 `export { type Foo }`。带 `from` 的普通重导出不按本地使用情况删除。直接 `eval(...)` 会保守保留其作用域可见的普通导入，显式类型导入仍擦除。
3. **不支持的 TS 特性（返回 `TS_UNSUPPORTED` 或 `TS_SYNTAX`）**：
   - JSX / TSX 语法
   - 运行时 `namespace` / `module`（`declare namespace` 环境声明支持擦除）
   - 装饰器（Decorators）与 `#private` 私有字段
   - `import = require(...)` 与 `export =`
   - 直接使用 `await` / `yield` 暂停枚举初始化，或在枚举初始化表达式中使用直接 `eval(...)` / `with`
   - 带参数属性的派生构造函数在参数默认值中调用 `super()`；仅实例字段初始化支持这一位置
   - 参数属性需要跨控制流初始化且源码含直接 `eval(...)` / `with`（为保留参数绑定，转换需要生成局部初始化函数）
4. **显式目标（`ES6` / `ES2021`）下的特定边界**：
   - 顶层 `await` 在 `ES6` 与 `ES2021` 下均会拒绝并返回 `JS_TARGET`。
   - 当需要引入临时变量或辅助函数的降级（如类字段、对象剩余、异步或复杂短路求值）与直接 `eval(...)` 或 `with` 语句同时出现时，为避免破坏动态作用域可见性，会显式返回 `JS_TARGET` 错误（`PRESERVE` 模式不受影响）。
   - `ES6` 对象展开使用 `Object.assign` 降级策略（触发目标上的 setter 及 `__proto__` 行为可能与原生对象展开存在细微差异）；若需严格原生对象展开语义请使用 `ES2021` 或 `PRESERVE`。

---

## 延伸阅读与维护者文档

- [2.0 扩展 API 迁移说明](docs/API_MIGRATION.md)：1.x 到 2.0 的扩展接口变更对照表与 AST 编辑契约。
- [支持矩阵与用例台账](docs/SUPPORT_MATRIX.md)：全部受支持的转换特性与回归测试用例映射。
- [异步降级设计说明](docs/ASYNC_LOWERING.md)：`ES6` 下 `async`/`await`、异步生成器与 `for await...of` 的降级模型与边界。
- [发行验收说明](docs/RELEASE_ACCEPTANCE.md)：源码构建、离线验证与性能预算测试指南。
- [第三方声明](THIRD_PARTY_NOTICES.md)：测试参考语法来源与许可说明。
