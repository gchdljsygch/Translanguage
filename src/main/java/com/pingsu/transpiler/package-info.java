/**
 * Translanguage：TypeScript 到 JavaScript 的转换库。
 *
 * <p><strong>定位声明：本项目的唯一交付形态是 Java 库（library），不是可直接运行的工具。</strong>
 * 它不包含 {@code main} 入口、命令行参数解析、启动脚本或安装分发包；调用方负责读取 TypeScript
 * 源码、决定输出位置并写回结果。</p>
 *
 * <p>典型用法：创建一个可复用的 {@link com.pingsu.transpiler.Transpiler}，把源码字符串交给
 * {@link com.pingsu.transpiler.Transpiler#transpile(String, String)}，再检查
 * {@link com.pingsu.transpiler.TranspileResult#diagnostics()}：</p>
 *
 * <pre>{@code
 * Transpiler transpiler = new TypeScriptTranspiler();
 * TranspileResult result = transpiler.transpile("example.ts", "const value: number = 42;");
 * if (result.successful()) {
 *     write(result.javaScript());
 * } else {
 *     result.diagnostics().forEach(System.err::println);
 * }
 * }</pre>
 *
 * <p>需要限定输出版本时，使用 {@code new TypeScriptTranspiler(JavaScriptTarget.ES6)}
 * 或 {@code new TypeScriptTranspiler(JavaScriptTarget.ES2021)}。默认
 * {@link com.pingsu.transpiler.JavaScriptTarget#PRESERVE} 保留原来的运行时语法与源码空白。
 * 显式目标重新打印 JavaScript，保留 ES 模块，按需内联转换辅助函数；不需要 Node.js。
 * {@link com.pingsu.transpiler.JavaScriptEmitter} 可用于扩展输出策略。
 * 目标版本控制语法，不为业务代码自动提供运行时 API polyfill。</p>
 *
 * <p>转换过程不做类型检查、跨文件解析或模块打包。需要额外语法变换时，可实现
 * {@link com.pingsu.transpiler.TransformationPass}，并通过
 * {@link com.pingsu.transpiler.TypeScriptTranspiler#TypeScriptTranspiler(java.util.List)}
 * 注入自定义阶段。解析树与 token 通过 {@link com.pingsu.transpiler.TransformationContext}
 * 暴露，公共扩展接口使用本项目的不可变语法视图与 UTF-16 半开区间。
 * 生产前端和后端均使用自有 Java 实现，全部目标（含 ES6 异步）仅需 JDK 17+ 与主 JAR，
 * 生产编译/运行类路径及发布元数据无第三方依赖。发行验收覆盖独立断网环境。</p>
 */
package com.pingsu.transpiler;
