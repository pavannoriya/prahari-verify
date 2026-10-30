package com.prahari.verify.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Assigned audit (AutoFill ke Audit model jaisa hi) */
@Serializable
data class Audit(
    val id: String = "",
    val name: String = "",
    val branchId: String = "",
    val branchName: String = "",
    val startDate: String = "",
    val endDate: String = ""
)

/** Checklist master ka ek parameter (category NTB/NTC/ETB ke liye) */
data class ChecklistParameter(
    val checklistId: Int,
    val parameterCode: String,
    val parameterName: String,
    val inputType: String = "",
    val isRequired: Boolean = false
)

/** NTB/NTC/ETB customer list ka ek record (ek loan = ek verification) */
data class VerifyCustomer(
    val disbursementId: String = "",
    val customerInfoId: String = "",
    val clientName: String = "",
    val centerId: String = "",
    val centerCode: String = "",
    val disbursedAmount: String = "",
    val principalOs: String = "",
    val arrears: String = "",
    val bucket: String = "",
    /** List response me ho to (loan-option display ke liye); na ho to "" */
    val disbursementDate: String = "",
    /** List endpoint ne status diya ho to ("verified"/"audited"/"pending"), warna "" */
    val listStatus: String = "",
    /** Debugging/display ke liye raw record (kabhi log me token ke saath mat bhejo) */
    val raw: JsonObject? = null
)

/** Chat Fill ke liye audit ka ek selected center (selected-centers response se) */
data class VerifyCenter(
    val centerId: String = "",
    val centerCode: String = "",
    val centerName: String = "",
    val meetingDay: String = "",
    val meetingTime: String = "",
    val branchId: String = ""
)

/**
 * Chat Fill ke liye center ka ek member (disbursements ya customers/risk-details se).
 * customerCode = poora code (jaise "Jab:NAR:187:285:016"); member number = iska
 * aakhri ":" segment. disbursementId khaali bhi ho sakta hai (risk-details fallback).
 */
data class CenterMember(
    val customerCode: String = "",
    val memberName: String = "",
    val disbursementId: String = "",
    val customerInfoId: String = "",
    val disbursedAmount: String = "",
    val disbursementDate: String = "",
    val centerId: String = "",
    val centerCode: String = ""
)

/** Branch counts — list ke upar wale summary cards */
data class VerifyCounts(
    val totalRecords: Int = 0,
    val auditedRecords: Int = 0,
    val disbursedAmount: String = "",
    val principalOs: String = "",
    val totalArrears: String = ""
)

/** Verification form ke 7 fields (user-confirmed defaults, edit karne layak) */
data class VerifyFieldValues(
    val disbVerifyStatus: String = "Yes",
    val disbVerifyAmount: String = "",
    val loanPurposeVerify: String = "personal",
    val loanCardReceived: String = "yes",
    val loanAgreementDoc: String = "yes",
    val commissionThirdParty: String = "0",
    val remark: String = "ok"
) {
    /** Save payload ke items ke liye (CALL_STATUS alag se pehle lagta hai) */
    fun toCodeAnswerPairs(): List<Pair<String, String>> = listOf(
        "DISB_VERIFY_STATUS" to disbVerifyStatus,
        "DISB_VERIFY_AMOUNT" to disbVerifyAmount,
        "LOAN_PURPOSE_VERIFY" to loanPurposeVerify,
        "LOAN_CARD_RECEIVED" to loanCardReceived,
        "LOAN_AGREEMENT_DOC" to loanAgreementDoc,
        "COMMISSION_THIRD_PARTY" to commissionThirdParty,
        "REMARK" to remark
    )
}

/** Ek customer ka fill result (bulk + single dono me) */
data class VerifyResult(
    val customer: VerifyCustomer,
    val status: Status,
    val message: String = ""
) {
    enum class Status { VERIFIED, FAILED, SKIPPED_ALREADY_VERIFIED, SAVE_OK_VERIFY_FAILED }
}
