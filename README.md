# 记一笔（Ink Ledger）

Android 原生记账应用原型，当前为可运行的本地演示版。

- [Android 工程与运行说明](android/README.md)
- [界面与动效设计](design/MASTER.md)
- [架构说明](architecture/README.md)
- [可选后端接口草案](backend/README.md)
- [第三方来源与许可](android/THIRD-PARTY-NOTICES.md)

目前支持手动记账、分类统计、月预算及本地持久化。微信与支付宝付款后的自动采集、截图 OCR、荣耀系统胶囊尚未接入；演示事件不会读取真实支付数据。

仓库保留 AutoAccounting 派生代码的原版权与 GPL-3.0 许可说明。构建需 Android SDK、JDK 17；本机生成的缓存、日志、`local.properties` 和调试 APK 不纳入源码仓库。
