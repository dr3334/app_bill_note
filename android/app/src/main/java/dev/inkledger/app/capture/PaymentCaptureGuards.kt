package dev.inkledger.app.capture

/** Conservative visible-UI guard; it never returns or persists captured text. */
internal fun isWechatChatWindow(lines: List<String>, hasEditable: Boolean): Boolean {
    if (hasEditable) return true
    val visible = lines.map { it.trim() }.toSet()
    if (visible.any { WECHAT_CHAT_CONTROL.matches(it) }) return true
    // The inbox has no EditText. Do not treat quoted receipts in chat previews as
    // payment windows, or allow a manual probe to capture the conversation list.
    return visible.containsAll(listOf("微信", "通讯录", "发现", "我"))
}

private val WECHAT_CHAT_CONTROL = Regex("发送|按住[ 说]*话|松开[ 结]*束|切换到键盘|切换到语音|聊天信息|语音输入|表情")
