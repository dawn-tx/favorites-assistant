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

- 协议是纯明文 JSON，数据接口只有三个：`POST /sync`（上传变更，回执直接带回解析好的收藏）、`GET /favorites?limit=&cursor=`（分页取收藏）、`POST /resolve`（手动重解析），另加免鉴权的 `GET /health`。
  没有信封加密、没有 RSA 密钥，也**没有取解析结果的轮询接口** —— 后台保存与解析在同一次调用里完成，所以协议里没有 `pending` 状态。
  `GET /favorites` 按 `favorite_at` 倒序做 keyset 分页，App 侧本地表只是它的分页缓存。
- 启动日志会列出本机所有非内部 IPv4 地址，平板照抄 `http://<电脑内网 IP>:8787/api/v1` 即可。
- 标题、作者、站点、`summary` 与 `segments[]` 素材清单（封面、可播地址、时长、清晰度都在段上）由服务端按 url 哈希确定性生成（`resolver: mock`），
  内联在每条收藏的 `media` 字段里：同一链接永远同一结果，四类构成（图+视频 / 纯图集 / 多段视频 / 单个视频）按哈希均匀出现，用来真跑一遍多素材展示链路。
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
    ├── main/                  自适应主界面：VideoWall / VideoCard / FocusOverlay（聚焦遮罩层）/ FilterSheet（筛选抽屉）+ MainViewModel
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
| 按 sw600dp/sw720dp 自适应 | 三档基线见 `values` / `values-sw600dp` / `values-sw720dp`；主界面不按宽度分栏，只有「视频墙 + 聚焦遮罩」两层，宽度只决定墙的列数与遮罩里信息栏是否并列 |
| 常驻 ≤80MB / 冷启动 ≤2s | `AppGraph` 全 lazy、单一 `ExoPlayer` 实例复用、无第三方 DI/无启动器插件；测量步骤见 `docs/架构与性能验证.md` |
| 后台静默同步不吃电量 | 只用 `ConnectivityManager` 回调触发，零轮询、零定时器 |
| 悬浮球拖拽 + 贴边半隐 | `TYPE_APPLICATION_OVERLAY` 窗口 + `ValueAnimator` 吸附，位置持久化。贴边判定看**球心到边的距离**（小于一个球径就吸边），停靠位只留 12dp 宽的细边露在外面、离屏 4dp，够手指拨出来；半隐态点一下先展开复原，再点才开面板 |
| 同步进度在悬浮球上可见 | `BallBadge`：`pending_ops` 两条 COUNT（总数 / `attempts>=3`）+ 联网回调，写触发无轮询；口径合并为「待同步 / 失败 / 离线」三项，数字直接画在球心替换图标（画在圆 Surface 边角上的小角标会被窗口裁掉），色调即底色，半隐时数字不显示、只留底色 |
| 复制即弹轻提示 | Android 10 起非焦点 App 禁止后台读剪贴板 → 采用悬浮球手动粘贴 + 系统分享接收（面板内已说明，见 `docs/使用说明.md`） |
| 面板四段结构（只读链接 / 手写备注 / 横滚标签 + 内联新建标签 / 保存同步·关闭） | `FloatingPanelScreen` |
| 手写笔直接书写 | `StylusNoteField`：AndroidView 承载真实 `EditText`，复用 Android 14+ 对可编辑文本框默认开启的手写笔直写；更早系统用手写输入法，无需额外权限 |
| 上报 url+备注+标签+来源+时间戳 | `SyncApi` 明文 JSON `POST /sync`；成功只认 `applied + unknown_ops == 送出条数`，对不上就重新排队，绝不静默丢数据 |
| 一条收藏链接 ≠ 一个视频 | 收藏只存用户数据；后台在**同一次 `/sync` 的响应里**把内容信息随收藏带回：`media{ title, author, site, summary, segments[], failed_reason, resolved_at }`，`segments[]` 就是素材清单（图 + 多段视频），一段一个可播/可看单元。本地存进 `favorites` 那一张表的 `segmentsJson` 一列，不额外建表、不额外发请求 |
| 同一条链接重复分享 | `FloatingSaveUseCase` 保存前先按链接原样查库（`FavoritesRepository.byUrl`，软删除过的不算，且排在建标签之前，免得留下孤儿标签）；命中返回 `SaveOutcome.Duplicate` → 不入库、不排队，面板用 `PanelStatus.NOTICE` 中性色提示已有收藏，草稿不动。刻意不做参数归一化：B 站 `?p=` 分 P 是不同内容 |
| 新收藏先进本地队列，按节奏逐条上传 | `SyncGate` 是所有出站请求（上传、分页拉取、手动重解析，含失败重试）的唯一闸门：一条响应完全结束后再等 `next_wait_ms ?? 上传间隔（默认 3000ms）` 才放下一条，成功、失败、旁路一视同仁；`SyncWorker` 每次只取 1 条 |
| 2 秒轻提示 + 自动收起 + 回到原 App | Toast `LENGTH_SHORT`，面板窗口关闭，不启动任何 Activity |
| 断网静默重试 3 次后进队列自动补传 | `SyncWorker` + `WorkManager` 网络约束与线性退避 + `pending_ops` 队列表 |
| 以视频列表为主的大屏（自适应横竖屏） | `MainScreen` = 整屏 `VideoWall`（列数按可用宽度取 2~5）+ 盖在墙上的 `FocusOverlay`；筛选、标签、同步状态收进顶栏 `FilterSheet` 抽屉 |
| 后台返回内容一目了然 | `VideoCard` 按 `segments[]` 真实构成变形（单图 / 等分拼格 / 首图 + 缩略条），左上角标构成（`4图 · 2段`），封面底部那条渐变带上是标签（最多 2 个 + 「+N」，尾部省略）+ 首段「清晰度 · 时长」+「+N」段数 + 同步/缓存色点；文字区是标题一行 + `summary` 两行；封面按卡片实际宽度解码，控制在 80MB 预算内。没有内容时占位块直接写是「解析失败」（附原因）还是「内容信息还没取回来」 |
| 看详情不丢浏览位置 | `FocusOverlay` 是墙的**兄弟节点**而不是分支：墙不退出组合，滚位与已解码封面都还在；逐段浏览用 `HorizontalPager` + 段条，段与段各自记进度；**换收藏改成整层 `VerticalPager` 上下滑**（跟手、吸附和媒体区翻段同一套手感，不再有两侧手势条），顺序在打开遮罩那一刻洗牌、进来那条固定是第一页 —— 向上滑是随机换一条，向下滑退回刚才那条；非当前页只渲染封面（一个 ExoPlayer 只能喂一个画面），落定才装载；关闭时先 `pausePlayback()` 再退（以前只解绑不暂停，声音会跟回墙后面），回到原位置并让那张卡闪一下边框 |
| 内置播放器倍速、拖动、全屏 | `PlayerStage`（遮罩媒体区内嵌 16:9，可一键全屏并沉浸系统栏，复用同一个 ExoPlayer 实例；倍速走选择器，全屏点画面只切换控制条显隐） |
| 备注编辑、标签调整、批量删除 | `FocusOverlay` 信息栏（备注、标签点一下即生效、缓存、删除）+ `VideoWall` 长按多选批量条 |
| 服务端为准 + 本地分页缓存 | 后台是收藏的事实来源，Room 这张表只缓存滚到过的那几页：冷启动发现空表自动取第一页、墙离底还有两屏静默补一页（`GET /favorites?limit=&cursor=` 走 keyset 游标），一次 50 条；每页同时带回 `total`（后台总数），**只有 `has_more` 为真时**才用那个数（墙头「已取回 50 / 1280 条」、顶栏「共 1280 · 已取回 50」）；页取全了本地就是镜像，改口只报本地条数 —— 总数只在翻页时刷新，删一条还拿它比就会说出「共 20 · 已取回 19」；写操作仍先落本地 + 排队上传，离线能翻已取回的页 |
| 视频离线可播 | `SimpleCache` 边播边存 + 单条「缓存到本地」预取（可显示进度、随时取消，上限 128MB~2GB 五档）；条目本身、备注与标签在本地库里，离线照常翻。「清空视频缓存」把字节和 `favorites.locallyCached` 一起清掉 —— 卡片那个绿点和遮罩里「已缓存，离线可直接播放」读的就是这一列，只删字节会留下「明明清完了还说着已缓存」 |
| 顶栏那颗按钮只做一件事、且不说歧义话 | 圆形「刷新」图标撤了——它一直被认为是在刷新墙列表。换成跟着当前该做什么写的**文字按钮**：有失败 →「重试上传 N」（红字），队列里还有 →「继续上传 N」，正在传 → 原地转圈，一切干净 → **不出现**。点它是把重试耗尽/被拒的任务重新排队并立刻跑一轮 `SyncWorker`，四种情况各有轻提示（离线 / 已重新排队 N 条 / 已推动继续上传 / 本地与后台一致）。要重新拉列表请用筛选抽屉里的「从后台加载」。状态胶囊三枚 `离线 / 待同步 N / 失败 N` 全部只数上传队列，失败那批已从待同步里减掉；解析失败不占任何全局计数，只是那条卡片上的一行字 |

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
