# 记一笔 Android 工程

当前版本 **0.4.0-alipay-samples**，应用名「记一笔 · 付款识别」，包名 `dev.inkledger.app.alipaytest`。使用本地真实账本，不再是 0.1 的演示 App。

**安装、授权与触发步骤见[项目首页使用说明](../README.md)**。详细操作：[支付宝实图自测](../docs/ALIPAY-REAL-SAMPLES.md) · [微信使用说明](../docs/WECHAT-QUICKSTART.md)。源码仓库不包含 APK，需自行构建或使用已单独交付的测试包。

## 当前能力

- 明细、统计、自动、设置四个原生 View 页面，Room 本地存储；手动记账、编辑、删除、待核对确认、月预算、主题及减少动态。
- 微信／支付宝来源分别选择和同意，页面读取、通知读取、本地截图 OCR 分别授权；仅针对所选支付应用，不读取账户流水接口。
- 页面事件触发识别；信息完整、身份可靠的近期人民币付款可确认。缺商户／时间、历史账单、疑似重复等待核对；没有金额不生成零元记录。
- Android 14+ 支持单窗口 OCR。已读到成功凭证但缺金额时尝试自动 OCR；完全无可读文字时，使用“自动 → 识别当前付款页”的 8 秒单次触发。OCR 及微信通知候选始终待核对。
- 支付宝付款页只取主金额，过滤原价、优惠、积分与广告；转账返回 `TRANSFER_NOT_EXPENSE` 并跳过记账；账单详情负号表示支出，保留账单时间，不把“收钱码收款”误判为收入。
- 首次四步指南可跳过、返回；“自动／设置 → 使用指南”可重看。三张脱敏实图可切换类型与放大滚动，阅读示例不触发授权或记账。

微信聊天、红包、转账、收款、退款不自动记为消费。没有云同步或荣耀系统胶囊；截图仅在内存处理，不上传、不写相册。相同凭据去重不等于能自动证明跨来源、无共同交易号的记录是同一笔，核对时仍需避免重复确认。

## 构建与检查

JDK 17、SDK Platform 35／Build Tools 35.0.0；Gradle Wrapper 8.11.1、AGP 8.9.3、Kotlin 2.1.21。先配置本机 SDK 与 `JAVA_HOME`，在本目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :ledger-core:testDebugUnitTest :app:lintDebug
```

macOS／Linux 使用 `./gradlew`。默认包在 `app/build/outputs/apk/debug/app-debug.apk`。Windows 中文路径遇到 worker 参数文件编码问题时可加 `-PinkBuildRoot=C:/ink-ledger-build`，将输出改到英文路径；完整步骤见[首页](../README.md#从源码构建)。

只编译设备测试源码、不运行设备：

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin :ledger-core:compileDebugAndroidTestKotlin
```

0.4 交付时 73 项 JVM 测试通过、lint 0 errors／25 warnings、APK 和设备测试源码编译成功。发布前替换测试日期为虚构值后，15 项转录测试再次通过。本轮未启动模拟器、未安装或执行设备测试；人工转录不等于实际图片 OCR 验证。真实页面结构、授权、后台与界面效果由用户在手机上检查。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Activity、ViewBinding、Fragment、BottomSheet、权限与来源状态、通知／无障碍／本地 OCR 适配器 |
| `ledger-core` | 金额、统计、Room 数据、微信／支付宝解析、持久幂等和测试；见[核心 API](ledger-core/README.md) |
| `capture-fixture` | 历史合成采集测试辅助模块，不能安装到个人手机，不随 App 分发 |
| `../backend` | 可选云识别接口草案，当前没有运行后端 |

App 直接使用 Kotlin Repository，不启动上游本地 HTTP 服务。`RoomLedgerRepository.createLive()` 使用 `ink-ledger-live.db`；内部 `Alipay*` 名称保留兼容，微信和支付宝采用隔离的来源身份空间。现有测试版同签名覆盖安装保留账目与设置；本工程不提供 AutoAccounting 旧数据库迁移。

## 来源

固定 AutoAccounting 上游提交 `34b38f82ca9ad6d7905c715c66891977f8809c88`。复用和修改见[第三方通知](THIRD-PARTY-NOTICES.md)、[核心来源记录](ledger-core/UPSTREAM.md)。未引入完整旧 App、Hook、远程私有规则包或作者遥测。保留原版权、GPL v3 文本及文件头冲突说明。
