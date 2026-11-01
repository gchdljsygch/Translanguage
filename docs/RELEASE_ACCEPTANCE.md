# 2.0.0 发行验收

本次发行范围是本地 Maven 仓库中的 `com.pingsu:Translanguage:2.0.0` 及主、源码、Javadoc 三个 JAR，不包含向公共仓库上传或创建远程 Git release。消费方只需 JDK 17+ 与主 JAR；构建及测试工具继续保留。

## 可重复门槛

```powershell
# 开发机准备；下载的 OS、JDK、QEMU 仅供测试，不进入制品。
.\scripts\download-offline-tools.ps1 -ToolsDirectory "$env:TEMP\Translanguage P8 tools"
.\gradlew.bat jar
.\scripts\verify-offline-guest.ps1 `
  -ToolsDirectory "$env:TEMP\Translanguage P8 tools" `
  -Jar build\libs\Translanguage-2.0.0.jar `
  -OutputLog "$env:TEMP\Translanguage P8 tools\guest.log"
.\gradlew.bat clean verifyRelease `
  "-PofflineGuestLog=$env:TEMP\Translanguage P8 tools\guest.log" `
  "-PofflineGuestManifest=$env:TEMP\Translanguage P8 tools\guest-manifest.json"
```

Windows 脚本使用 PowerShell 7、Python 3 和 7-Zip；这些都是**宿主测试设施**。下载器使用固定 SHA-256，不执行安装程序。Alpine netboot URL 是上游滚动路径，若内容变更会明确校验失败，须取得同摘要归档或重新审核并更新输入，不能静默换版本。镜像准备只读归档并生成 CPIO，不向宿主系统解包 Linux 路径。生产库不依赖这些脚本或工具。

`verifyRelease` 包括完整 build/check、两个 JDK 的性能验收、实际本地发布、独立 POM/module 消费以及离线产物的 Node/Acorn 验证。未提供成功的 guest 日志与 manifest、JAR 或消费源码摘要不匹配、JS 语法/运行结果不符均失败。修改生产代码或消费测试后需要重新生成并运行 guest。

`check` 和普通 Maven 发布仍强制执行 P7 审计及全部 JUnit/独立 JVM 检查；最终 P8 OS 验收通过 `verifyRelease` 单独执行。发布是本地验收流水线的中间步骤；不能把单独 `publish` 成功解释为全部 P8 条件通过。

## 无工具、无缓存、操作系统断网

QEMU 使用 `-nodefaults -no-user-config -nic none -display none -monitor none`，无磁盘、无共享目录、无网络设备。Linux 内核和一次性 RAM 根目录只含：BusyBox shell、musl/zlib/libgcc/libstdc++ 等 JDK 所需 OS 库、两个完整 JDK、主 JAR、消费源码。没有 Node/npm、Gradle、Maven、包管理器和依赖缓存；不会挂载开发机文件系统。镜像逐文件内容与输入摘要记录于 `guest-manifest.json`。

仅启用回环接口以供 JDK 枚举；guest 检查无其他网卡，连接 TEST-NET 地址得到网络不可达。编译/运行使用 `env -i`、空 HOME 和明确 classpath，在 `/work/中文 空格` 下分别用 JDK 17、25 的 javac 编译并执行。同一 guest 中运行完整 StandaloneConsumer 与 OfflineAcceptance，覆盖三目标、异步、扩展、错误契约、重复调用。

QEMU TCG 下 Linux JDK 25 的一次 JIT 运行发生 JVM 原生崩溃；原始日志保留于 preflight。最终隔离消费两套 JDK 统一使用 `-Xint`。这只改变 JVM 执行模式，未调整库或用例；性能门槛在宿主原生 JVM 正常 JIT 下单独测量，不用虚拟机解释执行成绩代替性能验证。

guest 用串口返回 8 份实际转换产物，之后由宿主测试域的 Acorn 按 PRESERVE/ES6/ES2021 对应语法检查，并由 Node 验证标准输出。扩展追加异步源码也包含在这 8 份产物中。主 JAR 摘要、guest 日志和测试报告相互绑定。

## 发布消费

`verifyPublishedConsumers` 使用已写入 `build/repo` 的正式坐标，分别强制 `mavenPom(); ignoreGradleMetadataRedirection()` 和 `gradleMetadata()`。两个模式均在 JDK 17/25 的仓库外中文/空格路径、全新 Gradle user home 下，以 `--offline` 编译并运行公开消费示例。

四个消费工程的 compile/runtime 图均必须只有本库一个制品，且 SHA-256 等于当前主 JAR。POM 模式由 Gradle 的 Maven POM 解析器消费，不宣称安装或运行了 Maven CLI。Maven local 发布另通过 `-Dmaven.repo.local=<隔离临时目录>` 验证，不写入用户日常 `.m2` 仓库。

## 固定语法与兼容边界

[规则映射](GRAMMAR_RULE_MAPPING.md) 将 P0 的 156 条规则全部关联到固定输入和三目标预期；另外登记后续新增的 `lastFormalParameterArg`、`arrayElement`、`argument`。`rules.tsv` 与参考 parser 的实际访问记录逐项比对。未被参考解析树访问的规则明确分为别名/替代路径或不支持语法拒绝，144 条有实际访问记录，13 条使用替代路径、2 条为拒绝映射；不能当作 159 条实际访问，也不宣称覆盖 grammar 的所有替代分支。

40 组固定输入产生 120 个三目标契约测试；另有规则台账一致性、命名空间导出实时绑定/名称碰撞测试。原始 45 个执行用例、68 份冻结记录和前阶段回归全部继续执行。`export * as ns from` 在 ES6 转为安全命名的 namespace import + export，保留模块身份及实时绑定；最终校验拒绝未降级的 namespace re-export。

ES6 对象展开的 Object.assign 策略继续保留，新增 `spread-boundary` 固定输入分别记录 setter 与 `__proto__` 的目标差异，禁止以“与原生完全一致”掩盖这一契约。动态 eval/with 的拒绝、函数/迭代器反射、业务模块及 polyfill 边界沿用 README/API 迁移说明。

## 性能预算

P0 没有保存可比较的耗时基线；本阶段首次固定最终发行预算，不倒填历史耗时，也不声称相对 1.x 的性能提升百分比。`PerformanceAcceptance` 为开发设施，仅主 JAR 在独立 JVM 中测量；不进入任何发布制品。

| 项目 | 固定门槛 |
| --- | --- |
| 工作负载 | 每单元包含类型、对象剩余/展开、空值、幂、字段、async/await/finally；1、32、128 单元，约 0.2、6.5、26 KiB |
| 分阶段 | 自有前端；从预先擦除的 JS 开始的后端；完整转换；全部目标 |
| 采样 | 每组合预热 2 次，实测 3 次；报告中位数、最大值、输入摘要、输入/输出字节、heap pool 峰值 |
| 延迟 | 小/中/大每次最多 5/10/30 秒，任一样本超限即失败 |
| 内存 | 每个性能进程固定 `-Xmx256m`；记录 heap pool 峰值，禁止 OOM |
| 输出增长 | 不超过 `40 × 输入 UTF-16 长度 + 32768` 字节 |
| 深层输入 | 20,000 层括号与泛型分别在 5 秒内诊断失败，无部分输出 |
| 主 JAR | 不超过 2 MiB |

预算用于本项目固定负载的发行防退化，不代表任意 TypeScript 输入的延迟 SLA。报告绑定相同正式 JAR；后续应按同一机器/输入摘要比较中位数，不用改变阈值掩盖性能回归。
