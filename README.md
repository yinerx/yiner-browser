# 银耳浏览器（Yiner Browser）

个人自用的 Android 浏览器。界面参考 Android 版 Arc Search 公开 beta 截图的布局与交互，但代码、名称、图标、配色资产均为独立实现，与 The Browser Company 无关，也不代表任何官方产品。

- 包名：`io.yiner.browser`（正式版）；测试版为 `io.yiner.browser.debug`
- 内核：**基于 Chromium 的 Android 系统 WebView**（设备上的 “Android System WebView”，由系统 / Google Play 提供与更新），通过 `androidx.webkit` 使用多配置文件等新接口。它不是完整的 Chromium 浏览器，也不随本应用分发
- 界面：Kotlin + Jetpack Compose
- 广告与跟踪屏蔽：自研规则引擎，执行随包的 EasyList / EasyPrivacy 规则子集（见下文）
- 没有任何 AI 功能，不调用任何模型或搜索 API

> **内核变更说明**：已发布的 0.2.0 正式版使用 Mozilla GeckoView + uBlock Origin。从本分支（`feature/arc-search-android-ui`）起改为系统 WebView。用户数据的迁移边界见下文“从 0.2.0（GeckoView）升级”。

## 兼容范围

| 项目 | 范围 |
|---|---|
| Android 版本 | Android 8.0（API 26）及以上；targetSdk 36，compileSdk 37.2 |
| 系统 WebView | 需要设备上的 Android System WebView（或 Chrome 提供的 WebView）。**无痕标签**需要支持 `MULTI_PROFILE` 的较新 WebView，不支持时应用会拒绝打开无痕标签，并提示更新 WebView |
| CPU 架构 | 不限（APK 不含浏览器引擎的原生库） |
| 安装体积 | debug 包约 14 MB，其中过滤规则约 3.5 MB |
| 测试状态 | **未在真机或模拟器上运行过**（构建环境没有可用的 KVM 模拟器和真机）。界面只做了离线渲染对照。请按 [ACCEPTANCE.md](ACCEPTANCE.md) 在手机上验收 |

## 安装

- **正式版**：从 [GitHub Releases](https://github.com/yinerx/yiner-browser/releases) 安装。目前的正式版 0.2.0 仍是 GeckoView 内核。
- **WebView 测试版**（本分支 debug 构建）：
  - 包名 `io.yiner.browser.debug`，桌面名称“银耳浏览器 测试版”，可以**与正式版并存安装，不需要也不应该卸载正式版**；
  - 使用构建机的 Android 默认**调试签名**（不是正式发布签名），所以不能覆盖安装正式版，也不参与应用内更新；
  - 测试版和正式版的数据（标签、历史、登录状态）互相独立。

```bash
adb install -r app-debug.apk   # 安装测试版，不影响已安装的正式版
```

## 界面

按 Android Police 2024-10 公开 beta 的实操截图做像素测量后实现，依据与差异见 [docs/arc-search-android-ui-references.md](docs/arc-search-android-ui-references.md)：
- 浏览时网页占满屏幕，底部是轻薄工具栏（标签缩略图 / 白色胶囊“+” / “^”展开菜单）；
- 上拉的页面菜单：地址胶囊、固定 / 查找 / 分享、文字缩放、桌面版网站、网站设置、拦截计数；
- 叠在网页上的搜索面板；
- 横向标签卡片；
- 分组式设置页。

没有翻译、AI 和品牌化的语音助手。界面未在真机上验证。

离线渲染界面截图（不是设备截图）：

```bash
./gradlew testDebugUnitTest -Pyiner.screenshots=true --tests 'io.yiner.browser.ui.UiScreenshotTest'
# 输出：build/ui-screenshots/*.png
```

## 外观（浅色 / 深色）

- **设置 → 选项 → 外观**：跟随系统（新安装默认）/ 浅色 / 深色，选择会持久保存。
  - 跟随系统时，系统切换浅色或深色后界面立即跟着变；
  - 手动选浅色或深色后，不再受系统影响。
- **适配范围**：底栏、搜索面板、横向标签卡片、展开菜单、网站设置面板、查找栏、设置及其二级页（历史、下载、已归档等）、原生对话框、状态栏和导航栏图标，都按所选外观切换。
- **深色色板**：参考截图里没有深色界面，深色色板是银耳浏览器自己的设计。
  - 两套色板的文字对比度都由单元测试检查：正文和次要文字至少 4.5:1，输入框占位提示和开关、对勾等控件至少 3:1。
  - 浅色版中有两处颜色为了达到对比度，比参考图测量值略深：设置页分组标题、标签卡片标题。
- **网页**：按系统 WebView 的正式机制处理。
  - targetSdk 33 起，WebView 按应用主题的 `isLightTheme` 设置网页的 `prefers-color-scheme`；
  - 网页有深色样式时显示深色，没有时保持原样；
  - **不开启算法暗化，不对网页反色，不保证所有网页都变成黑底**；
  - 切换外观时，已打开的网页会保存前进/后退历史后重新加载；
  - Android 8 / 9 不支持此机制，网页始终按浅色偏好显示。

## 功能与实现状态

“已实现”指代码完成并通过编译、Lint 和单元测试，**不代表已在真机上验证**。

| 功能 | 状态 | 说明 |
|---|---|---|
| 搜索 / 地址输入 | 已实现 | 默认 Google，可选 Bing、DuckDuckGo、百度；历史匹配加搜索引擎联想（可关闭，无痕不联想） |
| 多标签与会话恢复 | 已实现 | 每个标签一个 WebView；后台只保留最近 4 个普通标签的 WebView，其余保存前进/后退历史后释放（`WebView.saveState`）；杀进程后可恢复 |
| 标签自动归档 | 已实现 | 默认 1 天，可调 |
| 无痕标签 | 已实现，**有条件** | 见下文“无痕模式” |
| Google 登录 | **受限**，见下文 | 不伪装身份，打开 Google 登录页前提示限制 |
| 广告 / 跟踪屏蔽 | 已实现（规则子集） | 见下文 |
| 文件下载 | 已实现 | 下载前确认。http(s) 文件由下载服务带上原标签的 Cookie 重新请求；`data:` 直接保存；`blob:` 由页面读出后保存（上限 40 MB）；前台服务、进度通知、下载列表 |
| 文件上传 | 已实现 | 系统文件选择器，按 accept 过滤；图片可选拍照 |
| 网页对话框 | 已实现 | alert / confirm / prompt / 离开页面确认 / HTTP 认证；`<select>` 与日期选择由 WebView 自己处理 |
| 网站权限 | 已实现 | 摄像头、麦克风、DRM、定位每次询问，不记住选择；网页推送通知不支持 |
| 全屏视频 | 已实现 | `onShowCustomView`，返回手势退出 |
| 弹出窗口 | 已实现 | 只有用户点击触发的新窗口才打开为子标签，其余拦截 |
| 外部链接 | 已实现 | `tel:`、`intent://` 等先确认；`intent://` 清除组件与授权标志 |
| 证书错误 | **不放行** | 一律取消并显示本地错误页，没有“继续访问” |
| 安全浏览 | 已实现 | 开启 WebView Safe Browsing，命中时返回安全页面 |
| 仅 HTTPS | 已实现（近似） | 主文档 http 导航自动改为 https；子资源禁止混合内容 |
| 第三方 Cookie | 默认关闭 | 可在“全局网站设置”中开启（部分嵌入式登录需要） |
| 深色模式 | 已实现 | 跟随系统 / 浅色 / 深色，见上文“外观” |
| 文字缩放 / 桌面版网站 / 页内查找 | 已实现 | 桌面版只把 UA 中的设备描述改为桌面平台，保留 WebView 标识 |
| 默认浏览器、应用内更新 | 已实现 | 应用内更新只对正式版生效，测试版跳过 |

### 明确不包含

AI 搜索与总结、翻译、账号与云同步、密码管理器（可用系统自动填充）、扩展、网页推送通知、拦截 Cookie 横幅、静默安装更新。

## 关于 Google 登录

- Google 的 OAuth 2.0 政策规定，开发者**不得**把 Google OAuth 授权请求发往自己控制的“内嵌用户代理”（https://developers.google.com/identity/protocols/oauth2/policies）。系统 WebView 属于此类，所以 **Google 可能拒绝在银耳浏览器中登录**（常见报错为 `disallowed_useragent`），第三方网站的“用 Google 登录”也可能失败。
- 银耳浏览器**不伪装身份**：保留 WebView 默认 User-Agent（含 `; wv` 标识）和 `X-Requested-With` 请求头，不尝试绕过 Google 的检测。
- 打开 Google 登录页前，会弹窗说明限制，可选择“继续尝试”或“用其它浏览器打开”。改用其它浏览器登录后，**登录状态不会带回银耳浏览器**。
- 不在 Google 登录页上执行广告拦截以外的任何改动；过滤规则不拦截 accounts.google.com 主文档（有单元测试保证）。
- 是否能登录，以真机实测为准；本轮没有真机。

## 广告与跟踪屏蔽

uBlock Origin 是浏览器扩展，系统 WebView 不支持扩展，所以已经**移除**（包括随包的 .xpi 和全部入口）。现在的做法是：

1. **网络拦截**：在 `WebViewClient.shouldInterceptRequest`（以及 Service Worker 的请求拦截接口）中，用随包的 EasyList（广告）和 EasyPrivacy（跟踪器）规则判断子资源请求，命中时返回空响应。主文档（你打开的网页本身）不会被拦截。
2. **元素隐藏**：按 EasyList 的 `##` 规则生成 CSS。新版 WebView 在页面开始解析时注入（广告位不会先出现再消失）；旧版 WebView 退化为页面加载完成后注入。

规则快照：EasyList 与 EasyPrivacy，2026-11-14。作者为 The EasyList authors（https://easylist.to/），GPL-3.0 / CC BY-SA 3.0 双许可，文件未修改。实际加载后：

| 指标 | 数量 |
|---|---|
| 规则总数（不含注释） | 134,478 |
| 能执行的网络规则 | 100,051 |
| 能执行的元素隐藏规则 | 24,120 |
| 跳过的规则 | 10,307 |

**支持的写法**：
- `||域名^`；
- `$third-party`；
- 资源类型选项。WebView 不提供资源类型，按 `Accept` 请求头近似推断为图片 / 样式表 / 子框架 / 其它；
- `||域名/路径`；
- 例外 `@@||…`，保留路径和 `domain=` 的作用范围；
- 通用和按站点的 `##`，以及 `#@#` 例外。

**跳过的写法**（宁可漏拦，也不误拦）：
- 通配符和正则；
- 带 `domain=` 或 `~` 否定的拦截规则；
- `$popup`、`$important` 等其它选项；
- 过程式选择器（`:has-text` 等）、脚本注入（`+js`）、`$removeparam`。

**限制**：
- 无法拦截 WebSocket；无法修改网页脚本；
- Service Worker 的请求拿不到所属网页，按“第三方”处理，也不适用单站点放行；
- “第三方”的判断用近似的可注册域名，没有使用完整的公共后缀列表；
- 拦截计数只统计网络请求。

**设置**：
- “全局网站设置”中可以分别开关广告和跟踪器拦截，并从 easylist.to（固定的 HTTPS 地址）手动更新规则；
- 页面菜单 → “网站设置”可以对单个网站关闭拦截。

## 无痕模式

- 无痕标签使用独立的 **WebView Profile**（`androidx.webkit` 的 `MULTI_PROFILE`）。它与普通标签分开保存 Cookie、网站存储、HTTP 缓存和定位权限，彼此不可见。
- 每次无痕会话都使用一个**新的 Profile 名**。关闭最后一个无痕标签时：
  - 立即清空该 Profile 的 Cookie、网站存储和定位权限；
  - 官方文档说明，同一进程中加载过的 Profile 不能删除，所以它在磁盘上的目录（含 HTTP 缓存）会在**下次启动应用时**整体删除。
- 无痕标签不记录历史，不写入标签文件，下载不进入下载列表（文件本身仍会保存到“下载”目录）。
- **系统 WebView 不支持 `MULTI_PROFILE` 时，应用拒绝打开无痕标签**，并提示更新 WebView，不会让无痕标签与普通标签共享 Cookie。
- 以上隔离与清理效果**未在真机上验证**。

## 从 0.2.0（GeckoView）升级

| 数据 | 能否保留 |
|---|---|
| 标签的网址和标题、历史记录、下载列表、设置、已归档标签、固定的网站 | 保留（银耳浏览器自己的文件和数据库，格式不变） |
| 标签的前进/后退历史 | **不能**保留：GeckoView 的格式与 WebView 不通用，只按网址重新打开 |
| Cookie、登录状态、网站存储、网站权限 | **不能**迁移：两种内核的存储格式不同，需要**重新登录各网站** |
| uBlock Origin 的自定义规则和信任站点 | 不能迁移（扩展已移除）；单站点放行需在“网站设置”中重新设置 |
| 旧内核的数据目录 | **不会自动删除**。首次启动会弹窗说明；可在“设置 → 存储 → 清理旧内核数据”中手动清理 |

当前的测试版包名不同（`.debug`），不会读取正式版的数据。上表描述的是将来以同一包名发布 WebView 正式版时的行为。

## 隐私

- 应用代码本身没有统计、遥测或崩溃上报。
- 会联网的功能：
  - 你访问的网站；
  - 搜索联想（可关闭）；
  - WebView 安全浏览（Google Safe Browsing，由系统 WebView 执行）；
  - 手动更新过滤规则（easylist.to）；
  - 检查应用更新（api.github.com，可关闭）。
- 应用数据不参与 Android 云备份或设备迁移。

## 应用内更新

“设置 → 更新”中可以手动检查更新，也可以开关以下选项：

| 选项 | 默认 | 说明 |
|---|---|---|
| 启动时检查更新 | 开 | 打开应用时检查；距上次检查不足 12 小时则跳过，不会真正请求 GitHub |
| 定期检查更新 | 开 | WorkManager 约每天一次，在联网时后台检查（同样受 12 小时限频） |
| 自动下载新版本 | 开 | 发现新版本后自动下载，完成后发通知 |
| 仅在 Wi-Fi 下自动下载 | 开 | 手动点“下载”或“用移动数据下载”不受此限制 |

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

依赖从 Google Maven 和 Maven Central 下载。Gradle 内存上限为 1.5 GB（见 `gradle.properties`）。

### 签名

- 仓库不包含任何签名密钥（`.gitignore` 排除了 `*.keystore`、`*.jks`）。debug 包使用本机 Android 默认调试签名。不同电脑上的调试签名可能不同，如果无法覆盖安装，需要先卸载旧包（会丢失标签、历史和登录状态）。
- release 构建从环境变量读取签名配置：`YINER_KEYSTORE_FILE`（keystore 文件路径）、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。
  - 四项都有：使用该密钥签名。
  - 缺任何一项：本地 `./gradlew assembleRelease` 回退到调试签名，保证任何人都能构建；
  - 加上 `-Pyiner.requireReleaseSigning=true`（发布工作流使用）：直接失败。
- 正式发布请走上面的 GitHub Actions 流程，不要把本地构建的包当作正式版分发。

### 内核与规则的安全更新

- 网页内核是系统组件 Android System WebView，由 Google Play / 系统更新，不需要重新构建本应用；
- 过滤规则可以在应用内手动更新；随包的规则快照在每次发布前更新即可。

## 代码结构

```
app/src/main/java/io/yiner/browser/
├── YinerApp.kt            Application：数据库、设置、规则引擎、无痕配置（全部懒加载）
├── MainActivity.kt        外部 Intent、系统权限、文件选择/拍照、外链确认、下载确认、默认浏览器
├── core/                  纯 Kotlin 逻辑（有单元测试）：输入解析、外链策略、返回策略、归档策略、下载文件名、更新校验
├── browser/               TabManager（标签与 WebView）、无痕 Profile、会话保存、网页对话框、Google 登录提示、blob 下载、错误页
├── blocking/              广告与跟踪拦截：规则引擎 FilterEngine（有单元测试）、ContentBlocker、元素隐藏注入
├── data/                  SQLite（历史/归档/下载）、设置、搜索联想
├── download/              下载调度与前台服务
├── update/                应用内更新：GitHub API、可信下载、WorkManager 任务、PackageInstaller 安装、通知
└── ui/                    Compose 界面（设置页“更新”分区在 UpdateSection.kt）
core/update/               更新的纯逻辑（有单元测试）：Release/元数据解析、可信来源、versionCode 比较、安装前校验、限频
scripts/make_update_metadata.py   发布时生成 yiner-update.json 与 SHA256SUMS.txt
.github/workflows/         ci.yml（检查）与 release.yml（发布）
```

## 第三方许可

- 网页内核：设备上的 Android System WebView（基于 Chromium），不随本应用分发
- EasyList / EasyPrivacy 过滤规则 — The EasyList authors（https://easylist.to/），GPL-3.0 / CC BY-SA 3.0 双许可，原样内置于 `app/src/main/assets/filters/`
- AndroidX（含 androidx.webkit）、Jetpack Compose、Kotlin、kotlinx.coroutines — Apache License 2.0

本项目仅供个人使用。
