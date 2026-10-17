# 记一笔 · Android 界面预览

已确认的「墨色清单＋底部 Dock」原生实现。当前应用 ID 为 `dev.inkledger.app.demo`，应用名称为「记一笔 · 预览」。这是可独立运行的本地示例账本，不连接微信、支付宝或云端。

## 本次实现

- 明细、统计、自动、设置四个原生页面，列表复用 RecyclerView，面板复用 Material BottomSheet。
- Dock 指示块使用 AndroidX SpringAnimation；页面过渡与账目入列使用原生动画。遵守系统移除动画及应用内减少动态设置。
- 账目新增、编辑、待核对确认、删除、月预算、按月统计，浅深色与隐藏金额。
- 独立 Room 数据库持久化示例数据；金额为 CNY 整数分；未确认记录不计入收支；重复来源凭据不重复插入；批量导入有事务。
- 明确隔离的演示事件：付款成功、金额待核对、识别失败、来源失效、导入预览和取消。触发示例不会读取真实通知、图片或支付信息。

## 工程分层

| 目录 | 职责 |
| --- | --- |
| `app` | Activity、ViewBinding、四个 Fragment、ViewModel、Dock、通用列表、编辑面板及应用内提示 |
| `ledger-core` | Repository、Room 数据库、精确金额和统计、采集与识别端口、默认不可用实现及测试 |
| `../backend` | 可选云识别 OpenAPI 合同；没有运行中的后端，也不是个人支付流水 API |
| `../architecture` | 实际源码边界与可交互架构图说明 |
| `../design` | 已确认的视觉、动效和荣耀能力研究 |

App 直接调用 Kotlin Repository，没有启动上游本地 HTTP 服务。`CaptureCoordinator` 已定义组合流程，但未装配到真实来源；默认端口明确返回不可用。未来替换采集/OCR/解析实现，不需要重写四个页面。

## 本机构建

JDK 17、Android SDK/Build Tools 35、Gradle 8.11.1、AGP 8.9.3、Kotlin 2.1.21、KSP 2.1.21-2.0.1、Room 2.7.2。精确工具路径见 [工具链说明](../build-support/TOOLCHAIN.md)。

在 `D:\记账软件` 用 PowerShell：

```powershell
.\build-support\Invoke-Gradle.ps1 -GradleArgs @(':app:assembleDebug', ':ledger-core:testDebugUnitTest', ':app:lintDebug', '--no-daemon')
```

本机脚本设置进程级环境，不更改全局 Java/SDK 配置。为避开 Windows Gradle 测试进程对中文参数文件路径的兼容问题，脚本将构建输出放到 `D:\app\ink-ledger-build`；源码仍在本目录。其他机器可直接使用 `gradlew`，也可传入 `-PinkBuildRoot=<本机 ASCII 输出目录>`。

```powershell
# 明确指定自己的目标设备，避免安装到电脑上其他已连接手机
adb -s <设备序列号> install -r <app-debug.apk 的实际路径>
```

调试 APK 使用开发签名，当前并非应用商店发行包。不要把此工程安装后出现的示例交易当作实际采集结果。

## AutoAccounting 复用

固定上游提交 `34b38f82ca9ad6d7905c715c66891977f8809c88`。复用原账单类型/状态、改造后的账本字段与统计 SQL、矢量资源和 Gradle Wrapper；完整来源、修改与许可记录见 [第三方说明](THIRD-PARTY-NOTICES.md) 及 [核心复用记录](ledger-core/UPSTREAM.md)。原项目的旧页面耦合本地 HTTP、设置与采集链路，所以本次重做视觉表层，继续使用原生 View 技术路线。

未引入完整旧 App、Hook、私有规则包、OCR 模型或作者遥测。保留上游原版权与许可文件，未将存在冲突的文件头改称宽松许可。当前是独立新数据库，不提供上游旧数据库直接迁移。

## 荣耀适配边界

目标为荣耀 100 Pro / MagicOS 10。当前按 `WindowInsets` 处理系统栏、开孔与横屏边缘，字号随系统缩放，Dock 与内容留足底部空间，不按物理分辨率硬编码。

系统胶囊暂未接入，所有提示在 App 内显示。模拟器验证不能替代荣耀真机验证。通知采集/OCR、后台存活、120Hz 帧耗时、触感和功耗、真实微信/支付宝付款、应用商店审核均不在本次完成范围。

交互和测试结果见 [原生实现验收](../artifacts/VERIFICATION.md)。
