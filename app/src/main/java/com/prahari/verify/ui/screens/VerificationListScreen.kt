package com.prahari.verify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prahari.verify.PrahariApp
import com.prahari.verify.data.api.PrahariApi
import com.prahari.verify.data.models.ChecklistParameter
import com.prahari.verify.data.models.VerifyCounts
import com.prahari.verify.data.models.VerifyCustomer
import com.prahari.verify.data.models.VerifyResult
import com.prahari.verify.data.verify.VerifyEngine
import com.prahari.verify.ui.navigation.VerifySession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Verification list — category chuno (NTB/NTC/ETB) → counts + customer list →
 * per-customer status → single detail ya bulk fill.
 *
 * Bulk pacing: 5 customers ke batch, har batch ke baad 1 second gap
 * (visit-plan app wala anti-block pattern). Stop button se beech me roka ja sakta hai.
 */
@Composable
fun VerificationListScreen(
    auditId: String,
    branchId: String,
    onCustomerSelected: (VerifyCustomer) -> Unit,
    onChatFill: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PrahariApp
    val scope = rememberCoroutineScope()

    var category by remember { mutableStateOf(VerifySession.category) }
    var counts by remember { mutableStateOf<VerifyCounts?>(null) }
    var customers by remember { mutableStateOf<List<VerifyCustomer>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Bulk state
    var bulkRunning by remember { mutableStateOf(false) }
    var bulkProgress by remember { mutableStateOf("") }
    var bulkResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var bulkJob by remember { mutableStateOf<Job?>(null) }
    var bulkStop by remember { mutableStateOf(false) }

    // Search — naam/center/loan/amount se dhoondho. Same naam ke clients
    // alag-alag rows rahenge (har row ka loan ID + info ID alag hota hai),
    // search sirf dikhane wali list ko filter karta hai, bulk fill hamesha
    // poori unverified list par chalta hai.
    var searchQuery by remember { mutableStateOf("") }
    val visibleCustomers = remember(customers, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) customers
        else customers.filter { c ->
            c.clientName.lowercase().contains(q) ||
            c.centerCode.lowercase().contains(q) ||
            c.centerId.lowercase().contains(q) ||
            c.disbursementId.lowercase().contains(q) ||
            c.customerInfoId.lowercase().contains(q) ||
            c.disbursedAmount.lowercase().contains(q)
        }
    }

    fun load() {
        loading = true; error = null
        VerifySession.category = category
        scope.launch {
            val countsR = app.prahariApi.getCounts(VerifySession.branchName, branchId, category)
            val listR = app.prahariApi.getCustomerList(VerifySession.branchName, branchId, category)
            loading = false
            if (countsR.isSuccess) counts = countsR.getOrNull()
            if (listR.isSuccess) {
                customers = listR.getOrNull() ?: emptyList()
                if (countsR.isFailure) {
                    // List mil gayi, counts nahi — counts ki galti dikhao, list mat chhupao
                    error = "Counts nahi mile: ${countsR.exceptionOrNull()?.message}"
                }
            } else {
                error = listR.exceptionOrNull()?.message
                if (countsR.isSuccess) counts = countsR.getOrNull()
            }
        }
    }

    LaunchedEffect(category) { load() }

    fun isClearlyVerified(c: VerifyCustomer): Boolean {
        val s = c.listStatus.lowercase()
        return s.contains("verif") || s == "audited" || s == "done" || s == "complete"
    }

    fun startBulk() {
        val auditorId = app.secureStorage.getAuditorId()
        if (auditorId.isNullOrBlank()) {
            error = "Auditor ID nahi mila. Prahari Connect me Auditor ID dalo, phir dobara try karo."
            return
        }
        val targets = customers.filter { !isClearlyVerified(it) }
        if (targets.isEmpty()) {
            error = "Bulk ke liye koi unverified customer nahi hai (list status ke hisab se)."
            return
        }
        bulkRunning = true; bulkStop = false
        bulkProgress = "Shuru ho raha hai… (0/${targets.size})"
        bulkResults = emptyList()
        bulkJob = scope.launch {
            val masterR = app.prahariApi.getChecklistMaster(category)
            if (masterR.isFailure) {
                error = "Checklist master nahi mila: ${masterR.exceptionOrNull()?.message}"
                bulkRunning = false
                return@launch
            }
            val master: List<ChecklistParameter> = masterR.getOrNull() ?: emptyList()
            var done = 0
            var ok = 0
            var failed = 0
            var skipped = 0
            val results = mutableListOf<String>()
            // Batches of 5 + 1 second gap (anti-block)
            for ((batchIdx, batch) in targets.chunked(5).withIndex()) {
                if (bulkStop) break
                if (batchIdx > 0) delay(1000)
                for (customer in batch) {
                    if (bulkStop) break
                    val values = VerifyEngine.defaultValuesFor(customer)
                    val result: VerifyResult = VerifyEngine.fillOne(
                        api = app.prahariApi,
                        auditId = auditId,
                        auditorId = auditorId,
                        branchId = branchId,
                        category = category,
                        customer = customer,
                        master = master,
                        values = values
                    )
                    done++
                    val who = if (customer.clientName.isNotBlank()) customer.clientName else customer.disbursementId
                    when (result.status) {
                        VerifyResult.Status.VERIFIED -> {
                            ok++
                            results.add(0, "✅ $who — Verified")
                        }
                        VerifyResult.Status.SKIPPED_ALREADY_VERIFIED -> {
                            skipped++
                            results.add(0, "⏭️ $who — Pehle se verified, skip")
                        }
                        VerifyResult.Status.SAVE_OK_VERIFY_FAILED -> {
                            failed++
                            results.add(0, "⚠️ $who — ${result.message}")
                        }
                        VerifyResult.Status.FAILED -> {
                            failed++
                            results.add(0, "❌ $who — ${result.message}")
                        }
                    }
                    bulkProgress = "$done/${targets.size} — Success $ok, Failed $failed, Skipped $skipped"
                    bulkResults = results.toList()
                }
            }
            if (bulkStop) {
                bulkProgress = "Rok diya gaya ($done/${targets.size}) — Success $ok, Failed $failed, Skipped $skipped"
            } else {
                bulkProgress = "Poora hua ($done/${targets.size}) — Success $ok, Failed $failed, Skipped $skipped"
            }
            bulkRunning = false
            // Counts dobara lo taaki progress taaza dikhe
            val countsR = app.prahariApi.getCounts(VerifySession.branchName, branchId, category)
            if (countsR.isSuccess) counts = countsR.getOrNull()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Wapas") }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    VerifySession.auditName.ifBlank { "Verification" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Branch: ${VerifySession.branchName.ifBlank { branchId }}" +
                        if (branchId.isNotBlank()) " (ID: $branchId)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onChatFill) { Text("Chat se fill karo") }
        }
        Spacer(Modifier.height(12.dp))

        // Category selector
        Text("Category chuno:", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (cat in PrahariApi.CATEGORIES) {
                FilterChip(
                    selected = category == cat,
                    onClick = { if (!bulkRunning) category = cat },
                    label = { Text(cat) },
                    enabled = !bulkRunning
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Counts card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val c = counts
                if (c == null) {
                    Text("Counts load ho rahe hain…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "Audited: ${c.auditedRecords} / ${c.totalRecords}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    if (c.disbursedAmount.isNotBlank()) Text("Disbursed: ₹${c.disbursedAmount}")
                    if (c.principalOs.isNotBlank()) Text("Principal O/S: ₹${c.principalOs}")
                    if (c.totalArrears.isNotBlank()) Text("Total Arrears: ₹${c.totalArrears}")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { load() }, enabled = !loading && !bulkRunning) {
                        Text("Refresh")
                    }
                    Button(
                        onClick = { startBulk() },
                        enabled = !loading && !bulkRunning && customers.isNotEmpty()
                    ) { Text("Fill all unverified") }
                    if (bulkRunning) {
                        OutlinedButton(onClick = { bulkStop = true; bulkJob?.cancel() }) { Text("Stop") }
                    }
                }
            }
        }

        if (bulkRunning || bulkProgress.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(bulkProgress, style = MaterialTheme.typography.bodyMedium)
        }
        if (bulkResults.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                LazyColumn(Modifier.padding(8.dp)) {
                    items(bulkResults) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // Search field — center ID / naam / loan ID / amount se dhoondho
        if (!loading && customers.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search: naam / center ID / loan ID / amount") },
                placeholder = { Text("Jaise: 510492 ya Kusum ya 11506854") }
            )
            if (searchQuery.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${visibleCustomers.size} / ${customers.size} dikh rahe hain",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            customers.isEmpty() && error == null -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Text("Is category me koi customer nahi hai ($category)")
            }
            customers.isEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Text("List load nahi hui — upar di gayi error dekho, phir Refresh dabao.")
            }
            visibleCustomers.isEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Text("Search \"$searchQuery\" se koi match nahi mila")
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleCustomers) { customer ->
                    val verified = isClearlyVerified(customer)
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (!bulkRunning) onCustomerSelected(customer)
                        }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    customer.clientName.ifBlank { "Loan ${customer.disbursementId}" },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    if (verified) "Verified" else "Pending",
                                    color = if (verified) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Loan: ${customer.disbursementId} • Info: ${customer.customerInfoId}")
                            if (customer.disbursedAmount.isNotBlank()) {
                                Text("Disbursed: ₹${customer.disbursedAmount}")
                            }
                            if (customer.centerCode.isNotBlank() || customer.centerId.isNotBlank()) {
                                Text("Center: ${customer.centerCode.ifBlank { customer.centerId }}")
                            }
                            Text(
                                "Detail kholne par feedback se pakka status check hoga",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
