# 0.4 支付宝真实页面适配交付记录

日期：2026-10-18（Asia/Shanghai）。用户授权按三张真实支付宝截图完善识别和使用引导、脱敏展示，并明确不使用模拟器测试。

## 安装与行为

- 本地交付安装包：`ink-ledger-0.4.0-alipay-samples.apk`，63,582,126 字节，未放入源码仓库；自行构建见[首页说明](../README.md#从源码构建)。
- 包名 `dev.inkledger.app.alipaytest`，versionCode `4`，versionName `0.4.0-alipay-samples`；原开发签名配置和数据库保留，覆盖安装无需卸载。
- minSdk 29 / targetSdk 35；单窗口截图 OCR 仍需要 Android 14 / API 34+。
- [手机操作与预期结果](../docs/ALIPAY-REAL-SAMPLES.md)。

| 页面 | 新增行为 |
| --- | --- |
| 支付成功 | 定位主金额 8.60 元；原价 8.80、优惠 0.20、广告 0.5 和积分不当作实付；缺主金额不猜测、不建零元账目 |
| 转账成功 | 优先返回 `TRANSFER_NOT_EXPENSE`，不新增消费或待核对账目；可由用户手动记为转账 |
| 账单详情 | -8.50 表示 8.50 元支出，采用账单内支付时间；“收钱码收款”不反判为收入，明确正号收入不入消费 |

无支付时间的付款页待核对；历史账单保留原时间并待核对；OCR 一律待核对。已有弱身份重复处理规则保留，不保证成功页、详情与通知能在无共同交易号时自动证明同笔。

App 的支付宝 OCR 行按可用 top/left 坐标排序，避免原价列先于主金额；不拼接整行价格，不改变微信 OCR 原输入顺序。通知入口与微信解析原有测试继续保留。没有数据库迁移。

## 脱敏图与首次引导

新增三类图解选择和点开大图滚动。首次四步、可跳过、原手动识别章节与“自动 / 设置 → 使用指南”入口保留；已读用户不重新强制弹出。

三个图例是用户原图经内置 image_gen 编辑的脱敏展示副本，灰块遮盖姓名、商户、头像、亲情卡关联人名、状态栏及详情交易时间。已逐张目视核对遮盖和关键金额。原图与含原始信息的编辑提示词不纳入 APK 或源码仓库；展示资源见 [脱敏素材说明](../docs/GUIDE-ASSETS.md)。图片展示不生成账目。

## 实际本地检查

使用已有 `build-support/Invoke-Gradle.ps1`，没有安装新依赖、升级 SDK 或操作设备：

```text
:app:assembleDebug
:app:testDebugUnitTest
:ledger-core:testDebugUnitTest
:app:compileDebugAndroidTestKotlin
:ledger-core:compileDebugAndroidTestKotlin
:app:lintDebug
--no-daemon
```

最终 **BUILD SUCCESSFUL**，原始日志 `build-support/alipay-samples-v04-build.log` 仅本地保留，不上传源码仓库。

| JVM 测试类 | 通过 |
| --- | ---: |
| AlipayRealPageTranscriptTest（新增） | 15 |
| AlipayOcrReadingOrderTest（新增） | 3 |
| AlipayReceiptParserTest | 15 |
| WechatReceiptParserTest | 20 |
| PaymentEvidenceIdentityTest | 4 |
| PaymentCaptureGuardsTest | 3 |
| CaptureCoordinatorTest | 5 |
| LedgerStatsTest | 5 |
| MoneyTest | 3 |
| **总计** | **73** |

失败 0、错误 0、跳过 0。交付时 XML 位于本机构建输出的 `{app,ledger-core}/test-results/testDebugUnitTest/`，时间为 2026-10-18 20:38（本地）。新增 15 项使用三张图的脱敏人工转录及反例，不是执行过图片 OCR。

Lint：**0 errors / 25 warnings**。报告位于本机构建输出的 `app/reports/lint-results-debug.html`。APK 签名校验通过，包信息确认版本及包名；签名与包信息原始日志仅本地保留。打包权限仅本应用签名级动态接收器权限，未增加网络、存储或悬浮权限。

APK 资源列表确认包含 `guide_alipay_payment.png`、`guide_alipay_transfer.png`、`guide_alipay_bill.png` 三个脱敏资源，没有原始截图文件名或本地参考图库。核心代码与原图有一次独立只读核查，未发现阻断交付的分类或金额问题。

## 未运行的检查

GitHub 提交准备时，将人工转录和对应测试中的账单日期／时刻替换为虚构值，保留同日观察与旧账单时间差。聚焦 `AlipayRealPageTranscriptTest` **15 项再次通过，0 失败／错误／跳过**。本次聚焦执行的 XML 只代表该 15 项，不覆盖上述原交付的 73 项记录；未修改生产源码，不需要重打 APK。

**未启动或连接模拟器，未通过 adb 安装、运行或操作设备。** 新增的图例切换、大图返回与首次引导返回测试仅编译，未执行。没有实际运行 ML Kit 识别这三张图片，也没有把转录测试计为图片识别成功率。

实际支付宝节点顺序、OCR 行框/分行、系统授权和后台存活，以及原生布局、大字号、横屏和交互，需要用户在手机上按 [自测说明](../docs/ALIPAY-REAL-SAMPLES.md) 验证。
