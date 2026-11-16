# GitHub Actions 配置说明

本仓库的 `Release` 工作流位于 `.github/workflows/release.yml`，推送 Git 标签后运行测试、lint、签名构建并发布 APK。普通分支推送不会发布；工作流没有手动运行入口。

## 1. 启用 Actions

打开仓库的 **Settings → Actions → General**，确认允许工作流运行。若使用允许列表，允许 GitHub 官方的 `actions/checkout@v4` 和 `actions/setup-java@v4`。

工作流已声明 `permissions: contents: write`，用于创建 Release 和上传附件。发布时使用 GitHub 自动提供的 `GITHUB_TOKEN`，无需新建个人访问令牌，也无需添加名为 `GH_TOKEN` 的 Secret。组织策略若限制该权限，需要由组织管理员放行。

## 2. 添加四个 Repository secrets

打开 **Settings → Secrets and variables → Actions → Secrets → New repository secret**，分别创建下表中的四项。使用 **Secrets**，不要放到 **Variables**。当前工作流不依赖 Environment，不需要创建 Environment 或配置 Environment secrets。

| Name | Secret 中填写的实际值 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 本地 `signing.properties` 的 `storeFile` 指向的 keystore 文件，按原始字节编码得到的单行 Base64 |
| `RELEASE_STORE_PASSWORD` | 本地 `signing.properties` 的 `storePassword` 的原值 |
| `RELEASE_KEY_ALIAS` | 本地 `signing.properties` 的 `keyAlias` 的原值 |
| `RELEASE_KEY_PASSWORD` | 本地 `signing.properties` 的 `keyPassword` 的原值；不能因为已有 store password 就留空 |

密码和 alias 填写配置中的实际值，不填写字段名，不额外添加引号。`RELEASE_KEYSTORE_BASE64` 填写完整 Base64 内容，不填写 keystore 路径，也不填写 `data:` 前缀。必须使用与已发布 APK 相同的签名密钥，否则无法覆盖升级已安装的应用。

在可信的个人电脑上打开本地 `signing.properties` 查阅取值。不要把该文件、密码或 keystore 内容贴入聊天、提交到 Git，或放入 Actions 日志。

在仓库根目录的 PowerShell 中，可把 keystore 的 Base64 直接复制到剪贴板。将下面路径替换成 `storeFile` 指向的实际文件路径：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.\your-release-key.jks').Path)) | Set-Clipboard
```

把剪贴板内容粘贴到 `RELEASE_KEYSTORE_BASE64` 的 Secret 输入框并保存，然后清空：

```powershell
Set-Clipboard -Value ''
```

处理签名材料前关闭剪贴板历史和跨设备剪贴板同步。其余三个值从本地配置中逐项复制到对应 Secret。

## 3. 工作流中已配置的参数

以下参数无需在仓库 Variables 页面重复添加。

| 参数 | 当前值或来源 |
| --- | --- |
| 工作流名称 | `Release` |
| Runner | `ubuntu-latest` |
| JDK | Temurin `17` |
| Android SDK Platform | `35` |
| Android Build Tools | `35.0.0` |
| Token 权限 | `contents: write` |
| `GH_TOKEN` | GitHub 自动提供的 `${{ github.token }}` |
| `CLIPLINK_KEYSTORE_BASE64` | `secrets.RELEASE_KEYSTORE_BASE64` |
| `CLIPLINK_STORE_PASSWORD` | `secrets.RELEASE_STORE_PASSWORD` |
| `CLIPLINK_KEY_ALIAS` | `secrets.RELEASE_KEY_ALIAS` |
| `CLIPLINK_KEY_PASSWORD` | `secrets.RELEASE_KEY_PASSWORD` |
| `CLIPLINK_KEYSTORE_FILE` | 脚本在 Runner 的临时目录中还原的 keystore 路径 |
| `CLIPLINK_VERSION_NAME` | 标签去掉开头的 `v`，例如 `v1.0.1` 得到 `1.0.1` |
| `CLIPLINK_RELEASE_DIR` | 脚本自动创建，用于保存临时签名材料、构建日志、APK 和发布说明；工作流结束时清理 |
| `versionCode` | `app/build.gradle.kts` 中的整数；每次发布前手动递增，必须高于已发布版本 |

当前源码和 `v1.0.0` 标签的 `versionCode` 均为 `6`。若没有另外发布更高的值，下一版可使用 `7`。Git 标签只决定 `versionName`，不会自动递增 `versionCode`。

## 4. 发布新版本

配置好四个 Secrets 后，在 `app/build.gradle.kts` 中把 `versionCode` 从 `6` 改为 `7`（若已有更高版本，则继续递增），然后执行：

```sh
git add app/build.gradle.kts
git commit -m "Prepare release 1.0.1"
git push origin main
git tag v1.0.1
git push origin v1.0.1
```

正式版标签使用 `v1.0.1` 这样的格式；预发布版可以使用 `v1.0.2-rc.1`，工作流会自动标记为 Pre-release。不支持 `+` 构建元数据。推送格式错误的标签会触发工作流，但标签校验会失败。

在仓库 **Actions → Release** 查看运行结果。成功后可在 **Releases** 中下载 `XposedClipLinkRouter-1.0.1.apk`。Release 描述会公开包含本次版本范围内的提交标题，提交前应检查其中是否含有私密内容。

## 5. 重跑和常见错误

- 未配置完整四个 Secrets：会显示 `Release signing configuration is incomplete.`。
- keystore 编码错误：会显示 `Release keystore is not valid Base64.`；重新从原文件生成单行 Base64。
- 密码、alias 不正确，或测试、lint、构建失败：会显示 `Gradle verification or release build failed`。先核对本地签名配置，并在本机运行 README 中的测试和构建命令。工作流会清理可能包含敏感信息的构建日志。
- `HTTP 403`：检查 Actions 策略和 `contents: write` 权限是否被限制。
- 上传失败：旧 APK 会保留；API 恢复后可在 Actions 中重跑失败的任务。
- 改名或清理中断：可能保留 `pending-`、`backup-` 开头的 APK；确认正式 APK 可下载后再清理这些附件。
- 不可变 Release：已经启用 Immutable releases 的发布不能替换 APK，必须使用新版本标签。如果需要同标签重跑并替换附件，应在发布前关闭不可变发布。

没有单独配置自动更新时间、定时任务、PAT、仓库 Variables 或手动运行参数的要求。
