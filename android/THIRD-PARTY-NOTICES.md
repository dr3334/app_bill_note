# 第三方来源与许可通知

本文件记录当前本地示例 Android 工程的实际复用范围，更新于 2026-10-18；它不是“全部依赖均已完成发行授权审查”的声明。

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

## 本模块直接运行依赖

| 组件 | 当前声明版本 | 上游许可来源 |
| --- | --- | --- |
| AndroidX Room runtime / ktx / compiler（编译期） | 2.7.2 | [AndroidX source / LICENSE](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt)，Apache-2.0 |
| kotlinx.coroutines-core | 1.9.0 | [kotlinx.coroutines 1.9.0 LICENSE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/LICENSE.txt)，Apache-2.0 |
| Kotlin 标准库 | 随根 Kotlin 2.1.21 工具链 | [Kotlin LICENSE](https://github.com/JetBrains/kotlin/blob/v2.1.21/license/LICENSE.txt)，Apache-2.0 |

JUnit4 和 AndroidX test/room-testing 用于测试，不是本模块发布 AAR 的运行依赖。构建工具、App 自身依赖和所有传递依赖需按最终 APK 的依赖树/合并许可清单补齐；本表不能替代完整 SBOM。Android 商标及微信、支付宝、荣耀名称仅用于说明适配目标，本工程未取得官方合作或系统能力认证。
