# AGENTS.md — GameMonitor Android 工程交接说明

> 面向「下一个接手改代码 / 出新版本」的 agent。读完这份文件应能直接编译、改版本、签名、打包，不需要再向人确认环境。
> 所有路径与版本号均为本机实测结果（核实命令见文末「附录 A」）。

---

## 1. 项目定位与两个工程的关系

| 工程 | 路径 | 技术栈 | 说明 |
| --- | --- | --- | --- |
| **Android 版（本工程）** | `D:\Project\Trae_Work\GameMonitorAndroid` | Kotlin + Jetpack Compose（Material 3） | 本文档主体 |
| **Windows 桌面版** | `D:\Project\Trae_Work\GameMonitorWindows`<br>`GameMonitor.csproj` | **.NET 10 WinForms**（`net10.0-windows`、`UseWindowsForms`、版本 1.3.0） | 独立产品 |

> 历史路径说明：两个工程原先位于 `T:\Amateur_Project\Trae\...`（SMB 映射盘，UNC `\\10.10.10.10\Archives\...`），**已迁移到上述 `D:\Project\Trae_Work\...`**；旧的 `T:` / UNC 路径不再使用，若在其他脚本或旧文档里看到，请按新路径替换。

**关键约束**

- 两个工程**没有代码共享**，不存在共同模块、共同资源、共同版本号。**不要跨项目改文件**，也不要把 Android 侧的改动「同步」到 Windows 侧。
- 两者是同一产品思路的两个实现，功能不必强行对齐；Windows 版有自己的迭代节奏。
- 两个工程现在都在**本地盘** `D:\Project\Trae_Work\` 下，不再依赖 SMB 映射；若某台机器上仍挂着旧的 `T:` 映射，请忽略，不要从旧路径构建。

---

## 2. 工具链位置与版本（本机实测）

| 组件 | 路径 | 实测版本 |
| --- | --- | --- |
| JDK | `D:\Development\jdk-21` | `openjdk 21.0.13 2026-10-20 LTS`（Temurin-21.0.13+9） |
| Android SDK | `D:\Development\android-sdk` | platform-tools `37.0.1`（adb 1.0.41）、build-tools `35.0.0`、`platforms;android-35` |
| Gradle | `D:\Development\gradle\bin\gradle.bat` | `Gradle 8.11.1`（内嵌 Kotlin 2.0.20，Launcher JVM 21.0.13） |
| Gradle Wrapper | 工程根 `gradlew.bat` + `gradle\wrapper\gradle-wrapper.properties` | `distributionUrl=...gradle-8.11.1-bin.zip` |

- `local.properties` 当前内容：`sdk.dir=D:/Development/android-sdk`（**正斜杠**；改成反斜杠会触发 `local.properties` 转义告警，虽不影响构建但会多出警告）。
- **`JAVA_HOME` / `ANDROID_HOME` 只在单条命令内临时设置**，不要永久写入系统环境变量，也不要修改用户全局配置。
- 若某台机器缺上述工具链，用户已授权自行安装到 `D:\Development\`（各组件独立子目录，不装 C 盘、不影响 Android Studio），但**不要擅自改系统环境变量**。
- SDK 许可可用 `sdkmanager --licenses` 非交互接受（`yes | sdkmanager --licenses`）。

---

## 3. 签名密钥（安全红线，必须严格遵守）

| 文件 | 路径 | 说明 |
| --- | --- | --- |
| 密钥库 | `D:\Development\gm-keys\game-monitor-release.jks` | RSA 2048，alias `gamemonitor`，有效期至 **2054-03-24**（约 27.4 年），2,770 字节 |
| 口令备份 | `D:\Development\gm-keys\PASSWORD.txt` | 含别名、口令、生成日期、备份警告 |
| 签名参数 | `D:\Development\gm-keys\keystore.properties` | 字段：`storeFile`（绝对路径 `D:/Development/gm-keys/game-monitor-release.jks`）、`storePassword`、`keyAlias=gamemonitor`、`keyPassword` |

**三个文件都在工程外，工程内不得出现任何密钥或口令文件。**

### 绝对禁止

1. **不得生成、读取、打印或注入任何口令**；不得把口令写进命令行参数、环境变量、日志、构建产物或回复正文。
2. **不得覆盖已存在的 jks**（重复执行 `keytool -genkeypair` 会在「别名已存在，是否覆盖」处卡住，且覆盖后旧包永远无法升级）。
3. **不得把 `game-monitor-release.jks`、`PASSWORD.txt`、`keystore.properties` 复制进 `dist/` 或任何 zip**。
4. 需要校验密钥库时，只能让 Gradle 自己读 `keystore.properties`；如需人工核验，由用户在其终端执行，agent 只看 `keytool` 的非敏感输出（DN、算法、有效期、指纹）。

### 读取优先级（`app/build.gradle.kts` 内部实现）

```
GM_KEYSTORE_PROPERTIES（环境变量或 -D 系统属性）→ 默认 D:/Development/gm-keys/keystore.properties
字段回退：storeFile→GM_STORE_FILE、storePassword→GM_STORE_PASSWORD
         keyAlias→GM_KEY_ALIAS（默认 gamemonitor）、keyPassword→GM_KEY_PASSWORD（缺省回落 storePassword）
```

- `storeFile` 按 `File(path)` 解析，**支持绝对路径**，不拼接工程目录。
- 工程内有守卫任务 `verifyReleaseSigning`，挂在 `assembleRelease` / `bundleRelease` 的 `dependsOn` 上；缺任一字段或密钥库文件不存在时**直接 `GradleException` 失败并列出缺项**，不会静默产出未签名包。实测报错样例：

```
未找到可用的 release 签名配置：D:\Development\gm-keys\keystore.properties
缺少：storeFile（GM_STORE_FILE 或 properties 中的 storeFile）、storePassword（GM_STORE_PASSWORD）、keyPassword（GM_KEY_PASSWORD）
```

### 备份重要性

jks 或口令丢失后，已安装设备**无法用同签名新包覆盖升级**，只能换包名重装（配置、统计、管理密码全部丢失）。分发时务必提醒使用者备份密钥。

---

## 4. 构建命令（可直接复制）

### 4.1 Debug

```powershell
$env:JAVA_HOME="D:\Development\jdk-21"
$env:ANDROID_HOME="D:\Development\android-sdk"
& "D:\Development\gradle\bin\gradle.bat" --no-daemon --no-build-cache assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（当前实测 26,497,613 字节）

### 4.2 Release（签名）

```powershell
$env:JAVA_HOME="D:\Development\jdk-21"
$env:ANDROID_HOME="D:\Development\android-sdk"
& "D:\Development\gradle\bin\gradle.bat" --no-daemon --no-build-cache assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`（0.1.2 实测 19,717,438 字节）

0.1.2 内容：密码门统一入口校验 + `onStop` 重新锁定、取消宽限期、倒计时跨切换按绝对时间继续、加时（默认 30 分钟 / 1–720）、底部 TabRow `navigationBarsPadding()`。

签名配置由 Gradle 从 `keystore.properties` 内部读取，**命令里不需要、也不允许出现口令**。

### 4.3 校验（两条都要跑）

```powershell
# 签名：DN / 算法 / 有效期 / v2 / v3
& "D:\Development\android-sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs --verbose `
  "app\build\outputs\apk\release\app-release.apk"

# 版本与标签
& "D:\Development\android-sdk\build-tools\35.0.0\aapt2.exe" dump badging `
  "app\build\outputs\apk\release\app-release.apk" | Select-String "package:|application-label|uses-permission"
```

> `apksigner.bat` 需要 `JAVA_HOME` 可用；若报找不到 java，在同一会话先设 `$env:JAVA_HOME`。

### 4.4 分发打包（dist）

```powershell
$root="D:\Project\Trae_Work\GameMonitorAndroid"
$dist="$root\dist"; New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item "$root\app\build\outputs\apk\release\app-release.apk" `
  "$dist\GameMonitor-<version>-release.apk" -Force
# 安装说明另写 $dist\安装说明.txt，然后：
Compress-Archive -Path "$dist\GameMonitor-<version>-release.apk","$dist\安装说明.txt" `
  -DestinationPath "$dist\GameMonitor-<version>-android.zip" -CompressionLevel Optimal
```

zip **只允许**包含：签名 APK + `安装说明.txt`。打包后必须用下面的方式列出条目自查：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::OpenRead("$dist\GameMonitor-<version>-android.zip").Entries |
  Select-Object FullName,Length
```

当前 0.1.2 产物：

| 文件 | 字节数 |
| --- | --- |
| `dist\GameMonitor-0.1.2-release.apk` | 19,717,438 |
| `dist\GameMonitor-0.1.2-android.zip` | 6,680,311（内含 APK + 安装说明.txt 共 2 条目） |
| `dist\安装说明.txt` | 2,058 |

---

## 5. 版本号规则

- `versionName` 语义化（当前 `"0.1.2"`）。
- `versionCode` **必须单调递增**（当前 `12`），保证能覆盖安装。
- 改版本时**三处必须同步**，否则文档与包不一致：
  1. `app\build.gradle.kts` 的 `defaultConfig { versionCode / versionName }`
  2. `README.md` 顶部版本说明 + 「发布与分发」小节
  3. `APP介绍.md` 的「版本信息」表 + 文件名中的版本号
- 分发文件名同样带版本：`GameMonitor-<version>-release.apk` / `GameMonitor-<version>-android.zip`。

---

## 6. 已知坑（现象 → 解法）

| # | 现象 | 解法 |
| --- | --- | --- |
| 1 | 旧文档/旧脚本里出现 `T:` 或 `\\10.10.10.10\Archives\...`（`DriveNotFound`） | 工程已迁移到 `D:\Project\Trae_Work\GameMonitorAndroid` 与 `D:\Project\Trae_Work\GameMonitorWindows`，把旧路径直接换成新路径即可 |
| 2 | 网络盘构建慢、偶发文件锁冲突（`Failed to delete` / `file in use`） | 重试一次；持续失败则复制工程到 `D:\Development\GameMonitor-Android` 本地构建，产物再拷回 `dist` |
| 3 | `clean` 后首次重建报 Gradle state-tracking：`mapDebugSourceSetPaths` 输出 `data_binding_layout_info_type_merge` 不存在、`Failed to create MD5 hash ... does not exist` | 删除 `app\build`、`.gradle`、`.kotlin` 三个目录后重跑 `--no-daemon --no-build-cache`，不要反复 `clean` |
| 4 | 构建警告 `Unable to strip the following libraries: libandroidx.graphics.path.so, libdatastore_shared_counter.so` | **正常**，预编译 native 库原样打包，忽略即可 |
| 5 | 只有 v2 签名，v3 为 false | 侧载无影响；若需密钥轮换，在 `signingConfigs.release` 加 `enableV3Signing = true` 后重签 |
| 6 | debug 包与 release 包签名不同，覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 先卸载旧包（**卸载会清空 DataStore：条目/时段/统计/管理密码全丢**），或让使用者知晓代价 |
| 7 | `local.properties` 用反斜杠 | 改成 `sdk.dir=D:/Development/android-sdk`，避免转义告警 |
| 8 | 打包后 zip 里混入密钥 | 严格按 4.4 的两个文件路径传入 `Compress-Archive`，并用 4.4 的条目自查命令确认只有 2 条 |

---

## 7. 必须了解的现状（避免误判「已验证」）

1. **0.1.2 从未在真机 / 模拟器上运行过**。已完成的只有：`assembleDebug`、`assembleRelease`、`apksigner verify`、`aapt2 dump badging`。任何「运行时正常」的判断都必须重新实测；`adb devices` 当时为空。
2. **`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` 均未声明**：计时用 1 秒前台 tick，全库没有 `setExact` / `setAlarmClock` / `setRepeating` 调用，也不再有 `canScheduleExactAlarms` 探测。**不要再加回这两个声明**。
3. **无障碍自动点击默认关闭**（`AppSettings.autoForceStopEnabled = false`），作用域限定：
   - 仅系统设置类包（`com.android.settings` 及 MIUI/ColorOS/OriginOS/EMUI/三星等白名单）；
   - 仅本应用自己 `startActivity` 打开的「应用详情页」期间；
   - **15 秒超时**，超时立即停止一切自动动作；
   - 2 秒节流，最多点击一次「强行停止」，每次动作写日志 `GameMonitor.A11y`。
   - **浮层一律 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`，不得改回可触摸**——历史上全屏可触摸浮层曾导致「在其他应用界面自动滑动」的投诉。
4. **隐藏桌面图标前置条件**：必须先设置管理密码。恢复动作只能由「权限」页「恢复图标」按钮执行，进入该页前需过 PIN 门禁：
   - 通知正文/「管理」、桌面图标、快捷方式等任何入口 → `MainActivity` 统一密码门 → 「权限」页（默认再验一次）；
   - 通知/图标入口本身**不得**改变隐藏状态。
   - **不得增加任何免密恢复路径**（包括拨号恢复——该功能已被明确要求整套删除，且已删除，不要重新引入）。
   - PIN 规则：4–8 位数字，SHA-256 + 16 字节随机盐；连续错 3 次锁 30 秒（`lockout_until` 持久化到 DataStore）；PIN 页无「忘记密码 / 跳过」。
5. 通知文案必须中性（标题「游戏监控运行中」），action 标签只能用「管理」这类中性词，**不得出现「恢复图标 / 一键恢复」字样**。
6. 主界面 `MainActivity` 为 `excludeFromRecents="true"`，不进最近任务；前台服务通知必须保留，否则服务易被杀。
7. **安全红线**：设置过 PIN 后，任何入口都必须先过 `MainActivity` 的密码门；解锁状态仅限当前前台会话，`onStop` 立即重新锁定；任何入口/操作都不得改变 `activity-alias` 的隐藏状态（恢复只能由「权限」页显式操作）。

---

## 8. 代码结构速览

```
app/src/main/java/com/gamemonitor/android/
├── data/          持久化与模型
│   ├── Models.kt              MonitoredApp / ScheduleRule / UsageRecord / AppSettings / Snapshot
│   ├── GameRepository.kt      DataStore + org.json 序列化（apps / rules / settings / usage 四个 key）
│   ├── SecurityRepository.kt  独立 DataStore：pin_hash / pin_salt / settings_require_pin / lockout_until
│   ├── PinManager.kt          PIN 校验、失败计数、30 秒锁定、SHA-256+盐
│   └── TimeUtils.kt           todayKey / isoDay / minuteOfDay / clockText / hhmm
├── domain/        纯逻辑，无 Android UI 依赖
│   ├── MonitorState.kt        状态枚举（IDLE/RUNNING/COUNTING_DOWN/COOLDOWN/QUOTA_EXHAUSTED/BLOCKED_BY_SCHEDULE）
│   ├── MonitorEngine.kt       ★ 单点状态机：updateSnapshot / tick / evaluate / advanceSession / enforceBlocked / rotateDayIfNeeded
│   ├── ScheduleMatcher.kt     时段判定（跨零点、禁止优先、开放时段反向限制）
│   └── InstalledAppsProvider.kt  可启动应用枚举（排除系统应用与自身）
├── service/       系统集成
│   ├── MonitorService.kt      前台服务（1 秒 tick、通知刷新、START_STICKY）
│   ├── ForegroundDetector.kt  UsageStatsManager 增量 queryEvents 判定前台
│   ├── EnforcementManager.kt  权限探测 + setPackagesSuspended / goHome / 各设置页跳转
│   ├── GameAccessibilityService.kt  严格作用域自动动作 + 返回桌面
│   ├── OverlayController.kt   全屏悬浮层（不可触摸，不拦截手势）
│   ├── Notifier.kt            通知渠道 + 常驻通知（含条件 action「管理」）
│   ├── LauncherIconController.kt  activity-alias 启用/禁用 + adb 兜底命令常量
│   ├── MonitorDeviceAdminReceiver.kt  Device Owner
│   └── BootReceiver.kt        BOOT_COMPLETED / MY_PACKAGE_REPLACED 自启
├── ui/            Compose 页面
│   ├── AppRoot.kt             五 Tab（条目/时段/统计/样式/权限）+ TabRow 高度 60dp + 设置页 PIN 门禁
│   ├── AppsScreen.kt          条目 CRUD、单条加时（默认 30 分钟）、服务开关、当前前台显示
│   ├── ScheduleScreen.kt      时段规则（TimeInput + 周几 FilterChip）
│   ├── StatsScreen.kt         今日统计
│   ├── TextSettingsScreen.kt  三类文案 + 字号/颜色/透明度 + 倒计时 + 自动强行停止开关
│   ├── PermissionsScreen.kt   权限逐项 + 管控等级 L1/L2/L3 + 管理密码卡 + 隐藏/恢复图标 + adb 命令
│   └── PinGateScreen.kt       可复用 PIN 输入（错误提示、锁定倒计时、无后门）
├── MainActivity.kt            全部入口统一 PIN 门（onStop 重新锁定）、ACTION_HIDE_ICON / ACTION_OPEN_APP
└── GameMonitorApp.kt          Application：repository / securityRepository / pinManager / enforcement
```

资源侧：`res/xml/shortcuts.xml`（静态快捷方式 `hide_icon` / `open_app`）、`res/xml/accessibility_service_config.xml`（仅设置类包 + `flagDefault`）、`res/values/themes.xml`（仅 `Theme.GameMonitor`，0.1.2 起 `Theme.GameMonitor.Dialog` 已删除）。

### 核心不变量（改代码时必须保持）

1. **计时只用 `SystemClock.elapsedRealtime()` 差值**，不用 `System.currentTimeMillis()` 做时长计算（后者会被用户改时间绕过）。
2. **`tick` 全程 try/catch**，任何异常只记日志，不能让前台服务崩掉。
3. **状态机单点**：所有状态迁移只在 `MonitorEngine` 内发生；UI 与服务只读取快照，不直接改状态。
4. 拦截顺序固定：Device Owner 挂起 → 浮层 + 无障碍返回桌面 → 仅浮层 → 仅通知，逐级降级。
5. 前台判定只依赖 `UsageStatsManager`（历史上的 `AccessibilityTracker` 已删除，不要恢复）。
6. 日切由 `rotateDayIfNeeded` 处理，跨天自动重置当日用量。

---

## 附录 A：本文档事实的核实命令（可复现）

```powershell
# JDK
& "D:\Development\jdk-21\bin\java.exe" -version          # openjdk 21.0.13 2026-10-20 LTS / Temurin-21.0.13+9
# Gradle
$env:JAVA_HOME="D:\Development\jdk-21"; & "D:\Development\gradle\bin\gradle.bat" -v
                                                          # Gradle 8.11.1 / Launcher JVM 21.0.13 / OS Windows 11
# adb
& "D:\Development\android-sdk\platform-tools\adb.exe" version   # 1.0.41 / 37.0.1-15733141
# SDK 组件存在性（全部 True）
foreach($p in @('D:\Development\android-sdk\platform-tools\adb.exe',
                'D:\Development\android-sdk\build-tools\35.0.0\aapt2.exe',
                'D:\Development\android-sdk\build-tools\35.0.0\apksigner.bat',
                'D:\Development\android-sdk\platforms\android-35\android.jar')) { Test-Path $p }
# local.properties / wrapper
Get-Content local.properties                 # sdk.dir=D:/Development/android-sdk
Get-Content gradle\wrapper\gradle-wrapper.properties   # gradle-8.11.1-bin.zip
# 两工程存在性
Test-Path 'D:\Project\Trae_Work\GameMonitorAndroid'   # True
Test-Path 'D:\Project\Trae_Work\GameMonitorWindows'   # True
# Windows 版技术栈
Get-Content 'D:\Project\Trae_Work\GameMonitorWindows\GameMonitor.csproj'
    # net10.0-windows / UseWindowsForms / Version 1.3.0
# 密钥库（不读口令）
Get-Item 'D:\Development\gm-keys\game-monitor-release.jks' | Select-Object Length,LastWriteTime  # 2770
# 版本号来源
Select-String -Path app\build.gradle.kts -Pattern "versionCode|versionName"   # 12 / "0.1.2"
# 无 setExact 调用（应无输出）
Select-String -Path app\src\main\java -Pattern "setExact|setAlarmClock|setRepeating" -Recurse
# 无 INTERNET 声明（应无输出）
Select-String -Path app\src\main -Pattern "permission.INTERNET" -Recurse
# 产物与 dist
Get-ChildItem app\build\outputs\apk\release   # app-release.apk 19717438
Get-ChildItem dist                            # apk 19717438 / zip 6680311 / 安装说明.txt 2058
```

---

## 附录 B：接手后的最小动作清单

1. `Test-Path T:\`；不可见则全程用 UNC。
2. 确认 `D:\Development\gm-keys\keystore.properties` 存在（只 `Test-Path`，不读内容）。
3. 改版本 → 同步三处（build.gradle.kts / README.md / APP介绍.md）。
4. `assembleRelease` → `apksigner verify --print-certs --verbose` → `aapt2 dump badging`。
5. 复制到 `dist\GameMonitor-<version>-release.apk`，写 `安装说明.txt`，打 zip，列条目自查。
6. 回复中**不要**出现任何口令；如实标注「未实机验证」。
