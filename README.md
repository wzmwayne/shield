# 锁盾 Shield（wzmwayne.shield）

> ⚠️ **早期版本（0.1.0）**：这是面向**自己设备**的防护原型，不是通用安全产品。
> 部分能力（阻断、清理数据、卸载、调试接口）**会显著改变设备状态**，请先读完 README 再上手。
> ⚠️ **调试 HTTP 接口**默认关闭；一旦开启（固定绑定 `0.0.0.0`）**同一网络内知道 PIN 的人即可控制本机**
> （安装 APK、触发阻断、改白名单、截屏）。接口是明文 HTTP，仅建议在可信网络临时开启，用完请关闭。


## 一、已实现

### 1. 激活向导（六步，顺序按「先拿到 Shizuku，再判断能否激活」安排）
| 步骤 | 实现位置 | 说明 |
| --- | --- | --- |
| ① 辅助软件 | `activation/AuxAppInstaller.kt` | 内置 **Shizuku 官方版 13.6.0.r1086** 与 **雹 Hail 1.11.0** APK；Dhizuku **不内置** |
| ② 环境检查 | `activation/EnvChecker.kt` | 用 Shell 身份读真实状态，只回答「现在能不能进入激活」；数据取不到记 UNKNOWN 并计入阻塞；Shizuku 不可用时给出「使用 adb 激活」卡片（内置脚本，伪装 `.so` 随 APK 释放 + 回读校验） |
| ③ 所有者模式 | `core/Models.kt`、向导第 3 步 | **强制锁盾自身持有**（`OwnerMode.DIRECT`）；委派（Dhizuku）模式已禁用 |
| ④ 执行激活 | `activation/OwnerSetup.kt` | `dpm set-device-owner` → `pm grant WRITE_SECURE_SETTINGS` → `pm grant DUMP` → 预置恢复令牌（`setResetPasswordToken`），随后**回读校验** |
| ⑤ 无障碍与保活 | `activation/AccessibilityActivator.kt` | 用 WRITE_SECURE_SETTINGS 写 `enabled_accessibility_services`，启动保活层 |
| ⑥ 自检 | `activation/SelfCheck.kt` | 逐项检查，**点任意条目右侧按钮即可就地修复**（无需回退步骤） |

设计约束的落实：激活阶段**不添加** `DISALLOW_DEBUGGING_FEATURES`、**不通过 Shell 写**无障碍设置、Manifest **不声明 `testOnly`**、不内置 Dhizuku、运行时不用 Shizuku。

### 2. 无障碍保活（只保活锁盾自身）
参考 `luqijun9/AccessibilityManager` 的保活思路，差异是**永远只服务锁盾自己**。

| 机制 | 实现 | 说明 |
| --- | --- | --- |
| 保活（设置变化） | `KeepAliveService` 的 ContentObserver → `KeepAliveEngine` | 监听 `enabled_accessibility_services`，自身被移出立即写回 |
| 无障碍内节拍 | `ShieldAccessibilityService.logicTick` | **每 10 秒**自检 + 拉起前台服务 + 覆盖层巡检 |
| 定时检测（应用侧） | `KeepAliveEngine.schedulePeriodicAlarm` + `PeriodicCheckReceiver` | `setAndAllowWhileIdle`；**默认 30 秒，步进 10 秒，下限 10 秒**（息屏深度休眠时由无障碍内 10 秒节拍兜底） |
| 崩溃检测（假死） | `CrashDetector` + `core/logic/CrashParser.kt` | `dumpsys accessibility` 的 `Crashed services:` 行；需要 **DUMP** 权限 |
| 解锁检测 | `KeepAliveService` 动态注册广播 | `ACTION_USER_PRESENT` 后复查 |
| 开机/升级 | `BootReceiver` | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` |
| 延迟 1 秒保活 | `KeepAliveEngine.fix()` | 关闭 → 延迟 1000ms → 重新写入 |
| 复查与重试 | `KeepAliveEngine.recheck()` | 5 秒后复查，最多重试 2 次 |
| 重启强杀 app | `KeepAliveEngine.forceRestart()` | 服务仍在列表却不健康时才强杀自身进程；带 **15 分钟退避**与 **6 小时窗口内 3 次上限** |
| 语义 | — | **只要保活开关（或自保护）开着，被移出启用列表就一律写回**（包括用户在系统设置里手动关闭）。要真正停掉：先关保活开关，或用仪表盘「一键自杀」 |

### 3. 自我保护（第 40-45 条）
`protection/SelfProtection.kt`：设备所有者身份、进程存活、无障碍保活、卸载阻止（`setUninstallBlocked`）、自身权限校验（WRITE_SECURE_SETTINGS 有效性）、无障碍白名单自锁防护。生效时弹 Toast；自保页可「重新应用 / 立即校验 / 逐项修复」。

### 4. 感知层
`accessibility/ShieldAccessibilityService.kt` + `accessibility/EventRouter.kt`：窗口切换 → 前台应用追踪与高频切换；点击 → 5 秒高频点击；窗口变化 → 敏感界面出现。事件进入本地环形日志（最多 200 条，仅存本机）。

### 5. 响应对抗层
`response/` 目录。**两条路径，处置范围严格区分**：

**A. 自动针对性阻断（无需用户操作，绝不做全局处置）**
| 触发 | 判定 | 处置 | 恢复 |
| --- | --- | --- | --- |
| 进入**敏感页**：系统设置任意页面 / 应用安装-卸载器 / 权限页 / **锁盾自身所有页面** | `SensitiveGuard` 包名匹配 + 类名兜底（`UninstallerActivity`/`InstalledAppDetails`/`DeviceAdminAdd`/`AccessibilitySettings`/`DevelopmentSettings`…） | 立即**禁用非白名单应用的无障碍服务**，并把无障碍白名单收紧为「白名单应用 + 本应用」；30 秒冷却 | **离开敏感页 8 秒后自动恢复**（再次进入则取消恢复） |
| **非白名单（或无障碍创建的）覆盖层 ≥ 屏幕 70%** | `OverlaySentinel`（`AccessibilityWindowInfo` 覆盖率；只算 `TYPE_SYSTEM`/`TYPE_ACCESSIBILITY_OVERLAY`，排除系统 UI、锁盾自身、白名单） | 对来源应用**针对性阻断**：隐藏 + 挂起 + 撤销运行时权限；覆盖层标题标为「自动处置（针对性）」 | 覆盖层「恢复」按钮或自保页 |

**B. 全局阻断（唯一入口：用户手势，防伪造）**
- **长按音量上或音量下任意一个 3 秒** → 播 **三长音递升方波提示音**（`shield_trigger.wav`）+ Toast + 覆盖层弹出 → 执行全局处置
- 抗伪造：需真实硬件键持续按住（应用无法伪造硬件长按；注入需 `INJECT_EVENTS`）；两次短按不会累积
- 全局处置五步：① 白名单收紧为仅自身 ② 关闭其它无障碍（原值入快照）③ 禁用开发者选项与 ADB ④ 处置非白名单**第三方**应用（隐藏+挂起+撤权）⑤ 临时断网。**不含卸载与清理数据，系统应用一律跳过**
- 撤销：覆盖层「取消处置并恢复全部」或自保页按钮（按快照回滚）

**C. 紧急处置覆盖层（`PanicOverlay`）**
- `TYPE_ACCESSIBILITY_OVERLAY`；**融合组件**：全部自绘 + 原生 `onTouchEvent`，不注册可点击无障碍节点（`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`），其它无障碍**无法模拟点击**；`filterTouchesWhenObscured=true`；**每 3 秒重新注册**以尽量顶置
- 内容：**流式显示阻断过程** + **可滚动复选列表**（应用名 + 包名；**默认只看非系统应用**，可单选切「用户/系统/所有」，带**刷新**按钮，**全程异步加载**；自动处置失败且仍在运行的项**顶置红标**）
- 按钮组：**清理数据 / 再次禁用 / 恢复 / 卸载选中 / 取消处置并恢复全部**（仅在用户勾选后执行）

**D. 提示音（零第三方依赖）**
`PromptPlayer` 用 `SoundPool` 播放 APK 内置方波 WAV（`res/raw/shield_ready|trigger|alert.wav`，PCM 16bit/44.1kHz/单声道），走**闹钟音量通道**（媒体静音也能出声），失败回退 `ToneGenerator`。已**完全移除 TTS**，不依赖系统语音引擎。

**B2. 抗关闭联动**
| 场景 | 处置 |
| --- | --- |
| 锁盾无障碍 **1 分钟内被关闭 3 次** | **重启无障碍服务**（写回启用列表 + 拉起前台服务，**不重启设备**）+ **执行全局阻断**（10 分钟内最多升级 3 次） |
| **任意阻断过程中**无障碍被关闭 | **中止当前阻断** → 后台**禁用调试** → **重新执行全局阻断** |

> 调试通道开关在**仪表盘**「调试通道」卡片（设置页也有同样的两个按钮）：「禁止调试」= `DISALLOW_DEBUGGING_FEATURES` + `ADB_ENABLED=0` + `DEVELOPMENT_SETTINGS_ENABLED=0`，**全局阻断会自动执行这一步**；「恢复调试」用于测试期重新打开 adb（点「禁止调试」会立刻掐断 adb 通道，属预期）。

> 边界：**自动处置与全局阻断都不卸载、不清除数据**；「清理应用数据」（`clearApplicationUserData`，设备所有者 API）与「卸载」（`PackageInstaller.uninstall`，失败则跳系统应用详情页）**只存在于覆盖层的手动按钮**里。

### 6. 设置页与设备管理面板
- 底部新增「设置」tab：调试通道（禁止/恢复）、设备管理面板入口、关于（版本/包名/永久不做的操作/节拍参数）
- **设备管理面板**（`control/DeviceOps.kt` + `control/DeviceOwnerOps.kt` + `ui/screens/DeviceOpsScreen.kt`）：相机、截屏、状态栏、USB 文件传输、安全模式、多用户、用恢复令牌重置锁屏密码；每个开关执行后**回读真实状态**
- 能力清单参考开源项目 [OwnDroid](https://github.com/BinTianqi/OwnDroid)（GPL-3.0）的 DeviceOwner 操作集合，**代码为本项目自研实现，未包含其源码**，因此锁盾不继承 GPL 义务
- 面板**不提供** `wipeData` / `wipeDevice`（恢复出厂）与重启类操作，符合设计约束

---

## 二、尚未实现
| 模块 | 文件 | 状态 |
| --- | --- | --- |
| 威胁评估引擎（评分与规则聚合） | `threat/ThreatEngine.kt` | 仅接口 + 「只记录不下发」默认实现；当前自动判定用的是敏感页/覆盖层两条硬规则 |
| 清理类操作禁令 | `core/logic/CleanupPolicy.kt` | 已实现并单测：自动路径拒绝 `pm uninstall` / `pm clear` 等 Shell 清理 |
| L4 恢复流程（重置密码 123456/1234 + 锁屏悬浮窗） | — | 未实现 |
| 悬浮球长按、通知栏「紧急阻断」按钮 | — | 未实现 |

---

## 三、构建

### 镜像与依赖（全部中国源，禁止国外源）
- Gradle 发行版：`https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`（`gradle/wrapper/gradle-wrapper.properties`）
- Maven 仓库：阿里云 `google` / `central` / `gradle-plugin` / `public`（`settings.gradle.kts`）
- 内置 APK 来源：GitHub 官方 Release，使用 **gopeed 直连下载**（不经过加速节点），下载目录 `~/sdcard/Download`：
  - `shizuku-v13.6.0.r1086.2650830c-release.apk`（sha256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`，包名 `moe.shizuku.privileged.api`）
    - 此前内置的 Shizuku+（Drop-In）13.7.0.r2745 已移除：该 fork 的 `ShizukuUserServiceManager.getUserServiceStartCmd` 存在空指针，**任何应用绑定 Shizuku 用户服务都会让服务端 `System.exit(1)`**，与官方文档要求的 UserService 通道冲突。
  - `Hail-v1.11.0.apk`（sha256 `3be0c4a210b0472198ea3b675fcbd5720fa16288dcff119557644ea947003236`）
- 版本：AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.10.01、Shizuku API 13.1.5

### 本机（aarch64）特有的适配
1. **aapt2**：AGP 自带的 x86-64 `aapt2` 在 aarch64 上无法启动，`gradle.properties` 指定系统原生 aapt2：`android.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2`
2. **compileSdk = 34**：系统原生 aapt2(2.19) 无法解析 android-35/36 的资源表，因此编译与目标版本降到 34；运行下限仍是 API 28
3. **不使用 AIDL**：本机 `aidl` 崩溃（SIGILL），Shizuku 用户服务的 Binder 协议改为手写实现（`shell/IShieldShell.kt`），destroy 事务号与 Shizuku 常量 `16777115` 对齐

### 命令
```bash
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 39 个纯逻辑单元测试
```

---

## 四、真机验收步骤（建议）

1. 安装 APK：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 向导第 1 步装 Shizuku 并启动（有 root 直接「通过 Root 启动」）、在其中授权锁盾 → 第 2 步「开始检查」（Shizuku 不通时用「使用 adb 激活」里的脚本 + 回读校验）
3. 第 3 步确认「锁盾直接成为设备所有者」→ 第 4 步「执行激活」
4. 第 5 步「启用无障碍并启动保活」→ 第 6 步「运行自检」
5. **调试通道**（仪表盘或设置页）：点「禁止调试」确认 `DISALLOW_DEBUGGING_FEATURES` + ADB 关闭；需要继续用 adb 时点「恢复调试」。
6. 保活验证：`adb shell settings put secure enabled_accessibility_services ""` → 数秒内写回；`adb shell am force-stop wzmwayne.shield` → 进程被拉起
6. **响应层验证**：
   - 打开系统设置任意页面 / 卸载页 / 锁盾自身 → 看 Toast「敏感页看护已生效」，其它无障碍被关闭；退出 8 秒后自动恢复
   - **长按音量键 3 秒**（三长音递升）→ 覆盖层弹出并流式打印处置过程；在覆盖层里切筛选、点刷新、勾选应用后试「清理数据 / 再次禁用 / 恢复 / 卸载选中」
   - 点覆盖层或自保页的「取消处置并恢复全部」→ 核对无障碍列表、ADB、网络、应用状态全部回滚

> 说明：崩溃检测需要 DUMP 权限（激活时授予）；未授予时只保留定时重置与设置回写两条保活路径。

---

## 五、目录结构
```
app/src/main/java/wzmwayne/shield/
├── ShieldApplication.kt          # 应用入口（shell 进程直接跳过初始化）
├── MainActivity.kt               # 单 Activity；仅在非防护状态装配 Shizuku 通道
├── admin/                        # 设备所有者 Receiver
├── activation/                   # 激活向导：辅助软件/环境检查/激活/无障碍/自检
├── accessibility/                # 感知层 + 保活主逻辑节拍 + 自动响应执行者
├── control/                      # 管控层：白名单/隐藏/挂起/撤权/清理数据/卸载/断网
├── core/                         # 常量、状态、持久化、应用清单、纯逻辑（可单测）
├── keepalive/                    # 保活层：引擎、前台服务、崩溃检测、广播
├── protection/                   # 自我保护（第 40-45 条）+ 无障碍白名单
├── response/                     # 响应对抗层：敏感页看护/覆盖层哨兵/手势/覆盖层/提示音/处置清单
├── shell/                        # Shizuku 用户服务：手写 Binder + 客户端
├── threat/                       # 威胁引擎接口（占位）
└── ui/                           # Compose 黑白暗色界面
```

## 六、真机自保攻击测试（已实测）
| # | 攻击手段 | 结果 |
| --- | --- | --- |
| 1 | `adb uninstall` | 被拒：`DELETE_FAILED_OWNER_BLOCKED` |
| 2 | `am force-stop` | 完全无效（设备所有者应用受系统保护，pid 不变） |
| 2b | `kill -9`（root） | 约 2 秒自动重绑（新 pid），无障碍与保活链路全恢复 |
| 2c | `kill -9` + 同时抹掉启用列表 | 约 18 秒由外部唤醒（AlarmManager 定时检测）拉起并写回列表 |
| 3 | `pm disable-user` | 被拒：`SecurityException: Cannot disable a protected package` |
| 4 | 篡改 `enabled_accessibility_services` | 约 3 秒写回，且保留其它服务 |
| 6 | `pm revoke WRITE_SECURE_SETTINGS` | 写回能力确实失效（预期），自检报「权限缺失」；重新授予后恢复 |
| 7 | `dpm remove-active-admin` | 被拒：`SecurityException: Attempt to remove non-test admin` |
| 8 | 一键自杀 → `adb uninstall` | 释放设备所有者后卸载成功（自杀流程有效） |

自保语义（按用户要求）：**只要保活/自保开着，无障碍服务不在启用列表里就一律恢复**；要真正停掉必须先关保活开关，或用仪表盘「一键自杀」（**15 秒确认等待、无冷却**，执行后释放设备所有者 + 解除全部自我保护 + 完全停摆）。

架构分工：主逻辑节拍挂在**无障碍服务**（10 秒）；**自我保护留在应用侧**（前台服务），因为它的职责是把无障碍服务救回来。

## 七、已知限制
- Dhizuku 委托模式**已禁用**（强制锁盾自身持有设备所有者），代码中不再提供该入口。
- 自动响应目前只有「敏感页看护」与「覆盖层 ≥70%」两条硬规则；评分制威胁引擎（`threat/`）仍是占位。
- L4 恢复流程（重置锁屏密码、锁屏悬浮窗）未实现。
- 「卸载」依赖设备所有者的 `PackageInstaller.uninstall` 实际权限，失败会自动跳转系统应用详情页供手动卸载；该路径需真机实测确认。
- 上一轮设备 adb 桥接断开期间构建的 APK 尚未安装到测试机。

## 许可（GPL-3.0）
本项目整体以 **GNU General Public License v3.0** 发布，完整条款见 `LICENSE`，第三方声明见 `NOTICE`。

- **移植**：用户限制（UserRestrictions）能力清单与分组移植自 [OwnDroid](https://github.com/BinTianqi/OwnDroid)（Copyright (C) BinTianqi，GPL-3.0），文件：`app/src/main/java/wzmwayne/shield/port/owndroid/UserRestrictions.kt`；执行层与 UI 由本项目实现。
- **内置 APK**：Shizuku+（Drop-In）、雹 Hail 以未修改的官方发布 APK 随应用分发，著作权归各自作者。
- **源码可得性**：本仓库即锁盾源码（GPL-3.0 要求的分发源码在同一位置提供）。
