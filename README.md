# 平板视频收藏与播放工具（FavoritesAssistant）

Android 10+（minSdk 29）平板专用：全局悬浮收藏助手 + 横屏三栏大屏主 App + 内置播放器与离线缓存。
Kotlin 2.0.21 / Jetpack Compose（Material3）/ Room / WorkManager / Media3，官方 Jetpack 分层，无第三方 DI 框架。

- 包名：`com.favkit.tablet`
- 版本：1.0.0（versionCode 1）
- 语言：界面中文，代码注释中文

---

## 一、最快上手（本机零安装）

1. 把本目录推到 GitHub 仓库（`git init` → `git remote add` → `git push`）。
2. 打开仓库的 **Actions** 页，`Build APK` 工作流会自动跑（也可手动 Run workflow）。
3. 在本次运行的 **Artifacts → favorites-assistant-apk** 里下载 `app-debug.apk` / `app-release.apk`，装到平板即可。

云端流水线已包含 JDK 17 + Gradle 8.9 + Android SDK 34，Release 包在未配置正式签名时自动使用 debug 签名，
保证一定能产出「可安装」的 APK（正式分发再把 `signingConfig` 换成你自己的 keystore）。

> 仓库内不含 `gradlew` / `gradle-wrapper.jar`（二进制文件无法随源码生成）。
> 本机若想用 Gradle Wrapper，用任意 Gradle 8.9 执行一次 `gradle wrapper --gradle-version 8.9`，
> 或者直接用 Android Studio 打开工程，它会提示自动补全 wrapper。

## 二、本机想自己打包（可选，需要工具链）

```bash
# 需要 JDK 17 + Android SDK 34（platforms;android-34、build-tools;34.0.0）
export ANDROID_HOME=/your/android-sdk
gradle wrapper --gradle-version 8.9          # 只需执行一次
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

换后端地址、固化服务端公钥（可选）：

```bash
./gradlew :app:assembleRelease \
  -PSYNC_ENDPOINT="https://sync.example.com/api/v1" \
  -PSYNC_SERVER_PUBLIC_KEY="$(cat mock-server/server_public_key.pem)"
```

也可以走环境变量 `SYNC_ENDPOINT` / `SYNC_SERVER_PUBLIC_KEY`（GitHub Actions 里就是 `vars` / `secrets`）。

## 三、本地 mock 同步服务

只用 Node 内置模块，`npm install` 都不需要（Node 18+）：

```bash
node mock-server/keygen.mjs     # 生成 RSA-2048 密钥对，并打印公钥
node mock-server/server.mjs     # 监听 http://127.0.0.1:8787/api/v1
```

- 模拟器里把 App 的同步地址填 `http://10.0.2.2:8787/api/v1`（默认已是这个值）。
- 真机与电脑同一 Wi-Fi 时填 `http://<电脑内网 IP>:8787/api/v1`，
  注意 `network_security_config.xml` 只放开了 localhost / 127.0.0.1 / 10.0.2.2 / 192.168.0.1 的明文 HTTP，
  其它网段请上 HTTPS，或在里面补上你的调试网段。
- 把 `mock-server/server_public_key.pem` 的内容整段粘进「设置 → 服务端 RSA 公钥」，
  mock 服务就能真正解密并列出每一条收藏的链接、备注与标签（数据落在 `mock-server/data.json`）。
- 不填公钥时走设备 Keystore 加密（同样是密文出网），mock 服务只确认收到、看不到明文。

可用环境变量：`PORT`、`BASE_PATH`、`SYNC_TOKEN`（设置后校验 `Authorization: Bearer`，可用来演示 4xx 拒绝分支）、`DATA_FILE`、`PRIVATE_KEY_FILE`。

## 四、目录结构

```
app/src/main/java/com/favkit/tablet/
├── App.kt                     Application：只装依赖容器 + 通知渠道，冷启动不碰磁盘网络
├── core/                      平板物理尺寸换算（mm→px）、通知渠道
├── di/                        AppGraph 懒加载容器 + Compose/Service 双侧取用
├── data/                      Room 实体/DAO/仓储、DataStore 设置、领域模型
├── net/                       DTO、信封加密（RSA-OAEP + AES-256-GCM）、同步 API
├── sync/                      网络回调、WorkManager 队列与 Worker（静默重试 3 次）
├── media/                     Media3 缓存层（SimpleCache + CacheDataSource）、共享播放器、预取
├── util/                      视频链接识别与来源站点标注、剪贴板读取封装
├── floating/                  全局悬浮球 + 悬浮收藏面板（Service 窗口内的 Compose 宿主）
└── ui/
    ├── MainActivity.kt        强制横屏入口、旋转与权限处理、分享链接承接
    ├── ShareReceiverActivity.kt  系统分享接收（无界面，转交悬浮面板或主 App）
    ├── theme/ components/     Material3 主题与平板热区控件
    ├── main/                  三栏：NavPane / ListPane / DetailPane + MainViewModel
    └── settings/              SettingsSheet

mock-server/                   本地 mock 同步服务 + 密钥生成脚本
.github/workflows/             云端构建 APK
docs/                          使用说明、架构与性能验证
```

## 五、需求对照

| 需求 | 落点 |
| --- | --- |
| Android 10+ | `minSdk = 29` |
| 默认强制横屏、旋转不丢状态 | `sensorLandscape` + `configChanges`（Activity 不重建）+ 进程级草稿 `FloatingDraftStore` + `rememberSaveable` 列表状态 |
| 触控目标 ≥10mm、边缘控件距屏 ≥20mm | `TabletMetrics` 用 `xdpi/ydpi` 真实物理 DPI 换算，并与 `sw600dp/sw720dp` 三档 dimens 基线取大 |
| 间距比手机 +50% | `values/dimens.xml` 以 8dp × 1.5 为 `gap_sm` 基线，sw600/sw720 逐档加宽 |
| 禁止手机竖屏单列 | 三栏布局在 600dp 以下也保持三列（仅收窄列宽），三档基线见 `values` / `values-sw600dp` / `values-sw720dp` |
| 常驻 ≤80MB / 冷启动 ≤2s | `AppGraph` 全 lazy、单一 `ExoPlayer` 实例复用、无第三方 DI/无启动器插件；测量步骤见 `docs/架构与性能验证.md` |
| 后台静默同步不吃电量 | 只用 `ConnectivityManager` 回调触发，零轮询、零定时器 |
| 悬浮球拖拽 + 贴边半隐 | `TYPE_APPLICATION_OVERLAY` 窗口 + `ValueAnimator` 吸附，位置持久化 |
| 复制即弹轻提示 | Android 10 起非焦点 App 禁止后台读剪贴板 → 采用悬浮球手动粘贴 + 系统分享接收（面板内已说明，见 `docs/使用说明.md`） |
| 面板四段结构（只读链接 / 手写备注 / 横滚标签 + 内联新建标签 / 保存同步·关闭） | `FloatingPanelScreen` |
| 手写笔直接书写 | `StylusNoteField`：AndroidView 承载真实 `EditText`，复用 Android 14+ 对可编辑文本框默认开启的手写笔直写；更早系统用手写输入法，无需额外权限 |
| 加密上报 url+备注+标签+来源+时间戳 | `EnvelopeCrypto`（AES-256-GCM 会话密钥，RSA-OAEP-256 包裹；无公钥时走设备 Keystore） |
| 2 秒轻提示 + 自动收起 + 回到原 App | Toast `LENGTH_SHORT`，面板窗口关闭，不启动任何 Activity |
| 断网静默重试 3 次后进队列自动补传 | `SyncWorker` + `WorkManager` 网络约束与线性退避 + `pending_ops` 队列表 |
| 三栏大屏（导航 / 列表 / 详情预览） | `MainScreen` |
| 内置播放器倍速、拖动、全屏 | `PlayerStage`（右栏内嵌 16:9，可一键全屏并沉浸系统栏） |
| 备注编辑、标签调整、归档分类、批量删除 | `DetailPane` + `ListPane` |
| 全部本地缓存、离线可看 | Room 为唯一事实来源 + `SimpleCache` 边播边存 + 单条「缓存到本地」预取 |

## 六、已知边界（诚实说明）

1. **后台剪贴板自动弹窗**：Android 10（API 29）起系统禁止非前台焦点应用读取剪贴板内容，
   任何「复制即弹」的实现都需要无障碍服务或前台 App 轮询——前者是高危权限，后者直接违反省电要求。
   因此本项目采用悬浮球手动粘贴 + 系统分享接收两条零敏感权限路径，面板打开瞬间会做一次尽力读取。
2. **云端构建**：本机没有 `gh` 与仓库凭据，推送与首次 Actions 运行需要你在 GitHub 上完成。
3. **`gradle-wrapper.jar`**：二进制产物不随源码生成，云端流水线用 `setup-gradle` 提供的 Gradle 8.9 直接构建。
4. **Release 签名**：默认回落 debug 签名以保证可安装；正式上架前替换成你自己的 keystore 与 `signingConfig`。
5. **HLS/DRM**：播放器支持 mp4/webm/HLS；需要 DRM 的内容请在 `MediaCache` 里补 `DrmSessionManager`。

## 七、云构建备选平台

- **GitLab CI**：镜像 `ubuntu:24.04` + `openjdk-17-jdk-headless`，手动下载 `commandlinetools-linux`，`gradle assembleRelease`，`artifacts: paths: [app/build/outputs/apk/**]`。
- **阿里云效 Flow / CODING CI**：选「Android 构建」模板，JDK 17、Gradle 8.9、SDK 34，构建命令同上。
- **Bitrise / Codemagic**：直接选 Android 工作流，脚本步骤填 `gradle :app:assembleRelease`。
- 三者都不需要改动本工程源码；如需我把某一家写成现成的配置文件，说一声即可。
