package com.prahari.verify.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prahari.verify.PrahariApp
import com.prahari.verify.data.api.PrahariApi
import com.prahari.verify.data.models.Audit
import com.prahari.verify.data.models.CenterMember
import com.prahari.verify.data.models.VerifyCenter
import com.prahari.verify.data.models.VerifyCustomer
import com.prahari.verify.data.models.VerifyResult
import com.prahari.verify.data.verify.VerifyEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat Fill — auditor "center number / member number" me sochta hai
 * (jaise center 395, member 20, naam Laxmi). Chat me "395/20" ya
 * "395/20 laxmi" likho — app khud center dhoondhega, member nikalega,
 * naam cross-check karega, NTB/NTC/ETB list me loan dhoondhega aur fill karega.
 *
 * Loan ID auditor ko kabhi nahi chahiye: saari IDs Prahari ki apni
 * liston se aati hain. Koi bhi step guess nahi karta:
 * - Center/member ek se zyada mile to tappable options dikhata hai, WAIT karta hai
 * - Naam match na ho to ROK deta hai, galat client kabhi fill nahi karta
 * - Fill ke liye wahi VerifyEngine.fillOne use hota hai (skip-if-verified +
 *   save + dobara padhkar verify), jo list/detail screens use karte hain
 *
 * Har step chat message me dikhta hai, taaki user verify kar sake ki
 * sahi client fill ho raha hai.
 */

private data class ChatMsg(val text: String, val fromUser: Boolean, val isError: Boolean = false)

private data class LoanOption(val customer: VerifyCustomer, val category: String)

private sealed interface PendingChoice {
    data class Centers(val options: List<VerifyCenter>, val centerNo: String) : PendingChoice
    data class Loans(val options: List<LoanOption>, val memberName: String) : PendingChoice
}

/** "395/20" ya "395/20 laxmi" (+ "/" ke aas-paas spaces) -> (centerNo, memberNo, naam) */
private fun parseChatInput(raw: String): Triple<String, String, String>? {
    val m = Regex("""^(\d+)\s*/\s*(\d+)\s*(.*)$""").find(raw.trim()) ?: return null
    return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3].trim())
}

/** Code ka aakhri ":" segment (center code "Jab:NAR:187:285" -> "285") */
private fun lastSegment(code: String): String = code.substringAfterLast(":").trim()

/** Dono taraf digits hon to leading zeros hatakar milao ("014" == "14") */
private fun numbersEqual(a: String, b: String): Boolean {
    val x = a.trim()
    val y = b.trim()
    if (x.isEmpty() || y.isEmpty()) return false
    if (!x.all { it.isDigit() } || !y.all { it.isDigit() }) return false
    val nx = x.trimStart('0').ifEmpty { "0" }
    val ny = y.trimStart('0').ifEmpty { "0" }
    return nx == ny
}

/**
 * Center/member match: poora code barabar ho, ya aakhri segment barabar ho
 * (exact string ya numeric — "014" == "14").
 */
private fun codeMatches(input: String, code: String): Boolean {
    val inp = input.trim()
    if (inp.isEmpty() || code.isBlank()) return false
    if (inp.equals(code.trim(), ignoreCase = true)) return true
    val last = lastSegment(code)
    if (last.isNotEmpty()) {
        if (inp.equals(last, ignoreCase = true)) return true
        if (numbersEqual(inp, last)) return true
    }
    return false
}

/** Code ke ":" segments (khaali hatakar) */
private fun codeSegments(code: String): List<String> =
    code.split(":").map { it.trim() }.filter { it.isNotEmpty() }

/** Poore code ke aakhri DO segments center + member hon (numeric-normalized) */
private fun pairMatches(centerNo: String, memberNo: String, code: String): Boolean {
    val segs = codeSegments(code)
    if (segs.size < 2) return false
    val last = segs.last()
    val secondLast = segs[segs.size - 2]
    val memberOk = memberNo.equals(last, ignoreCase = true) || numbersEqual(memberNo, last)
    val centerOk = centerNo.equals(secondLast, ignoreCase = true) || numbersEqual(centerNo, secondLast)
    return memberOk && centerOk
}

/**
 * List entry ka center+member match: customerCode se (poora code), ya centerCode
 * se (agar centerCode hi poora customer code ho). List-first resolution isi par
 * chalti hai — selected-centers ki zaroorat nahi padti.
 */
private fun entryPairMatches(centerNo: String, memberNo: String, entry: VerifyCustomer): Boolean {
    return pairMatches(centerNo, memberNo, entry.customerCode) ||
        pairMatches(centerNo, memberNo, entry.centerCode)
}

/** List entry ka center match: centerCode ka aakhri segment, ya centerId barabar ho */
private fun entryCenterMatches(centerNo: String, entry: VerifyCustomer): Boolean {
    return codeMatches(centerNo, entry.centerCode) ||
        codeMatches(centerNo, entry.centerId)
}

/** as_on_date ke liye aaj ki date (yyyy-MM-dd) */
private fun todayAsOnDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

@Composable
fun ChatFillScreen(
    preselectAuditId: String,
    preselectBranchId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PrahariApp
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var audits by remember { mutableStateOf<List<Audit>>(emptyList()) }
    var auditsLoading by remember { mutableStateOf(true) }
    var auditsError by remember { mutableStateOf<String?>(null) }
    var selectedAudit by remember { mutableStateOf<Audit?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    var messages by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingChoice by remember { mutableStateOf<PendingChoice?>(null) }
    var choiceDeferred by remember { mutableStateOf<CompletableDeferred<Int>?>(null) }

    fun addBot(text: String, isError: Boolean = false) {
        messages = messages + ChatMsg(text, fromUser = false, isError = isError)
    }

    fun loadAudits() {
        auditsLoading = true
        auditsError = null
        scope.launch {
            val r = app.prahariApi.getAudits()
            auditsLoading = false
            if (r.isSuccess) audits = r.getOrNull() ?: emptyList()
            else auditsError = r.exceptionOrNull()?.message
        }
    }

    LaunchedEffect(Unit) { loadAudits() }

    // Preselect: route se aaya audit chuno, na mile to pehla audit
    LaunchedEffect(audits) {
        if (audits.isEmpty() || selectedAudit != null) return@LaunchedEffect
        val pre = audits.firstOrNull { it.id == preselectAuditId }
        if (pre != null) {
            selectedAudit = pre
        } else {
            selectedAudit = audits.first()
            if (preselectAuditId.isNotBlank()) {
                addBot("Audit $preselectAuditId list me nahi mila, isliye pehla audit chuna hai.")
            }
        }
    }

    // Naya message/option aaye to neeche scroll karo
    LaunchedEffect(messages.size, pendingChoice) {
        val total = messages.size + if (pendingChoice != null) 1 else 0
        if (total > 0) listState.scrollToItem(total - 1)
    }

    /** Option chune tak WAIT karta hai; Cancel par -1 milta hai */
    suspend fun awaitChoice(choice: PendingChoice, prompt: String): Int {
        addBot(prompt)
        val deferred = CompletableDeferred<Int>()
        choiceDeferred = deferred
        pendingChoice = choice
        val idx = deferred.await()
        pendingChoice = null
        choiceDeferred = null
        return idx
    }

    fun onChoiceTapped(index: Int) {
        choiceDeferred?.complete(index)
    }

    fun onChoiceCancelled() {
        choiceDeferred?.complete(-1)
    }

    suspend fun runChatCommand(raw: String) {
        val parsed = parseChatInput(raw)
        if (parsed == null) {
            addBot(
                "Samajh nahi aaya. Aise likho: 395/20 ya 395/20 laxmi " +
                    "(center number / member number, naam optional hai)",
                isError = true
            )
            return
        }
        val (centerNo, memberNo, typedName) = parsed
        val audit = selectedAudit
        if (audit == null) {
            addBot("Pehle upar se audit chuno.", isError = true)
            return
        }
        val auditId = audit.id
        val branchId = audit.branchId.ifBlank { preselectBranchId }
        if (auditId.isBlank() || branchId.isBlank()) {
            addBot("Audit ya branch ID nahi mila. Audit list dobara kholo.", isError = true)
            return
        }

        // ---- e. Fill (helper — list-direct aur normal, dono raston se) ----
        // Section (e) ka wahi code: master + VerifyEngine.fillOne (skip-if-verified
        // + save + dobara padhkar verify) + result message.
        suspend fun doFill(
            loan: LoanOption,
            center: VerifyCenter,
            centerNoShown: String,
            memberMatches: List<CenterMember>
        ) {
            val entry = loan.customer
            val auditorId = app.secureStorage.getAuditorId()
            if (auditorId.isNullOrBlank()) {
                addBot(
                    "Auditor ID nahi mila. Prahari Connect me Auditor ID dalo, phir dobara try karo.",
                    isError = true
                )
                return
            }
            val masterR = app.prahariApi.getChecklistMaster(loan.category)
            if (masterR.isFailure) {
                addBot(
                    "Checklist master nahi mila (${loan.category}): ${masterR.exceptionOrNull()?.message}",
                    isError = true
                )
                return
            }
            val fillCustomer = entry.copy(
                centerId = entry.centerId.ifBlank { center.centerId },
                centerCode = entry.centerCode.ifBlank { center.centerCode },
                disbursedAmount = entry.disbursedAmount.ifBlank {
                    memberMatches.firstOrNull { it.disbursedAmount.isNotBlank() }?.disbursedAmount ?: ""
                }
            )
            addBot("Fill kar raha hun: ${fillCustomer.clientName} (${loan.category})…")
            val result = VerifyEngine.fillOne(
                api = app.prahariApi,
                auditId = auditId,
                auditorId = auditorId,
                branchId = branchId,
                category = loan.category,
                customer = fillCustomer,
                master = masterR.getOrNull() ?: emptyList(),
                values = VerifyEngine.defaultValuesFor(fillCustomer)
            )
            when (result.status) {
                VerifyResult.Status.VERIFIED -> addBot(
                    "✅ Verified: ${fillCustomer.clientName} • Center $centerNoShown • " +
                        "Loan ${fillCustomer.disbursementId} • ₹${fillCustomer.disbursedAmount} • ${loan.category}"
                )
                VerifyResult.Status.SKIPPED_ALREADY_VERIFIED -> addBot(
                    "⏭️ Pehle se verified: ${fillCustomer.clientName} • Center $centerNoShown • " +
                        "Loan ${fillCustomer.disbursementId} • ${loan.category} — dobara fill nahi kiya."
                )
                else -> addBot(
                    "❌ Fill fail: ${fillCustomer.clientName} • Loan ${fillCustomer.disbursementId} • " +
                        "${loan.category} — ${result.message}",
                    isError = true
                )
            }
        }

        // ---- a0. Verification lists se seedha match (selected-centers ki zaroorat nahi) ----
        // Gadarwara jaise audits me selected-centers khaali milta hai (0 centers),
        // lekin NTB/NTC/ETB list me center+member ki jaankari hoti hai — isliye
        // pehle list se match karo. List se centerId bhi mil jaata hai, jo
        // member-lookup (disbursements) ke liye chahiye.
        addBot("NTB / NTC / ETB list la raha hun…")
        val earlyLists = mutableListOf<Pair<String, VerifyCustomer>>()
        val earlyErrors = mutableListOf<String>()
        for (cat in PrahariApi.CATEGORIES) {
            val listR = app.prahariApi.getCustomerList(audit.branchName, branchId, cat)
            if (listR.isFailure) {
                earlyErrors.add("$cat: ${listR.exceptionOrNull()?.message}")
                continue
            }
            for (entry in listR.getOrNull() ?: emptyList()) {
                earlyLists.add(cat to entry)
            }
        }
        if (earlyLists.isEmpty() && earlyErrors.isNotEmpty()) {
            addBot(
                "Verification list nahi mili (${earlyErrors.joinToString("; ")}) — " +
                    "phir bhi center-selection se try kar raha hun…"
            )
        }
        // Direct: center+member dono list entry se mile
        val directFound = earlyLists
            .filter { entryPairMatches(centerNo, memberNo, it.second) }
            .map { LoanOption(it.second, it.first) }
            .distinctBy { it.category + "|" + it.customer.disbursementId + "|" + it.customer.customerInfoId }
        if (directFound.isNotEmpty()) {
            val directLoan: LoanOption = when {
                directFound.size == 1 -> directFound[0]
                else -> {
                    val idx = awaitChoice(
                        PendingChoice.Loans(directFound, directFound[0].customer.clientName),
                        "Is member ke ${directFound.size} loans list me mile — sahi wala chuno:"
                    )
                    if (idx < 0) {
                        addBot("Theek hai, ye command chhod diya.")
                        return
                    }
                    directFound[idx]
                }
            }
            val directEntry = directLoan.customer
            if (typedName.isNotBlank()) {
                if (directEntry.clientName.isBlank()) {
                    addBot(
                        "Tumne likha $typedName, lekin list me is client ka naam khaali hai — " +
                            "naam verify nahi ho paya, isliye fill nahi kar raha.",
                        isError = true
                    )
                    return
                }
                if (!directEntry.clientName.contains(typedName, ignoreCase = true)) {
                    addBot(
                        "Tumne likha $typedName, list me mili hui client ${directEntry.clientName} hai — " +
                            "naam match nahi hua, isliye fill NAHI kar raha.",
                        isError = true
                    )
                    return
                }
                addBot("Naam match ho gaya: ${directEntry.clientName} ✓")
            }
            addBot(
                "List se mil gaya: ${directEntry.clientName} • ${directLoan.category} • " +
                    "Loan ${directEntry.disbursementId} • ₹${directEntry.disbursedAmount}"
            )
            val directCenter = VerifyCenter(
                centerId = directEntry.centerId,
                centerCode = directEntry.centerCode,
                centerName = ""
            )
            val directCenterNoShown = lastSegment(directEntry.centerCode).ifBlank { centerNo }
            doFill(directLoan, directCenter, directCenterNoShown, emptyList())
            return
        }
        // Center list se: centerId nikal lo (selected-centers ke bina member-lookup ke liye)
        var centerFromList: VerifyCenter? = null
        val listCenters = earlyLists
            .filter { entryCenterMatches(centerNo, it.second) && it.second.centerId.isNotBlank() }
            .map {
                VerifyCenter(
                    centerId = it.second.centerId,
                    centerCode = it.second.centerCode,
                    centerName = ""
                )
            }
            .distinctBy { it.centerId }
        if (listCenters.size == 1) {
            centerFromList = listCenters[0]
        } else if (listCenters.size > 1) {
            val idx = awaitChoice(
                PendingChoice.Centers(listCenters, centerNo),
                "Center $centerNo ke ${listCenters.size} matches list me mile — sahi wala chuno:"
            )
            if (idx < 0) {
                addBot("Theek hai, ye command chhod diya.")
                return
            }
            centerFromList = listCenters[idx]
        }

        // ---- a0b. Branch ke saare centers (Visit Plan wala tareeka) ----
        // Selected-centers khaali ho tab bhi branch ke centers se centerId mil jaata hai.
        var centerFromBranch: VerifyCenter? = null
        if (centerFromList == null) {
            val bName = audit.branchName
            if (bName.isBlank()) {
                addBot("Branch ka naam nahi mila, isliye branch-centers skip kar raha hun…")
            } else {
                addBot("Branch ($bName) ke saare centers la raha hun…")
                val branchCentersR = app.prahariApi.getCentersByBranch(bName)
                if (branchCentersR.isFailure) {
                    addBot("Branch centers nahi mile: ${branchCentersR.exceptionOrNull()?.message}")
                } else {
                    val branchCenters = branchCentersR.getOrNull() ?: emptyList()
                    val branchMatches = branchCenters.filter { codeMatches(centerNo, it.centerCode) }
                    if (branchMatches.size == 1) {
                        centerFromBranch = branchMatches[0]
                    } else if (branchMatches.size > 1) {
                        val idx = awaitChoice(
                            PendingChoice.Centers(branchMatches, centerNo),
                            "Center $centerNo ke ${branchMatches.size} matches branch me mile — sahi wala chuno:"
                        )
                        if (idx < 0) {
                            addBot("Theek hai, ye command chhod diya.")
                            return
                        }
                        centerFromBranch = branchMatches[idx]
                    }
                }
            }
        }

        // ---- a. Center dhoondho (list/branch se mil gaya ho to selected-centers skip) ----
        val center: VerifyCenter = if (centerFromList != null) {
            centerFromList
        } else if (centerFromBranch != null) {
            centerFromBranch
        } else {
            addBot("Center $centerNo dhoondh raha hun…")
            val centersR = app.prahariApi.getSelectedCenters(auditId)
            if (centersR.isFailure) {
                addBot("Center list nahi mili: ${centersR.exceptionOrNull()?.message}", isError = true)
                return
            }
            val centers = centersR.getOrNull() ?: emptyList()
            val centerMatches = centers.filter { codeMatches(centerNo, it.centerCode) }
            when {
                centerMatches.isEmpty() -> {
                    addBot(
                        "Center $centerNo nahi mila — verification list me bhi nahi, " +
                            "aur is audit ki selected-centers list me bhi ${centers.size} centers mile. " +
                            "Center number dobara check karo.",
                        isError = true
                    )
                    return
                }
                centerMatches.size == 1 -> centerMatches[0]
                else -> {
                    val idx = awaitChoice(
                        PendingChoice.Centers(centerMatches, centerNo),
                        "Center $centerNo ke ${centerMatches.size} matches mile — sahi wala chuno:"
                    )
                    if (idx < 0) {
                        addBot("Theek hai, ye command chhod diya.")
                        return
                    }
                    centerMatches[idx]
                }
            }
        }
        val centerNoShown = lastSegment(center.centerCode).ifBlank { center.centerId }
        val meeting = listOf(center.meetingDay, center.meetingTime)
            .filter { it.isNotBlank() }.joinToString(" ")
        addBot(
            "Center mil gaya: ${center.centerName.ifBlank { center.centerCode }} " +
                "(${center.centerCode}" +
                (if (center.centerId.isNotBlank()) ", ID: ${center.centerId}" else "") + ")" +
                if (meeting.isNotBlank()) " • $meeting" else ""
        )

        // ---- b. Member dhoondho (disbursements primary, customers fallback) ----
        addBot("Center $centerNoShown ke members la raha hun…")
        val asOn = todayAsOnDate()
        var memberMatches: List<CenterMember> = emptyList()
        val disbR = app.prahariApi.getCenterDisbursements(center.centerId, asOn)
        if (disbR.isSuccess) {
            val rows = disbR.getOrNull() ?: emptyList()
            addBot("Disbursements se ${rows.size} rows mile.")
            memberMatches = rows
                .filter { codeMatches(memberNo, it.customerCode) || codeMatches(memberNo, it.memberNo) }
        } else {
            addBot(
                "Disbursement list se nahi mila " +
                    "(${disbR.exceptionOrNull()?.message}), customer list se try kar raha hun…"
            )
        }
        if (memberMatches.isEmpty()) {
            val custR = app.prahariApi.getCenterCustomers(center.centerId, asOn)
            if (custR.isSuccess) {
                val fallback = custR.getOrNull() ?: emptyList()
                addBot("Customer list se ${fallback.size} rows mile.")
                val found = fallback.filter { codeMatches(memberNo, it.customerCode) || codeMatches(memberNo, it.memberNo) }
                if (found.isNotEmpty()) {
                    memberMatches = found
                    addBot("Customer list se member mila.")
                }
            } else {
                addBot("Customer list bhi nahi mili: ${custR.exceptionOrNull()?.message}")
            }
        }
        if (memberMatches.isEmpty()) {
            addBot("Center $centerNoShown me member $memberNo nahi mila.", isError = true)
            return
        }
        val memberName = memberMatches.firstOrNull { it.memberName.isNotBlank() }?.memberName ?: ""
        addBot(
            "Member mil gaya: ${memberName.ifBlank { "Member $memberNo" }} " +
                "(code: ${memberMatches[0].customerCode.ifBlank { "—" }})"
        )

        // ---- c. Naam cross-check (galat client kabhi fill nahi hoga) ----
        if (typedName.isNotBlank()) {
            if (memberName.isBlank()) {
                addBot(
                    "Tumne likha $typedName, lekin record me is member ka naam khaali hai — " +
                        "naam verify nahi ho paya, isliye fill nahi kar raha. " +
                        "Sahi naam ke saath dobara likho.",
                    isError = true
                )
                return
            }
            if (!memberName.contains(typedName, ignoreCase = true)) {
                addBot(
                    "Tumne likha $typedName, mili hui client $memberName hai — " +
                        "naam match nahi hua, isliye fill NAHI kar raha. " +
                        "Sahi naam ke saath dobara likho.",
                    isError = true
                )
                return
            }
            addBot("Naam match ho gaya: $memberName ✓")
        }

        // ---- d. NTB/NTC/ETB list me loan dhoondho ----
        addBot("NTB / NTC / ETB list me is loan ko dhoondh raha hun…")
        val disbIds = memberMatches.map { it.disbursementId }
            .filter { it.isNotBlank() }.distinct()
        val memberInfoId = memberMatches
            .firstOrNull { it.customerInfoId.isNotBlank() }?.customerInfoId ?: ""
        val found = mutableListOf<LoanOption>()
        val listErrors = mutableListOf<String>()
        for (cat in PrahariApi.CATEGORIES) {
            val listR = app.prahariApi.getCustomerList(audit.branchName, branchId, cat)
            if (listR.isFailure) {
                listErrors.add("$cat: ${listR.exceptionOrNull()?.message}")
                continue
            }
            val list = listR.getOrNull() ?: emptyList()
            for (entry in list) {
                val hit = when {
                    // Loan ID mile to wahi pakka pehchaan hai (exact match)
                    disbIds.isNotEmpty() -> entry.disbursementId in disbIds
                    // Loan ID na mile to customer info ID se
                    memberInfoId.isNotBlank() -> entry.customerInfoId == memberInfoId
                    // Dono na mile to center + naam se (aakhri upaay; zyada mile to options dikhenge)
                    else -> entry.centerId == center.centerId &&
                        entry.clientName.isNotBlank() && memberName.isNotBlank() &&
                        entry.clientName.equals(memberName, ignoreCase = true)
                }
                if (hit) found.add(LoanOption(entry, cat))
            }
        }
        val uniqueFound = found.distinctBy {
            it.category + "|" + it.customer.disbursementId + "|" + it.customer.customerInfoId
        }
        if (uniqueFound.isEmpty()) {
            val errNote = if (listErrors.isNotEmpty()) {
                " (List errors: ${listErrors.joinToString("; ")})"
            } else ""
            addBot("Is loan ki NTB/NTC/ETB list me entry nahi mili.$errNote", isError = true)
            return
        }
        val loan: LoanOption = when {
            uniqueFound.size == 1 -> uniqueFound[0]
            else -> {
                val idx = awaitChoice(
                    PendingChoice.Loans(uniqueFound, memberName),
                    "Is member ke ${uniqueFound.size} loans mile — sahi wala chuno:"
                )
                if (idx < 0) {
                    addBot("Theek hai, ye command chhod diya.")
                    return
                }
                uniqueFound[idx]
            }
        }
        val entry = loan.customer
        addBot(
            "List me mil gaya: ${entry.clientName} • ${loan.category} • " +
                "Loan ${entry.disbursementId} • ₹${entry.disbursedAmount}"
        )

        // ---- e. Fill (wahi VerifyEngine: skip-if-verified + save + re-read verify) ----
        doFill(loan, center, centerNoShown, memberMatches)
    }

    fun onSend() {
        val raw = input.trim()
        if (raw.isEmpty() || busy || pendingChoice != null) return
        input = ""
        messages = messages + ChatMsg(raw, fromUser = true)
        busy = true
        scope.launch {
            try {
                runChatCommand(raw)
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Wapas") }
            Spacer(Modifier.width(8.dp))
            Text("Chat se fill karo", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { if (!busy) messages = emptyList() },
                enabled = !busy
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Likho: center number / member number (naam optional) — jaise 395/20 ya 395/20 laxmi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // Branch/audit selector (AutoFill ki branch-select jaisa dropdown)
        when {
            auditsLoading -> Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            auditsError != null -> Column {
                Text("Audit list nahi mili: $auditsError", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { loadAudits() }) { Text("Dobara try karo") }
            }
            audits.isEmpty() -> Text("Koi audit assign nahi hai")
            else -> {
                val sel = selectedAudit
                Box {
                    OutlinedButton(onClick = { menuExpanded = true }) {
                        Text(
                            if (sel == null) "Audit chuno"
                            else "${sel.branchName.ifBlank { sel.branchId }} — ${sel.name.ifBlank { sel.id }}" +
                                if (sel.branchId.isNotBlank()) " (Branch ID: ${sel.branchId})" else ""
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        for (a in audits) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${a.branchName.ifBlank { a.branchId }} — " +
                                            "${a.name.ifBlank { a.id }}" +
                                            if (a.branchId.isNotBlank()) " (ID: ${a.branchId})" else ""
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    if (selectedAudit?.id != a.id) {
                                        selectedAudit = a
                                        addBot(
                                            "Audit badal gaya: " +
                                                "${a.branchName.ifBlank { a.branchId }} — " +
                                                a.name.ifBlank { a.id }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                msg.isError -> MaterialTheme.colorScheme.errorContainer
                                msg.fromUser -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            msg.text,
                            Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            // Tappable options (center ya loan choice) — user ke tap ka WAIT hota hai
            val choice = pendingChoice
            if (choice != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            when (choice) {
                                is PendingChoice.Centers -> {
                                    for ((i, c) in choice.options.withIndex()) {
                                        val meeting = listOf(c.meetingDay, c.meetingTime)
                                            .filter { it.isNotBlank() }.joinToString(" ")
                                        OutlinedButton(
                                            onClick = { onChoiceTapped(i) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                "${c.centerName.ifBlank { c.centerCode }} " +
                                                    "(${c.centerCode})" +
                                                    if (meeting.isNotBlank()) " • $meeting" else ""
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                is PendingChoice.Loans -> {
                                    for ((i, loanOpt) in choice.options.withIndex()) {
                                        val e = loanOpt.customer
                                        OutlinedButton(
                                            onClick = { onChoiceTapped(i) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                "₹${e.disbursedAmount} • " +
                                                    "${e.disbursementDate.ifBlank { "—" }} • " +
                                                    "Loan ${e.disbursementId} • ${loanOpt.category}"
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { onChoiceCancelled() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("❌ Cancel — ye command chhodo") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (busy) {
            Text(
                if (pendingChoice != null) "Ek option chuno — uske baad aage badhega…"
                else "Kaam chal raha hai…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !busy,
                label = { Text("Center / member") },
                placeholder = {
                    Text(
                        if (pendingChoice != null) "Pehle upar se ek option chuno"
                        else "Jaise: 395/20 laxmi"
                    )
                }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSend() },
                enabled = input.isNotBlank() && !busy && pendingChoice == null
            ) { Text("Send") }
        }
    }
}
