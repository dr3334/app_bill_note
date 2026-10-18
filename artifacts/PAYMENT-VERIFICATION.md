# 0.3 微信 / 支付宝识别交付记录

这是 0.3 历史记录。当前 0.4 支付宝实图适配及脱敏引导的交付结果见 [ALIPAY-SAMPLES-VERIFICATION.md](ALIPAY-SAMPLES-VERIFICATION.md)。

日期：2026-10-18。用户要求完成微信识别、首次说明触发方式并可回看，且不使用安卓模拟器测试。

## 安装与使用

- 安装包：`ink-ledger-0.3.0-payment-test.apk`，60,001,896 字节。
- 名称：记一笔 · 付款识别。
- 包名：`dev.inkledger.app.alipaytest`，versionCode `3`，versionName `0.3.0-payment-test`。
- minSdk 29 / targetSdk 35；本地单窗口 OCR 需要 API 34+。
- 继续使用原签名配置、包名及 `ink-ledger-live.db`，覆盖安装保留原账目。无需先卸载，微信要另行选择。
- 操作说明：`../docs/WECHAT-QUICKSTART.md`。首次四步图解可跳过，在“自动”和“设置”均可重看。

## 本轮实现

1. 微信消费付款成功页 / 单笔账单详情解析，支持拆分标签、商户、交易时间、微信支付单号、币种与数字拆分及详情负号支出金额；原支付宝解析入口保留。
2. 微信单独选择，通知、无障碍文字与本地 OCR 三条路径贯通；手动选择目标应用后 8 秒单次识别，按钮随服务连接状态实时更新。
3. 微信通知与 OCR 始终待核对；缺金额不建零元账目。聊天、红包、转账、收款、退款、未完成支付不记为消费。
4. 微信 / 支付宝持久身份分隔；保留支付宝原身份输入。微信通知交易号不作为强身份，通知和页面可能分别生成待核对，不保证合并。
5. 锁屏、窗口变动、撤回授权或取消时作废相应未完成处理；截图仅内存处理。APK 合并权限未包含 INTERNET / ACCESS_NETWORK_STATE。
6. 原生分步 BottomSheet、章节进度、主题化矢量图、点按和最近任务手势演示、上一步 / 跳过 / 回看目录；阅读不授权。延续 UI/UX Pro Max 建议及原墨色清单设计。

## 实际执行结果

统一命令由 `../build-support/Invoke-Gradle.ps1` 执行：

```text
:app:assembleDebug
:app:testDebugUnitTest
:ledger-core:testDebugUnitTest
:app:compileDebugAndroidTestKotlin
:ledger-core:compileDebugAndroidTestKotlin
:app:lintDebug
--no-daemon
```

最终 **BUILD SUCCESSFUL**，日志 `../build-support/payment-final-build.log`。

| 已执行的 JVM 测试 | 通过 |
| --- | ---: |
| WechatReceiptParserTest | 20 |
| PaymentEvidenceIdentityTest | 4 |
| PaymentCaptureGuardsTest | 3 |
| AlipayReceiptParserTest | 15 |
| CaptureCoordinatorTest | 5 |
| LedgerStatsTest | 5 |
| MoneyTest | 3 |
| **合计** | **55** |

失败 0、错误 0、跳过 0。XML 结果在 `D:/app/ink-ledger-build/{app,ledger-core}/test-results/testDebugUnitTest/`。

Lint：**0 errors / 25 warnings**，涉及依赖版本、绘制/属性、资源、KTX 建议、文本与程序构建 View 构造器等；未以抑制 lint 代替修复错误。报告：`D:/app/ink-ledger-build/app/reports/lint-results-debug.html`。

设备测试源码已编译，包括首次引导、回看、微信明确同意、通知筛选、采集取消及 Room 微信去重等；**没有执行这些设备测试**。

首次构建发现微信详情页底部“领取优惠券”会误触营销过滤，已修复：仅通过凭证结构检查的微信非通知页允许促销页尾；营销标题、转账与失败状态仍拒绝，支付宝公开入口行为不变。新增回归用例后上述 55 项全部通过。初始失败日志 `../build-support/payment-build.log` 保留作为问题记录。

## 留给用户手机验证的内容

本轮未启动或连接安卓模拟器，未通过 adb 安装或运行真机测试。规则输入是合成夹具；真实微信版本的通知形式、页面节点 / OCR 命中率，以及荣耀 100 Pro / MagicOS 10 的后台行为尚需用户实测。

原生实际渲染、系统大字号、横屏、读屏及触感也未作设备验收。请按手机自测说明检查；本记录中的“编译通过”不代表这些运行效果已验证。
