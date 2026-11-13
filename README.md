# 平板视频收藏与播放工具（FavoritesAssistant）

Android 10+（minSdk 29）平板专用：全局悬浮收藏助手 + 以视频墙为主体的自适应大屏（横屏/竖屏都可用） + 内置播放器与离线缓存。
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

### 什么时候才会重新构建

推送不等于要出包，`.github/BUILD_TRIGGER` 是唯一的构建开关：

- 需要新包时，把里面的 `build` 数字 +1（顺手更新 `code` 与 `note`），**和代码一起提交推送**；
- 只有该文件内容变化，或在 Actions 页面手动 **Run workflow**，流水线才会装 SDK、编译、上传产物；
- 纯文档/说明的提交只跑一个「判断是否需要构建」的步骤就结束（十几秒），不占构建时间；
- 改了 `app/` 或 gradle 相关文件却忘了 bump，流水线给出 warning 提醒，而不是静默跳过（避免产物落后于代码）；
- 一次都不想跑，就在提交信息里写 `[skip ci]`（GitHub 原生行为，连运行记录都不创建）；反过来说，
  **这个字面量出现在 message 任何位置都会吃掉 run**，讲解它的提交也别带上，否则触发器改了也不出包。

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

换后端地址（可选，留空则用 `gradle.properties` 里的默认值）：

```bash
./gradlew :app:assembleRelease -PSYNC_ENDPOINT="https://sync.example.com/api/v1"
```

也可以走环境变量 `SYNC_ENDPOINT`（GitHub Actions 里就是 Repository variable `SYNC_ENDPOINT`）。
传输层没有加密，所以不再需要往包里塞服务端公钥。

## 三、本地 mock 同步服务

只用 Node 内置模块，`npm install` 都不需要（Node 18+）：

```bash
node mock-server/server.mjs     # 监听 0.0.0.0:8787/api/v1
```

- 协议是纯明文 JSON（`POST /sync`、`GET /favorites`、`GET /videos`、`POST /videos/resolve`、`GET /health`、`GET /stats`），不再有信封加密和 RSA 密钥。
- 启动日志会列出本机所有非内部 IPv4 地址，平板照抄 `http://<电脑内网 IP>:8787/api/v1` 即可。
- 视频标题、封面、时长、播放地址由服务端按 url 哈希确定性生成（`resolver: mock`），
  App 不需要自己猜标题；数据落在 `mock-server/data.json`。
- 服务默认监听 `0.0.0.0`，同一局域网内任何设备都能访问，只适合内网调试，不要暴露到公网。
- **真机连不上先看这三条**：
  1. Windows 防火墙默认拦 8787 入站，管理员 PowerShell 放行一次即可：
     `netsh advfirewall firewall add rule name="favkit-mock" dir=in action=allow protocol=TCP localport=8787 profile=private`
  2. App 里填的必须是电脑的**局域网 IPv4**（启动日志已经打出来）。`10.0.2.2` 只是模拟器到本机的别名，真机用它连不上。
  3. 电脑和平板要在同一个网段，且路由器没开 AP 隔离。先在平板浏览器里打开 `http://<电脑 IP>:8787/api/v1/health`，
     返回 `{"ok":true...}` 才说明链路通，再去 App 设置里改地址。

可用环境变量：`HOST`、`PORT`、`BASE_PATH`、`SYNC_TOKEN`（设置后数据接口校验 `Authorization: Bearer`，可用来演示 4xx 拒绝分支）、`DATA_FILE`、`UPLOAD_WAIT_MS`、`UPLOAD_WAIT_JITTER_MS`。

## 四、目录结构

```
app/src/main/java/com/favkit/tablet/
├── App.kt                     Application：只装依赖容器 + 通知渠道，冷启动不碰磁盘网络
├── core/                      平板物理尺寸换算（mm→px）、通知渠道
├── di/                        AppGraph 懒加载容器 + Compose/Service 双侧取用
├── data/                      Room 实体/DAO/仓储、DataStore 设置、领域模型
├── net/                       后台协议 DTO 与同步 API（纯明文 JSON）
├── sync/                      网络回调、WorkManager 队列与 Worker（静默重试 3 次）
├── media/                     Media3 缓存层（SimpleCache + CacheDataSource）、共享播放器、预取
├── util/                      视频链接识别与来源站点标注、剪贴板读取封装
├── floating/                  全局悬浮球 + 悬浮收藏面板（Service 窗口内的 Compose 宿主）
└── ui/
    ├── MainActivity.kt        入口、旋转与权限处理、分享链接承接
    ├── ShareReceiverActivity.kt  系统分享接收（无界面，转交悬浮面板或主 App）
    ├── theme/ components/     Material3 主题与平板热区控件
    ├── main/                  自适应主界面：VideoWall / VideoCard / NavPane / DetailPane + MainViewModel
    └── settings/              SettingsSheet

mock-server/                   本地 mock 同步服务（明文协议 + 确定性假视频解析）
.github/workflows/             云端构建 APK
.github/BUILD_TRIGGER          构建开关：改它才会重新出包
docs/                          使用说明、架构与性能验证、同步后台接口说明
```

## 五、需求对照

| 需求 | 落点 |
| --- | --- |
| Android 10+ | `minSdk = 29` |
| 旋转不丢状态 | `fullSensor`（横竖屏都有排版）+ `configChanges`（Activity 不重建）+ 进程级草稿 `FloatingDraftStore` + `rememberLazyGridState`/`rememberLazyListState` 滚动位置 |
| 触控目标 ≥10mm、边缘控件距屏 ≥20mm | `TabletMetrics` 用 `xdpi/ydpi` 真实物理 DPI 换算，并与 `sw600dp/sw720dp` 三档 dimens 基线取大 |
| 间距比手机 +50% | `values/dimens.xml` 以 8dp × 1.5 为 `gap_sm` 基线，sw600/sw720 逐档加宽 |
| 按 sw600dp/sw720dp 自适应 | 三档基线见 `values` / `values-sw600dp` / `values-sw720dp`；宽屏走「导航 + 视频墙 + 详情」分栏，窄屏/竖屏以视频墙为主体、详情整屏打开 |
| 常驻 ≤80MB / 冷启动 ≤2s | `AppGraph` 全 lazy、单一 `ExoPlayer` 实例复用、无第三方 DI/无启动器插件；测量步骤见 `docs/架构与性能验证.md` |
| 后台静默同步不吃电量 | 只用 `ConnectivityManager` 回调触发，零轮询、零定时器 |
| 悬浮球拖拽 + 贴边半隐 | `TYPE_APPLICATION_OVERLAY` 窗口 + `ValueAnimator` 吸附，位置持久化 |
| 复制即弹轻提示 | Android 10 起非焦点 App 禁止后台读剪贴板 → 采用悬浮球手动粘贴 + 系统分享接收（面板内已说明，见 `docs/使用说明.md`） |
| 面板四段结构（只读链接 / 手写备注 / 横滚标签 + 内联新建标签 / 保存同步·关闭） | `FloatingPanelScreen` |
| 手写笔直接书写 | `StylusNoteField`：AndroidView 承载真实 `EditText`，复用 Android 14+ 对可编辑文本框默认开启的手写笔直写；更早系统用手写输入法，无需额外权限 |
| 上报 url+备注+标签+来源+时间戳 | `SyncApi` 明文 JSON `POST /sync`；成功只认 `applied + unknown_ops == 送出条数`，对不上就重新排队，绝不静默丢数据 |
| 一条收藏链接 ≠ 一个视频 | 收藏只存用户数据；标题/封面/时长/播放地址由后台解析后经 `video_info` 表缓存，卡片区分「解析中 / 已就绪 / 解析失败」并可手动重新解析 |
| 新收藏先进本地队列，按节奏逐条上传 | `SyncWorker` 每次只取 1 条，等待 `响应 next_wait_ms ?? 设置里的上传间隔（默认 3000ms）` 再传下一条 |
| 2 秒轻提示 + 自动收起 + 回到原 App | Toast `LENGTH_SHORT`，面板窗口关闭，不启动任何 Activity |
| 断网静默重试 3 次后进队列自动补传 | `SyncWorker` + `WorkManager` 网络约束与线性退避 + `pending_ops` 队列表 |
| 以视频列表为主的大屏（自适应横竖屏） | `MainScreen`：`>=900dp` 左栏导航 + 视频墙，`>=1120dp` 再加常驻详情栏；更窄时整屏视频墙 + 整屏详情，列数按可用宽度算（2~5 列） |
| 内置播放器倍速、拖动、全屏 | `PlayerStage`（详情栏内嵌 16:9，可一键全屏并沉浸系统栏，复用同一个 ExoPlayer 实例；倍速走选择器，全屏点画面只切换控制条显隐） |
| 备注编辑、标签调整、批量删除 | `DetailPane` + `VideoWall` 长按多选批量条 |
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
