package com.prahari.verify.data.verify

import com.prahari.verify.data.api.PrahariApi
import com.prahari.verify.data.models.ChecklistParameter
import com.prahari.verify.data.models.VerifyCustomer
import com.prahari.verify.data.models.VerifyFieldValues
import com.prahari.verify.data.models.VerifyResult

/**
 * Ek customer ka verification fill karne ka shared engine.
 * List screen (bulk) aur Detail screen (single) dono yahi use karte hain,
 * taaki rule ek jagah rahe aur baad me AutoFill me merge karna aasan ho.
 *
 * Flow (verify-by-rereading discipline):
 * 1. Feedback GET — pehle se verified hai to SKIP (duplicate nahi)
 * 2. Save POST
 * 3. Feedback GET dobara — stored values expected se milao (VERIFIED / SAVE_OK_VERIFY_FAILED)
 * Koi bhi step fail ho to saaf message ke saath FAILED — kabhi silent nahi.
 */
object VerifyEngine {

    /** User-confirmed defaults — amount customer record se aata hai */
    fun defaultValuesFor(customer: VerifyCustomer): VerifyFieldValues {
        return VerifyFieldValues(
            disbVerifyStatus = "Yes",
            disbVerifyAmount = customer.disbursedAmount,
            loanPurposeVerify = "personal",
            loanCardReceived = "yes",
            loanAgreementDoc = "yes",
            commissionThirdParty = "0",
            remark = "ok"
        )
    }

    suspend fun fillOne(
        api: PrahariApi,
        auditId: String,
        auditorId: String,
        branchId: String,
        category: String,
        customer: VerifyCustomer,
        master: List<ChecklistParameter>,
        values: VerifyFieldValues
    ): VerifyResult {
        val who = if (customer.clientName.isNotBlank()) customer.clientName else customer.disbursementId

        // 1. Pehle se verified?
        val pre = api.getFeedback(customer.disbursementId, category)
        if (pre.isFailure) {
            return VerifyResult(
                customer, VerifyResult.Status.FAILED,
                "Pehle se check nahi ho paya ($who): ${pre.exceptionOrNull()?.message}"
            )
        }
        if (api.isAlreadyVerified(pre.getOrNull()!!)) {
            return VerifyResult(customer, VerifyResult.Status.SKIPPED_ALREADY_VERIFIED, "Pehle se verified hai")
        }

        // 2. Save
        val save = api.saveVerification(
            auditId = auditId,
            auditorId = auditorId,
            branchId = branchId,
            customer = customer,
            category = category,
            values = values,
            checklistParams = master
        )
        if (save.isFailure) {
            return VerifyResult(
                customer, VerifyResult.Status.FAILED,
                "Save fail ($who): ${save.exceptionOrNull()?.message}"
            )
        }

        // 3. Dobara padhkar verify
        val post = api.getFeedback(customer.disbursementId, category)
        if (post.isFailure) {
            return VerifyResult(
                customer, VerifyResult.Status.SAVE_OK_VERIFY_FAILED,
                "Save hua, lekin dobara verify nahi ho paya ($who): ${post.exceptionOrNull()?.message}"
            )
        }
        val expected = values.toCodeAnswerPairs()
        val (ok, detail) = api.verifySavedAnswers(post.getOrNull()!!, expected)
        return if (ok) {
            VerifyResult(customer, VerifyResult.Status.VERIFIED, "Verified ✅")
        } else {
            VerifyResult(
                customer, VerifyResult.Status.SAVE_OK_VERIFY_FAILED,
                "Save hua, lekin stored values match nahi hui ($who): $detail"
            )
        }
    }
}
