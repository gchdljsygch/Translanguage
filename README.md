# Translanguage

**Translanguage** 是一个零运行时依赖的 Java 库，用于将 TypeScript 源码字符串转换为 JavaScript。

> **定位声明**：本项目**仅以 Java 库（library）的形式提供**，供其他 JVM 程序（如构建插件、服务端应用、脚手架或编辑器扩展）嵌入调用。生产 JAR 不包含 `main` 入口、命令行参数解析、启动脚本或分发安装包；源码读取、文件写入与路径管理等 I/O 均由调用方负责。

## 核心特性

- **零第三方依赖**：编译与运行仅需 **JDK 17+** 标准库（`java.base`）与单个主 JAR，无需 Node.js、`tsc`、ANTLR 或 Closure Compiler。
- **纯内存字符串转换**：接收 `(sourceName, source)` 字符串，返回生成的 JavaScript 文本与结构化诊断信息，不触碰文件系统或外部进程。
- **多目标语法输出**：支持保留原运行时语法（`PRESERVE`），或按目标环境降级输出为 `ES2021` / `ES6`，或将同步及异步语法降级为 `ES5`（ES6 含 `async`/`await`、异步生成器与公开类字段降级）。
- **并发安全与可扩展**：`Transpiler` 实例可跨线程安全复用；内置不可变语法树（AST）与源码区间编辑接口，支持注入自定义 `TransformationPass` 与 `JavaScriptEmitter`。

---

## 环境要求与引入方式

- **运行环境**：默认主 JAR 为 JDK 17+；显式选择 `java8` 副 JAR 可用于 JDK 8+。构建工具仍运行于 JDK 17/25。
- **模块名**：`com.pingsu.transpiler`（`Automatic-Module-Name`）
- **Maven 坐标**：`com.pingsu:Translanguage:2.0.3-SNAPSHOT`（后续开发版本，尚未发布）

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
    implementation 'com.pingsu:Translanguage:2.0.3-SNAPSHOT'
}
```

### 2. 使用构建目录仓库或单 JAR 引入

执行 `.\gradlew.bat publish` 会将 POM、主 JAR、sources JAR 和 javadoc JAR 发布到 `build/repo`，可直接作为本地 Maven 仓库引用：

```groovy
repositories {
    maven { url = uri('path/to/Translanguage/build/repo') }
}

dependencies {
    implementation 'com.pingsu:Translanguage:2.0.3-SNAPSHOT'
}
```

也可以直接执行 `.\gradlew.bat jar`，将生成的 `build/libs/Translanguage-2.0.3-SNAPSHOT.jar` 加入消费方类路径（无任何传递依赖）。

---

## 可选 Java 8 副制品

主 JAR 保留 Java 17 字节码与 record API。执行 `.\gradlew.bat java8Jar` 可独立生成完整、零运行依赖的 `-java8.jar`；执行 `.\gradlew.bat build -PwithJava8=true` 会同时构建并验证两种制品与各自源码/文档。

Maven 消费方显式指定 `<classifier>java8</classifier>`。Gradle 消费方使用：

```groovy
implementation('com.pingsu:Translanguage:2.0.3-SNAPSHOT') {
    capabilities { requireCapability('com.pingsu:Translanguage-java8') }
}
```

副版本的 record 改为具有相同组件访问器的不可变 final 类。请针对选定制品编译，确保 classpath 中只出现其中一个。默认发布不带副制品，发布者需显式开启 `-PwithJava8=true`。版本选择、JDK 8 编译样例、同步维护与完整验收见 [Java 8 支持](docs/JAVA8_SUPPORT.md)。

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

### 指定 ECMAScript 目标版本（`ES5` / `ES6` / `ES2021`）

当运行环境对 ECMAScript 语法版本有限制时，可在构造 `TypeScriptTranspiler` 时指定 `JavaScriptTarget`：

```java
import com.pingsu.transpiler.JavaScriptTarget;
import com.pingsu.transpiler.TranspileResult;
import com.pingsu.transpiler.Transpiler;
import com.pingsu.transpiler.TypeScriptTranspiler;

Transpiler es5Transpiler = new TypeScriptTranspiler(JavaScriptTarget.ES5);
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

## TSX / JSX 与装饰器

以 ".tsx" 或 ".jsx" 结尾的 `sourceName` 启用 JSX 解析（后缀不区分大小写）。默认将 JSX 转为 `React.createElement`，片段使用 `React.Fragment`；支持组件、成员组件、自定义元素、嵌套片段、表达式、属性/子元素展开、实体解码和 TSX 类型参数。工厂及组件作为运行时引用参与 import elision。普通 `.ts` 仍支持 `<T>value` 类型断言，TSX 泛型箭头应写为 `<T,>(x: T) => x`。

默认支持现代装饰器的 `(value, context)` 协议，包括类、方法、getter/setter、公开字段、替换返回值、`addInitializer`、`context.access`，以及宿主定义 `Symbol.metadata` 时的共享元数据。legacy TypeScript 装饰器必须显式选择：

~~~java
Transpiler transpiler = new TypeScriptTranspiler(
        JavaScriptTarget.ES2021, DecoratorMode.LEGACY);
TranspileResult result = transpiler.transpile("component.tsx", source);
~~~

LEGACY 支持类、属性、方法、访问器和参数装饰器，包括构造函数参数属性；按 TS 的求值与反向应用顺序执行。两种模式均转换为普通 JavaScript，并接入 PRESERVE / ES5 / ES6 / ES2021 输出。React 等业务 runtime 由调用方提供，生成代码不自动导入它们。

自定义 JSX 工厂时，将 JSX pass 放在类型擦除前、装饰器 pass 放在其他语法转换后：

~~~java
Transpiler custom = new TypeScriptTranspiler(JavaScriptTarget.ES6, List.of(
        new JsxLoweringPass("h", "Fragment"),
        new TypeErasurePass(), new EnumLoweringPass(), new NamespaceLoweringPass(),
        new DecoratorLoweringPass(DecoratorMode.STANDARD)));
~~~

自定义 pass 列表仍只执行调用方指定的阶段。装饰器 pass 会物化前序编辑并替换整个编译文本；基于原始节点区间的自定义编辑应放在它之前。完整语义、边界和测试见 [JSX 与装饰器支持](docs/JSX_DECORATORS.md)。

## 私有字段与 namespace

支持 `#name` 实例/静态私有字段、类型注解、读写与自增、函数值调用、可选访问和 `#name in object`。`PRESERVE` 保留原生语法；`ES2021` / `ES6` / `ES5` 按目标降级，保留品牌校验、初始化顺序和私有存储。降级使用宿主 `WeakMap` 等内置对象，不注入 polyfill。

支持运行时 `namespace` / 内部 `module`，包含嵌套与点分名称、重复声明合并、可变导出、类/函数/枚举合并，以及作用域感知的导入擦除。纯类型和环境 namespace 被擦除。完整范围、自定义 pass 顺序及现有 ES5 边界见 [私有字段与 namespace](docs/PRIVATE_FIELDS_NAMESPACES.md)。

## 输出目标版本对比

| 目标枚举 | 输出语法行为 | 适用场景 |
| --- | --- | --- |
| `JavaScriptTarget.PRESERVE`（默认） | 擦除 TS 类型、省略无运行时引用的导入并转换枚举/参数属性；保留其余运行时 JS 语法与未修改区域的空白、换行和注释。 | 后续接入现代打包器/运行时，或希望尽量保留原始代码排版。 |
| `JavaScriptTarget.ES2021` | 重新打印代码，确保语法不超过 ECMAScript 2021；原生保留 `async`/`await`、可选链、空值合并、BigInt、动态 `import()` 和 `import.meta`，同时降级公开类字段并移除数字分隔符。 | 现代浏览器或 Node.js 环境，需兼容尚未支持 ES2022 类字段的引擎。 |
| `JavaScriptTarget.ES5` | 输出 ES5 **script** 语法；降级块级绑定、箭头函数、默认/剩余参数、类及公开字段、绑定解构、模板字符串、计算属性、展开、`for...of` 和可选链等表达式。支持 async/await、同步/异步生成器、带标签模板、静态块、BigInt 字面量、资源管理和 auto-accessor；ES 模块仍返回 `JS_TARGET`。 | 需要 ES5 语法的脚本或显式 CommonJS 输入；详细支持范围和运行时要求见 [ES5 支持](docs/ES5_SUPPORT.md)。 |
| `JavaScriptTarget.ES6` | 重新打印代码，确保语法不超过 ECMAScript 2015（ES6）；自动降级 `async`/`await`、异步生成器、`for await...of`、可选链 `?.`、空值合并 `??`、逻辑赋值、幂运算 `**`、对象展开/剩余、可选 `catch` 绑定、数字分隔符、`export * as ns` 以及公开类字段和资源管理。 | 仅支持 ES6 语法的运行时环境。 |

### 显式目标（`ES6` / `ES2021`）行为说明

- **自包含辅助函数**：`ES6` 异步控制流、类字段定义与对象剩余解构所需的辅助函数（helper）会按需去重内联到输出代码中，生成代码无需引入额外 runtime 包。对象剩余解构仅在需要时生成数组迭代适配器和赋值结果回溯表。
- **精简且可读的输出**：安全的实例字段直接在构造函数中定义，保留原生 `class`；需要独立初始化作用域的字段继续使用语义完整的降级路径，并省略未使用的 Symbol 和初始化变量。最终代码按语法树组织换行、两空格缩进与运算符间距，保持现有换行语义和模板原文。具体边界与体积对比见 [显式目标输出优化](docs/OUTPUT_QUALITY.md)。
- **保留 ES 模块**：经 import elision 保留的 `import` / `export` 仍使用标准 ES 模块语法，不打包、不解析也不改写模块路径；`export * as ns from 'mod'` 在 `ES6` 下会降级为命名空间导入加命名导出，保留实时绑定（live binding）。
- **类字段语义**：实例和静态公开类字段通过 `Object.defineProperty` 降级，保留自有属性语义（含无初始值字段）；构造函数参数属性保持 `this.prop = prop;` 赋值语义。
- **仅降级语法，不注入业务 Polyfill**：目标版本约束的是**语法**，不会为业务代码自动注入 `Promise.any`、`Array.prototype.includes` 等标准库 API 的 polyfill。运行 `ES6` 降级产物需要宿主具备 ES6 标准内置对象（`Promise`、`Symbol`、`Reflect`、`Proxy`、`WeakMap`）。
- **语法复核**：显式目标在生成代码后会重新解析并校验最终 AST，确保不会将残留高版本语法的产物标记为成功。

---

### ES5 脚本目标

```java
Transpiler transpiler = new TypeScriptTranspiler(JavaScriptTarget.ES5);
TranspileResult result = transpiler.transpile("greet.ts",
        "const greet = (name: string) => `Hello ${name}`; console.log(greet('ES5'));");
```

ES5 产物经过重新解析及目标校验，主 JAR 和 Java 8 副 JAR 提供相同能力。ES5 支持将 `import x = require("m")` 与 `export = value` 转为 CommonJS，并擦除 `export as namespace`、`declare global`。普通 ES 模块不会隐式改写；包括类型导入擦除后为维持模块身份生成的 `export {};`，都会返回诊断。BigInt 使用宿主原生 `BigInt`，异步操作需要宿主 `Promise`，不会注入通用 polyfill。完整边界见 [ES5 支持](docs/ES5_SUPPORT.md)。

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
- `JS_TARGET`：显式目标（`ES5` / `ES6` / `ES2021`）不支持的语法特性（如 `ES6` 下的 BigInt、动态 `import()`、`import.meta`、高版本正则特性，或顶层 `await`）。`JS_TARGET` 的行列坐标基于类型擦除后的中间 JavaScript。

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
- **Auto-accessor**：支持未装饰的公开/私有实例及静态 `accessor`，生成私有存储与原型 getter/setter，保留品牌校验、计算名称、继承及初始化顺序。
- **显式资源管理**：显式目标降级 `using` / `await using`，按逆序释放资源，覆盖返回、异常、循环退出和生成器关闭，并保留 suppressed error 链。
- **CommonJS 与环境声明**：`import x = require("m")`、顶层内部实体别名和 `export = value` 转换为变量绑定/`require`/`module.exports`；类型专用及未使用导入被擦除。`export as namespace` 与 `declare global` 没有运行时代码。
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
   - JSX 自动 runtime（`react-jsx` / `react-jsxdev`）；当前支持经典工厂模式
   - 私有方法的显式目标降级、私有成员装饰器、装饰器 auto-accessor（`accessor`）、`emitDecoratorMetadata` 类型反射元数据；现代模式不允许参数装饰器
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
- [ES5 支持](docs/ES5_SUPPORT.md)：ES5 脚本目标、转换语义、运行时要求与诊断边界。
- [异步降级设计说明](docs/ASYNC_LOWERING.md)：`ES6` 下 `async`/`await`、异步生成器与 `for await...of` 的降级模型与边界。
- [发行验收说明](docs/RELEASE_ACCEPTANCE.md)：源码构建、离线验证与性能预算测试指南。
- [第三方声明](THIRD_PARTY_NOTICES.md)：测试参考语法来源与许可说明。
