package com.prahari.verify.ui.navigation

import com.prahari.verify.data.models.VerifyCustomer

/**
 * Verification flow ka shared state — screens ke beech data pass karne ke liye
 * (AutoFill ke FillSession pattern jaisa, lekin center/member/CDS kuch nahi —
 * sirf audit + category + selected customer).
 */
object VerifySession {
    var auditId: String = ""
    var auditName: String = ""
    var branchId: String = ""
    var branchName: String = ""
    var category: String = "NTC"
    var selectedCustomer: VerifyCustomer? = null

    fun resetAudit() {
        auditId = ""; auditName = ""; branchId = ""; branchName = ""
        category = "NTC"
        selectedCustomer = null
    }

    fun resetCustomer() {
        selectedCustomer = null
    }
}
