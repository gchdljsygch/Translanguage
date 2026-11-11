# 能力和用例台账

状态：P2–P8 已完成，正式版本 `2.0.0`。全部目标（含 ES6 异步）使用自有后端，生产及发布依赖闭包为空；无工具、无缓存、无网卡的 JDK 17/25 消费验收通过。

原始输入、断言、退出状态和输出在 [P0 证据目录](baseline/2026-11-01/) 中固定；`case-ids.json` 是机器可读 ID 映射。原有 35 个方法展开为 45 个 JUnit 执行用例，均未删除。`mixed` 表示一个方法内包含多个目标/扩展分支。

| case ID | 原始目标 | 关联测试 | 基线 |
| --- | --- | --- | --- |
| TS-001 | PRESERVE | TranspilerTest.erasesTypesAndPreservesExpressions | 通过 |
| TS-002 | PRESERVE | TranspilerTest.genericArrowsAssertionsAndNonNull | 通过 |
| TS-003 | PRESERVE | TranspilerTest.classesAndParameterProperties | 通过 |
| TS-004 | PRESERVE | TranspilerTest.enumsAndConstEnums | 通过 |
| TS-005 | PRESERVE | TranspilerTest.overloadsAmbientAndThisParameters | 通过 |
| TS-006 | PRESERVE | TranspilerTest.explicitTypeOnlyImportsDoNotLoadModules | 通过 |
| TS-007 | PRESERVE | TranspilerTest.mixedImportsAndExportsKeepRuntimeBindings | 通过 |
| TS-008 | PRESERVE | TranspilerTest.preservesPlainJavaScriptAndTrivia | 通过 |
| TS-009 | PRESERVE | TranspilerTest.nestedTemplatesAndControlFlow | 通过 |
| TS-010 | PRESERVE | TranspilerTest.lineTerminatorsInsideCommentsPreserveReturnSemantics | 通过 |
| TS-011 | PRESERVE | TranspilerTest.nestedTemplateBlocksKeepBraceState | 通过 |
| TS-012 | PRESERVE | TranspilerTest.derivedParameterPropertiesRunAfterSuper | 通过 |
| TS-013 | PRESERVE | TranspilerTest.typedObjectMethodsAndAnonymousFunctions | 通过 |
| TS-014 | PRESERVE | TranspilerTest.comparisonsAndOptionalChainsRemainJavaScript | 通过 |
| TS-015 | PRESERVE | TranspilerTest.reportsSyntaxAndLexerLocationsWithoutOutput | 通过 |
| TS-016 | PRESERVE | TranspilerTest.unsupportedRuntimeFeaturesFailExplicitly | 通过 |
| TS-017 | PRESERVE | TranspilerTest.erasingAmbientNamespaceDoesNotCreateAnExternalModule | 通过 |
| TS-018 | PRESERVE | TranspilerTest.exportsKeepRuntimeDeclarations | 通过 |
| TS-019 | PRESERVE | TranspilerTest.asyncDestructuringRestAndExceptionTypes | 通过 |
| TS-020 | PRESERVE | TranspilerTest.reusableTranspilerDoesNotLeakState | 通过 |
| JT-001/ES6 | ES6 | JavaScriptTargetTest.modernExpressionsPreserveEvaluationOrderAndMethodReceiver | 通过 |
| JT-001/ES2021 | ES2021 | JavaScriptTargetTest.modernExpressionsPreserveEvaluationOrderAndMethodReceiver | 通过 |
| JT-002/ES6 | ES6 | JavaScriptTargetTest.asyncFunctionsCarryTheirOwnHelpers | 通过 |
| JT-002/ES2021 | ES2021 | JavaScriptTargetTest.asyncFunctionsCarryTheirOwnHelpers | 通过 |
| JT-003/ES6 | ES6 | JavaScriptTargetTest.classFieldsDefineOwnPropertiesAndPreserveInitializationOrder | 通过 |
| JT-003/ES2021 | ES2021 | JavaScriptTargetTest.classFieldsDefineOwnPropertiesAndPreserveInitializationOrder | 通过 |
| JT-004/ES6 | ES6 | JavaScriptTargetTest.computedAndExpressionClassesKeepKeysAndLexicalValues | 通过 |
| JT-004/ES2021 | ES2021 | JavaScriptTargetTest.computedAndExpressionClassesKeepKeysAndLexicalValues | 通过 |
| JT-005/ES6 | ES6 | JavaScriptTargetTest.preservesModulesAndDoesNotResolveDependencies | 通过 |
| JT-005/ES2021 | ES2021 | JavaScriptTargetTest.preservesModulesAndDoesNotResolveDependencies | 通过 |
| JT-006/ES6 | ES6 | JavaScriptTargetTest.spreadRestAndOptionalCatchBindings | 通过 |
| JT-006/ES2021 | ES2021 | JavaScriptTargetTest.spreadRestAndOptionalCatchBindings | 通过 |
| JT-007/ES6 | ES6 | JavaScriptTargetTest.accessorsStaticSuperAndConstructorShadowingKeepSemantics | 通过 |
| JT-007/ES2021 | ES2021 | JavaScriptTargetTest.accessorsStaticSuperAndConstructorShadowingKeepSemantics | 通过 |
| JT-008/ES6 | ES6 | JavaScriptTargetTest.asyncIterationKeepsCleanupAndResults | 通过 |
| JT-008/ES2021 | ES2021 | JavaScriptTargetTest.asyncIterationKeepsCleanupAndResults | 通过 |
| JT-009/ES6 | ES6 | JavaScriptTargetTest.defaultExportAndAnonymousClassNamesRemainStable | 通过 |
| JT-009/ES2021 | ES2021 | JavaScriptTargetTest.defaultExportAndAnonymousClassNamesRemainStable | 通过 |
| JT-010 | mixed | JavaScriptTargetTest.es2021RetainsSupportedSyntax | 通过 |
| JT-011 | mixed | JavaScriptTargetTest.incompatibleSyntaxFailsInsteadOfLeakingIntoAnOlderTarget | 通过 |
| JT-012/ES6 | ES6 | JavaScriptTargetTest.topLevelAwaitAndUnsafeDerivedInitializationFailExplicitly | 通过 |
| JT-012/ES2021 | ES2021 | JavaScriptTargetTest.topLevelAwaitAndUnsafeDerivedInitializationFailExplicitly | 通过 |
| JT-013 | mixed | JavaScriptTargetTest.defaultsAndExtensionsRemainUsable | 通过 |
| JT-014 | mixed | JavaScriptTargetTest.repeatedAndConcurrentCompilationsDoNotShareState | 通过 |
| EX-001 | PRESERVE | ExampleTest.transpilesBundledExampleThroughThePublicApi | 通过 |


## 能力与验证

前端、TS 转换、ES2021 与全部已支持的 ES6 降级已由自有实现覆盖。P0 遗留的逐规则固定映射与最终性能预算在 P8 补齐。

| 能力 | 既有 case ID | 新基础验证 | 自有实现剩余工作 |
| --- | --- | --- | --- |
| 词法、原文、Unicode、模板 | TS-008/009/010/011 | SourceLexerTest、SourceLexerOracleTest、SourceModelTest | P2 已完成；生产接入自有 parser，ASI/模板/正则与 Unicode 验证通过 |
| 泛型、比较和表达式边界 | TS-002/013/014/015 | 词法 goal、checkpoint 回滚 | P2 已完成；Pratt/递归下降、有限试探、严格模式及独立 Acorn 接受/拒绝对照通过 |
| 类型擦除、重载、修饰符 | TS-001/002/003/005/013 | 自有 SyntaxNode 编辑验证 | P3 已完成；重写 TypeErasurePass 并删除 ANTLR 适配器 |
| 模块与纯类型声明 | TS-006/007/017/018、JT-005 | 冻结输入/输出差分 | P3 已完成；自有模块节点与标记维护，无路径解析或模块加载 |
| 枚举 | TS-004/016/018、JT-005 | 冻结输入/输出差分 | 支持同步计算表达式、成员引用、合并声明与作用域遮蔽；RuntimeSyntaxBoundaryTest 覆盖三目标单次求值、数字反向映射、类型擦除与异常顺序 |
| 参数属性 | TS-003/012/016、JT-003 | 冻结输入/输出差分 | 支持分支/循环/表达式/词法箭头中的 super；RuntimeSyntaxBoundaryTest 验证参数捕获、setter/字段顺序、异常重试、重复 super 与替代对象返回 |
| 可选链、空值 | JT-001、TS-014 | 原 Node receiver/顺序测试保留 | P5 已完成；引用模型、作用域临时变量、自有短路 lowering；括号调用/删除/标签 receiver 通过 |
| 逻辑赋值、幂 | JT-001/013 | 原 Node 测试保留 | P5 已完成；LHS/key 表达式单次求值，读写 key 转换及 getter/setter/异常顺序对照通过 |
| 展开与剩余 | JT-006、TS-019 | 原 Node 测试保留 | P5 已完成；Symbol、null/undefined、排除 key、Proxy 轨迹、嵌套绑定/赋值/参数/循环/catch 与迭代清理通过；展开保留 Object.assign 边界 |
| catch、数字、尾逗号 | JT-001/006 | 自有数字 lexer 与 Acorn token oracle | P5 已完成；按节点/token 处理，并在最终重解析中检查 |
| 类字段 | JT-003/004/007 | 原描述符/继承 setter/计算 key 测试保留 | DefineField、继承 setter、描述符及计算 key 转换通过；新增任意控制流 super 与构造函数参数默认值中的字段初始化验证 |
| 类名、表达式、导出 | JT-004/007/009 | 原 Node 反射与静态 super 测试保留 | P4 已完成；自有作用域、类名、默认导出、字段函数名、this/super 与反射无内部成员泄漏通过 |
| async/await | JT-002、TS-019 | 原 Node finally/receiver 测试保留 | P6 已完成；自有 Promise 驱动、参数初始化、arguments/this/super、箭头捕获、thenable 和异常/微任务顺序通过 |
| async generator/for-await | JT-008 | 原 Node break/清理测试保留 | P6 已完成；自有请求队列、yield*、IteratorClose、throw/return/rejection、标签和循环头 TDZ 通过 |
| 目标拒绝 | JT-010/011/012 | 原 Acorn 与失败空输出断言保留 | P4/P5 已完成；自有 JS AST、实际产物重解析、正则目标特性校验；直接 eval/with 的不安全 lowering 组合显式拒绝 |
| TS 不支持边界 | TS-015/016、JT-012 | lexer 非法输入/深模板限制 | P2/P3 已完成；自有语法拒绝、深度与工作预算、完整输入消费 |
| 扩展、并发、状态 | TS-020、JT-013/014 | SourceModelTest、CompilationLifecycleTest、StandaloneConsumer | P7 全目标消费在 JDK 17/25 仅主 JAR 通过，包含扩展追加异步源码 |

156 条原始 parser 规则及后续新增的 3 条规则已关联到固定输入和三目标预期，详见 [规则映射](GRAMMAR_RULE_MAPPING.md)。其中 144 条有独立参考 parser 的实际访问记录，14 条标为替代解析路径/别名，1 条映射明确拒绝输入；不将替代路径计作实际规则访问，也不宣称完整 ECMAScript/TypeScript grammar 分支覆盖。最终性能预算和首次实测基线见 [发行验收](RELEASE_ACCEPTANCE.md)。

## 新增测试与隔离检查

- `SourceModelTest`：UTF-16/码点定位、CRLF 与 Unicode 换行、BOM、边界插入、冲突、不可变节点/token、固定种子编辑、20,000 层迭代遍历。
- `CompilationLifecycleTest`：阶段顺序、重入、单次执行、诊断和异常、扩展 emitter 失败空输出。
- `SourceLexerTest`：词法分类、数值和转义正反例、模板模式栈、正则词法 goal、固定种子 trivia、深度上限、checkpoint 状态与诊断回滚。
- `SourceLexerOracleTest`：Acorn 对 JS 字符串、正则、模板、Unicode、数字和运算符给出的独立 token 边界。
- `ReferenceCompatibilityTest`：68 份冻结转换记录的三方比较，以及参考/生产类来源隔离。原 `JT-013` 自定义 Java lambda 无法由默认编译器重放，仍由原 JUnit 测试验证，未删除断言。
- `verifyExtensionApi`：JDK 17，仅主 JAR 编译完整扩展示例，运行模型分支。
- `verifyStandaloneConsumer*`：JDK 17/25，仅主 JAR 运行全部转换；全部目标及扩展现已通过，含 ES6 异步。
- `auditConsumerDependencies`：生产依赖图、所有消费变体元数据、JAR/sources/javadoc 内容、manifest、字节码/API jdeps 与生产加载/进程/网络路径扫描；P7 已通过，Javadoc 仅按明确路径允许标准文档 UI 脚本。

## P2/P3 新增验证

- `SourceParserTest`：独立类型语法、泛型/比较试探、JS 模式、ASI、严格指令/模块/类、完整输入消费、复杂度预算与 Unicode 诊断。
- `SourceParserOracleTest`：Acorn 独立接受/拒绝判定及固定种子 `0x50325033` 的 100 组表达式，覆盖运算优先级、空值混用、正则/除法、模板、严格模式、非法赋值和成员访问。
- `TypeScriptTransformTest`：P3 新产物经 Acorn 语法检查和 Node 实际执行；验证参数属性 setter/字段顺序/super 异常、混合模块、复杂类型擦除、枚举与 Unicode trivia。
- `verifyPreserveConsumer*`：JDK 17/25、仅主 JAR 的默认转换及扩展；`auditFrontendIsolation`：生产源码/依赖/JAR/sources 无 ANTLR，已接入 check。
- 249 个用例在 JDK 17/25 的干净完整构建通过；证据见 [P2/P3 验证目录](verification/2026-11-01-p2-p3/)。

## P4/P5 新增验证

- `OwnJavaScriptBackendTest`：46 个用例，以原生 Node 结果和事件顺序对照生成产物，并由 Acorn 按 2015/2021 解析。覆盖字段、计算 key、匿名/默认/字段类名、静态 super、getter/setter、可选链 receiver、参数环境、生成器参数初始化、对象剩余与迭代器关闭、冻结迭代结果、Unicode 命名冲突。
- 固定种子 `0x50345035` 的 60 个表达式、结构打印往返、实际产物超目标拒绝、动态作用域组合拒绝。
- `ReferenceCompatibilityTest` 保留参考后端对 68 份冻结记录的逐字检查；PRESERVE 继续逐字相等，显式目标改为 Acorn 语法与可运行夹具的 Node 输出对照；未安装业务模块夹具继续检查路径保留。JS_TARGET 位置采用自有中间源码位置，TS 诊断继续精确位置对照。
- `verifyRegularConsumer*` 在 JDK 17/25 仅主 JAR 验证全部 P4/P5 路径（含 ES2021 原生异步），已接入 check。最终消费任务另包含 ES6 异步，现已通过 P6/P7 门槛。

## P6/P7 新增验证

- `OwnAsyncBackendTest`：64 组命名语义输入分别通过 ES6/ES2021 的原生 Node 输出/事件轨迹对照和 Acorn 语法检查；另有 helper 确定性/去重和 eval 拒绝测试，共 130 项。
- 参数拒绝、调用时参数求值、函数体环境、箭头与派生构造器 this、super 读写/调用/模板/解构、finally、同步/异步委托、微任务、队列 receiver、未启动/完成状态、12,000 排队请求、循环头 TDZ 捕获与嵌套标签。
- JDK 17/25 完整 clean build 共 425 项，0 失败、0 错误、0 跳过；原始 45 项及 68 份冻结记录保留。
- 主 JAR、源码和 Javadoc 审计、生产空依赖图、POM/module 无依赖、jdeps 仅 java.base。完整消费和审计已接入 check 与所有 Maven 发布任务。
- 负向注入主 JAR JS 会阻断直接发布；未知文档 JS 和生产依赖泄漏分别被拒绝。证据见 [P6/P7 验证目录](verification/2026-11-02-p6-p7/)。

## P8 发行验证

- `ReleaseContractTest`：40 组固定输入 × 3 目标，规则清单完整性、命名空间导出实时绑定/对象身份/Unicode 名称碰撞，共 122 项；完整回归 547 项。
- `spread-boundary` 明确保留 ES6 Object.assign 的 setter、`__proto__` 差异；格式差异、JS_TARGET 定位与动态作用域兼容差异继续按原契约分类。
- `verifyPublishedConsumers`：实际本地 `2.0.0` 的 POM/module 各在 JDK 17/25、全新缓存、offline 模式编译并运行，仅解析一个主 JAR。
- `verifyPerformanceJdk17/25`：固定输入、阶段、预热、采样、输出增长、256 MiB heap、深层失败和 2 MiB 制品预算。
- `verifyOfflineArtifacts`：绑定正式主 JAR 和消费源码摘要，校验无网卡 guest 的 8 份产物；缺少隔离证据会阻断 `verifyRelease`。
- 双 JDK 干净完整构建与中文/空格路径构建通过。详细日志、清单、摘要、JUnit 和性能报告见 [P8 证据](verification/2026-11-02-p8/)。


## 构造与枚举语法边界扩展（2026-11-02）

`RuntimeSyntaxBoundaryTest` 在 PRESERVE、ES6、ES2021 三目标下，通过独立 Acorn 语法检查与 Node 执行验证以下行为：

- `super()` 成功后先初始化实例字段，再赋值参数属性；保留表达式结果、异常重试、重复调用的原生错误，以及不调用 `super()` 而直接返回对象的路径。
- 参数属性在构造函数作用域捕获，分支块和箭头参数的同名绑定不会改变赋值来源；嵌套类的构造函数拥有独立的 `super`，其 heritage 和计算属性名仍属于外层词法环境。
- 计算枚举按声明顺序运行，支持带类型的回调、嵌套类/枚举、Unicode 成员名及同作用域的合并声明。仅数字值生成反向映射；字符串或未知计算值之后的隐式递增仍会诊断失败。
- 剩余拒绝边界：参数属性与参数默认值中的 `super()` 组合、跨控制流参数属性与动态作用域组合、枚举初始化的直接 `eval`/`with` 或外层 `await`/`yield`。

P0 冻结输入和参考实现保持不变。`ReferenceCompatibilityTest` 对原先拒绝的计算枚举及分支构造输入保留旧参考断言，并对当前输出独立验证语法和运行结果。P8 的 `reject-enum` 与 `reject-derived` 固定输入更新为仍不支持的边界，避免继续把新增能力列为拒绝契约。


## Import Elision（2026-11-02）

默认 `TypeErasurePass` 按文件内运行时引用省略未使用及仅用于类型的导入；PRESERVE、ES6、ES2021 共用此分析。默认导入、命名/别名导入和命名空间导入可分别删减，显式副作用导入与空列表导入保留；仅在没有剩余静态模块声明时补充 `export {};`。

`ImportElisionTest` 使用独立 Acorn 和 Node 验证不存在的类型模块不被加载、不存在的未使用导出不被链接，以及局部遮蔽、参数默认值、计算名称、简写属性、值导出、枚举、Unicode 标识符和副作用顺序；另验证直接 eval 的保守保留、原文保持、自定义 pass 与并发隔离。普通远程重导出继续保留，不查询外部类型信息。

旧参考后端和 P0 冻结产物不修改；新增行为以独立运行验证及更新后的当前契约断言为准。

## ES5 脚本目标

`JavaScriptTarget.ES5` 使用自有后端，并由 `Es5TargetTest` 独立覆盖；原模块/异步目标测试继续明确使用 `PRESERVE`、`ES6`、`ES2021`，不会将 ES 模块误作为 ES5 脚本验收。详见 [ES5 支持](ES5_SUPPORT.md)。

| 能力 | 回归测试 |
| --- | --- |
| API、类型擦除、独立 Acorn ES5 语法验收 | `erasesTypesAndExposesTargetThroughPublicApi` |
| TDZ、const、块级遮蔽、循环闭包 | `lexicalBindingsKeepShadowingTdzAndConstWrites`、`loopClosuresKeepPerIterationEnvironmentsAndControlFlow` |
| 箭头、参数与接收者 | `arrowsCaptureThisAndArgumentsAcrossNestedFunctions`、`directivesDetachedCallsAndParameterArgumentsStayCorrect` |
| 模板、字面量、对象、展开、绑定解构 | `templatesUnicodeAndNumericLiteralsAreEs5`、`objectMethodsComputedPropertiesAndSpreadPreserveOrder`、`spreadCallsKeepReceiversAndSparseArrays`、`destructuringDefaultsRestAndComputedKeysEvaluateOnce` |
| 迭代、提前关闭、计算属性 | `forOfClosesIteratorsOnBreakAndCapturesBindings`、`boundedDestructuringClosesIteratorWithoutExhaustingIt`、`lexicalForOfHeaderKeepsTdzAndComputedRestCoercesKeysOnce` |
| 类、字段、继承 | `classesFieldsMethodsAndSimpleInheritance`、`derivedFieldsAndReturnValuesPreserveInitialization`、`fieldArrowsNativeInheritanceAndEs5OnlyBuiltins` |
| 后续版本表达式与可组合降级 | `laterExpressionsLowerAndPreserveShortCircuiting`、`optionalSpreadAndCommentsComposeWithLowering` |
| 诊断、产物复核、并发隔离 | `unsupportedFeaturesProduceLocatedDiagnosticsAndNoPartialOutput`、`validatorRejectsResidualModernSyntax`、`sharedCompilerHasIsolatedConcurrentSessions` |


## JSX / 装饰器扩展

| 能力 | 回归测试 | 范围 |
| --- | --- | --- |
| TSX/JSX、工厂、片段、属性/子元素展开、文本实体 | JsxTransformTest | 四目标执行、独立语法检查、类型/运行时导入、嵌套模板/枚举/装饰器 |
| 现代装饰器 | DecoratorTransformTest | 类/方法/访问器/字段、初始化顺序、类替换、metadata/access、计算名、导出 |
| legacy TS 装饰器 | DecoratorTransformTest | 类/属性/方法/访问器/参数、参数属性、类替换、计算名与作用域 |

详细配置和明确边界见 [JSX 与装饰器支持](JSX_DECORATORS.md)。历史 TS-016 的装饰器拒绝用例由独立执行验证替代；冻结参考输出保留不变。


## ES5 扩展语法（2026-11-10）

`Es5ExtendedSyntaxTest` 以 Acorn ES5 script 语法校验和 Node 行为对照覆盖 async/await、同步/异步生成器、委托与异常完成、带标签模板、静态块、BigInt、CommonJS、环境声明、资源管理与公开/私有 auto-accessor。生成器状态机、模板对象缓存及资源栈均由单次编译创建，辅助函数按需内联，不引入生产依赖。

`p8/reject-import-alias` 与 `p8/reject-export-equals` 保留历史 fixture ID，其预期已改为成功生成 CommonJS script；前者在绑定未使用时被擦除。具体宿主 API 要求与仍明确拒绝的输入见 [ES5 支持](ES5_SUPPORT.md)。
