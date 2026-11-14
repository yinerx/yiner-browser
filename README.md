# 银耳浏览器（Yiner Browser）0.2.0

个人自用的 Android 浏览器。交互上参考了“以搜索为入口、标签自动归档”的极简移动浏览器理念，但所有代码、名称、图标和配色均为独立实现，与任何浏览器厂商无关，也不代表任何官方产品。

- 包名：`io.yiner.browser`
- 内核：Mozilla **GeckoView 160.0**（release 通道，`org.mozilla.geckoview:geckoview-arm64-v8a:160.0.20261104181552`，MPL-2.0）
- 界面：Kotlin + Jetpack Compose（Material 3）
- 广告屏蔽：内置 **uBlock Origin 1.76.0**（AMO 官方签名包，GPL-3.0）+ Gecko 增强型跟踪保护
- 第一版不含任何 AI 功能，不调用任何模型或搜索 API

## 兼容范围

| 项目 | 范围 |
|---|---|
| Android 版本 | **Android 8.0（API 26）及以上**。targetSdk 36，compileSdk 37.2 |
| CPU 架构 | **仅 arm64-v8a**（2017 年后的主流 64 位手机）。不支持 32 位 ARM（armeabi-v7a）、x86/x86_64 设备、x86 模拟器 |
| 安装体积 | APK 约 205 MB（debug 包，未压缩优化；GeckoView 内核本身就占约 150 MB） |
| 测试状态 | **未在真机或模拟器上运行过**（构建环境没有 KVM，且 APK 只含 arm64 代码）。请按 [ACCEPTANCE.md](ACCEPTANCE.md) 在手机上验收 |

## 安装

从 [GitHub Releases](https://github.com/yinerx/yiner-browser/releases) 下载最新正式版的 `yiner-browser-<版本>.apk`，然后：

```bash
adb install -r yiner-browser-0.2.0.apk
```

也可以把 APK 拷到手机上，用文件管理器打开安装（需要允许“安装未知应用”）。之后的版本可以在应用内更新，见下文“应用内更新”。

首次启动时会在本地安装内置的 uBlock Origin，大约需要几秒钟，进度可在“设置 → 广告屏蔽”中查看。

## 界面（feature/arc-search-android-ui 测试版）

本分支按 Android 版 Arc Search 公开 beta（2024-10）的实操截图，重做了浏览器界面：
- 浏览时网页占满屏幕，底部是轻薄工具栏（标签缩略图 / 白色胶囊“+” / “^”展开菜单）；
- 上拉的页面菜单，包括地址胶囊、固定 / 查找 / 分享、文字缩放、桌面版网站、网站设置；
- 叠在网页上的搜索面板；
- 横向标签卡片；
- 分组式设置页。

尺寸和颜色都取自参考图的像素测量，依据与差异见 [docs/arc-search-android-ui-references.md](docs/arc-search-android-ui-references.md)。

需要说明的几点：
- 仍使用 **GeckoView** 内核，还没有满足“完整 Chromium 内核”的要求，原因见 [docs/chromium-engine-feasibility.md](docs/chromium-engine-feasibility.md)；
- 没有任何 AI 功能，也没有翻译功能；
- 本界面**未在真机上验证**。

离线渲染界面截图（不是设备截图）：

```bash
./gradlew testDebugUnitTest -Pyiner.screenshots=true --tests 'io.yiner.browser.ui.UiScreenshotTest'
# 输出：build/ui-screenshots/*.png
```

## 功能与实现状态

“已实现”指代码已完成并通过编译、Lint 和单元测试，**不代表已在真机上验证**。

| 功能 | 状态 | 说明 |
|---|---|---|
| 搜索/地址输入 | 已实现 | 自动区分网址和关键词；默认 Google，可选 Bing、DuckDuckGo、百度；历史匹配 + 搜索引擎联想（可关闭，无痕标签不联想） |
| 多标签与主页网格 | 已实现 | 普通标签和无痕标签分组显示；最多 4 个后台标签保留活动会话，其余释放内存，切回时从保存的状态恢复 |
| 会话恢复 | 已实现 | 普通标签的前进/后退历史（GeckoView SessionState）保存在 `files/tabs.json`，杀进程或重启后可以恢复。无痕标签从不写盘 |
| 标签自动归档 | 已实现 | 默认 1 天未使用即归档（可设 12 小时/1 天/7 天/30 天/从不）；在应用启动和回到前台时检查；已归档的标签可一键恢复（含历史栈） |
| 无痕会话 | 已实现 | 使用 GeckoView 私密模式（`usePrivateMode`）：不记录历史、不写标签文件、不显示在下载列表。Cookie 和网站数据的清除**依赖 Gecko 自身的私密浏览机制**（最后一个私密会话关闭时清除），需真机验证 |
| Google 登录 | 已实现，**不保证** | 见下文“关于 Google 登录” |
| 广告/跟踪屏蔽 | 已实现 | 见下文“广告与跟踪屏蔽” |
| 文件下载 | 已实现 | 下载前确认；前台服务保证切到后台时不中断；保存到系统“下载”目录（Android 10+ 用 MediaStore，8/9 需要存储权限）；进度通知、取消、完成后点按打开；下载列表 |
| 文件上传 | 已实现 | 系统文件选择器（单选/多选，按网页的 accept 过滤）；图片可选“拍照”；网页指定 `capture` 时直接打开相机 |
| 网页对话框 | 已实现 | alert/confirm/prompt、`<select>`（含多选和分组）、日期/时间/日期时间/月份、颜色、HTTP 认证、弹窗拦截确认、离开页面确认、重新提交表单确认、`navigator.share` |
| 网站权限 | 已实现 | 定位、摄像头/麦克风、DRM、第三方 Cookie 访问（Storage Access）、局域网访问，**每次都弹窗征得同意**；系统权限只在同意后才申请。有声自动播放默认阻止 |
| 返回手势 | 已实现 | 支持预测性返回（Android 13+）。顺序：退出全屏 → 关闭菜单/查找/搜索层/二级页面 → 网页后退 → 由其它 App 打开的标签：关闭并回到那个 App → 由网页打开的子标签：关闭并回到父标签 → 回到主页 → 系统处理（回桌面） |
| 默认浏览器 | 已实现 | 声明 http/https 浏览入口和 `APP_BROWSER` 类别；Android 10+ 通过系统角色对话框申请，失败时跳到系统“默认应用”设置；设置页显示当前是否为默认浏览器 |
| 外部链接 | 已实现 | `tel:`、`mailto:`、`intent://`、各类 App 协议都先弹窗确认；`intent://` 会清除组件名和 URI 授权标志，只能打开可浏览的 Activity；目标 App 未安装时使用 `browser_fallback_url`（仅限 http/https） |
| 其它入口 | 已实现 | 其它 App 的“分享”文字/链接、系统文字选择菜单（PROCESS_TEXT）、WEB_SEARCH |
| 页面菜单 | 已实现 | 前进、刷新/停止、分享、复制链接、页内查找、桌面版网站、对此网站关闭跟踪保护、新建（无痕）标签、关闭标签 |
| 历史记录 | 已实现 | 搜索、单条删除、清空；为已访问链接着色 |
| 清除浏览数据 | 已实现 | 历史、Cookie 与网站数据、缓存、下载列表、已归档标签 |
| 加载错误页 | 已实现 | 中文错误页；**证书/安全错误只提示，不提供“继续访问”**，不绕过 TLS 校验 |
| 深色模式 | 已实现 | 跟随系统，网页的 `prefers-color-scheme` 也跟随系统 |
| 仅 HTTPS 模式 | 已实现 | 设置中可开启，默认关闭 |
| 应用内更新 | 已实现，**未真机验证** | 只读取本仓库 GitHub 正式版 Release；自动下载、通知；安装前校验哈希、包名、versionCode 和签名；安装由系统确认。见下文“应用内更新” |

### 明确不包含

AI 搜索/总结、账号与云同步、密码管理器（请使用系统自动填充或第三方密码管理器）、uBlock Origin 以外的扩展、网页推送通知（网站请求会被拒绝）、XR、文件夹上传、下载断点续传（进程被杀后未完成的下载标记为失败）、阅读模式、书签、上架应用商店、静默安装更新（更新安装始终需要你在系统对话框中确认）。

## 关于 Google 登录

- GeckoView 是 Firefox for Android 使用的同一个内核。本应用**不修改 User-Agent**，GeckoView 160 的应用 UA 名称常量是 `Firefox`（release 通道），所以网站看到的是标准的 Firefox for Android 浏览器，而不是“内嵌 WebView”。Google 拦截内嵌 WebView 登录（`disallowed_useragent`）的规则针对的是 Android WebView，理论上不适用于这里。
- 网站上的“用 Google 登录”弹窗（`window.open`）会作为子标签打开，登录完成后由网页自行关闭，并回到原标签。
- 第三方 Cookie 采用 Firefox 默认的“全面 Cookie 保护”（按站点隔离）。少数登录嵌入页会通过 Storage Access 请求 Cookie 权限，此时会弹窗询问，选“允许”即可。
- **不保证所有 Google 登录都能成功**。Google 可能根据风控、设备、网络环境或其它信号拒绝登录或要求额外验证。通行密钥（Passkey）和 Google 账号的“手机提示”取决于 GeckoView 对 WebAuthn 的支持和设备上的 Google Play 服务，未经验证。开启“严格跟踪保护”可能影响部分登录流程，遇到问题时可在页面菜单中对该网站关闭跟踪保护，或改回标准模式。

## 广告与跟踪屏蔽

两层同时生效：

1. **Gecko 增强型跟踪保护（ETP）**：默认“标准”，拦截已知的广告、分析、社交、加密货币挖矿和指纹跟踪器（使用 Firefox 内置列表）。第三方 Cookie 按站点隔离，并自动清理跟踪 Cookie。可在设置中切到“严格”，也可在页面菜单中对单个网站关闭。
2. **uBlock Origin 1.76.0**：随 APK 打包的 `.xpi` 下载自 addons.mozilla.org（SHA256 `6f20ff6749193599548770539103069c20948dfcd3e61c3089a67da7bfc3be39`，与 AMO 公布的值一致），首次启动时通过 `WebExtensionController.install()` 从本地文件安装，并允许在无痕标签中运行。
   - 过滤规则列表由 uBlock Origin 自己联网更新
   - “设置 → uBlock Origin 设置”打开它的原版控制面板，可以管理过滤规则列表、信任站点（白名单）和自定义规则
   - “设置 → 检查 uBlock Origin 更新”通过 AMO 更新扩展本体
   - 应用只接受这一个扩展；网页触发的其它扩展安装请求一律拒绝

本版没有实现 uBlock Origin 的工具栏弹窗（按网站一键关闭）。要让某个网站不被 uBO 屏蔽，请在它的控制面板“信任站点”中添加。

## 隐私

- 应用代码本身没有任何统计、遥测或崩溃上报，也没有接入 Glean/Firebase 等 SDK。
- 会联网的功能：你访问的网站；搜索联想（发送给所选搜索引擎，可关闭）；Gecko 的安全浏览列表更新（Mozilla 代理的 Safe Browsing 服务）；uBlock Origin 的过滤规则更新；手动检查扩展更新（AMO）；检查应用更新（只请求 `api.github.com` 上本仓库的公开 Release 信息，不带任何 token 或个人信息，可在设置中关闭）。
- 应用数据不参与 Android 云备份或设备迁移（`allowBackup=false` + `dataExtractionRules` 全部排除）。

## 应用内更新

“设置 → 更新”中可以手动检查更新，也可以开关以下选项：

| 选项 | 默认 | 说明 |
|---|---|---|
| 启动时检查更新 | 开 | 打开应用时检查；距上次检查不足 12 小时则跳过，不会真正请求 GitHub |
| 定期检查更新 | 开 | WorkManager 约每天一次，在联网时后台检查（同样受 12 小时限频） |
| 自动下载新版本 | 开 | 发现新版本后自动下载，完成后发通知 |
| 仅在 Wi-Fi 下自动下载 | 开 | 安装包约 200 MB。手动点“下载”或“用移动数据下载”不受此限制 |

手动“检查更新”两次之间至少间隔 1 分钟。GitHub 未认证 API 每个 IP 每小时限 60 次；被限流时，应用会显示解除时间，并在此之前不再请求。另外使用 ETag 条件请求，Release 没有变化时 GitHub 返回 304，不消耗额度。

### 工作方式与安全校验

1. **只看本仓库的正式版**。请求 `https://api.github.com/repos/yinerx/yiner-browser/releases/latest`（公开 API，不内置也不读取任何 token）。该接口本身只返回非 draft、非 prerelease 的最新版本，应用也会再检查一遍这两个标志。
2. **只信任本仓库该次 Release 的附件**。`yiner-update.json` 和 APK 的下载地址必须严格等于 `https://github.com/yinerx/yiner-browser/releases/download/<tag>/<文件名>`：必须 HTTPS，不能带端口、用户信息或查询参数。下载时手动跟随重定向，每一跳都必须是 HTTPS，且主机只能是 `github.com`、`objects.githubusercontent.com`、`release-assets.githubusercontent.com`。应用不接受任何用户输入的 URL，也不使用元数据里的 URL。
3. **只比较 versionCode**。只有远端 `versionCode` 大于当前安装版本时才算有更新；`versionName` 仅用于显示。设置页会显示远端版本号和发布说明。
4. **下载**：在应用私有目录 `files/updates/` 中进行，支持断点续传。断网、被系统暂停或达到后台执行时限时保留已下载部分，WorkManager 按指数退避自动重试（最多 8 次）。404/410 视为 Release 文件不存在，直接失败；403/429/5xx 稍后重试；空间不足时提示所需大小。可以随时“取消下载”。
5. **安装前校验**（下载完成后做一次，点“安装”时再做一次），任一项不通过都会删除安装包并拒绝安装：
   - 文件大小、SHA-256 与 `yiner-update.json` 一致；如果 GitHub 为该附件提供了服务端计算的 `digest`，也必须一致；
   - APK 的包名是 `io.yiner.browser`；
   - APK 的 versionCode 与发布信息一致，且大于当前版本；
   - APK 的签名证书集合与当前已安装应用**完全一致**。

   注意：`yiner-update.json` 和 SHA256 与 APK 来自同一个 Release，只能防止文件损坏或下载过程中被替换，**不能代替数字签名**。真正证明“这是同一个发布者”的是第 4 条签名检查；Android 系统安装时也会再做一次同样的签名检查。
6. **安装**：先确认已授予“安装未知应用”权限；没有授予时会解释原因，并跳转到系统设置页面。随后把 APK 从私有目录直接写入 `PackageInstaller` 会话（不经 FileProvider 对外暴露文件），由系统弹出安装确认，**必须由你点“安装”**。Android 12+ 明确设置了 `USER_ACTION_REQUIRED`。应用不会尝试静默安装，也不使用 root 或无障碍服务。
7. 安装完成后，旧进程会被系统结束；再次打开时，新版本会自动清理下载文件。

### 重要：签名必须一致

应用内更新只会安装与当前已安装应用**同一签名**的包。因此：

- 自己本地构建的 debug 包（调试签名）**无法**通过应用内更新升级到 GitHub 发布的正式版，会显示“签名证书与当前应用不同”。需要先卸载（会丢失标签、历史和登录状态），再安装 Release 中的 APK。从那以后，正式版之间就可以在应用内更新了。
- 发布签名的密钥一旦丢失或更换，已安装的用户就无法再通过应用内更新升级。

## 发布新版本（维护者）

发布由 GitHub Actions 工作流 `.github/workflows/release.yml` 完成。它会构建签名 APK，生成 `yiner-update.json`（包名、versionCode、versionName、minSdk、文件名、SHA-256、大小，全部用 `aapt2` 从签名后的 APK 中读出），再生成 `SHA256SUMS.txt`，然后创建正式 Release。**只有构建产物、没有 Release 的话，应用检查不到更新。**

### 一次性准备：仓库 secrets

在仓库 Settings → Secrets and variables → Actions 中配置（只写在 GitHub，不进仓库）：

| 名称 | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | 发布 keystore 文件的 Base64 |
| `SIGNING_STORE_PASSWORD` | keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥密码 |

缺少任何一项，发布工作流都会直接失败，不会发布未签名或调试签名的包。工作流把 keystore 解码到 runner 的临时目录，构建结束后（无论成败）立即删除。

### 每次发布

1. 在 `app/build.gradle.kts` 中把 `versionCode` **加 1 或更多**（必须大于上一个正式版，工作流会自动检查），并更新 `versionName`。
2. 可选：写 `docs/release-notes/v<版本>.md` 作为发布说明；没有这个文件时使用 GitHub 自动生成的说明。
3. 提交并推送到 `main`，等 CI 通过。
4. 触发发布，二选一：
   - 推送 tag：`git tag v0.2.0 && git push origin v0.2.0`（tag 必须等于 `v` + versionName）
   - 或在 GitHub 的 Actions → Release → Run workflow 中选择 `main` 分支，输入 tag（如 `v0.2.0`）
5. 工作流会先以草稿上传全部附件，再一次性发布为正式版（Latest），避免应用读到附件还没传完的 Release。已存在同名 Release 时直接失败，不会覆盖。

发布后，已安装同一签名正式版的手机会在下次检查时发现更新（启动时，或每天一次的定期检查；也可以在设置中手动检查）。

### CI 与安全

- `.github/workflows/ci.yml`：每次 push 和 PR 都运行 `lintDebug testDebugUnitTest assembleDebug`。使用 `pull_request` 而不是 `pull_request_target`，并且不引用任何 secret，所以 fork 来的 PR 拿不到 secrets。PR 只读 Gradle 缓存。
- 两个工作流默认 `permissions: contents: read`，只有发布任务提升到 `contents: write`。
- 所有第三方 action 都锁定到完整 commit SHA（注释中标明对应版本）。
- checkout 使用 `persist-credentials: false`；手动输入的 tag 只通过环境变量传给脚本，并先做格式校验。

## 从源码构建

本仓库在 Linux x86_64 上用以下工具链构建（都装在工作目录内，没有修改系统环境）：

- JDK 21（Temurin 21.0.13）
- Android SDK：platform `android-37.2`、build-tools 37.0.0、platform-tools
- Gradle 9.9.1（wrapper）、Android Gradle Plugin 9.5.1、Kotlin 2.4.21、Compose BOM 2026.09.00

```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk     # 或在 local.properties 中写 sdk.dir=
./gradlew lintDebug testDebugUnitTest assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

依赖从 Google Maven、Maven Central 和 `https://maven.mozilla.org/maven2/`（仅 GeckoView）下载。Gradle 内存上限为 1.5 GB（见 `gradle.properties`）。

### 签名

- 仓库不包含任何签名密钥（`.gitignore` 排除了 `*.keystore`、`*.jks`）。debug 包使用本机 Android 默认调试签名。不同电脑上的调试签名可能不同，如果无法覆盖安装，需要先卸载旧包（会丢失标签、历史和登录状态）。
- release 构建从环境变量读取签名配置：`YINER_KEYSTORE_FILE`（keystore 文件路径）、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。
  - 四项都有：使用该密钥签名。
  - 缺任何一项：本地 `./gradlew assembleRelease` 回退到调试签名，保证任何人都能构建；
  - 加上 `-Pyiner.requireReleaseSigning=true`（发布工作流使用）：直接失败。
- 正式发布请走上面的 GitHub Actions 流程，不要把本地构建的包当作正式版分发。

### 升级 GeckoView

GeckoView 的安全更新只能靠重新构建获得。修改 `app/build.gradle.kts` 中的 `geckoviewVersion`（版本号见 `https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-arm64-v8a/maven-metadata.xml`），再重新构建即可。新版本可能提高 compileSdk 要求或调整 API，届时需要按编译错误修改代码。**建议至少每 1–2 个月更新一次。**

## 代码结构

```
app/src/main/java/io/yiner/browser/
├── YinerApp.kt            Application：GeckoRuntime、数据库、设置（全部懒加载，GeckoView 子进程中不初始化）
├── MainActivity.kt        外部 Intent、系统权限、文件选择/拍照、外链确认、下载确认、默认浏览器
├── core/                  纯 Kotlin 逻辑（有单元测试）：输入解析、外链策略、返回策略、归档策略、下载文件名、更新校验
├── browser/               TabManager（标签与 GeckoSession）、网页对话框、权限、uBlock Origin 安装与 ETP 设置、错误页
├── data/                  SQLite（历史/归档/下载）、设置、搜索联想
├── download/              下载调度与前台服务
├── update/                应用内更新：GitHub API、可信下载、WorkManager 任务、PackageInstaller 安装、通知
└── ui/                    Compose 界面（设置页“更新”分区在 UpdateSection.kt）
core/update/               更新的纯逻辑（有单元测试）：Release/元数据解析、可信来源、versionCode 比较、安装前校验、限频
scripts/make_update_metadata.py   发布时生成 yiner-update.json 与 SHA256SUMS.txt
.github/workflows/         ci.yml（检查）与 release.yml（发布）
```

## 第三方许可

- GeckoView — Mozilla Public License 2.0
- uBlock Origin — GPL-3.0，源码 https://github.com/gorhill/uBlock 。本应用原样内置 AMO 发布的签名包，未做修改
- AndroidX、Jetpack Compose、Kotlin、kotlinx.coroutines — Apache License 2.0

本项目仅供个人使用，不分发、不上架。
