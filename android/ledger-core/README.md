# ledger-core

独立的 Kotlin / Room 本地账本，包含示例库和真实测试库。不依赖上游的 Ktor、HTTP 本地服务、QuickJS、Hook、OCR 或云端 AI。

## 接口

账本公共类型位于 `dev.inkledger.core`，支付识别类型位于 `dev.inkledger.core.alipay`（内部名称保留兼容，包含微信路由）。`RoomLedgerRepository.create(context)` 创建进程内单例，打开 **ink-ledger-demo.db**。调用方先执行 `seedDemoIfNeeded()`，并持续明确显示“示例账本”。`createLive(context)` 打开独立的 **ink-ledger-live.db**，播种方法在真实库是无操作。真实采集拒绝写示例库。这两份库与 AutoAccounting 的数据库、名称和版本没有兼容或迁移关系；不要把本数据库重命名成用户现有数据文件。

- `observeEntries()`：按时间倒序、同时间按 id 排序的 Flow。
- `save(entry)`：按 id 新建/编辑；返回 `Saved(entry)`、`Duplicate(existing)` 或 `Rejected(reason)`。编辑必须保留原 evidenceId。
- `saveBatch(entries)`：批量事务。重复证据返回已有账单；无效字段、来源身份变更或存储失败抛 `BatchSaveException`，整批回滚。UI 只在方法成功返回后展示本批结果，不以输入条数当作新增条数。
- `delete(id)`：删除对应账单。支付宝处理器产生的账单会同时留下删除标记，同一事件或可靠交易号重放不会重建。手工保存的普通 evidenceId 约束仍随账单删除移除。
- `clearEntries()`：清账单、保留示例播种 marker 与支付宝删除标记。重启不会再次插入演示记录或重放已识别旧交易。清除整个 App 数据会一并清除这些记录。
- `LedgerStats.forMonth(entries, YearMonth, ZoneId)` 与 Room `statsForMonth()`：仅已确认账单计金额。毛支出、退款、净支出分别返回；转账不计收支。`balanceMinor` 是当月结余，**不是银行卡余额**；类别统计为毛支出，退款单列。日期范围为本地月份的半开区间，非固定天数或 UTC 月。
- `Money.parseMinor()`：输入精确转 CNY 分，拒绝零、负数、超两位小数及超产品上限。`Money.MAX_AMOUNT_MINOR = 99_999_999_999`（999,999,999.99 元），与保存验证和后端合同一致。`formatMinor()` 不经过浮点。

当前仅支持 CNY，金额是正 Long，收支方向由 kind 表达。汇总溢出抛异常而非静默回绕。底层使用复制的 BillType/BillState 映射（CONFIRMED→Edited、PENDING→Wait2Edit），不暗示已同步任何第三方。

## 幂等边界

`evidenceId` 是跨重试稳定的来源身份，唯一索引位于数据库层，所有批量检查/写入位于同一 Room 事务。不同 id 携带同一个非空 evidenceId 时，保留原记录并返回 Duplicate；同 id 同来源可编辑；同 id 不能换来源。空白来源标识拒绝，null 允许多个独立手工账单。

`EvidenceIds.fromBytes(namespace, originalBytes)` 可生成基于原始内容的稳定 SHA-256。不要使用当前时间、随机数或临时 URI 生成 evidenceId。此能力仅处理同一份来源重复投递，**不声称能把微信通知、支付宝页面、银行卡记录自动判断为同一笔交易**，也不按相同金额猜测重复。退款目前作为独立 kind 统计，尚无原订单/部分退款关系模型。

## 微信 / 支付宝付款处理器

`AlipayProcessor(RoomLedgerRepository.createLive(context)).process(AlipayEvidence(...))` 接收通知、无障碍节点或已有 OCR 文本。它不获取系统权限、不采集截图，也不上传内容；App 的采集适配器必须从 Android 事件取得真实来源包名，不能从页面文字推断。仅接受支付宝 `com.eg.android.AlipayGphone` 与微信 `com.tencent.mm`；按系统来源路由解析器，包名过滤本身不是对文本真实性的密码学证明。

`AlipayEvidence` 包含 `packageName`、`source`（NOTIFICATION / ACCESSIBILITY / OCR）、`textLines`、`observedAt`、`captureId`、可选 `sessionId`。返回 `Saved`、`Duplicate`、`NeedsReview`、`Ignored` 或 `Failed`。诊断 code/message 是固定脱敏文案；完整文字仅在内存解析，采集者也应避免把原文写日志。

当前规则是本项目新写的窄范围中文解析器。测试包含合成夹具，以及 0.4 新增的三张真实支付宝截图的脱敏人工转录；人工转录不是 OCR 执行结果，**没有验证某个真实微信 / 支付宝版本的完整模板覆盖率**。支付宝基础规则如下：

- 明确成功状态、唯一有效的人民币实付金额、明确商户、无字段冲突或重复疑点才确认。优先实付/付款金额，余额、原价、优惠不代替实付。支持“你已向某商户成功付款…”和“你已成功付款…”；后者缺商户会待核对。
- 无障碍页面还必须有有效交易时间；缺时间会以采集时间暂存 **PENDING**，因此不能宣称所有 App 内付款都可直接自动确认。通知无交易时间可用事件采集时间；调用者应传该通知对应事件时间。超前 5 分钟或早于采集 24 小时的显式交易时间须核对。
- OCR 永远待核对，OCR 交易号不作强身份。成功但无金额返回 `NeedsReview(entry = null, NO_AMOUNT)`，不创建 0 元账单，可供 App 触发用户已同意的 OCR 回退。
- 待付款、失败、收款、转账、退款等明确交易状态/类型不作为消费支出；纯营销通知忽略。成功页面底部“领券/退款/转账”快捷入口不单独否定实际交易。

`AlipayIdentityStore` 在同一 Room 事务中保存账单和采集记录；事件哈希及非 OCR 的支付宝交易号哈希有唯一约束。商户订单号不当支付宝交易号。同事件重试、可靠交易号跨来源重放返回已有结果，保留用户编辑和待核对状态；同交易号金额/商户冲突仅诊断，不覆盖原记录。

缺少可靠共同交易号时，跨来源不能证明同笔：相同采集身份变化、10 分钟内同通知槽位/页面会话、近时同商户同额，以及任何历史完全相同文本只生成独立 **PENDING**，不当作 Duplicate 合并。两笔明确不同交易号即使同商户、同金额、同时间、同通知槽位仍各自确认。这意味着相同文案的新真实消费可能需要人工确认，但不会被静默吞掉。相同页面 10 分钟后重开也不会再次自动累计。

删除后同事件/强身份直接返回 `Duplicate(deleted = true)`；没有交易号、但内容或采集身份无法与已删除信息区分时返回 `NeedsReview(entry = null)`，不会重建。存储仅保留事件/内容/交易号/商户匹配哈希、时间、状态及正常账单字段，不留原始交易号、完整页面文字或图片；哈希不是加密。识别记录/删除标记目前随本地数据库保留，清账单不会清除，后续可另做可管理的清除策略。

Room v2 以 `MIGRATION_1_2` 为本项目 v1 新增 `alipay_receipts`、`alipay_evidence` 表，保留原账单和示例播种 marker；不启用破坏性迁移。这不是 AutoAccounting 原数据库迁移。

## 页面规则与来源隔离（0.3 / 0.4）

### 支付宝真实页面适配（0.4）

`AlipayPageStructure` 仅用于支付宝的非通知页面，定位结果头部主金额与详情字段区，通知和微信入口不走新结构分支。付款页的原价商户行只帮助找商户，不参与金额候选；优惠、积分和广告金额不能补足缺失主金额。账单详情的支出负号转换为内部正整数分，保留账单支付时间；明确正号收入不记消费。“商品说明：收钱码收款”不单独当作收入证据。

转账成功返回 `TRANSFER_NOT_EXPENSE`，经现有 `Ignored` 链路显示固定诊断，不建立消费或待核对账目。当前没有扩展自动转账账务模型。无支付时间、历史时间和 OCR 仍沿用待核对条件；事件身份与数据库版本保持不变。

`AlipayRealPageTranscriptTest` 覆盖真实页面转录、拆行、缺主金额、纯广告、收入、退款、重复主金额与通知入口。App 对支付宝 OCR 单独按可用行坐标排序，不拼接价格列；坐标缺失的行排到末尾且维持相对顺序。真机 OCR 行框和无障碍树顺序尚待用户验证。

### 微信 0.3 契约继续保留

`WechatReceiptParser` 复用金额、商户与交易时间校验，先检查付款凭证结构。支持成功页、账单详情中的拆分字段、`当前状态`、`收款商家`、`微信支付单号`、独立币种节点，以及详情中表示支出的负号金额。至少两组不同的凭证字段才尝试页面解析；重复金额标签不算两组。明确聊天控件、转账、红包、收款、退款、未完成支付与收入正号金额不生成消费支出。

信息完整且交易时间近期的页面凭证可确认。缺商户、缺交易时间、金额歧义、OCR 或疑似重复仍待核对；缺金额返回 `NO_AMOUNT`，不创建零元账单。微信通知要求“微信支付”标题，但标题不能验证发信主体，所以即使字段完整也加入 `WECHAT_NOTIFICATION_UNVERIFIED`。App 适配器在此之前还会过滤聊天类通知与页面。

`PaymentEvidenceIdentity` 集中管理持久来源身份。微信使用 `wechat-*`，支付宝保留原 `alipay-*` 哈希输入与旧数据库标识；事件、payload、capture、session、交易号和商户匹配均隔离渠道。不需要数据库升级或清空旧账目。OCR 和微信通知中的交易号均不作为强身份；因此通知和后续页面可能分别生成待核对记录，不保证自动合并。可靠页面交易号可防重复，金额相同但交易号不同则保留两笔。

0.3 新增 JVM：`WechatReceiptParserTest` 20 项、`PaymentEvidenceIdentityTest` 4 项。新增 Room 测试源 `WechatProcessorRoomTest` 7 项涵盖持久幂等、编辑/删除、微信通知弱身份及跨渠道隔离。本轮仅编译设备测试源，不运行模拟器或设备测试；实际结果见 `../../artifacts/PAYMENT-VERIFICATION.md`。

## 已实现端口与明确未接入项

通用 `CaptureSource → RecognitionEngine → ReceiptParser → CaptureCoordinator → LedgerRepository` 骨架继续保持默认 `Unavailable`；已提供文本可穿过 `ProvidedTextRecognitionEngine`，图片会明确返回未接入 OCR；默认通用票据解析器也返回未接入。字段不全留在 NeedsReview，不能生成 0 元账单。通用协调器仅保存 PENDING。已实现的支付专用链路使用上文的 `AlipayProcessor`，不是把通用默认端口伪装成可用。

`CapsulePresenter` 有 App 内回调适配器及明确不可用的系统适配器。这里没有荣耀系统胶囊 SDK、自动通知监听权限申请、无障碍服务、MediaProjection、悬浮窗或上传行为。App 内提示不得作为系统胶囊接入成功的证明。

## 验证（历史与当前分开）

- JVM：金额精度、半开月份/时区/DST、pending 排除、退款/转账、溢出、稳定证据身份、未接入能力不假成功、解析候选保持待确认。
- Android instrumentation：并发来源重试、数据库唯一约束、同 id 编辑、批量回滚、批内重复、示例 marker、SQL 与纯统计一致性。测试代码存在不等于已在真机执行；以构建/测试报告为准。

基线结果：13 个 JVM 测试通过；上述 8 个 Room instrumentation 测试在 `emulator-5554` 实际执行通过，见 [执行日志](../../build-support/core-instrumentation.log)。支付宝 0.2 的后续记录见 `../../artifacts/ALIPAY-VERIFICATION.md`；微信 0.3 以 `../../artifacts/PAYMENT-VERIFICATION.md` 为准，不能沿用历史设备验证结论。模拟器存储验证不等于荣耀 100 Pro 真机、真实支付宝模板、MagicOS 后台采集或系统胶囊验证。

本轮新增测试：`AlipayReceiptParserTest`（14 项），`AlipayProcessorRoomTest`（15 项），`AlipayMigrationTest`（1 项）。覆盖金额歧义、实际金额与余额、页尾营销入口、无交易时间/OCR待核对、同额不同交易号、通知更新、相同页面隔时重开、并发事务、编辑/删除重放、重启、删除标记多次重放、v1 迁移。可在 Android 工程目录执行 `./gradlew.bat -PinkBuildRoot=D:/app/ink-ledger-build :ledger-core:testDebugUnitTest :ledger-core:assembleDebugAndroidTest`；执行设备测试另用 `:ledger-core:connectedDebugAndroidTest`（需要明确选定测试设备）。ASCII 输出目录规避当前 Windows Gradle worker 参数文件中文路径编码问题，源文件仍在中文项目目录。

构建使用 compileSdk 35 / minSdk 29 / JDK 17，Room 2.7.2 与 KSP2。Room 2.6.1 在当前 KSP2 组合触发 `unexpected jvm signature V`，兼容验证后升级；没有为此更改业务模型。

许可与来源见 [UPSTREAM.md](UPSTREAM.md)、[LICENSE](LICENSE) 及上级 [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)。
