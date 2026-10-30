package com.prahari.verify.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.prahari.verify.PrahariApp
import com.prahari.verify.data.models.ChecklistParameter
import com.prahari.verify.data.models.VerifyCustomer
import com.prahari.verify.data.models.VerifyFieldValues
import com.prahari.verify.data.models.VerifyResult
import com.prahari.verify.data.verify.VerifyEngine
import com.prahari.verify.ui.navigation.VerifySession
import kotlinx.coroutines.launch

/**
 * Per-customer verification — borrower/loan details + 7 fields.
 * Fields user-confirmed rules se pre-filled hote hain, sab edit karne layak hain.
 * Save → VerifyEngine (feedback check → save → re-read verify) → result banner.
 * Agar loan pehle se verified hai to stored values dikhti hain + banner aata hai.
 */
@Composable
fun VerificationDetailScreen(
    customer: VerifyCustomer,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PrahariApp
    val scope = rememberCoroutineScope()
    val category = VerifySession.category

    var master by remember { mutableStateOf<List<ChecklistParameter>?>(null) }
    var masterError by remember { mutableStateOf<String?>(null) }
    var values by remember { mutableStateOf(VerifyEngine.defaultValuesFor(customer)) }
    var storedNote by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VerifyResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Open par: master + pehle ka feedback (verified? stored values?)
    LaunchedEffect(customer.disbursementId, category) {
        loading = true
        val masterR = app.prahariApi.getChecklistMaster(category)
        val fbR = app.prahariApi.getFeedback(customer.disbursementId, category)
        loading = false
        if (masterR.isSuccess) {
            master = masterR.getOrNull()
        } else {
            masterError = "Checklist master nahi mila: ${masterR.exceptionOrNull()?.message}"
        }
        if (fbR.isSuccess) {
            val fb = fbR.getOrNull()!!
            val answers = app.prahariApi.feedbackAnswers(fb)
            if (app.prahariApi.isAlreadyVerified(fb)) {
                // Stored values fields me dikhao (jo save hai wahi)
                values = VerifyFieldValues(
                    disbVerifyStatus = answers["DISB_VERIFY_STATUS"] ?: values.disbVerifyStatus,
                    disbVerifyAmount = answers["DISB_VERIFY_AMOUNT"] ?: values.disbVerifyAmount,
                    loanPurposeVerify = answers["LOAN_PURPOSE_VERIFY"] ?: values.loanPurposeVerify,
                    loanCardReceived = answers["LOAN_CARD_RECEIVED"] ?: values.loanCardReceived,
                    loanAgreementDoc = answers["LOAN_AGREEMENT_DOC"] ?: values.loanAgreementDoc,
                    commissionThirdParty = answers["COMMISSION_THIRD_PARTY"] ?: values.commissionThirdParty,
                    remark = answers["REMARK"] ?: values.remark
                )
                storedNote = "Ye loan pehle se verified hai. Neeche stored values dikh rahi hain."
            }
        }
        // Feedback GET fail ho to bhi form khula rehta hai — save ke waqt dobara check hoga.
    }

    fun doSave() {
        val auditorId = app.secureStorage.getAuditorId()
        if (auditorId.isNullOrBlank()) {
            error = "Auditor ID nahi mila. Prahari Connect me Auditor ID dalo."
            return
        }
        val m = master
        if (m == null) {
            error = masterError ?: "Checklist master load nahi hua. Wapas jakar dobara kholo."
            return
        }
        if (values.disbVerifyAmount.isBlank()) {
            error = "Disbursement Verification Amount khaali hai."
            return
        }
        saving = true; error = null; result = null
        scope.launch {
            val r = VerifyEngine.fillOne(
                api = app.prahariApi,
                auditId = VerifySession.auditId,
                auditorId = auditorId,
                branchId = VerifySession.branchId,
                category = category,
                customer = customer,
                master = m,
                values = values
            )
            saving = false
            result = r
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Wapas") }
            Spacer(Modifier.width(8.dp))
            Text("Disbursement Verification ($category)", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))

        // Loan & customer profile (modal jaisa)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    customer.clientName.ifBlank { "Loan ${customer.disbursementId}" },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text("Loan ID: ${customer.disbursementId}")
                Text("Customer Info ID: ${customer.customerInfoId}")
                if (customer.centerCode.isNotBlank() || customer.centerId.isNotBlank()) {
                    Text("Center: ${customer.centerCode.ifBlank { customer.centerId }}")
                }
                if (customer.disbursedAmount.isNotBlank()) Text("Disbursed Amount: ₹${customer.disbursedAmount}")
                if (customer.principalOs.isNotBlank()) Text("Principal O/S: ₹${customer.principalOs}")
                if (customer.arrears.isNotBlank()) Text("Total Arrears: ₹${customer.arrears}")
                if (customer.bucket.isNotBlank()) Text("Bucket: ${customer.bucket}")
            }
        }

        if (loading) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        storedNote?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        masterError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Text("Disbursement Verification Checklist — 7 parameters", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))

        // 1. Status (user rule: "Yes")
        Text("1. Disbursement Verification Status *")
        Spacer(Modifier.height(4.dp))
        YesNoToggle(
            value = values.disbVerifyStatus,
            yesValue = "Yes",
            onChange = { values = values.copy(disbVerifyStatus = it) }
        )
        Spacer(Modifier.height(12.dp))

        // 2. Amount
        OutlinedTextField(
            value = values.disbVerifyAmount,
            onValueChange = { values = values.copy(disbVerifyAmount = it) },
            label = { Text("2. Disbursement Verification Amount *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // 3. Purpose
        OutlinedTextField(
            value = values.loanPurposeVerify,
            onValueChange = { values = values.copy(loanPurposeVerify = it) },
            label = { Text("3. Loan Purpose Verification") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // 4. Card (user rule: "yes")
        Text("4. Loan Card Received *")
        Spacer(Modifier.height(4.dp))
        YesNoToggle(
            value = values.loanCardReceived,
            yesValue = "yes",
            onChange = { values = values.copy(loanCardReceived = it) }
        )
        Spacer(Modifier.height(12.dp))

        // 5. Agreement (user rule: "yes")
        Text("5. Loan Agreement Document *")
        Spacer(Modifier.height(4.dp))
        YesNoToggle(
            value = values.loanAgreementDoc,
            yesValue = "yes",
            onChange = { values = values.copy(loanAgreementDoc = it) }
        )
        Spacer(Modifier.height(12.dp))

        // 6. Commission
        OutlinedTextField(
            value = values.commissionThirdParty,
            onValueChange = { values = values.copy(commissionThirdParty = it) },
            label = { Text("6. Commission To Third Party For Loan *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))

        // 7. Remark
        OutlinedTextField(
            value = values.remark,
            onValueChange = { values = values.copy(remark = it) },
            label = { Text("7. Remark *") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "Physical verification — Call Status N/A ke roop me automatic store hota hai.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        result?.let { r ->
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (r.status) {
                        VerifyResult.Status.VERIFIED -> MaterialTheme.colorScheme.primaryContainer
                        VerifyResult.Status.SAVE_OK_VERIFY_FAILED -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        when (r.status) {
                            VerifyResult.Status.VERIFIED -> "Verified ✅"
                            VerifyResult.Status.SKIPPED_ALREADY_VERIFIED -> "Pehle se verified ⏭️"
                            VerifyResult.Status.SAVE_OK_VERIFY_FAILED -> "Save hua, verify fail ⚠️"
                            VerifyResult.Status.FAILED -> "Fail ❌"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (r.message.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(r.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (r.status == VerifyResult.Status.VERIFIED) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSaved, modifier = Modifier.fillMaxWidth()) {
                    Text("List par wapas")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { doSave() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving && !loading
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("Save Verification")
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Yes/No toggle — dropdown ki jagah simple do button (koi opt-in API nahi chahiye).
 *  yesValue field ke hisab se alag hai: Status ke liye "Yes", Card/Agreement ke liye "yes"
 *  (user-confirmed rules ke exact strings). */
@Composable
private fun YesNoToggle(value: String, yesValue: String, onChange: (String) -> Unit) {
    val isYes = value.equals("yes", ignoreCase = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = isYes,
            onClick = { onChange(yesValue) },
            label = { Text("Yes") }
        )
        FilterChip(
            selected = !isYes,
            onClick = { onChange("No") },
            label = { Text("No") }
        )
    }
}
