# 选择性复用记录

上游：[AutoAccountingOrg/AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting)，固定提交 `34b38f82ca9ad6d7905c715c66891977f8809c88`。本地原仓库未修改。

| 本模块文件 | 上游出处 | 2026-10-18 的修改 |
| --- | --- | --- |
| `src/main/java/dev/inkledger/core/upstream/BillType.kt` | `server/src/main/java/org/ezbook/server/constant/BillType.kt` | 直接复制，保留原 copyright/完整文件头及全部枚举/中文转换，仅改包名并追加来源注释 |
| `src/main/java/dev/inkledger/core/upstream/BillState.kt` | `server/src/main/java/org/ezbook/server/constant/BillState.kt` | 同上，仅改包名并追加来源注释 |
| `LedgerModels.kt`、`data/LedgerEntryEntity.kt` | `server/src/main/java/org/ezbook/server/db/model/BillInfoModel.kt` 的账目、商户、类别、时间、账户和状态字段 | 裁剪为新独立模型；Double 改 Long 分；新 String 主键；新增唯一 evidenceId；只保留四类收支和两个产品状态。不是原表兼容迁移 |
| `data/LedgerDao.kt`、`LedgerStats.kt` | `server/src/main/java/org/ezbook/server/db/dao/BillInfoDao.kt` 的 SUM/COALESCE、类别分组、退款冲减及半开时间范围 | 新表名/字段；仅已确认；整数金额；转账排除；新增事务/唯一来源策略，不复制同金额/近时间去重 |

`LICENSE` 为上游根 GPL v3 完整文本的原样副本。上游根/README 指向 GPL3，但上述原文件头写着不存在的 “Apache License, Version 3.0”。直接复制文件中保留原冲突声明；改写文件保留原作者 copyright 并指向此记录。这里按仓库级 GPL v3 路线进行本地派生开发，**不声称已获得额外宽松许可，也不把文件头冲突隐藏或修正成新授权**。公众发行前仍需与维护者澄清声明、准备对应源码/通知并审查第三方资产。

未复制上游完整 server、远程规则 ZIP、QuickJS、OCR 包/权重、Hook、背敲模型、Bugly、LicenseNetwork、Pastebin 或本地 HTTP 路由。本模块的采集和系统胶囊端口是新写边界，并明确返回未接入能力。
