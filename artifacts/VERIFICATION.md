# 原生示例版验收记录

2026-10-18，版本 `0.1.0-demo`。当前交付为独立的原生界面与本地示例账本；**25 项自动化测试通过，最终 Lint 为 0 错误、16 警告**。设备测试仅在 API 37 模拟器 `emulator-5554` 执行。

## 交付物

[ink-ledger-0.1.0-demo.apk](ink-ledger-0.1.0-demo.apk)，15,065,075 字节。SHA-256：

```text
0D5F05B2ED5D56F1D5D8ECBC1CAA7A7475E5C6B4F39E923652FBAFF282FC2DAB
```

APK 包含 Dock 测量与横屏居中修复、主题切换后关闭面板的修复、系统栏与墨色主题调整，以及删除/取消按钮的描边样式。[最终 APK 构建日志](../build-support/button-final-build.log)

## 已核对的验证

| 检查 | 结果与覆盖 | 实际证据 |
| --- | --- | --- |
| 核心 JVM | 13 通过，0 失败、0 错误、0 跳过：金额 3、统计 5、采集协调 5 | [Money XML](D:/app/ink-ledger-build/ledger-core/test-results/testDebugUnitTest/TEST-dev.inkledger.core.MoneyTest.xml)、[Stats XML](D:/app/ink-ledger-build/ledger-core/test-results/testDebugUnitTest/TEST-dev.inkledger.core.LedgerStatsTest.xml)、[Capture XML](D:/app/ink-ledger-build/ledger-core/test-results/testDebugUnitTest/TEST-dev.inkledger.core.CaptureCoordinatorTest.xml) |
| Room instrumentation | 8 通过；数据库约束、幂等、事务及统计等 | [运行日志](../build-support/core-instrumentation.log) |
| UI instrumentation | 4 通过；Dock/返回、主题切换与面板重建、错误金额输入、金额隐私及减少动态后的导航 | [最终运行日志](../build-support/ui-instrumentation-final.log)、[测试源码](../android/app/src/androidTest/java/dev/inkledger/app/InterfaceTest.kt) |
| 最终 Lint | `BUILD SUCCESSFUL`，0 errors、16 warnings | [运行日志](../build-support/delivery-lint.log)、[完整报告](D:/app/ink-ledger-build/app/reports/lint-results-debug.html) |

主代理已人工检查[明细](screenshots/ledger.png)、[统计](screenshots/statistics.png)、[自动](screenshots/capture.png)及[深色](screenshots/ledger-dark.png)外观。最终视觉复验确认：

- [横屏](screenshots/landscape-final.png)内容限制 600dp 居中、Dock 限制 480dp 居中；[实际滚动到底](screenshots/landscape-bottom-final.png)后，最后一笔完整显示在 Dock 上方，底部留白足够。
- [最终编辑面板](screenshots/editor-final.png)保存为主按钮、删除为描边次按钮。
- [系统字号 2.0](screenshots/large-font.png)下内容可读，Dock 和账目自适应。检查后已恢复字号 1.0、自由旋转和角度 0。

旧 `initial.png`、`initial-settled.png` 为已修复问题的历史现场，不代表交付外观。

## 尚未验证或接入

- 荣耀 100 Pro / MagicOS 10 未实测；模拟器结果不能代替目标手机验证。
- 微信/支付宝真实通知采集、OCR 和官方系统胶囊均未接入；演示入账与 App 内提示不能证明付款后自动记账已完成。
- 后端只有 [OpenAPI 合同](../backend/openapi.yaml)及[范围说明](../backend/README.md)，没有已运行服务或 Android 网络接入。
- 本记录覆盖本次示例版检查，不代表商店审核、公众发布或所有系统版本兼容性已通过。
