# ledger-core

独立的 Kotlin / Room 本地示例账本，不依赖上游的 Ktor、HTTP 本地服务、QuickJS、Hook、OCR 或云端 AI。

## 接口

公共类型都位于 `dev.inkledger.core`。`RoomLedgerRepository.create(context)` 创建进程内单例，打开 **ink-ledger-demo.db**。调用方先执行 `seedDemoIfNeeded()`，并持续明确显示“示例账本”。它与 AutoAccounting 的数据库、名称和版本没有兼容或迁移关系；不要把本数据库重命名成用户现有数据文件。

- `observeEntries()`：按时间倒序、同时间按 id 排序的 Flow。
- `save(entry)`：按 id 新建/编辑；返回 `Saved(entry)`、`Duplicate(existing)` 或 `Rejected(reason)`。编辑必须保留原 evidenceId。
- `saveBatch(entries)`：批量事务。重复证据返回已有账单；无效字段、来源身份变更或存储失败抛 `BatchSaveException`，整批回滚。UI 只在方法成功返回后展示本批结果，不以输入条数当作新增条数。
- `delete(id)`：删除对应记录。删除之后该记录的证据约束随之移除；未来持续采集若要“忽略并永不恢复”，需另建用户可管理的忽略记录，当前不宣称具备该能力。
- `clearEntries()`：Room 实现提供的清空入口，只清账单、不清示例播种 marker。重启不会再次插入演示记录。恢复出厂/清除整个 App 数据仍会生成新的示例库。
- `LedgerStats.forMonth(entries, YearMonth, ZoneId)` 与 Room `statsForMonth()`：仅已确认账单计金额。毛支出、退款、净支出分别返回；转账不计收支。`balanceMinor` 是当月结余，**不是银行卡余额**；类别统计为毛支出，退款单列。日期范围为本地月份的半开区间，非固定天数或 UTC 月。
- `Money.parseMinor()`：输入精确转 CNY 分，拒绝零、负数、超两位小数及超产品上限。`Money.MAX_AMOUNT_MINOR = 99_999_999_999`（999,999,999.99 元），与保存验证和后端合同一致。`formatMinor()` 不经过浮点。

当前仅支持 CNY，金额是正 Long，收支方向由 kind 表达。汇总溢出抛异常而非静默回绕。底层使用复制的 BillType/BillState 映射（CONFIRMED→Edited、PENDING→Wait2Edit），不暗示已同步任何第三方。

## 幂等边界

`evidenceId` 是跨重试稳定的来源身份，唯一索引位于数据库层，所有批量检查/写入位于同一 Room 事务。不同 id 携带同一个非空 evidenceId 时，保留原记录并返回 Duplicate；同 id 同来源可编辑；同 id 不能换来源。空白来源标识拒绝，null 允许多个独立手工账单。

`EvidenceIds.fromBytes(namespace, originalBytes)` 可生成基于原始内容的稳定 SHA-256。不要使用当前时间、随机数或临时 URI 生成 evidenceId。此能力仅处理同一份来源重复投递，**不声称能把微信通知、支付宝页面、银行卡记录自动判断为同一笔交易**，也不按相同金额猜测重复。退款目前作为独立 kind 统计，尚无原订单/部分退款关系模型。

## 已实现端口与明确未接入项

`CaptureSource → RecognitionEngine → ReceiptParser → CaptureCoordinator → LedgerRepository` 是可注入的真实处理骨架。默认采集返回 `Unavailable`；已提供文本可穿过 `ProvidedTextRecognitionEngine`，图片会明确返回未接入 OCR；默认支付票据解析器也返回未接入。字段不全留在 NeedsReview，不能生成 0 元账单。未来注入解析器后，协调器仅保存 PENDING，用户确认后才进入统计。

`CapsulePresenter` 有 App 内回调适配器及明确不可用的系统适配器。这里没有荣耀系统胶囊 SDK、自动通知监听权限申请、无障碍服务、MediaProjection、悬浮窗或上传行为。App 内提示不得作为系统胶囊接入成功的证明。

## 验证

- JVM：金额精度、半开月份/时区/DST、pending 排除、退款/转账、溢出、稳定证据身份、未接入能力不假成功、解析候选保持待确认。
- Android instrumentation：并发来源重试、数据库唯一约束、同 id 编辑、批量回滚、批内重复、示例 marker、SQL 与纯统计一致性。测试代码存在不等于已在真机执行；以构建/测试报告为准。

2026-10-18 本次结果：13 个 JVM 测试通过；上述 8 个 Room instrumentation 测试在 `emulator-5554` 实际执行通过，见 [执行日志](../../build-support/core-instrumentation.log)。这验证了模拟器上的存储行为，不等于荣耀 100 Pro 真机、MagicOS 后台采集或系统胶囊验证。

构建使用 compileSdk 35 / minSdk 29 / JDK 17，Room 2.7.2 与 KSP2。Room 2.6.1 在当前 KSP2 组合触发 `unexpected jvm signature V`，兼容验证后升级；没有为此更改业务模型。

许可与来源见 [UPSTREAM.md](UPSTREAM.md)、[LICENSE](LICENSE) 及上级 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)。
