# 改用完整 Chromium 内核：可行性核查（2026-11-13）

结论：**在当前这台电脑和当前条件下，无法完成向完整 Chromium 的迁移。** 本轮没有下载 Chromium 源码，也没有开始构建；没有改成系统 WebView；当前代码仍然是 GeckoView。

## 1. 已核实的事实

| 事实 | 出处 |
|---|---|
| Chromium 没有面向第三方 App 的 Android 嵌入 SDK。可以自己嵌入 `//content`，但版本之间不保证 API 稳定，也没有现成的 AAR 构建目标；只能 fork Chromium、作为 embedder 用 GN 构建，或者自己用 `dist_aar` 模板导出。Content Shell 只是测试工具。无论哪种方式，跟进安全更新都是长期的大工作量。 | Chromium-dev 邮件列表，2026-01-14，WebView 维护者的回复：https://groups.google.com/a/chromium.org/g/Chromium-dev/c/7oa3OoEt-7w |
| 原来唯一的官方嵌入方案 WebLayer 已在 M108 左右弃用，之后从 Chromium 源码树中移除（当前主干的 `weblayer/README.md` 已返回 404）。 | https://chromium.googlesource.com/chromium/src/+/c272c84deea1cd690509bdec786f03998fb54386 |
| 官方 Android 构建要求：x86-64 Linux，至少 8 GB 内存，**强烈建议 16 GB 以上**，**至少 100 GB 可用磁盘**。完整浏览器的构建目标是 `chrome_public_apk`；仅拉取源码就要 30 分钟到数小时。 | https://chromium.googlesource.com/chromium/src/+/main/docs/android_build_instructions.md |
| 链接阶段失败通常是内存耗尽。官方构建说明里另有一处：用 tmpfs 做完整 debug 构建需要约 20 GB 内存加 swap。 | https://chromium.googlesource.com/chromium/src/+/main/docs/linux/build_instructions.md |
| CEF 不支持 Android。 | 同上邮件列表回复 |

## 2. 本机条件（实测）

| 项目 | 本机 | Chromium 要求 |
|---|---|---|
| CPU | 8 核 x86_64 | 能用；但 8 核本地完整构建，预计需要**很多小时**（官方文档没有给出耗时估计；本机也没有配置远程构建服务 `use_remoteexec`） |
| 内存 | 共 16 GB，**可用约 2.5 GB**（其余被本机其它进程占用），**无 swap** | 最低 8 GB，建议 16 GB 以上可用 ✗ |
| 磁盘 | 可用 98 GB | 至少 100 GB ✗ |

可用内存和磁盘都低于官方要求。按照“不要无限下载或构建”的约束，没有尝试拉取源码。

## 3. 可行的选择（请协调者 / 用户决定）

| 方案 | 内核 | 广告屏蔽 | 许可 | 安全更新 | 代价与风险 |
|---|---|---|---|---|---|
| A. 基于现成的 Chromium 分支（例如 Cromite），在它的 Java UI 层上改造成银耳 / Arc 风格 | 完整 Chromium | Cromite 自带内置广告屏蔽（本轮未核实具体实现机制和规则列表） | GPL-3.0（Cromite 自述）。银耳浏览器整体需要按 GPL-3.0 开源（仓库本来就是公开的） | 跟随上游 Cromite 的版本 rebase 再构建 | 需要一台能构建 Chromium 的机器（建议 32 GB 以上内存、200 GB 以上 SSD，或者大内存的自托管 CI runner）。界面要用 Chromium 的 Android Java UI 重写，**现有的 Compose 界面和 GeckoView 代码都不能沿用** |
| B. 自己 fork Chromium（`chrome_public_apk` 或 `//content` embedder），广告屏蔽接入 Brave 的 adblock-rust（MPL-2.0，支持网络拦截、外观过滤和 EasyList 语法） | 完整 Chromium | adblock-rust，或者 Chromium 自带的 subresource_filter 配合规则列表 | BSD（Chromium）+ MPL-2.0 | 每个 Chromium 稳定版都要自行 rebase、构建、发布 | 工作量最大；构建机要求同方案 A |
| C. 保持 GeckoView，只改界面 | Gecko（不是 Chromium） | 继续使用 uBlock Origin | 不变 | 跟随 GeckoView 发版 | 不满足“改用 Chromium”的要求，只能作为过渡 |
| D. 系统 WebView | 系统 WebView（不是完整 Chromium） | 只能拦截请求，效果打折扣 | — | 跟随系统 | **用户已经明确拒绝**，本轮没有采用 |

另外，GitHub 免费托管的 runner 磁盘和内存都不足以构建 Chromium。方案 A 或 B 的正式发布需要自托管 runner，或者付费的大型 runner；签名 secrets 的约定可以保持不变。

## 4. 换内核后的用户数据

包名 `io.yiner.browser` 和签名都保持不变，所以可以覆盖安装，0.2.0 的应用内更新链路也还能用。但 Gecko 的配置目录与 Chromium 的用户数据格式不同，**以下数据无法直接迁移**：

- Cookie 和登录状态（包括 Google 登录）：需要重新登录；
- 网站存储（localStorage、IndexedDB）和网站权限：会丢失；
- uBlock Origin 的自定义规则和信任站点：不会自动带过去；
- 打开的标签：GeckoView 的 SessionState 格式不通用，只能按 URL 恢复，前进/后退历史会丢失。

银耳浏览器自己保存的数据，可以写迁移代码保留：历史记录和下载列表（SQLite）、设置（SharedPreferences）、已归档标签的 URL 和标题。

## 5. 2026-11-13 复查：现有 GitHub 资源能否构建 Chromium

| 检查项 | 结果 | 出处 |
|---|---|---|
| 仓库自托管 runner | 0 个 | `gh api repos/yinerx/yiner-browser/actions/runners` |
| 账户类型 | 个人账户（User）。larger runners 只对组织的 GitHub Team / Enterprise Cloud 开放 | `gh api users/yinerx`；https://docs.github.com/en/actions/reference/runners/github-hosted-runners |
| 公开仓库标准 Linux runner（文档） | 4 核、16 GB 内存、14 GB SSD（文档承诺的值） | 同上文档 |
| 公开仓库标准 Linux runner（**实测**） | 4 核、15 GB 内存；根分区 145 GB，**可用 86 GB** | CI 运行 43540582061 的 “Report runner resources” 步骤（2026-11-14） |
| 单个作业时长上限 | 6 小时 | https://docs.github.com/en/actions/reference/limits |

结论（按实测值修正）：
- **磁盘**：实测可用 86 GB，略低于官方要求的 100 GB。删除 runner 预装的工具链通常能再腾出几十 GB，但本轮**没有验证**能腾出多少。
- **CPU 与内存**：4 核、15 GB。官方建议 16 GB 以上，链接阶段可能因内存不足失败。
- **时长**：在 4 核上做完整构建，很可能超过单个作业 6 小时的上限。理论上可以把构建拆成多个作业，并跨作业保存编译缓存来接力，但这种方式**本轮没有验证**，下载约 30 GB 以上的源码也需要很长时间。
- 因此本轮**仍然无法**确认可以在现有免费资源上完成完整 Chromium 内核的构建。按要求，没有购买或开通任何付费资源，也没有启动大规模下载或构建。

下一步可以做的、可逆的验证（需协调者同意后执行）：
- 在一个独立的手动触发工作流中，只做“清理磁盘 + 拉取 Chromium 源码（`--no-history`）+ 生成构建配置”，记录磁盘占用和耗时；
- 不构建、不发布，用来实测“多作业接力”这条路是否可行。

推进所需的额外资源（二选一）：
- 一台自托管 runner 或构建机：建议 16 核以上、32 GB 以上内存、250 GB 以上 SSD，注册到本仓库；
- 或者用组织账户开通 larger runners（付费）。

在此之前，界面改造与内核解耦，先在 GeckoView 上交付。新的界面组件不依赖 GeckoView，迁移内核时可以保留：
- `ui/arc/*`
- `ui/ScreenContents.kt`
- `ui/TabSwitcher.kt` 中的 `TabSwitcherContent`

## 6. 2026-11-14 决定：改用基于 Chromium 的系统 WebView

用户决定不再等待完整 Chromium 的构建资源，改用 Android 系统 WebView。需要说明：
- 它是**基于 Chromium 的系统组件**，不是完整的 Chromium 浏览器，不能称为“完整 Chromium”；
- 内核随系统 / Google Play 更新，本应用不再分发浏览器引擎。

与 GeckoView 方案相比：
- 不支持扩展（uBlock Origin 已移除），广告拦截改为请求拦截 + 规则子集；
- Google 依据其 OAuth 政策，可能拒绝在 WebView 中登录；
- 无痕隔离依赖 WebView 的 `MULTI_PROFILE`，较旧的 WebView 不支持。

实现细节和边界见 README 的“关于 Google 登录”“广告与跟踪屏蔽”“无痕模式”“从 0.2.0（GeckoView）升级”。
