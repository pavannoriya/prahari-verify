package com.prahari.verify.ui.navigation

/** Saare screens ke routes */
object Routes {
    const val PRAHARI_CONNECT = "prahari_connect"
    const val AUDIT_LIST = "audit_list"
    const val VERIFY_LIST = "verify_list/{auditId}/{branchId}"
    const val VERIFY_DETAIL = "verify_detail"
    const val CHAT_FILL = "chat_fill"
    const val CHAT_FILL_WITH_AUDIT = "chat_fill/{auditId}/{branchId}"

    fun verifyList(auditId: String, branchId: String) = "verify_list/$auditId/$branchId"
    fun chatFill() = "chat_fill"
    fun chatFillWithAudit(auditId: String, branchId: String) = "chat_fill/$auditId/$branchId"
}
