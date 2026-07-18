# 关键短信强提醒（小米/MIUI/HyperOS）

一款 Android 应用：读取短信，按「关键词」或「特定号码」规则匹配，命中后触发**最强级别的全屏强提醒**——
即使手机处于**静音、媒体音量为 0、勿扰（DND）**状态也会响铃+震动+亮屏+锁屏上层弹窗，**直到用户主动点「关闭提醒」才停止**。

本仓库是一套**完整、可直接用 Android Studio 打开并编译**的 Kotlin 工程。

---

## 1. 功能一览

| 功能 | 说明 |
| --- | --- |
| 规则匹配 | 支持「关键词」（正文包含）与「特定号码」（发件人包含/结尾匹配）两类规则，可启用/停用、增删 |
| 双通道侦测 | ① 系统 `SMS_RECEIVED` 广播（优先级最高）；② 前台服务 `ContentObserver` 监听收件箱（抗 MIUI 后台限制备份通道） |
| 开机自启 | `BOOT_COMPLETED` 后自动拉起后台监控，重启不漏提醒 |
| 强提醒 | 锁屏上层全屏界面 + 点亮屏幕 + 红色背景闪烁 + 最大音量循环闹钟声 + 强震动 + 最高优先级全屏通知 |
| 不自动消失 | 只有点「关闭提醒」才会停止响铃/震动/通知；拒绝返回键 |
| 重复保活 | 每隔 N 秒重新拉满音量、确保播放、重新震动，对抗系统把音量/播放“偷偷”改回去 |
| 可调强度 | 自定义铃声、重复间隔、最大音量开关、震动开关、闪烁开关 |
| 一键测试 | 主页「测试提醒」立即验证效果 |

---

## 2. 架构与模块

```
┌─────────────────────────── 触发层 ───────────────────────────┐
│  SmsReceiver (SMS_RECEIVED 广播)      MonitorService          │
│                                        (ContentObserver 备份)  │
│  BootReceiver (开机拉起 MonitorService)                        │
└───────────────────────────────┬──────────────────────────────┘
                                 │ 命中规则
                                 ▼
                       RulesRepository.matches()
                                 │ 命中
                                 ▼
┌─────────────────────────── 强提醒层 ─────────────────────────┐
│  AlertService (前台服务)                                       │
│   • MediaPlayer(STREAM_ALARM) 循环播放，强制最大音量           │
│   • Vibrator 强震动波形                                        │
│   • PARTIAL_WAKE_LOCK 保活                                     │
│   • 定时器每 N 秒“重复保活”                                    │
│   • 最高优先级 + setBypassDnd + 全屏意图 通知                  │
│         │ 同时拉起                                             │
│         ▼                                                     │
│  AlertActivity (锁屏上层全屏)                                 │
│   • setShowWhenLocked / setTurnScreenOn / FLAG_KEEP_SCREEN_ON  │
│   • 红色背景呼吸闪烁                                           │
│   • 大号「关闭提醒」按钮 → 停止 AlertService                   │
└───────────────────────────────────────────────────────────────┘

 数据层：RulesRepository / SettingsRepository（SharedPreferences + JSON）
 工具层：PermissionHelper（权限与小米设置跳转）
 入口：App（创建通知渠道：强提醒渠道 bypass DND）
```

---

## 3. 为什么能“越强越好、静音/勿扰也能响”

1. **走闹钟音频流（STREAM_ALARM）**：闹钟流不受「静音键」「媒体音量」影响，且在绝大多数机型的「勿扰(允许闹钟/优先)」下仍会响。
2. **强制拉满闹钟音量**：启动时 `setStreamVolume(STREAM_ALARM, max, 0)`，并在重复保活定时器里反复拉满，防止被系统/用户改小。
3. **绕过勿扰的通知渠道**：`NotificationChannel` 设 `IMPORTANCE_MAX` + `setBypassDnd(true)`，通知本身即可穿透 DND。
4. **全屏意图（Full-Screen Intent）**：通知带 `setFullScreenIntent`，配合 Activity `showOnLockScreen`，在锁屏之上直接弹出。
5. **点亮屏幕 + 常亮**：`setTurnScreenOn(true)` + `FLAG_KEEP_SCREEN_ON`，黑屏也能亮起。
6. **视觉冲击**：红色背景呼吸闪烁，即使无声也能第一时间看到。
7. **强震动**：长波形重复震动，触觉兜底。
8. **绝不自动消失**：仅有「关闭提醒」能结束；定时器持续保活，直到人为关闭。

> ⚠️ 说明：在小米「绝对静音/总勿扰（所有声音关闭）」极端档位下，系统会切断一切声音，连闹钟流也静音。此时**亮屏 + 全屏弹窗 + 震动 + 闪烁**仍会触发，但铃声可能被压制。这是系统级限制，任何第三方 App 都无法突破，需用户把勿扰设为「允许闹钟」或在系统中把本应用加入勿扰例外。

---

## 4. 小米 / MIUI / HyperOS 适配清单（必做）

小米对后台与通知管控极严，以下授权不到位会“收不到 / 不响 / 锁屏不弹”。请逐项开启：

1. **短信权限**：首页「授予短信权限」→ 允许 发送/读取短信。
2. **通知权限**：首页「授予通知权限」→ 允许通知；并在系统设置里打开「悬浮通知」「锁屏通知」。
3. **电池无限制**：首页「电池无限制」→ 选“无限制/不限制”；关闭“省电策略”“智能节电”。
4. **自启动**：首页「小米：开启自启动」→ 为本应用开启（不同版本路径：设置 → 应用设置 → 权限 → 自启动）。
5. **后台弹出/显示在其他应用上层**：首页「授予悬浮窗（可选）」→ 允许。
6. **勿扰例外**（关键）：设置 → 声音与振动 → 勿扰 → 例外/允许的应用 → 把本应用加入；或勿扰模式选「允许闹钟」。
7. **锁屏显示**：设置 → 锁屏 → 锁屏通知 → 显示全部/隐藏敏感内容以外的内容；确保本应用锁屏可见。
8. **安全中心“通知过滤”**：小米部分版本有“通知过滤/短信智能识别”，可能把短信拦截进垃圾箱，请在「短信 → 设置 → 垃圾信息拦截」里放行，或关闭智能拦截。

> 小米各版本入口名称不完全一致，若按钮无法自动跳转，请按上面文字描述在系统设置中手动操作。

---

## 5. 构建与安装

> 没有 Android Studio？请看 **BUILD_WITHOUT_STUDIO.md**：提供 GitHub Actions 云端构建、本机命令行构建（Windows `build.bat`）两种方式，零安装也能出包。

前置：安装 **Android Studio** 与 **Android SDK（Platform 34、Build-Tools 34.0.0）**。

1. 用 Android Studio **Open** 本目录（根目录含 `settings.gradle`）。
2. 首次打开会自动创建 Gradle Wrapper 并同步依赖（联网）。
3. 连接小米手机，开启 **USB 调试**，运行 `app` 模块（或 `Build → Build Bundle(s)/APK → Build APK`）。
4. 安装后按第 4 节逐项授权，回到首页点「开启后台监控」「测试提醒」验证。

> 若想用命令行生成 wrapper：`gradle wrapper --gradle-version 8.2`（需本机有 Gradle）。

---

## 6. 使用流程

1. 首页点「规则设置」→ 加关键词（如「验证码」「银行」「报警」）或加号码（如「10086」「+86...」）。
2. 点「高级设置」调铃声/强度，建议保持“最大音量+震动+闪烁”全开。
3. 返回首页点「开启后台监控」。
4. 用另一台手机给本机发送包含关键词/号码的短信，验证强提醒；点「关闭提醒」结束。

---

## 7. 已知限制与合规提醒

- 仅用于**本人设备**的关键短信（验证码、家人、告警）提醒，**请勿用于监控他人**。
- 受 Android 后台限制与厂商定制 ROM 影响，极端省电/绝对勿扰下提醒强度可能衰减（见第 3 节说明）。
- `READ_SMS` 为敏感权限，Google Play 对短信类应用有严格审核；本工程定位为**个人自用的侧载/调试应用**，非上架版本。
- 后台监控服务常驻会消耗一定电量，属预期行为。

---

## 8. 文件结构

```
关键短信强提醒工具/
├── settings.gradle / build.gradle          # 工程与依赖
├── app/
│   ├── build.gradle                        # 模块配置（minSdk24 / targetSdk34）
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml             # 权限、组件、前台服务类型、图标
│       ├── java/com/example/smsalert/
│       │   ├── App.kt                      # 创建通知渠道
│       │   ├── Constants.kt                # 全局常量
│       │   ├── model/Rule.kt               # 规则模型
│       │   ├── data/RulesRepository.kt     # 规则持久化+匹配
│       │   ├── data/SettingsRepository.kt  # 设置持久化
│       │   ├── util/PermissionHelper.kt    # 权限/小米跳转
│       │   ├── receiver/SmsReceiver.kt      # 短信广播触发
│       │   ├── receiver/BootReceiver.kt     # 开机拉起监控
│       │   ├── service/MonitorService.kt   # 后台监控(ContentObserver)
│       │   ├── service/AlertService.kt      # 强提醒播放引擎
│       │   └── ui/                         # MainActivity/Rules/Settings/Alert
│       └── res/                            # 布局、字符串、主题、图标
└── README.md                               # 本设计文档
```
