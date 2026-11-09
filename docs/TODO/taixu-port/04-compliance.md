---
For_Agent: https://github.com/AAswordman/Operit
---

# 太墟移植 · 许可与构建影响评估（04）

> 范围：只回答"把 GPL-3.0 的太墟源码并进 LGPL-3.0 的 Operit fork"这件事的**法律后果、合规动作、构建代价**，并给出需要用户拍板的清单。
> 本任务只写这一个文件：不改 `.kt`/`.xml`/`.gradle`，不执行编译/构建。
> 免责：这是工程侧风险评估，不是法律意见。第 6 节列出的点建议在正式对外分发前由律师过一遍；本文给出的所有"必须"均按 FSF 许可证文本的通行解释推导，并标注了不确定处。

## 0. 结论先行

1. **只要复制太墟的代码，整个仓库（含 APK 组合作品）就必须以 GPL-3.0-only 分发，不可能维持 LGPL-3.0。** LGPLv3 §2(b)/§4 与 GPLv3 §5/§10 的合力是单向道：LGPL 代码可以升到 GPL，GPL 代码不能降到 LGPL。而且现有 LGPL 声明分散在 3 处（[LICENSE](LICENSE)、[README.zh-CN.md](README.zh-CN.md#L228)、[strings.xml](app/src/main/res/values/strings.xml#L1173) 的用户协议文案），不改就会形成"文件说 LGPL、实际是 GPL"的自相矛盾——这是比换 LICENSE 更硬的合规点。
2. **只移植"设计思想/数据结构/交互语义"、不复制表达，则完全不需要改许可，可以维持 LGPL-3.0-only。** 边界不在"像不像"，而在是否触碰受版权保护的表达：源码、注释、文档原文、测试表、字符串文案、提示词常量，以及"命名+目录+类结构+文档一起照抄"这种整体选择与编排。风险最高的一类恰恰是本项目正在生产的 `docs/TODO/taixu-port/*` 系列——**"读他们的文档并转述"是复制表达，不是思想**。
3. **构建代价不是主要矛盾，版本冲突才是。** 体积增量约 +6~9 MB（仅 harness 路径）/ +18~24 MB（含 runtime 依赖），相对当前 347 MB 的 debug APK 是 2%~7%；真正会炸的是 okhttp 4.12→5.5、Kotlin 2.2→2.4、AGP 8.13→9.4、kapt→KSP、ObjectBox+Room 双 ORM、navigation-compose 2.7 vs navigation3 1.2 这一组**同仓共存**的升级，代价按"整包并入"算会翻倍。

---

## 1. 许可证事实基线

本文所有推论建立在下面这张实测表上（命令见附录 A）。

| 项 | Operit fork（本仓库） | 太墟（`/storage/emulated/0/inmyfeelings/inmyfeelings/taixu-main`） |
|---|---|---|
| 许可证文件 | [LICENSE](LICENSE)：LGPL v3 全文 165 行，无附加条款 | `LICENSE`：GPL v3 全文 676 行，首行 `Copyright (C) 2026 王克斌` |
| 声明的 SPDX | LGPL-3.0-only（[README.zh-CN.md](README.zh-CN.md#L228) 明确写 only，非 or-later） | 仓库 README **未写任何许可证段落**，仅靠 LICENSE 文件；无 NOTICE、无逐文件头 |
| 仓库内 LICENSE 之外的声明 | 用户协议文案 [strings.xml:1173](app/src/main/res/values/strings.xml#L1173) 引用了根 LICENSE | 无（Kotlin/XML 中 `grep GPL` 零命中，About 页不展示 GPL 声明） |
| 代码规模 | `app/src/main` 1384 个 .kt，454,735 行；单 `:app` + 7 个本地模块 | 2185 个 .kt，297,276 行；**38 个 Gradle 模块**（明细见 §4.2） |
| 依赖/工具链 | AGP 8.13.2 / Kotlin 2.2.21 / compileSdk 36 / minSdk 26 / Java 17 / kapt | AGP 9.4.1 / Kotlin 2.4.21 / compileSdk 34 / minSdk 29 / Java 17 / KSP |

**两条关键旁证（决定这不是理论推演）：**

- 太墟的 `taixu-main/settings.gradle.kts`（第 120~127 行的库注释与 include）里自己写着：`:showerclient`「源自 Operit，LGPL-3.0，**并入后按 GPL-3.0 分发**」，`:termux-terminal-emulator`「源自 termux-app，GPL-3.0，独立成模块以保持来源边界清晰」。也就是说**同一个动作太墟已经做过一次**（把 LGPL 的 Operit 代码并入 GPL 项目并整体按 GPL 分发）。若我们反向把太墟代码搬回来，等于接收"我们自己代码的 GPL 版本"——这部分应当直接从 Operit 自己的历史里取（仍是 LGPL），而不是从太墟取。
- 太墟自身**没有**履行 GPLv3 §5 的若干形式义务（无 NOTICE、无修改声明、UI 无版权声明）。这不减轻我们的义务：合规责任在分发者身上，与上游做没做无关。

---

## 2. 场景 A：直接复制太墟代码

### 2.1 法律结果

**结论：组合后的整体必须以 GPL-3.0-only 分发；"仓库主体仍是 LGPL-3.0"这句话从此不成立。**

推导链（可交给律师复核的部分已标注）：

1. 一个 Android APK 里，太墟的类与 Operit 的类在**同一进程、同一类加载器、同一 dex 集合**里互相调用 → 属于 GPLv3 意义上的 "work based on the Program"（GPLv3 §5 的传导机制）。这不是"mere aggregation"（GPLv3 §5 末段），因为两者不是独立程序并行分发，而是编译进同一个产物、共享内存对象与生命周期。
2. 因此 GPLv3 的全部条款约束**整个 APK 与其对应源码**，包括 Operit 原有部分。
3. Operit 这一侧的 LGPLv3 不会阻止这件事：LGPLv3 §2(b) 允许把修改版按 GPLv3 分发（去掉 LGPL 的附加许可），§4 允许把 "Combined Work" 按自选条款分发但必须同时满足 GPL 侧条款。**方向是单向的**：LGPL → GPL 允许，GPL → LGPL 无任何条款支持。
4. 两个项目都是裸许可证文本、没有 "or any later version" 声明 → 固化为 **GPL-3.0-only**，不能用 GPL-3.0-or-later 做缓冲。
5. ⚠️ 不确定项：`§4 Combined Work` 的"按自选条款分发"在字面上留了余地（理论上可以"整体仍标 LGPL、GPL 部分单独满足 GPL"）。但 GPLv3 §10 禁止对 GPL 代码施加额外限制，实务上不会被接受为"整体仍 LGPL"。**本项目的工程结论按最保守的一侧执行：整体 GPL-3.0-only。**

### 2.2 必须做的事（合规清单，按落地顺序）

| # | 动作 | 落点（本仓库） | 强制等级 |
|---|---|---|---|
| 1 | 根 `LICENSE` 换成 GPL-3.0 全文；把原 LGPL-3.0 文本另存为 `LICENSE.LGPL-3.0`（历史代码仍受其约束） | `LICENSE` | GPLv3 §4/§5 |
| 2 | 新建 `NOTICE`/`THIRD-PARTY-NOTICES.md`：列出太墟（Copyright (C) 2026 王克斌, GPL-3.0-only）、本 fork、以及仓库内 GPL/其它许可的第三方（termux fork 等） | 仓库根 | GPLv3 §5(b) |
| 3 | 每个来自太墟的文件保留原版权头；若原本没有版权头，**新增**一行来源说明（`Derived from Taixu, Copyright (C) 2026 王克斌, GPL-3.0-only`） | 新增目录（如 `app/src/main/java/.../taixu/**`） | GPLv3 §5(b) |
| 4 | 修改声明 + 日期："本文件修改自 …，修改日期 YYYY-MM-DD"（本 fork 每次改动都要更新日期） | 同上 + `CHANGELOG`/`docs` | GPLv3 §5(a) |
| 5 | 分发 APK 时提供 Corresponding Source：包含构建脚本、Gradle wrapper、非系统库、安装所需脚本；给**精确 commit/tag** 与获取方式 | CI artifact 说明、About 页、Release notes | GPLv3 §6 |
| 6 | 同步改掉所有"LGPL"字样：README（中/英）、用户协议文案 [strings.xml:1173](app/src/main/res/values/strings.xml#L1173)、[OpenSourceLicenses.kt](app/src/main/java/com/ai/assistance/operit/ui/features/about/screens/OpenSourceLicenses.kt)、About 页描述 | 见左 | 一致性（否则形成虚假许可声明） |
| 7 | About 页展示 "Appropriate Legal Notices"（版权、无担保、许可证名与获取方式） | [AboutScreen.kt](app/src/main/java/com/ai/assistance/operit/ui/features/about/screens/AboutScreen.kt) | GPLv3 §5(d)，见下注 |
| 8 | 不得加"额外限制"：不做使用范围限制、不禁止再分发、不因分发收取许可费 | 用户协议文案 | GPLv3 §10 |
| 9 | 保留专利授权；若涉及反规避（DRM/校验封锁），不得借此限制修改版运行 | 运行时逻辑 | GPLv3 §11 / §3 |
| 10 | 不再发布任何"LGPL 版"给下游做闭源链接 | 对外沟通 | — |

> 注（7）：GPLv3 §5(d) 有一条例外——"若原程序就没有展示 Appropriate Legal Notices，你的作品无须新增"。太墟 About 页确实没有 GPL 声明（附录 A 已核），所以严格说这条可以不援引。但依赖这个例外会让 fork 承担举证成本，且对下游不友好；**建议照做**，成本只是 About 页加一个区块。

### 2.3 连带后果（比改 LICENSE 更贵）

- **永久丧失与上游互操作的一半**：可以继续吸收 AAswordman/Operit 的 LGPL 提交（LGPL→GPL 允许），但**永远无法把自己的改动回推上游**（除非作者本人同意把整个 fork 的贡献按 LGPL 接收，等于他接收 GPL 代码）。对"fork 要跟上游长期合并"这一现状，这是结构性损伤。
- **下游预期被打破**：若有人把 Operit 当 LGPL 库嵌进闭源产品、或依赖"LGPL 可动态链接"的合规叙事，他们会立刻违规。这属于 [AGENTS.md](AGENTS.md) 所说的破坏 userspace——必须由用户拍板，不能由执行者默认。
- **对外分发风险**：CI 产物是 347 MB APK，从 GitHub Actions artifact 分发；每次分发都对应一次源码义务。artifacts 默认有保留期（本仓库靠 artifact 交付），**源码义务不因 artifact 过期而消失**，必须保证 tag + 仓库公开可访问。
- **GPLv3 §6 Installation Information 的争议**：APK 装在手机上（User Product）时，是否触发"提供安装信息"义务在业界有争议（多数中文 Android 项目不处理）。实际可执行的最低标准：**保证用户能自行签名安装修改版**（不引入安装期完整性校验、不锁死升级路径）。这条写进 §6 让用户知情。
- **App 内"开源许可声明"页面** [OpenSourceLicenses.kt](app/src/main/java/com/ai/assistance/operit/ui/features/about/screens/OpenSourceLicenses.kt) 目前是手写列表（`OpenSourceLibrary(name, description, license, website)`），**移植后必须新增太墟条目并改正自身许可**，否则该页本身就是错误陈述。

### 2.4 唯一能保住 LGPL 的隔离方式

理论上存在一条路：**进程级隔离 + 独立分发**——太墟部分做成独立 APK/独立进程，通过 AIDL/Intent/IPC 通信，两边各自是独立程序（GPLv3 承认 "mere aggregation"）。

- 在**单个 APK 内**（哪怕拆成独立 Gradle 模块、独立 dex、独立 ClassLoader）都**不构成**独立程序：同一进程共享内存与类型系统，实务上按组合作品处理。"独立模块只是让来源边界清晰，不能阻断许可传染"——这正是太墟对 `showerclient` 的做法，他们也没指望模块隔离能保住 LGPL，而是直接整体 GPL。
- 而把 AI 输出链路拆成独立进程，等于推翻本次移植的全部动机（harness 需要与 UI/工具/存储同址）。**所以在单体 APK 路线下，"复制代码 + 保住 LGPL"无解。**

---

## 3. 场景 B：只移植设计/数据结构（不改代码文本）

### 3.1 结论

**不需要改许可，可以维持 LGPL-3.0-only。** 著作权保护的是"表达"，不是"思想/原理/算法/数据结构/交互流程"（中国《著作权法》与《计算机软件保护条例》第 6 条、美国 §102(b)、GPL 也只作用于"the Program"这一具体表达）。把"设计"用另一套代码重新实现，不产生衍生作品。

### 3.2 边界表（可照做 / 禁止）

| 可以（思想层） | 禁止（表达层） | 说明 |
|---|---|---|
| 数据流走向、状态机、重试/中断/压缩语义 | 具体函数体、表达式、控制流细节的逐行对应 | "同样的语义"允许；"同一段代码换个名字"不允许 |
| 数据结构**字段语义**（如"payload 带版本号 + fail-closed 解码"） | `data class` 的完整字段清单+命名+默认值+序列化注解一起照抄 | 字段集合的"选择与编排"可构成汇编性表达 |
| 公开 API 的**形状概念**（`suspend fun run()` 这种通用形态） | 类名/包名/文件名/目录层级原样映射 | 命名体系+目录结构是最容易被认定为相似的证据 |
| 错误分类学的**分类维度** | 错误码常量表、错误文案字符串原样 | 常量表与文案是明确的表达 |
| 协议/格式规范（JSON 字段含义、事件顺序） | 把对方的文档段落翻译后放进本仓库 docs | 见 3.3 |
| 由自己实测得到的行为结论 | 对方的测试用例表（输入→期望输出） | 测试集是表达，尤其"全套照搬" |

### 3.3 本项目的具体红线（最容易踩的三处）

1. **`docs/TODO/taixu-port/01~04` 系列**。太墟 `docs/` 下有 43 篇 md（例如 168 行的 `DSH_BORROWING_ANALYSIS.md`、`CONCESSIONS_LEDGER.md`），这些是受版权保护的文字作品。**"读文档 → 用自己的话重述"是允许的；"摘句、改写、按同样顺序搬运"则是复制/改编表达。** 具体要求：本系列文档只写"我们自己的结论与可执行清单"，不逐段复刻对方文档结构，不摘引长句，引用不超过必要的短语并注明来源。
2. **系统提示词/工具描述/常量串**。太墟里 `SystemPromptBuilder`、工具描述、审批文案都属于表达且体量大。**照搬文案等于照搬代码。** 要移植必须自己重写措辞（并接受语义差异）。
3. **命名与目录的同构映射**。`top.wkbin.taixu.harness.*` → `com.ai.assistance.operit.harness.*` 这种"整包同名"在纠纷中会被当作复制证据，而且会让后续代码审查难以区分来源。建议新代码用 Operit 自己的命名习惯（本项目是 `ui/features/*`、`core/*` 扁平结构），并保留一份"设计来源说明"文档（不附对方代码）。

### 3.4 干净室纪律（把"只是想法"变成可举证的事实）

- 分工可以是：一人只读行为/接口并写"行为规格"（不含代码、不含命名），另一人只按规格实现；本仓库目前是 AI 协作，最低要做到**在提交信息里写明"独立实现，参考行为不参考源码"**，并在文档里记录"看了什么、没看什么"。
- 保留时间线：规格文档的提交早于实现的提交，是抗"抄袭"主张的最实用证据。
- 不把太墟源码放进本仓库的任何目录（包括 `docs/.Meta`、`.bak`、测试夹具）。**一旦代码进仓库，前面的所有纪律都作废。**

---

## 4. 构建影响

### 4.1 现状基线（Operit，实测/已文档化）

| 指标 | 值 | 出处 |
|---|---|---|
| 模块 | `:app`（单体）+ `:dragonbones` `:terminal` `:mnn` `:llama` `:mmd` `:fbx` `:showerclient` `:quickjs` | `settings.gradle.kts` |
| app 代码 | 1384 个 .kt，454,735 行；`implementation(...)` 117 条 | 实测 |
| 构建配置 | AGP 8.13.2、Kotlin 2.2.21、compileSdk 36、minSdk 26、targetSdk 34、Java 17、**kapt**（Room 2.8.4 + ObjectBox 5.3.0） | [gradle/libs.versions.toml](gradle/libs.versions.toml)、[app/build.gradle.kts:361](app/build.gradle.kts#L361) |
| 混淆 | **release / nightly / debug / clone 全部 `isMinifyEnabled = false`** | [app/build.gradle.kts:421-462](app/build.gradle.kts#L421-L462) |
| 产物 | debug APK ≈ **347 MB**（artifact zip ≈ 338 MB） | [docs/54-CI构建与安装.md](docs/54-CI构建与安装.md#L66-L67) |
| CI | 单次 20~40 分钟，job timeout 240 分钟 | 同上 #L63-L65 |

> `isMinifyEnabled = false` 是本节最重要的一条：**依赖字节不会被 R8 裁掉**，体积增量≈ jar 原大小，且 release 也不缩。

### 4.2 太墟基线

| 指标 | 值 |
|---|---|
| 模块 | **38 个**（`:harness`/`:harness:core`、`:core:*`×6、`:feature:*`×12、`:runtime`、`:runtime:browser`、`:plugin:*`×4、`:app`、`:tools`、`:project-template`、`:baselineprofile`、`:showerclient`、`:termux-terminal-emulator` …） |
| 代码 | 2185 个 .kt / 297,276 行（含测试）。分层：`feature/` 628 文件 110,679 行；`harness/src/main` **343 文件 45,951 行**；`runtime/` 276 文件 39,343 行；`core/` 355 文件 29,218 行；`feature/chat` 91 文件 19,802 行 |
| 构建 | AGP 9.4.1、Kotlin 2.4.21、KSP 2.3.11、compileSdk 34、minSdk 29、targetSdk 34、Room 2.8.5（KSP）、Koin 4.2.2、Compose BOM 2026.09.00、material3 **1.5.0-beta01**、navigation3 1.2.0 |
| 结构特征 | DI 全走 Koin（`core/common`、`core/database`、`core/datastore`、`core/network`、`core/security`、`runtime`、`tools`、`project-template`、`harness` 都 `implementation(libs.koin.core)`）；本地 Gradle convention plugin（`build-logic`）；本地 `repo/` maven 放第三方 AAR |

### 4.3 新依赖清单（相对 Operit 现有依赖的**净增量**）

| 依赖 | 版本 | 引入位置（太墟） | Operit 现状 | 体积估算* |
|---|---|---|---|---|
| Koin（core / android / compose-viewmodel / workmanager） | 4.2.2 | 9+ 个模块，`harness` 与 `:app` 必带 | **完全没有** | 0.8~1.2 MB |
| JGit | 6.10.1.2025… | `harness`（`api(libs.jgit)`）、`feature/git` | 无 | 2.4~2.8 MB（+JavaEWAH 35 KB/slf4j 70 KB） |
| Rhino（codemode 脚本引擎） | 1.9.1 | `harness` | 无（Operit 有自己的 JS 方案 quickjs） | 1.4~1.7 MB |
| ktor-server-core + ktor-server-cio | 3.6.0 | `harness`（本地 server） | 只有 ktor client 3.2.3 | 1.0~1.4 MB |
| okhttp | **5.5.0** | 全仓 | 4.12.0 | 版本替换，体积≈0 但 API 破坏 |
| ktor-client | 3.6.0 | 全仓 | 3.2.3 | 同上 |
| jsch + bcprov-jdk18on | 2.28.0 / 1.84 | `runtime`（SSH/PTY） | 无 | jsch 0.3 MB + **BC 约 8 MB** |
| zstd-jni(AAR) / xz | 1.5.7-23 / 1.12 | `runtime` | 无 | 1.5~2 MB（arm64 so）+ 0.12 MB |
| WorkManager | **2.12.0** | `:app` | 2.9.0（已存在） | 版本升级 |
| kadb / hiddenapibypass | 2.1.4 / 6.2 | `runtime` | 无 | 小（数百 KB） |
| navigation3 + miuix-navigation3-ui | 1.2.0 / 0.9.3 | `feature/*`、`:app` | navigation-compose 2.7.7 | 仅在移植 UI 时出现 |
| material3 1.5.0-beta01 / Compose BOM 2026.09.00 | — | 全仓 | BOM 2026.02.01 | 版本升级 |
| a2ui / media3 / mlkit-translate / asm 9.11 / coil3 | — | `feature/a2uipoc` 等 | 部分不同代 | 仅整包并入时出现 |

*体积为按上游发布物通用大小的**估算**，本任务禁止构建，未实测；实测应在 CI 里对 `assembleDebug` 前后跑 `apkanalyzer files list`/`du`（见 §6 决策项）。

### 4.4 真正的风险：同仓版本共存

| 冲突 | 具体后果 | 处理成本 |
|---|---|---|
| okhttp 4.12 → 5.5 | Operit 用 `okhttp-sse` 与 `mcp-sdk`；5.x 有 API/行为变更，SSE 与拦截器链要回归 | 中高，影响所有 AI 网络链路 |
| Kotlin 2.2.21 → 2.4.21 + AGP 8.13.2 → 9.4.1 | 不是"能compile"问题，是 Gradle 缓存键全变、CI 冷启动变长、convention 插件与 `build-logic` 重复 | 高；**不建议为了移植升级 AGP** |
| kapt vs KSP | Operit 用 kapt（Room+ObjectBox），太墟全 KSP；混用会叠加注解处理时间 | 中 |
| ObjectBox vs Room | Operit 主库是 ObjectBox，太墟是 Room（harness 表 `payloadVersion` 已到 v81）；移植必然变成**双 ORM 并行** | 中高，还有数据库迁移治理 |
| navigation-compose 2.7 vs navigation3 | 只影响 UI 移植；但导航体系不同意味着聊天页要大改 | 高（若移植 UI） |
| minSdk 26 vs 29 | 太墟代码可能直接用 API 29+；**升 minSdk 会砍掉现有用户**，必须逐点 guards | 中，且是 userspace 决策 |
| compileSdk 36 vs 34 | 太墟 conventional 34；移植代码按 34 写不会炸，但新 API 用法需核对 | 低 |

### 4.5 体积 / CI 时间估算（**估算，非实测**）

两条移植路径分开算：

| 场景 | 体积增量（debug APK 347 MB 基数） | CI 时间增量（现状 20~40 min） | 依据 |
|---|---|---|---|
| **仅 harness 子集**（Koin+JGit+Rhino+ktor-server，约 6~9 MB；代码 3k~8k 行） | +1.7%~2.6% | +2~6 min（新增 1~3 模块 + 增量编译）；全量构建 +2~5 min | 依赖 jar 大小 + 编译行数线性外推 |
| **harness + runtime 依赖**（再加 BC/jsch/zstd/xz，约 +18~24 MB） | +5%~7% | +3~8 min | 同上，BC 是主要体积项 |
| **整包并入 38 模块**（含 UI 栈） | +30~60 MB（含 UI/媒体/A2UI，粗估） | +8~15 min；**首次因 AGP/Kotlin 升级导致缓存全失效 +10~25 min** | 模块配置开销 + 版本升级 |

- 方法数/多 dex：minSdk 26 → 天然 multidex，不构成阻断；D8 合并时间会涨但非瓶颈。
- **真正的瓶颈仍是 native 栈**（ffmpeg/MNN/terminal/llama），它才是 347 MB 与 20~40 min 的主因，移植 Java/Kotlin 代码相对占比很小。**不要把"体积/时间"当成否决移植的主要理由——主要理由是许可与版本冲突。**
- CI 侧需要新增的只有：`pr-check.yml`/`android-build.yml` 的 paths 过滤加新目录（否则改了新代码不触发构建）、以及 R8 keep 规则（虽然当前 minify=false，但 keep 规则是为将来打开 minify 留的；JGit 用反射、Rhino 需 keep）。

### 4.6 模块边界建议（无论选哪条路都成立）

- **新增独立 Gradle 模块**（例如 `:taixu-harness`）只解决"来源可追溯/可替换"，**不阻断 GPL 传染**（§2.4）。不要用它来做"许可隔离"的自我安慰。
- 若走场景 B（重写），模块边界仍有价值：把"AI 输出链路"的接口先抽出来（这本是 03 文档的活），让新实现可被替换、可被单测，从而避免把重写绑死在 Compose/UI 上。
- 不建议把 38 个模块的目录结构搬进来：Operit 是单体 `:app` + 少量本地库，38 模块会带来配置期开销与 CI 缓存治理成本，且与《[AGENTS.md](AGENTS.md)》的构建约束（只能走 GitHub Actions）冲突——配置变复杂意味着本地无法自查，只能靠 CI 试错。

---

## 5. 三选项成本对比

人日为**量级估算**（1 人日 = 一个熟练协作者/AI 协作一轮的等效投入），用于比较而不是排期。

| | **a) 直接复制太墟代码** | **b) 只照搬设计，自己重写** | **c) 不移植，只改 Operit** |
|---|---|---|---|
| 许可结果 | 仓库整体 **GPL-3.0-only**，永久 | 维持 **LGPL-3.0-only** | 维持 **LGPL-3.0-only** |
| 一次性工程 | 15~30 人日（模块/DI/导航/DB 适配 + okhttp/Kotlin 版本升级回归）| 25~45 人日（先写行为规格，再重写核心链路 3k~8k 行 + 测试）| 3~8 人日（补现有链路的缺口：中断语义、增量渲染、上下文压缩策略）|
| 合规工作量 | 2~4 人日（LICENSE/NOTICE/头注释/About/协议文案/源码义务流程），且**持续**：每次分发都要对齐源码 tag | 0.5 人日（只需在文档里记录"独立实现"）| 0 |
| 体积/CI | +6~24 MB / +2~8 min（视并入范围），另有版本升级的冷启动代价 | +2~6 MB（只带真需要的库）/ +1~4 min | 0 |
| 与上游合并能力 | **永久单向**：能收上游，不能回推 | 保持双向 | 保持双向 |
| 功能达成 | 最高（含大量边界语义） | 中高（取决于重写覆盖度；设计可完整照搬） | 低（只补最痛的点） |
| 回滚难度 | **不可回滚**（许可一旦分发无法收回）| 可回滚（删掉自己写的代码）| 可回滚 |
| 主要风险 | 许可事故 + 版本冲突 + 长期双轨维护 | 工期 + 重写引入新 bug（但有测试兜底） | 体验差距仍在 |

**判断**：a 的"省事"是假象——真正的工程量集中在"让它能在 Operit 的依赖版本下跑起来"，而许可代价是不可逆的；b 的工程量最大但把风险放在可回滚、可分期的一侧；c 是保底。

### 附：第四条路（不在题目三选项内，但性价比最高）

**联系太墟作者（Copyright (C) 2026 王克斌，GitHub `wkbin/taixu`）取得双许可/再许可例外**：即允许把指定文件以 LGPL-3.0 或 Apache-2.0 使用。成本 ≈ 0.5 人日发起、结果不确定（对方可能拒绝或要求署名/资金）。若接受，则 a 路径的许可代价归零，b 的部分工作也可省。**建议先花这半天，再决定 a/b/c。**

---

## 6. 需要用户拍板的清单

按"必须先有答案"排序，每项给出可选答案与推荐。

1. **许可取向**：接受整个 fork 变 GPL-3.0-only，还是不惜代价维持 LGPL-3.0？
   - 推荐默认：若这个 fork 还要长期跟上游合并、还要分发给别人 → **维持 LGPL**（即不走 a）。
2. **是否先尝试向太墟作者申请再许可**（第四条路）？同意的话，a/b 的边界会大幅移动。
3. **对外分发范围**：仅自用（不分发 → GPL 源码义务不触发）还是公开发布 APK？这直接决定 §2.2 的清单是"必须"还是"建议"。
4. **minSdk 是否允许从 26 升到 29**（太墟基线 29）？这会砍掉现有低版本用户，属于 userspace 破坏。
5. **是否允许升级 AGP/Kotlin（8.13/2.2 → 9.4/2.4）**？这会把 CI 冷启动与 Gradle 缓存全部作废；推荐"不升级，把太墟代码适配到现有版本"。
6. **数据库策略**：接受 ObjectBox + Room 双 ORM 并行，还是要求移植部分复用 ObjectBox/现有 Room 实例？
7. **模块边界**：新增独立 `:taixu-harness` 模块（可追溯）还是直接并入 `:app`？前者不阻断 GPL，只是工程卫生。
8. **是否移植 UI 层**：只移植输出链路（harness）还是连 feature/chat 的 Compose UI 一起？后者会牵出 navigation3/material3-beta 升级，成本翻倍。
9. **文档纪律**：确认 `docs/TODO/taixu-port/*` 只写"我们自己的结论"，不转述/摘抄太墟文档；如需要引用，单独在文末给出出处与本仓库的引用政策。
10. **About/协议文案改动授权**：若走 a，需要改 [README.zh-CN.md](README.zh-CN.md#L228)、[README.md](README.md)、[strings.xml:1173](app/src/main/res/values/strings.xml#L1173)、[OpenSourceLicenses.kt](app/src/main/java/com/ai/assistance/operit/ui/features/about/screens/OpenSourceLicenses.kt) 的许可叙述——这是用户可见承诺，需明确授权。
11. **GPLv3 §6 立场**：确认"允许用户自行重签安装修改版"（不放完整性校验/不封锁侧载），以满足 Installation Information 的最低标准。
12. **体积/时间预算**：是否接受 debug APK +6~24 MB、CI +2~8 min；是否需要在 CI 里加一条体积门禁。

---

## 附录 A：核查命令（可复现）

```bash
# 许可证事实
head -20 LICENSE ; wc -l LICENSE
head -3 /storage/emulated/0/inmyfeelings/inmyfeelings/taixu-main/LICENSE
grep -n "LGPL" README.zh-CN.md            # 228 行：LGPL-3.0-only
grep -n "LGPL" app/src/main/res/values/strings.xml   # 1173 行：用户协议引用根 LICENSE

# 太墟的许可态度（单向道的实证）
grep -n "LGPL\|GPL" /storage/emulated/0/inmyfeelings/inmyfeelings/taixu-main/settings.gradle.kts
grep -rn "GPL" /storage/emulated/0/inmyfeelings/inmyfeelings/taixu-main --include='*.kt' --include='*.xml' | grep -v /build/   # 零命中：UI 未展示 GPL 声明

# 规模
find app/src/main -name '*.kt' | wc -l ; find app/src/main -name '*.kt' -exec cat {} + | wc -l
cd /storage/emulated/0/inmyfeelings/inmyfeelings/taixu-main && grep -c 'include(' settings.gradle.kts
find . -name '*.kt' -not -path '*/build/*' -exec cat {} + | wc -l

# 依赖差异
grep -nE 'koin|jgit|rhino|work-runtime|okhttp|ktor' gradle/libs.versions.toml
grep -nE 'isMinifyEnabled|isShrinkResources' app/build.gradle.kts   # 全 false
```

## 附录 B：本文与其它交付物的关系

- 本文只做"许可 + 构建"判断；**类型映射、分期落地、每阶段要改哪些文件**属于 03（`03-mapping.md`），01/02 分别负责两侧链路剖析。
- 本文不含任何代码改动，也不涉及实现方案；§6 的答案确定后，a/b/c 的取舍应回写到本目录的 `index.md`（由汇总方维护），并按《[docs/TODO/README.md](../README.md)》的约定分步推进。
