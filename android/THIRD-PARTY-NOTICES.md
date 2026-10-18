# 第三方来源与许可通知

本文件记录当前 `0.4.0-alipay-samples` Android 工程的实际复用范围，更新于 2026-10-18；历史版本的权限核验记录仍按对应版本理解。

## AutoAccounting

- 项目：[AutoAccountingOrg/AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting)
- 固定提交：`34b38f82ca9ad6d7905c715c66891977f8809c88`
- 核心代码保留原作者声明 `Copyright (C) 2024 ankio(ankio@ankio.net)`；图标已有声明包含 2023/2025，按原文件保留。
- 仓库根许可证：GPL v3；完整文本保存在 [ledger-core/LICENSE](ledger-core/LICENSE)。复制/改写范围详见 [ledger-core/UPSTREAM.md](ledger-core/UPSTREAM.md)。
- 重要冲突：若干上游文件头写着 “Apache License, Version 3.0”，该声明与仓库 GPL3 不一致。复制文件保留原文；本工程不以此宣称宽松许可、重新授权或已获得上架许可。派生开发按仓库 GPL v3 路线保留通知/修改记录，公开发行前仍需澄清文件声明并提供所需对应源码。

核心模块直接复制 BillType/BillState，并改写账单字段、分类/月份统计 SQL 与退款口径。金额改为整数分，加入确认状态过滤及基于 evidenceId 的事务性幂等；数据库是新设计，未复制上游数据库数据或迁移器。

### App 矢量图标

由 App 集成方从该固定上游 `app/src/main/res/drawable` 复制以下资源，保留原文件已有 copyright。修改内容为文件命名、部分尺寸引用改为 24dp、tint 使用 `ink_text`；`ic_ledger`/`ic_settings` 当前保留 48dp 的固有尺寸。路径与实际最终文件应在发行前再核对。

| 上游资源名 | 本工程资源名 |
| --- | --- |
| `bottom_unselect_order` | `ic_ledger` |
| `ic_statistic` | `ic_stats` |
| `bottom_unselect_rule` | `ic_auto` |
| `bottom_unselect_setting` | `ic_settings` |
| `ic_cancel` | `ic_close` |
| `float_add` | `ic_add` |
| `ic_arrow_back` | `ic_back` |
| `ic_arrow_forward_24` | `ic_forward` |

未复制远程私有规则、OCR 模型/封装、背敲模型或上游服务凭据。不因主仓库开源而推定这些独立资产获得再发行授权。

## 用户提供的支付宝页面示例（0.4）

用户授权把三张真实页面脱敏后用于提示。应用资源 `guide_alipay_payment.png`、`guide_alipay_transfer.png`、`guide_alipay_bill.png` 是内置 image_gen 根据原图编辑的脱敏展示副本；已检查关键金额、标签及遮盖区域。原图、私人文件路径和含原始信息的编辑提示词不进入 APK 或源码仓库。展示来源与遮盖清单见 [脱敏素材说明](../docs/GUIDE-ASSETS.md)。这些支付宝界面、商标与广告元素仅用于识别说明，不表示官方合作；本轮没有将 AutoAccounting 公开截图或旧官方截图随软件分发。

## 本模块直接运行依赖

| 组件 | 当前声明版本 | 上游许可来源 |
| --- | --- | --- |
| AndroidX Room runtime / ktx / compiler（编译期） | 2.7.2 | [AndroidX source / LICENSE](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt)，Apache-2.0 |
| kotlinx.coroutines-core | 1.9.0 | [kotlinx.coroutines 1.9.0 LICENSE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/LICENSE.txt)，Apache-2.0 |
| Kotlin 标准库 | 随根 Kotlin 2.1.21 工具链 | [Kotlin LICENSE](https://github.com/JetBrains/kotlin/blob/v2.1.21/license/LICENSE.txt)，Apache-2.0 |

JUnit4 和 AndroidX test/room-testing 用于测试，不是本模块发布 AAR 的运行依赖。构建工具、App 自身依赖和所有传递依赖需按最终 APK 的依赖树/合并许可清单补齐；本表不能替代完整 SBOM。Android 商标及微信、支付宝、荣耀名称仅用于说明适配目标，本工程未取得官方合作或系统能力认证。

## App 中文 OCR 依赖：Google ML Kit

| 组件 | 本版声明 | 来源与条款 |
| --- | --- | --- |
| ML Kit Text Recognition v2，中文模型捆绑版 | `com.google.mlkit:text-recognition-chinese:16.0.1` | [Android 接入说明](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[ML Kit 条款与隐私](https://developers.google.com/ml-kit/terms)、[Android SDK 数据披露](https://developers.google.com/ml-kit/android-data-disclosure) |

模型随 APK 提供，未使用需要首次下载模型的 `play-services-mlkit-text-recognition-*` 方案。它是 Google 的独立 SDK 和模型依赖，不是从 AutoAccounting 复制的 OCR、Paddle/NCNN 封装或模型；不因本工程使用 GPL v3 路径就把该 SDK/模型改标为 GPL 或 Apache。最终公开发行须同时遵守其适用条款及传递依赖的通知要求。

Google 说明识别输入和输出在设备上处理，同时说明 SDK 可收集设备/应用、安装及性能使用指标。因此，“捆绑模型”本身不代表 SDK 没有网络相关组件。本工程依赖树及合并清单仍含 ML Kit 初始化和 DataTransport 组件；没有据此声明这些组件已从依赖中删除。

**本版权限核验范围**：2026-10-18 成功交付构建的打包 Manifest 及最终 APK 包信息均已核实，`uses-permission` 仅有本应用签名级 `dev.inkledger.app.alipaytest.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；未申请 `INTERNET`、`ACCESS_NETWORK_STATE`、存储、悬浮窗或 `POST_NOTIFICATIONS`。源码用 Manifest merger 移除了前两项。通知/无障碍服务上的 `BIND_*` 是限制谁可绑定服务的声明，不是 App 已获得用户读取授权；使用时仍需用户在系统中逐项授权。该核验说明本版请求权限，不等于已做全系统流量审计；系统自身、支付 App 以及用户主动选择的文档导出目标不在此结论内。

构建与最终安装包核验摘要见 [0.4 交付记录](../artifacts/ALIPAY-SAMPLES-VERIFICATION.md)。原始构建日志仅本地保存。变更依赖或发布版本时应重新检查合并权限、对应依赖条款及隐私说明。
