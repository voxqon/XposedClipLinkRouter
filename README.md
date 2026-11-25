# XposedClipLinkRouter

**复制链接或分享口令，轻点通知直达目标应用。**

一个面向 Android 15 及以上版本的 Xposed 模块，帮助你识别剪贴板中的链接和分享口令，并根据规则打开对应应用或浏览器。

![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)
![Xposed API 101](https://img.shields.io/badge/Xposed_API-101-6C5CE7)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)

[功能特性](#功能特性) · [安装与配置](#安装与配置) · [隐私说明](#隐私说明) · [从源码构建](#从源码构建) · [常见问题](#常见问题)

## 功能特性

- **链接与口令识别**：支持 HTTP(S) 链接、裸域名，以及淘宝、天猫、京东等分享口令。
- **按规则打开**：使用内置规则或自定义匹配方式、目标应用和打开方式；目标应用不可用时可回退浏览器。
- **通知确认**：复制后通过通知选择是否跳转，不自动打开第三方应用。
- **按需后台处理**：系统剪贴板变化时触发识别，无需常驻服务；锁屏时暂停，解锁后检查当前内容。
- **减少重复提醒**：过滤同一次复制的重复事件和部分微软设备同步组件的重复回写；手机主动再次复制相同内容仍可通知。
- **灵活设置**：可调整识别文本长度、关闭口令识别，或隐藏本应用的剪贴板读取提示。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 15（API 35）及以上 |
| 框架 | 支持现代 Xposed API 101 和静态作用域的框架，例如 Vector 2.2 或更高版本 |
| 系统兼容性 | ROM 的剪贴板实现需兼容模块支持的方法签名；后台限制可能影响运行 |

## 安装与配置

1. 安装 [GitHub Releases](https://github.com/voxqon/XposedClipLinkRouter/releases) 中的 2.0.1 或更高版本，或[从源码构建](#从源码构建)。仍使用旧版框架的设备请保留 1.0.0。
2. 在框架管理器中启用模块。静态作用域只允许 **系统框架（system）**；如果该项尚未勾选，请勾选，然后重启手机。
3. 打开本应用，授予通知权限，确认“启用路由总开关”和“Xposed 后台监听”已开启。
4. 在其他应用复制链接或口令，点击识别通知打开目标应用。

> 部分 ROM 还需允许本应用自启动和后台运行。

## 隐私说明

模块在系统框架中监听剪贴板变化，并向本应用发送不含原文的事件；应用随后读取当前剪贴板并匹配规则。识别结果通知不显示链接或口令原文；为了点击通知后打开目标，原文会随通知关联的系统 `PendingIntent` 暂存。

- 跳过已标记为敏感的剪贴板内容。默认保留系统读取提示；启用新版系统 Hook 并重启后，可在应用的「系统设置 → 服务运行控制」打开「隐藏剪贴板读取提示」。这只影响本应用的系统 Toast，其他应用的提示和本应用的链接通知照常显示；不兼容的系统会保留提示。
- 为过滤重复事件，应用在本地保存最多 128 条匹配摘要及相关时间信息，不保存剪贴板原文。摘要不是加密，可能通过猜测原文进行验证。
- 不保存打开记录；从 1.0.0 覆盖升级后，首次启动会删除旧记录并保留已有规则和设置。
- 应用数据不再纳入 Android 云备份和设备迁移。模块诊断只输出固定信息，不记录剪贴板内容或来源包名。

## 从源码构建

需要 **JDK 17** 和 **Android SDK Platform 35**。使用 Android Studio 打开项目，或在本机 `local.properties` 中配置 `sdk.dir`，然后运行：

```powershell
# Windows
.\gradlew.bat :core:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

```sh
# macOS / Linux
./gradlew :core:test :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

构建产物位于 `app/build/outputs/apk/release/`。Release 默认启用代码和资源压缩；没有本地签名配置时，生成的是未签名 APK，不能直接安装。

<details>
<summary><strong>配置 Release 签名</strong></summary>

在项目根目录创建本地 `signing.properties`，填写自己的签名信息：

```properties
storeFile=your-release-key.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

签名文件和配置已加入 `.gitignore`，请自行妥善保管。覆盖更新已有安装需要使用相同的签名密钥。

</details>

### 通过 Git 标签发布

完整设置步骤、Secrets 取值方式和工作流参数见 [GitHub Actions 配置说明](docs/GITHUB_ACTIONS.md)。

仓库管理员先在 GitHub 仓库的 **Settings → Secrets and variables → Actions → New repository secret** 中配置以下 Secrets：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 本地 `signing.properties` 中 `storeFile` 指向的发布 keystore 文件的单行 Base64 |
| `RELEASE_STORE_PASSWORD` | 本地 `signing.properties` 中 `storePassword` 的原值 |
| `RELEASE_KEY_ALIAS` | 本地 `signing.properties` 中 `keyAlias` 的原值 |
| `RELEASE_KEY_PASSWORD` | 本地 `signing.properties` 中 `keyPassword` 的原值 |

必须使用与已发布版本相同的签名密钥，才能覆盖升级已安装的应用。不要把 keystore、密码或 Base64 内容提交到仓库或贴入日志。Windows PowerShell 中可在可信的个人电脑上将 keystore 编码后直接复制到剪贴板，粘贴到对应 Secret，随后清空剪贴板：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\private\release-key.jks')) | Set-Clipboard
# 在 GitHub 的 Secret 页面粘贴并保存后执行：
Set-Clipboard -Value ''
```

请先关闭可能保存剪贴板历史或同步剪贴板的功能，并替换路径为自己的 keystore 路径。也可在私密环境中生成仅当前用户可读的临时文件，粘贴后立即删除，避免在终端打印编码内容。

每次发布前，在 `app/build.gradle.kts` 中将 `versionCode` 更新为**高于所有已发布版本的正整数**，并提交更改。将本地默认 `versionName` 与标签保持一致，并添加与标签同名的更新说明。例如发布 `v2.0.2`：

```sh
git add app/build.gradle.kts docs/releases/v2.0.2.md
git commit -m "Prepare release 2.0.2"
git tag v2.0.2
git push origin main refs/tags/v2.0.2
```

正式版示例为 `v2.0.2`，预发布版示例为 `v2.0.3-rc.1`。标签格式为 `v主版本.次版本.修订版本`，可附加符合 SemVer 规则的 `-` 预发布后缀；不支持 `+` 构建元数据。推送标签会启动发布工作流，格式不符的标签会使工作流失败；普通分支推送不会触发发布。预发布后缀会自动设置 GitHub Pre-release。工作流先运行发布脚本回归测试、应用测试、lint 和 Release 构建，校验 APK 签名后才发布。

重跑会更新同名 Release，先以临时名称上传 APK，检查上传状态、大小及 API 返回的 SHA-256（若有），再替换原文件。上传失败时旧 APK 保持可下载；替换过程中改名失败时会尝试恢复原文件名。若 API 故障导致恢复或清理失败，Release 中可能保留 `pending-` 或 `backup-` 开头的 APK，可在确认正式文件后清理。启用了不可变发布（Immutable releases）的 Release 无法替换附件，需要使用新的版本标签发布。

Release 描述使用对应标签提交中的 UTF-8 文件 `docs/releases/<标签>.md`，不会自动附带提交标题。文件缺失或为空时工作流会停止。查看 [2.0.1 更新说明](docs/releases/v2.0.1.md)。

### 项目结构

```text
app/       Android 界面、系统集成、通知与数据存储
core/      链接与口令提取、规则匹配、打开及去重策略
```

单元测试覆盖提取、规则匹配、Hook 边界、去重及部分应用层行为；真机上的 Hook、通知和目标应用跳转仍需单独验证。

## 常见问题

<details>
<summary><strong>复制后没有出现通知？</strong></summary>

确认模块已启用、作用域为系统框架且设备已重启；检查应用开关、通知权限，以及 ROM 的自启动和后台运行限制。系统“强行停止”应用后，需要重新打开应用才能恢复。

</details>

<details>
<summary><strong>如何判断系统 Hook 是否正常？</strong></summary>

查看框架日志。出现 `clipboard hook unavailable` 时，请检查框架是否支持现代 Xposed API 101，以及 ROM 的剪贴板实现是否兼容。应用内开关只表示配置状态，不代表 Hook 已成功安装。

如果已打开「隐藏剪贴板读取提示」但仍看到提示，先确认安装或更新后已重启手机，再检查本次启动的框架日志是否出现 `clipboard access toast hook installed`。系统加载新版 Hook 后，切换该开关无需再次重启。

</details>

<details>
<summary><strong>更新后识别行为异常？</strong></summary>

重启手机加载新版系统 Hook。覆盖更新但尚未重启时，旧事件可能在短时间内被重复过滤。

</details>
