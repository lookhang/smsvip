# 关键短信强提醒 - 无需 Android Studio 的编译方案

本工程已是一套标准 Gradle 工程，下面三种方式任选其一即可产出 `app-debug.apk`。

---

## 方案 A：GitHub Actions 云端编译（推荐，零安装）

不需要在本机装任何 Android 工具。只要有一个 GitHub 账号即可。

1. 在 GitHub 新建一个**空仓库**（如 `sms-alert`）。
2. 在本工程目录执行（替换成你的仓库地址）：
   ```bash
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/sms-alert.git
   git push -u origin main
   ```
3. 推送后，仓库里的 `.github/workflows/build.yml` 会自动触发构建。
4. 进入仓库 **Actions → Build Debug APK**，等待绿色对勾，点开 **Artifacts → app-debug** 下载 APK。
5. 之后每次 `git push` 都会重新构建；也可在 Actions 页面点 **Run workflow** 手动触发。

> 该工作流使用 Gradle 8.2 + Android SDK cmdline-tools，自动接受许可、安装 platform-34 与 build-tools，无需 Gradle Wrapper。

---

## 方案 B：本机命令行编译（Windows）

适合不想上传代码、想本地出包的情况。

1. 安装 **JDK 17**（推荐 Eclipse Temurin）：https://adoptium.net ，记下安装路径，设置 `JAVA_HOME`。
2. 安装 **Gradle 8.2**：https://gradle.org ，解压后把 `bin` 加入 `PATH`（或在本目录执行 `gradle wrapper --gradle-version 8.2` 生成 wrapper）。
3. 安装 **Android SDK Command-line Tools**：
   - 下载 https://developer.android.com/tools/releases/cmdline-tools 的 `commandlinetools-win-XX_latest.zip`
   - 解压到如 `C:\Android\Sdk\cmdline-tools\latest`，设置 `ANDROID_HOME=C:\Android\Sdk`
   - 命令行执行：
     ```bat
     %ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat "platforms;android-34" "build-tools;34.0.0"
     %ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat --licenses
     ```
4. 在本目录执行构建：
   ```bat
   gradle :app:assembleDebug
   ```
   或（若已生成 wrapper）：`gradlew.bat :app:assembleDebug`
5. 产物：`app\build\outputs\apk\debug\app-debug.apk`

> 嫌命令多可直接双击工程里的 `build.bat`（它会检查 `JAVA_HOME` / `ANDROID_HOME` 并调用 `gradle`）。

---

## 方案 C：第三方在线构建（Codemagic / Bitrise）

把仓库推到 GitHub/GitLab 后，在 https://codemagic.io 用“Android”模板连接仓库，指定 Gradle 任务 `:app:assembleDebug` 即可，免费额度足够个人构建。

---

## 关于“我（WorkBuddy）能否直接帮你编”

我运行在隔离环境里，本机没有 Android SDK，也无法把 APK 直接装到你手机上。但我可以帮你走**方案 A**：你建好空 GitHub 仓库后把地址给我，我帮你把工程推上去并确认构建通过。需要你提供仓库地址（或允许我生成 push 命令）。
