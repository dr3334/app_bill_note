# AutoAccounting 实际复用记录

更新：2026-10-18。上游固定为 [AutoAccountingOrg/AutoAccounting @ 34b38f82ca9ad6d7905c715c66891977f8809c88](https://github.com/AutoAccountingOrg/AutoAccounting/tree/34b38f82ca9ad6d7905c715c66891977f8809c88)。本次核查对象是该提交源码，不是作者最新安装包、私有规则服务或支付宝真实运行环境。

本页区分四种情况：**直接复制**有对应源文件；**改造复用**保留具体上游逻辑并说明改动；**新写适配**只借鉴接口/流程、没有复制上游实现；**第三方依赖**复用独立 SDK。使用同一种 Android 系统 API，不等于复用了 AutoAccounting 的实现。

## 已落地的账本与 UI 复用

| 本工程 | 上游位置 | 类型与改动 |
| --- | --- | --- |
| `android/ledger-core/.../upstream/BillType.kt`、`BillState.kt` | `server/src/main/java/org/ezbook/server/constant/` 同名文件 | 直接复制，改包名、追加来源说明，保留枚举、转换函数与原文件头 |
| `LedgerModels.kt`、`data/LedgerEntryEntity.kt` | `server/.../db/model/BillInfoModel.kt` | 字段与领域语义改造；新主键、独立库、Long 分、来源凭据 ID；不兼容上游原表或数据迁移 |
| `data/LedgerDao.kt`、`LedgerStats.kt` | `server/.../db/dao/BillInfoDao.kt`、`server/.../tools/StatisticsService.kt` | 改造月份范围、分类分组及退款抵扣口径；新增确认状态过滤与整数金额；不照搬同额近时间合并 |
| `android/app/src/main/res/drawable/ic_*.xml` 中已记录的 8 个图标 | 上游 `app/src/main/res/drawable` | 复制矢量资源，改名、部分尺寸和 tint；完整逐项对应见 [THIRD-PARTY-NOTICES.md](../android/THIRD-PARTY-NOTICES.md) |

准确的源文件和修改记录以 [ledger-core/UPSTREAM.md](../android/ledger-core/UPSTREAM.md) 为准。Room、ViewModel、RecyclerView、Material Components、SpringAnimation 是各自的框架/库复用，不应计入“直接复制 AutoAccounting 的功能”。

## 通知：可局部改造，不能整类搬入

上游 [`NotificationService.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/service/NotificationService.kt) 使用 Android `NotificationListenerService`。可抽离的范围是 `onNotificationPosted()` 中从 extras 取标题、优先 `EXTRA_BIG_TEXT` 再回退 `EXTRA_TEXT` 的处理，及空文本、目标包名过滤。它不是官方支付宝账单接口。

整类还依赖 `App`、`WorkMode`、`PrefManager`、`Logger`、`MD5HashTable`、`JsAPI`，最终通过本地 HTTP 分析链路处理；本工程不引入这些全局依赖。重绑实现需要使用本工程真实服务组件，不照搬原文件中传入基类 `NotificationListenerService::class.java` 的代码。Android 只有在用户授权后才可绑定，断开后没有通知事件；服务“权限已打开”与“当前已连接”需分开显示。[Android API](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

上游通知键为 `pkg + title + text`，[`MD5HashTable.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/server/src/main/java/org/ezbook/server/tools/MD5HashTable.kt) 默认缓存 60 秒。这只能抑制重复内容；相近时间真实发生的两笔同额支付可能使用相同文案，不可直接当成账目唯一身份。新实现必须区分通知更新、同一交易多来源和真实不同交易。

## 无障碍：有通用骨架，没有现成支付宝成功页解析器

上游 [`SelectToSpeakService.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt) 监听窗口/内容变化，500 ms 防抖，遍历节点文本，与已记住的页面结构匹配，再启动 OCR。可抽离的是事件防抖、遍历 `text` / `contentDescription`、限制深度和回收节点的通用模式。它依赖 `PageSignatureManager`、`CoreService`、偏好与日志，不能整类直接纳入本工程。

必须避免照搬的行为：

- `generateFingerprint()` 把所有数字替换成 `#`，并用 `lastContentFingerprint` 抑制后续页面。这个布局指纹不能作交易身份，否则可能吞掉不同金额或相同商户的新支付。
- `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` 自 API 26 起已弃用且不再使用，不能因上游注释就声称它能让支付宝 WebView 可读。[Android API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY)
- 新服务使用本工程包名与真实用途，不保留 `com.google.android.accessibility.selecttospeak` 这一类名/包路径来暗示 Google 组件身份。
- 本期只处理支付宝包 `com.eg.android.AlipayGphone`，不把全局窗口树采集当成默认能力；支付密码、键盘、非成功页不属于成功凭据。

[`PageSignatureManager.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/service/ocr/PageSignatureManager.kt) 的实际匹配为包名、Activity 与结构字符串相等；注释中关于模糊/空 Activity 的描述不应替代代码事实。新成功页判断需要项目自己的本地规则与真机样本。

## OCR：系统截图模式可借鉴，ML Kit 是另一个 SDK

上游 [`OcrTools.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/service/ocr/OcrTools.kt) 包含 Android 11+ `takeScreenshot()`、`HardwareBuffer` 转 Bitmap 和资源释放的示例，可按需要局部改造。后半段 Root/Shizuku 修改 secure settings、命令行授权不纳入本工程。Android 截图要求服务声明截图能力；安全窗口、权限、调用频率或显示状态可导致失败，不能保证任何支付页均可截图。[Android 截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))

上游 [`ocr/OcrProcessor.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/ocr/src/main/java/net/ankio/ocr/OcrProcessor.kt) 是 `paddleocr4android` / NCNN PP-OCRv5 的封装，`ocr/build.gradle.kts` 限定 arm64-v8a，仓库带 det/rec 模型资源；代码使用 `Device.CPU`，虽然注释仍写默认 GPU。这个封装和权重没有因“主仓库开源”就自动获得独立且清晰的再发行授权，本期不复制。

当前选择的独立替代是 Google ML Kit **模型捆绑版中文 OCR** `com.google.mlkit:text-recognition-chinese:16.0.1`，不同于需模型下载的 `play-services-mlkit-*`。它属于第三方 SDK 复用，不属于 AutoAccounting 代码复用。[官方接入文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

Google 说明输入图像和识别结果在设备上处理，但 SDK 可能发送性能、使用与安装指标；“本地 OCR”不能自动推出“SDK 零联网”。最终发行须核对合并 Manifest、依赖树、运行网络行为及隐私披露，当前 APK 的权限核验结果另记录在测试文档。[ML Kit 条款与隐私](https://developers.google.com/ml-kit/terms)、[Android 数据披露](https://developers.google.com/ml-kit/android-data-disclosure)

## 规则：开源执行器不等于开源支付宝规则包

[`server/.../engine/RuleGenerator.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/server/src/main/java/org/ezbook/server/engine/RuleGenerator.kt) 从 Room 中的 `RuleModel.js` 拼接脚本；`JsExecutor.kt` 执行。系统规则经 [`RuleAPI.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/http/license/RuleAPI.kt) 的 `/rule/latest`、`/rule/download/` 获取，再由 [`RuleUpdateHelper.kt`](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/update/RuleUpdateHelper.kt) 解包并读取 `dist/rules.json`。

本次对固定仓库 `app/src/main`、`server/src/main` 的源码/JSON/JS 检索，没有找到可直接带入的完整支付宝付款成功通知/页面规则。上游 `xposed/hooks/alipay` 下确有内部消息与 WebView Hook；这些需要 Hook 宿主内部实现，不是通知服务的独立解析器，本项目不采用。

因此，本期支付宝成功/失败/退款解析及证据关联需明确标为**新写的 Kotlin 本地适配**，对受支持文案做窄范围判断。不下载作者私有规则、不承诺作者的识别覆盖率，也不把合成单测文本称作真实支付宝样本。

## 本轮新增实现核对

下表按当前源码核对，构建结果和真机结果另见 [测试文档](ALIPAY-HONOR-TEST.md)。这些实现存在不表示已覆盖真实支付宝所有文案。

| 新工程文件 | 实际来源与边界 |
| --- | --- |
| `app/.../capture/AlipayNotificationService.kt` | 新写的 Android 通知适配，参考上游大文本回退模式；没有复制原整类、JS HTTP 链或内容 TTL 去重 |
| `app/.../capture/AlipayAccessibilityService.kt` | 新写的只读支付宝服务，参考事件防抖/资源释放模式；650 ms 防抖、可见节点上限、跳过输入/敏感页、窗口与锁屏校验；不执行点击/手势 |
| 同文件内截图/OCR | 新写系统截图适配加 ML Kit SDK；仅 API 34+ 使用 `takeScreenshotOfWindow()`，不降级整屏截图；API 30–33 只保留页面节点读取。图片只在内存，关闭/窗口改变后丢弃迟到结果。仅缺金额成功页自动尝试，完全无文字页需用户单次诊断 |
| `app/.../capture/AlipayCaptureManager.kt`、`CaptureState.kt` | 新写本地开关、分项同意、服务状态、串行处理、8 秒单次诊断和最多 100 条脱敏状态日志；不是上游 CoreService 的直接移植 |
| `app/.../ui/AlipayCaptureScreen.kt`、`AlipaySetupSheet.kt` | 新写用户授权与诊断入口，延续本项目原生 Views/BottomSheet；JSON 由系统文档保存器导出到用户选择的位置 |
| `ledger-core/.../alipay/AlipayModels.kt`、`AlipayReceiptParser.kt` | 新写 Kotlin 窄规则解析；单测文案为合成夹具，未取得上游私有规则；当前只处理人民币消费，退款/收款/转账等跳过，OCR 一律待核对 |
| `ledger-core/.../alipay/AlipayProcessor.kt`、`AlipayIdentityStore.kt`、`data/AlipayCaptureDao.kt` 及相关实体 | 新写解析编排、持久化证据关联与删除标记；证据摘要和账目在同一 Room 事务提交，不使用上游内容 TTL 或抹除数字后的页面指纹作为交易 ID |

当前主 App 包名 `dev.inkledger.app.alipaytest`，`InkApplication` 使用 `createLive()` 写入 `ink-ledger-live.db`；原 `ink-ledger-demo.db` 与样例播种不参与真实采集。Room schema v2 新增采集证据/交易关联表，1→2 迁移保留旧账目；这仍不是 AutoAccounting 数据迁移。源码 Manifest 显式移除 `INTERNET` 和 `ACCESS_NETWORK_STATE`，本轮成功交付构建的打包 Manifest 已核实没有这两项，仅请求应用自身签名级动态接收器权限；这不是全系统流量审计，详细范围见 [第三方说明](../android/THIRD-PARTY-NOTICES.md)。

本地数据的备份配置也已收紧：Manifest 设置 `allowBackup=false`、`fullBackupContent=false`，`local_only_data.xml` 对系统云备份和设备迁移排除全部已列数据域。这是本版配置事实，未在荣耀换机/恢复环境实测；用户主动使用系统保存面板导出诊断仍是另一项显式操作。撤回 OCR 只取消 OCR 任务，不应中断仍获准的通知或节点处理。

本轮身份策略的边界：同一来源事件摘要或可靠的非 OCR 支付宝交易号可以精确防重；不同强交易号不会仅因同额同商户就合并。近 10 分钟同额同商户、同会话，或历史任意时刻已经出现过相同正文摘要时，会进入待核对，**不声称自动判断出它们就是同一笔交易**；不会仅因 10 分钟过去就再次自动确认旧页面。OCR 读到的交易号不作强身份。重复事件不覆盖用户已编辑的账目；删除账目同时保留删除标记，防止旧证据自动重建。若缺强身份又与已删内容相同，可能只提示需核对而不生成新账目，用户应手动补记确属新的消费。

解析规则有意保守：没有明确金额不生成账目；有金额但缺商户、页面缺交易时间、身份歧义、历史时间或 OCR 来源等会待核对。通知缺交易时间时当前可使用采集时间记账，不能描述成已经读到支付宝交易时间。自动确认的类别暂为“其他”，用户可修改；本期没有直接复制作者自动分类规则。

OCR 金额适配仅扩展明确标签中的“实付金额/实付金額”等受支持形式；货币符号丢失时仍须有金额标签，不将任意裸数字当作消费。不修饰商户识别结果，保留 OCR 待核对要求；这是新写本地解析的窄修，不是取得了上游识别规则覆盖率。

## 许可与发行边界

仓库根许可证为 GPL v3，部分源文件头却写“Apache License, Version 3.0”，两者冲突尚未澄清。保留来源、原版权和修改说明，按现有项目许可路径管理；不能把异常文件头改写成已经获得宽松授权。最终公开发行还需审查对应源码提供方式、资源/模型/SDK 独立条款及市场隐私要求。此记录不构成法律意见或应用商店批准。

没有纳入本工程的能力：作者遥测、私有规则分发、QuickJS、Hook/LSPosed/LSPatch、Root、Shizuku、本地 HTTP 服务和作者凭据。系统通知/无障碍授权是用户本机授予的 Android 能力，不是微信/支付宝官方个人账单接入，也不是荣耀系统胶囊认证。
