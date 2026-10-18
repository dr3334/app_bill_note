# 记一笔 0.2 支付宝测试版交付核验

日期：2026-10-18。安装 [ink-ledger-0.2.0-alipay-test.apk](ink-ledger-0.2.0-alipay-test.apk)。这是已连接支付宝通知、无障碍节点和本地 OCR 的测试包，**不是旧 0.1 演示版**；包名 `dev.inkledger.app.alipaytest`，使用空白起步的 `ink-ledger-live.db`，不播种演示账目。

| 核验项 | 结果 |
| --- | --- |
| 文件大小 | 61,088,881 bytes |
| SHA-256 | `D68D9ED2F312056D3889111BC63DC6B6D91EA952F210259C27E35EDE6852DFDA`；[校验文本](ink-ledger-0.2.0-alipay-test.apk.sha256) |
| 版本 / 签名 | `0.2.0-alipay-test`，versionCode 2；签名验证通过，Android Debug 证书 |
| 系统与 ABI | min API 29 / target 35；含 arm64-v8a；截图 OCR 仅 API 34+ |
| 构建 / lint | 成功；0 errors / 22 warnings |
| 自动测试 | 28 JVM + 24 core instrumentation + 17 App instrumentation = **69 项通过** |
| 请求权限 | 仅本应用签名级动态接收器权限；无网络、存储、悬浮窗、发送通知权限；通知读取/无障碍仍需用户在系统中授权 |

模拟器已验证实际服务通知入账、同交易号跨来源防重、不同交易号同额保留；实际窗口截图 → 捆绑中文 ML Kit → 解析 → Room 保存 23.45 元待核对账目。安全窗口返回错误 6 时没有新增账目。输入来自开发夹具，**没有真实付款，也没有验证荣耀 100 Pro / MagicOS 10 上的真实支付宝模板和后台行为**。OCR 仍须核对，不承诺全部付款自动入账或应用市场通过审核。

测试结束后已卸载模拟器夹具并恢复临时系统授权。[诊断截图](screenshots/alipay-capture-diagnostics.png) 与 [合成账本截图](screenshots/alipay-synthetic-ledger.png) 是测试证据；截图中的两笔确认、一笔待核对不随新安装包播种。

安装后从“自动”页逐项阅读说明、开启通知读取和页面读取；OCR 另有独立同意与开关。用户手机测试不需要 Root、Shizuku 或 USB 调试。

- [荣耀手机权限步骤、25 项实测矩阵和诊断反馈](../docs/ALIPAY-HONOR-TEST.md)
- [AutoAccounting 直接复用、改造与新写适配的区别](../docs/AUTOACCOUNTING-REUSE.md)
- [第三方依赖与 ML Kit 说明](../android/THIRD-PARTY-NOTICES.md)
- [构建日志](../build-support/alipay-delivery-build.log)、[包信息](../build-support/alipay-apk-badging.log)、[签名核验](../build-support/alipay-apk-signature.log)、[最终 OCR 端到端日志](../build-support/alipay-e2e-ocr-final.log)

旧 0.1 APK、`.demo` 包及交互架构 HTML 仍是历史材料，不能代表本版采集能力；两版数据库分离。
