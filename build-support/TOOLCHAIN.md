# Android 构建工具链盘点

核实日期：2026-10-18。初始盘点只检查本机工具与缓存；后续按任务授权启动了无窗口模拟器并开始依赖解析，进展见文末。没有修改上游或操作手机，没有下载、安装 SDK 或接受许可证。

## 当前选择

新工程使用 `compileSdk 35 / targetSdk 35 / minSdk 29`。建议使用下面已经存在的工具，项目无需升级到 Java 25 或 SDK 36。

| 工具 | 版本／路径 | 验证结果 |
| --- | --- | --- |
| 首选 JDK | Temurin 17.0.20.1+1；`C:\Users\dr\AppData\Local\Android\jdk17\jdk-17.0.20.1+1` | `java -version`、`javac -version` 均成功 |
| 备用 JDK 17 | Microsoft 17.0.13；`C:\Users\dr\AppData\Local\Programs\Java\jdk-17.0.13` | `release` 与编译器文件存在 |
| 已有 JDK 21 | JetBrains JBR 21.0.3；`C:\Program Files\JetBrains\PyCharm 2024.2.1\jbr` | `java -version`、`javac -version` 均成功；本轮不需要使用 |
| 不选用的 Studio JBR | 25.0.3；`C:\Program Files\Android\Android Studio\jbr` | 与本项目 Gradle 8.11.1 组合已有失败记录 |
| Gradle | 8.11.1；`C:\Users\dr\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat` | 使用首选 JDK 执行 `--version` 成功 |
| SDK 根目录 | `C:\Users\dr\AppData\Local\Android\Sdk` | 平台与工具可复用 |
| 平台 | `platforms\android-35`，API 35、revision 2 | `android.jar` 所在平台已安装；未安装平台 36 |
| Build Tools | `build-tools\35.0.0` | 已安装 |
| Platform Tools | `platform-tools`，37.0.1 | 已安装；本轮没有执行设备安装命令 |
| NDK / CMake | 28.1.13356709 / 3.31.6 | 已安装；普通原生 UI 不需要引入 |

现有 Gradle 8.9 目录不代表完整可执行分发；已确认完整分发是 8.11.1。`gradle --version` 输出中的 Kotlin 2.0.20 是 Gradle 内置 Kotlin，并非 Android 工程的 Kotlin 插件版本。

## 依赖缓存

缓存根目录：`C:\Users\dr\.gradle\caches\modules-2\files-2.1`。以下为开始构建前的盘点快照，后续依赖解析已补入新工程所需组件。

- AGP：8.2.0、8.7.3、**8.9.3**。
- Kotlin Gradle 插件：1.9.10、1.9.22、**2.1.21**。没有发现 2.1.20，所以建议新工程使用 2.1.21。
- Compose UI Android：1.7.8；Material3 Android：1.3.1；Activity Compose：1.10.1。
- AndroidX Core KTX：1.12.0、1.13.0、1.13.1；Material Components：1.11.0、1.12.0。
- 未发现 Compose compiler Gradle 插件、Compose BOM、KSP、Room runtime/compiler 的对应缓存目录；如果工程需要这些依赖，仍需从官方仓库下载。

缓存目录存在不等于整个工程能够离线构建。实际依赖解析、资源编译、单元测试和 APK 构建需在新工程创建后分别确认。优先使用 Google Maven、Maven Central 和 Gradle 官方插件仓库；本次没有为缺失依赖引入第三方镜像。

## 构建入口

`Invoke-Gradle.ps1` 只为当前进程临时配置 Java 与 SDK 路径，结束后恢复环境变量，不修改系统全局设置。默认只输出版本。新工程创建后可运行：

```powershell
& 'D:\记账软件\build-support\Invoke-Gradle.ps1' -GradleArgs @('--version')
& 'D:\记账软件\build-support\Invoke-Gradle.ps1' -GradleArgs @(':app:assembleDebug', ':ledger-core:test', '--no-daemon')
```

脚本默认工程路径为 `D:\记账软件\android`。现已创建 `local.properties`，内容为 `sdk.dir=C:/Users/dr/AppData/Local/Android/Sdk`。脚本默认添加本机参数 `-PinkBuildRoot=D:/app/ink-ledger-build`，各模块的 APK、测试结果及其他产物位于该目录下的模块子目录；可用 `-BuildOutputRoot` 参数替换，不改变源码路径。

## 模拟器

- 可执行文件：`C:\Users\dr\AppData\Local\Android\Sdk\emulator\emulator.exe`，版本 37.2.12。
- `-list-avds` 返回：`Medium_Phone_API_37.0`。
- AVD 配置：`C:\Users\dr\.android\avd\Medium_Phone.avd\config.ini`。
- 镜像：`system-images\android-37.0\google_apis_playstore\x86_64`，revision 6。
- `-accel-check` 成功：`WHPX(10.0.22621) is installed and usable.`
- 本轮仅列举及检查加速，没有启动或改动已有 AVD。此镜像只能验证一般 Android UI/兼容性，不能证明 API 35 或荣耀 100 Pro / MagicOS 10 的表现。
- 后续安装必须显式使用已核实的 `emulator-端口` 序列号；禁止无 `-s` 目标的安装，不能误用连接中的物理设备。

## 上游与许可证

上游路径：`D:\app\accounting-plan\autoaccounting\upstream`；提交 `34b38f82ca9ad6d7905c715c66891977f8809c88`。`git status --porcelain=v1 --untracked-files=all` 输出为空，检查时没有他人未提交修改。

SDK 下已有 `licenses\android-sdk-license` 文件。它表示本机已有许可记录；本轮没有新增许可记录、运行 `sdkmanager --licenses` 或替用户接受新的协议。SDK 36 与 SDK command-line tools 当前未安装，新 UI 选用 API 35 后暂时不需要补装。

## 新工程准备进展

已创建 `android/local.properties` 指向上述现有 SDK。已运行 `:app:dependencies --configuration debugRuntimeClasspath`，Google 与 Maven Central 上的外部依赖元数据可解析；该次 `:ledger-core` 尚未写入模块构建文件，因此依赖树中该项为 `FAILED`。依赖报告任务的 `BUILD SUCCESSFUL` 不等于整个 App 已可构建。

已用 `Start-Process -WindowStyle Hidden` 启动现有 AVD，参数为 `-avd Medium_Phone_API_37.0 -port 5554 -no-window -no-snapshot -no-audio -no-boot-anim -read-only`。确认实际序列号 **`emulator-5554`**，`sys.boot_completed=1`，API 37；AVD 名称回读匹配。使用只读 AVD，不保存快照。日志位于本目录的 `emulator-5554.stdout.log` 与 `emulator-5554.stderr.log`。

后续针对该模拟器的命令统一使用：

```powershell
& 'C:\Users\dr\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5554 get-state
```

已观察到本进程现有本地代理配置，官方 Maven 连通性检查成功；没有修改全局代理，也没有将凭据写入项目。

## 已定位的集成问题

1. Room 2.6.1 在当前 KSP2 下报 `unexpected jvm signature V`。临时使用 KSP1 能通过该处理步骤；最终由模块负责人统一 Room 到 **2.7.2**，继续使用默认 KSP2，没有保留 KSP1 降级设置。官方说明见 [Room 2.7](https://developer.android.com/jetpack/androidx/releases/room#2.7.0)，同类问题见 [KSP #2177](https://github.com/google/ksp/issues/2177)。
2. 中文输出路径让 Gradle 测试 worker 找不到已编译测试类。已经对同一份 MoneyTest 做对照：通过 Gradle 生成的 `@argfile` 启动时出现 ClassNotFoundException，通过直接 Unicode `-cp` 参数启动时 3 项测试通过。Java 17 与现有 Java 21 都受该参数文件问题影响，不能靠更换 JDK 宣称修复。类似机制见 [Gradle #30304](https://github.com/gradle/gradle/issues/30304)。
3. 最终方案是根构建脚本接受可选 `inkBuildRoot`，本机使用 `D:/app/ink-ledger-build`，只改变模块构建输出，保持源码位置、Java 17 编译目标及全局系统设置不变。完整 Gradle 测试结果必须以此方案的后续实际结果为准，不能把上述手工对照当作全部测试已通过。
4. API 37 上旧 Espresso 3.6.1 的界面测试在初始化阶段因 `InputManager.getInstance` 不存在而失败，3 项均未进入有效断言阶段。只将 App 的测试依赖更新到 Espresso 3.7.0、runner 1.7.0、ext.junit 1.3.0；新测试 APK 与 Lint 已重新通过编译。官方 [Espresso 3.7.0 发布说明](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)明确记录改用 `getSystemService` 代替该反射调用。没有因此升级 App 的 compileSdk、运行时业务依赖或系统配置。

## 集成验证结果

核心 JVM 13 项、Room instrumentation 8 项、UI instrumentation 3 项，**合计 24 项通过**。UI 复验由主代理在同一个 `emulator-5554` 上执行并回传结果；本代理交接后没有并行操作设备。以下结果不等于荣耀真机自动付款采集已验证。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Debug APK | 构建成功；包含主代理的 Dock 测量修复 | `dock-fix-build.log`；APK `D:\app\ink-ledger-build\app\outputs\apk\debug\app-debug.apk` |
| 核心 JVM 单测 | **13 通过，0 失败**：Money 3、Stats 5、Capture 5 | `D:\app\ink-ledger-build\ledger-core\test-results\testDebugUnitTest\TEST-*.xml` |
| Room instrumentation | 在 `emulator-5554` **8 通过** | `core-instrumentation.log` |
| App Lint | 通过，0 errors、16 warnings；未添加隐藏错误的 baseline | `espresso37-build.log`，`D:\app\ink-ledger-build\app\reports\lint-results-debug.html` |
| App 首次安装与启动 | 明确指定 emulator-5554，安装成功、冷启动成功；初次观察 AndroidRuntime 无崩溃日志 | `initial-androidruntime.log` |
| 默认外观 | 首次发现 Dock 遮挡正文，主代理修复后已确认明细、统计、自动页面正常 | `D:\记账软件\artifacts\screenshots\ledger.png`、`statistics.png`、`capture.png`；`initial.png` 与 `initial-settled.png` 保留为原失败现场 |
| App UI instrumentation | 更新 Espresso 3.7.0 后，由主代理在 emulator-5554 实际执行，**3 项通过** | `ui-instrumentation-final.log`；`ui-instrumentation.log` 为旧失败记录 |

上述 Debug APK 在 00:27:38 生成，SHA-256：`0E12EFEFB12C45387ABC50AEB89B079BBDCAD2E9C545C2C0A83B2273ADA8741B`。后续重编后校验值会变化，应以最新产物为准。所有安装和 instrumentation 均通过显式 `-s emulator-5554` 进行，没有在物理手机安装 APK。

## 主题切换修复构建（00:32）

主代理修复主题切换时已关闭 Sheet 在 AppCompat 重建后恢复的问题（切换前使用 `dismissNow`），调整 Sheet 系统栏对比，并新增第 4 项 UI 回归测试。本代理随后运行 `:app:assembleDebug`、`:app:assembleDebugAndroidTest`，构建成功；再运行 `:app:lintDebug`，结果仍为 **0 errors、16 warnings**。证据为 `theme-fix-build.log` 与 `theme-fix-lint.log`。

本轮 App APK 于 00:32:46 生成，14,163,675 字节，SHA-256：`E24AEBE20B6F588A383399878B2AE726916025C242D975A7375ACE939ADE62C6`；测试 APK 于 00:32:47 生成。产物路径沿用上表。这里只确认新增测试已编译，**本代理没有运行本轮 4 项 UI 测试，也没有操作 ADB**；最新运行结果由接管模拟器的主代理补充，不能将上一轮 3 项通过直接当作新回归测试通过。

## 最终交付结论（2026-10-18）

以下结论取代上文历史阶段的“24 项”和“第 4 项待运行”状态：已核对 **13 项 JVM、8 项 Room、4 项 UI，共 25 项通过**。最终 UI 日志为 `ui-instrumentation-final.log`，4 项覆盖 Dock/返回、主题切换后面板重建、错误金额输入、金额隐私及减少动态后的导航。最终 `delivery-lint.log` 为 `BUILD SUCCESSFUL`，报告 **0 errors、16 warnings**。设备测试仅在 `emulator-5554` 执行。

最终交付 [APK](../artifacts/ink-ledger-0.1.0-demo.apk) 为 **15,065,075 字节**，SHA-256：`0D5F05B2ED5D56F1D5D8ECBC1CAA7A7475E5C6B4F39E923652FBAFF282FC2DAB`，包含最后的删除/取消描边按钮样式。具体证据和能力边界集中记录于 [VERIFICATION.md](../artifacts/VERIFICATION.md)。荣耀 100 Pro 未实测；真实微信/支付宝通知、OCR、官方系统胶囊未接入；后端只有 OpenAPI 合同。当前交付是独立原生示例账本，不能据此宣称真实付款自动采集或上架已完成。

主代理已完成人工视觉复验：横屏内容与 Dock 居中，实际滚到底后最后一笔位于 Dock 上方且留白足够；最终编辑面板主次按钮正确；系统字号 2.0 下内容可读，Dock 与账目自适应。对应截图见上述验收记录。模拟器已恢复字号 1.0、自由旋转和角度 0。本轮收尾仅更新文档，未再构建或操作 ADB。
