# 记一笔：Android 本地架构与扩展边界

日期：2026-10-18。本页对应工作区实现与本次设计，不是已发布产品的部署证明。源码持续建设中；图中“代码已实现”仅表示存在对应实现，构建、模拟器和荣耀真机验收由工程验证记录单独说明。

[打开可交互架构图](../.archify/architecture-ink-ledger-20261018-000358/ink-ledger-architecture.html)：支持节点聚焦、搜索、明暗主题和导出。图中标签区分“代码已实现”“接口已定义”和“未来计划”。

## 前端与本地业务后端

本期采用原生 Kotlin / Android Views。Gradle 只包括 `:app`、`:ledger-core`：前者负责页面、交互和展示状态，后者负责账本模型、Repository 接口、Room 存取与本地统计。同一 APK 进程内通过 Kotlin 调用，既不要求本机 HTTP 服务，也不依赖远程服务器完成本地记账。

四项 Dock 为明细、统计、自动、设置。ViewBinding、RecyclerView、Material BottomSheet、ViewModel 和 SpringAnimation 各负责已有框架擅长的工作；不另造 UI 引擎。动效只能反馈已经完成的业务状态，不决定是否保存。

## 数据约束

- 金额使用 CNY 最小单位分的 `Long`；输入由十进制文本精确转换，避免经由 `Double`。
- `PENDING` 与 `CONFIRMED` 分离。正式金额和分类统计只纳入确认记录；退款按发生月份冲减支出；转账不重复算消费。
- 稳定凭据标识 `evidenceId` 与记录 ID 分离；相同商户、金额和近似时间本身不足以自动合并两笔消费。
- 演示账本使用独立数据库，样例事件不冒充微信、支付宝真实付款通知。
- 隐私、主题和减少动态偏好属于前端设置；不能把“用户希望开启”当成采集能力已可用。

## 上游实际复用与改造范围

上游：[AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting/tree/34b38f82ca9ad6d7905c715c66891977f8809c88)，固定提交 `34b38f82ca9ad6d7905c715c66891977f8809c88`，检查时上游工作区干净。

| 上游证据 | 在新工程中的处理 |
| --- | --- |
| `server/.../db/AppDatabase.kt:52–86` 定义 Room 实体与 DAO；`Db.kt:42–81` 创建 `autoAccount.db` | 复用 Room 与账本领域经验，采用独立的演示数据结构与库名；不直接继承整个 server 模块 |
| `server/.../db/model/BillInfoModel.kt:24–155` 包含类型、金额、时间、商家、分类、来源和状态 | 新模型区分支付渠道与扣款账户，金额改为整数分，保留来源凭据身份 |
| `server/.../db/dao/BillInfoDao.kt:143–150` 用支出减退款；`StatisticsService.kt:40–64` 查询分类/商户统计 | 改造为本地纯计算统计，额外过滤未确认记录；分类展示采用明确口径 |
| `app/.../http/api/BillAPI.kt:52–55` 经 `LocalNetwork` 保存；`LocalNetwork.kt:38` 指向 `127.0.0.1:52045` | 本期不继承这条 HTTP 链路，改为 Repository 直接调用 Room 实现 |
| `server/.../tools/BillService.kt:231–283` 串行执行插入、分组、分类与悬浮反馈 | 不整段搬入；新账本独立约束凭据幂等和事务，App 内展示成功结果 |

不纳入运行依赖：作者遥测、私有/远程 JS 规则、Hook/LSPosed/LSPatch、作者本地 HTTP 服务。相关许可、修改及版权说明以 `android/ledger-core` 的复用说明为准；架构图不构成重新授权。

## 未接通能力

通知采集、截图 OCR、微信与支付宝凭据解析、荣耀系统胶囊使用可替换端口隔离。存在端口不代表已有实现、系统权限、场景准入或真机覆盖率。未来数据进入后，仍须经过校验、凭据身份判断、保存，再通知 UI。

`CaptureCoordinator` 已有编排实现，但 App 尚未装配或调用。默认采集与解析实现会返回不可用；`ProvidedTextRecognitionEngine` 只读取已提供文本，不做图片 OCR。未来注入可用依赖后，当前编排最多保存 `PENDING` 草稿，不将解析结果直接确认为真实支出。后续可靠事实自动确认策略尚待实现和验证。

微信/支付宝在这里是未来交易凭据来源。商户支付 API 不能画成读取任意个人消费历史的 OAuth 授权接口。本期不登录支付账户、不抓取账单、不模拟真实付款成功。

荣耀 100 Pro / MagicOS 10 是目标适配设备。系统胶囊场景尚未获准，本期使用应用内状态提示；具体官方能力边界见 [HONOR-CAPSULE.md](../design/HONOR-CAPSULE.md)。

## 可选远程后端

目前只存在 [OpenAPI 合同](../backend/openapi.yaml) 与 [后端范围说明](../backend/README.md)，没有运行服务、部署地址或 Android 调用端。合同仅含 `POST /v1/recognitions`：未来由用户预览并主动同意后，上传最小必要文本，获得 `NEEDS_REVIEW` 或 `UNRECOGNIZED` 结果；`confirmationRequired` 始终为 `true`。请求要求 `Idempotency-Key`，同一用户、同一键且请求内容不同应返回 `409`。

合同未包含图片上传、账户注册、云同步、加密备份、支付账户读取或账本写入。未来服务自身的 Bearer 身份验证不代表微信或支付宝账户授权。云识别预设关闭，不得作为本地识别失败后自动上传的兜底；不开启云端也能完成本地查看、编辑与统计。

## 已读工作区证据

工作区没有为此次图创建 Git 提交，因此不将未提交文件冒充某个 `HEAD` 的固定内容。下列为本次人工追踪的源码位置；图的自动验收只验证图本身与浏览器行为，不验证支付系统接通。

| 责任 | 代码位置 |
| --- | --- |
| 模块边界 | [settings.gradle.kts](../android/settings.gradle.kts:8) |
| 前端框架与依赖 | [app/build.gradle.kts](../android/app/build.gradle.kts:29) |
| 本地 Repository 装配 | [InkApplication.kt](../android/app/src/main/java/dev/inkledger/app/InkApplication.kt:7) |
| 页面装配、安全区与应用内提示 | [MainActivity.kt](../android/app/src/main/java/dev/inkledger/app/MainActivity.kt:22) |
| 账本观察、保存反馈与演示事件 | [LedgerViewModel.kt](../android/app/src/main/java/dev/inkledger/app/LedgerViewModel.kt:36) |
| 四项 Dock 与可取消弹簧动效 | [InkDock.kt](../android/app/src/main/java/dev/inkledger/app/ui/InkDock.kt:17) |
| 观察、保存、删除及演示播种端口 | [LedgerRepository.kt](../android/ledger-core/src/main/java/dev/inkledger/core/LedgerRepository.kt:5) |
| 账目、来源、状态与保存结果 | [LedgerModels.kt](../android/ledger-core/src/main/java/dev/inkledger/core/LedgerModels.kt:15) |
| 确认记录统计与退款口径 | [LedgerStats.kt](../android/ledger-core/src/main/java/dev/inkledger/core/LedgerStats.kt:29) |
| 整数分金额输入与格式化 | [Money.kt](../android/ledger-core/src/main/java/dev/inkledger/core/Money.kt:6) |
| 事务、凭据防重、独立演示数据库 | [RoomLedgerRepository.kt](../android/ledger-core/src/main/java/dev/inkledger/core/RoomLedgerRepository.kt:15) |
| Room 表与初始化标记 | [InkLedgerDatabase.kt](../android/ledger-core/src/main/java/dev/inkledger/core/data/InkLedgerDatabase.kt:8) |
| 凭据唯一索引与持久化模型 | [LedgerEntryEntity.kt](../android/ledger-core/src/main/java/dev/inkledger/core/data/LedgerEntryEntity.kt:20) |
| Room Flow 与写入操作 | [LedgerDao.kt](../android/ledger-core/src/main/java/dev/inkledger/core/data/LedgerDao.kt:21) |
| 采集、识别、解析与胶囊端口 | [CapturePorts.kt](../android/ledger-core/src/main/java/dev/inkledger/core/CapturePorts.kt:28) |
| 尚未装配到 App 的采集编排 | [CaptureCoordinator.kt](../android/ledger-core/src/main/java/dev/inkledger/core/CaptureCoordinator.kt:16) |
| 可选云识别合同，非运行服务 | [openapi.yaml](../backend/openapi.yaml:12) |
