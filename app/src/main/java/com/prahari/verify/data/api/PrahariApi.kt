package com.prahari.verify.data.api

import com.prahari.verify.data.models.Audit
import com.prahari.verify.data.models.CenterMember
import com.prahari.verify.data.models.ChecklistParameter
import com.prahari.verify.data.models.VerifyCenter
import com.prahari.verify.data.models.VerifyCounts
import com.prahari.verify.data.models.VerifyCustomer
import com.prahari.verify.data.models.VerifyFieldValues
import com.prahari.verify.data.secure.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Prahari API Client — saari requests phone se direct (VPN on hona chahiye)
 * Base: https://prahari.sonataindia.com
 *
 * Pattern (AutoFill jaisa): POST body me {"token": ...} bhejta hai.
 * GET-style URLs ke liye token query param + Authorization header dono bhejta hai,
 * taaki server jo tareeka maane wahi chal jaye.
 *
 * Kabhi bhi koi password/token code ya log me hardcode NAHI hai — token sirf
 * SecureStorage se aata hai.
 */
class PrahariApi(private val secureStorage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    // ================= Login =================
    /**
     * Prahari login. JSON body me username/password (form-encoded se 400 aata hai).
     * Response se access_token nikalta hai. Auditor id bhi nikalne ki koshish karta hai:
     * pehle login response ke keys, phir username AUDIT_<number> pattern, phir hint.
     */
    suspend fun login(username: String, password: String, auditorIdHint: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("username", username)
                    put("password", password)
                }.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("$BASE_URL/backend/auth/login/")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception(plainError(resp.code, text, "login")))
                    }
                    val obj = try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (_: Exception) {
                        return@withContext Result.failure(Exception("Login ka jawab samajh nahi aaya"))
                    }
                    val token = obj["access_token"]?.jsonPrimitive?.contentOrNull
                        ?: obj["token"]?.jsonPrimitive?.contentOrNull
                        ?: obj["access"]?.jsonPrimitive?.contentOrNull
                        ?: obj["jwt"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext Result.failure(
                            Exception(obj["message"]?.jsonPrimitive?.contentOrNull ?: "Login failed")
                        )
                    secureStorage.savePrahariToken(token)
                    secureStorage.savePrahariUsername(username)

                    // Auditor id: response > hint > AUDIT_<number> pattern
                    val fromResp = listOf("auditor_id", "user_id", "userid", "userId", "id")
                        .firstNotNullOfOrNull { key ->
                            val p = obj[key]?.jsonPrimitive ?: return@firstNotNullOfOrNull null
                            p.intOrNull?.toString() ?: p.contentOrNull?.takeIf { it.all { c -> c.isDigit() } }
                        }
                    val fromName = Regex("^AUDIT_(\\d+)$").find(username.trim())?.groupValues?.get(1)
                    val auditorId = fromResp ?: auditorIdHint?.takeIf { it.isNotBlank() } ?: fromName
                    if (auditorId != null) secureStorage.saveAuditorId(auditorId)

                    Result.success(token)
                }
            } catch (e: Exception) {
                Result.failure(Exception("Network error: ${e.message}. VPN on hai? Internet check karo."))
            }
        }

    // ================= Raw helpers =================
    private fun tokenOrFail(): String? = secureStorage.getPrahariToken()

    /** POST — token body me (AutoFill pattern). Response JsonElement (object ya array). */
    private suspend fun postElement(endpoint: String, payload: Map<String, Any?> = emptyMap()): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            try {
                val token = tokenOrFail()
                    ?: return@withContext Result.failure(Exception("Prahari me login nahi ho. Dobara Connect karo."))
                val bodyJson = buildJsonObject {
                    put("token", token)
                    for ((k, v) in payload) {
                        when (v) {
                            null -> put(k, JsonNull)
                            is String -> put(k, v)
                            is Number -> put(k, v.toDouble())
                            is Boolean -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                }.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("$BASE_URL$endpoint")
                    .post(bodyJson)
                    .build()

                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception(plainError(resp.code, text, endpoint)))
                    }
                    try {
                        Result.success(json.parseToJsonElement(text))
                    } catch (_: Exception) {
                        Result.success(buildJsonObject { put("raw", text) })
                    }
                }
            } catch (e: Exception) {
                Result.failure(Exception("Network error: ${e.message}. VPN on hai? Internet check karo."))
            }
        }

    /** GET — token query param + Authorization header dono (GET-style URLs ke liye). */
    private suspend fun getElement(fullUrl: String): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            try {
                val token = tokenOrFail()
                    ?: return@withContext Result.failure(Exception("Prahari me login nahi ho. Dobara Connect karo."))
                val sep = if (fullUrl.contains("?")) "&" else "?"
                val url = fullUrl + sep + "token=" + URLEncoder.encode(token, "UTF-8")
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception(plainError(resp.code, text, fullUrl)))
                    }
                    try {
                        Result.success(json.parseToJsonElement(text))
                    } catch (_: Exception) {
                        Result.success(buildJsonObject { put("raw", text) })
                    }
                }
            } catch (e: Exception) {
                Result.failure(Exception("Network error: ${e.message}. VPN on hai? Internet check karo."))
            }
        }

    /**
     * List/counts endpoints ka method pakka nahi hai (user ne sirf URL diya hai).
     * Pehle POST try karta hai (app ka standard pattern). 404/405 aaye to GET try karta hai.
     * Dono fail ho to POST wali galti dikhata hai (saaf message ke saath).
     */
    private suspend fun postOrGet(endpoint: String, params: Map<String, String>): Result<JsonElement> {
        val postResult = postElement(endpoint, params)
        if (postResult.isSuccess) return postResult
        val msg = postResult.exceptionOrNull()?.message ?: ""
        if (msg.contains("(404)") || msg.contains("(405)")) {
            val query = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            return getElement("$BASE_URL$endpoint?$query")
        }
        return postResult
    }

    /** Plain-word error messages — kabhi silent fail nahi, code + hint ke saath. */
    private fun plainError(code: Int, body: String, endpoint: String): String {
        val snippet = body.replace("\n", " ").take(300).trim()
        val tail = if (snippet.isNotEmpty()) " Jawab: $snippet" else ""
        return when (code) {
            400 -> "Galat request (400) [$endpoint].$tail"
            401 -> "Login expire ho gaya hai (401). Dobara Prahari Connect karo."
            403 -> "Prahari ne request rok di hai (403). VPN on hai? Thodi der baad dobara try karo."
            404 -> "Ye endpoint nahi mila (404) [$endpoint]."
            405 -> "Method galat hai (405) [$endpoint]."
            500 -> "Prahari server error (500).$tail Thodi der baad dobara try karo."
            else -> "API Error: $code [$endpoint].$tail"
        }
    }

    // ================= Assigned audits (AutoFill jaisa) =================
    suspend fun getAudits(): Result<List<Audit>> {
        var r = postElement("/backend/audit/list/")
        if (r.isFailure && r.exceptionOrNull()?.message?.contains("(404)") == true) {
            r = postElement("/backend/audit/assigned-audits/")
        }
        return r.map { el ->
            val obj = el.jsonObject
            val keys = obj.keys.joinToString(",")
            val arr = obj["audits"]?.jsonArray
                ?: obj["data"]?.jsonArray
                ?: obj["results"]?.jsonArray
                ?: obj["assigned_audits"]?.jsonArray
                ?: obj.values.firstOrNull {
                    try { it.jsonArray.isNotEmpty() } catch (_: Exception) { false }
                }?.jsonArray
                ?: emptyList()
            val audits = arr.mapNotNull { item ->
                try {
                    val o = item.jsonObject
                    fun str(vararg keys: String): String {
                        for (k in keys) {
                            o[k]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) return it }
                            o[k]?.jsonPrimitive?.intOrNull?.let { return it.toString() }
                        }
                        return ""
                    }
                    val id = str("id", "audit_id", "auditId", "auditID")
                    if (id.isEmpty()) return@mapNotNull null
                    Audit(
                        id = id,
                        name = str("name", "audit_name", "auditName", "title", "branch_name"),
                        branchId = str("branch_id", "branchId", "branchID"),
                        branchName = str("branch_name", "branchName"),
                        startDate = str("start_date", "startDate", "from_date"),
                        endDate = str("end_date", "endDate", "to_date")
                    )
                } catch (_: Exception) { null }
            }
            if (audits.isEmpty()) throw Exception("Audit parse nahi hua. Response keys: $keys")
            audits
        }
    }

    // ================= Checklist master =================
    /**
     * GET /backend/audit/disbursement-checklist-master/?category=NTB|NTC|ETB
     * Response ka shape pakka nahi hai, isliye parameter_code wale objects ko
     * poore JSON tree me dhoondhkar collect karta hai (nested sections bhi).
     */
    suspend fun getChecklistMaster(category: String): Result<List<ChecklistParameter>> {
        val r = getElement("$BASE_URL/backend/audit/disbursement-checklist-master/?category=$category")
        return r.map { el ->
            val found = mutableListOf<ChecklistParameter>()
            collectParameters(el, found)
            // Duplicate code ho to pehla rakho
            val unique = found.distinctBy { it.parameterCode }
            if (unique.isEmpty()) {
                val keys = if (el is JsonObject) el.keys.joinToString(",") else "top-level array"
                throw Exception("Checklist master me koi parameter nahi mila ($category). Response keys: $keys")
            }
            unique
        }
    }

    private fun collectParameters(el: JsonElement, out: MutableList<ChecklistParameter>) {
        when (el) {
            is JsonObject -> {
                val code = el["parameter_code"]?.jsonPrimitive?.contentOrNull
                    ?: el["code"]?.jsonPrimitive?.contentOrNull
                if (!code.isNullOrBlank()) {
                    val id = el["checklist_id"]?.jsonPrimitive?.intOrNull
                        ?: el["id"]?.jsonPrimitive?.intOrNull
                        ?: -1
                    val name = el["parameter_name"]?.jsonPrimitive?.contentOrNull
                        ?: el["name"]?.jsonPrimitive?.contentOrNull
                        ?: el["title"]?.jsonPrimitive?.contentOrNull
                        ?: code
                    val inputType = el["input_type"]?.jsonPrimitive?.contentOrNull
                        ?: el["type"]?.jsonPrimitive?.contentOrNull ?: ""
                    val required = el["is_required"]?.jsonPrimitive?.booleanOrNull
                        ?: el["required"]?.jsonPrimitive?.booleanOrNull ?: false
                    out.add(ChecklistParameter(id, code, name, inputType, required))
                }
                for ((_, v) in el) collectParameters(v, out)
            }
            is JsonArray -> for (v in el) collectParameters(v, out)
            else -> Unit
        }
    }

    // ================= Customer list =================
    /**
     * /backend/audit/branch/ntb-ntc-data/ — branch ka NTB/NTC customer list.
     * Method confirm nahi hai: pehle POST (token + audit/branch/category body me),
     * 404/405 par GET (query params me). Dono fail ho to saaf error.
     */
    suspend fun getCustomerList(auditId: String, branchId: String, category: String): Result<List<VerifyCustomer>> {
        val params = mapOf(
            "audit_id" to auditId,
            "branch_id" to branchId,
            "category" to category,
            "clientCategory" to category
        )
        val r = postOrGet("/backend/audit/branch/ntb-ntc-data/", params)
        return r.map { el ->
            val arr = findArray(el)
                ?: throw Exception("Customer list me koi record nahi mila ($category). Response samajh nahi aaya.")
            val seen = mutableSetOf<String>()
            arr.mapNotNull { item ->
                try {
                    val o = item.jsonObject
                    val c = parseCustomer(o)
                    if (c.disbursementId.isBlank() && c.customerInfoId.isBlank()) return@mapNotNull null
                    val key = c.disbursementId + "|" + c.customerInfoId
                    if (!seen.add(key)) return@mapNotNull null // duplicate loan record skip
                    c
                } catch (_: Exception) { null }
            }
        }
    }

    /** Object me data/results/customers/records/list/items array dhoondho, ya top-level array. */
    private fun findArray(el: JsonElement): JsonArray? =
        findArrayIn(el, listOf("data", "results", "customers", "records", "list", "items", "rows"))

    /** Wahi dhoondh, lekin caller ki key-order se (centers/disbursements/risk-details ke liye). */
    private fun findArrayIn(el: JsonElement, keys: List<String>): JsonArray? {
        if (el is JsonArray) return el
        if (el !is JsonObject) return null
        for (key in keys) {
            val v = el[key]
            if (v is JsonArray) return v
            if (v is JsonObject) {
                val nested = findArrayIn(v, keys)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val p = this[k]?.jsonPrimitive ?: continue
            p.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            p.intOrNull?.let { return it.toString() }
            p.doubleOrNull?.let {
                // 30300.0 -> "30300" (amount clean dikhe)
                return if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString()
            }
        }
        return ""
    }

    private fun parseCustomer(o: JsonObject): VerifyCustomer {
        val statusRaw = o.str("verification_status", "status", "audited_status")
        val verifiedFlag = o["is_verified"]?.jsonPrimitive?.booleanOrNull
            ?: o["verified"]?.jsonPrimitive?.booleanOrNull
        val listStatus = when {
            verifiedFlag == true -> "verified"
            statusRaw.isNotBlank() -> statusRaw
            else -> ""
        }
        return VerifyCustomer(
            disbursementId = o.str("disbursementid", "disbursement_id", "disbursementId", "loan_id", "loanId", "loanID"),
            customerInfoId = o.str("customerinfoid", "customer_info_id", "customerInfoId", "customer_id"),
            clientName = o.str("client_name", "borrower_name", "customer_name", "applicant_name", "clientName"),
            centerId = o.str("center_id", "centerid", "centerId"),
            centerCode = o.str("center_code", "centercode", "centerCode"),
            disbursedAmount = o.str("disbursed_amount", "disbursedamt", "disbursedAmount", "disbursement_amount"),
            principalOs = o.str("principal_os", "principalos", "principalOs", "principal_outstanding"),
            arrears = o.str("total_arrears", "arrears", "totalArrears"),
            bucket = o.str("bucket", "bucket_days", "arrear_bucket"),
            disbursementDate = o.str("disbursement_date", "disbursementdate", "disbursementDate"),
            listStatus = listStatus,
            raw = o
        )
    }

    // ================= Counts =================
    /**
     * /backend/audit/branch/ntb-ntc-counts/ — total/audited records + amounts.
     * List jaisa hi POST-pehle-then-GET pattern.
     */
    suspend fun getCounts(auditId: String, branchId: String, category: String): Result<VerifyCounts> {
        val params = mapOf(
            "audit_id" to auditId,
            "branch_id" to branchId,
            "category" to category,
            "clientCategory" to category
        )
        val r = postOrGet("/backend/audit/branch/ntb-ntc-counts/", params)
        return r.map { el ->
            val o = findCountObject(el)
                ?: throw Exception("Counts samajh nahi aaye ($category).")
            fun intVal(vararg keys: String): Int {
                for (k in keys) {
                    val p = o[k]?.jsonPrimitive ?: continue
                    p.intOrNull?.let { return it }
                    p.contentOrNull?.filter { c -> c.isDigit() }?.toIntOrNull()?.let { return it }
                }
                return 0
            }
            VerifyCounts(
                totalRecords = intVal("total_records", "totalRecords", "total", "total_customers"),
                auditedRecords = intVal("audited_records", "auditedRecords", "audited", "verified_records", "verified"),
                disbursedAmount = o.str("disbursed_amount", "disbursedamt", "total_disbursed"),
                principalOs = o.str("principal_os", "principalos"),
                totalArrears = o.str("total_arrears", "arrears")
            )
        }
    }

    private fun findCountObject(el: JsonElement): JsonObject? {
        if (el !is JsonObject) return null
        val countKeys = setOf(
            "total_records", "totalRecords", "total", "audited_records",
            "auditedRecords", "audited", "verified_records", "verified"
        )
        if (el.keys.any { it in countKeys }) return el
        for (key in listOf("data", "counts", "summary", "result")) {
            val v = el[key]
            if (v is JsonObject) {
                val nested = findCountObject(v)
                if (nested != null) return nested
            }
        }
        return null
    }

    // ================= Chat Fill: centers / members =================
    /**
     * POST /backend/audit/selected-centers/ {audit_id} — AutoFill wala endpoint.
     * Response shape pakka nahi hai, isliye centers/data/results/list array dhoondhta hai.
     * Kabhi crash nahi karta: samajh na aaye to saaf error.
     */
    suspend fun getSelectedCenters(auditId: String): Result<List<VerifyCenter>> {
        val r = postElement("/backend/audit/selected-centers/", mapOf("audit_id" to auditId))
        return r.map { el ->
            val arr = findArrayIn(el, listOf("centers", "data", "results", "list", "items", "rows"))
                ?: throw Exception("Selected centers ki list samajh nahi aayi.")
            arr.mapNotNull { item ->
                try {
                    val o = item.jsonObject
                    val centerId = normalizeId(o.str("center_id", "centerid", "centerId", "id"))
                    val centerCode = o.str("center_code", "centercode", "centerCode", "code")
                    if (centerId.isBlank() && centerCode.isBlank()) return@mapNotNull null
                    VerifyCenter(
                        centerId = centerId,
                        centerCode = centerCode,
                        centerName = o.str("center_name", "centername", "centerName", "name"),
                        meetingDay = o.str("meeting_day", "meetingday", "meetingDay", "day"),
                        meetingTime = o.str("meeting_time", "meetingtime", "meetingTime", "time"),
                        branchId = o.str("branch_id", "branchId")
                    )
                } catch (_: Exception) { null }
            }
        }
    }

    /**
     * POST /backend/audit/centers/disbursements/ {center_id, as_on_date} — AutoFill wala endpoint.
     * Center ke saare disbursement rows (member lookup ka PRIMARY source).
     */
    suspend fun getCenterDisbursements(centerId: String, asOnDate: String): Result<List<CenterMember>> {
        val r = postElement(
            "/backend/audit/centers/disbursements/",
            mapOf("center_id" to centerId, "as_on_date" to asOnDate)
        )
        return r.map { el ->
            val arr = findArrayIn(
                el,
                listOf("disbursements", "data", "results", "list", "items", "rows", "records")
            ) ?: throw Exception("Disbursement list samajh nahi aayi.")
            arr.mapNotNull { item ->
                try {
                    val o = item.jsonObject
                    parseCenterMember(o)
                        // Entry nested ho (jaise {customer: {...}}) to andar bhi dhoondho
                        ?: o.values.firstNotNullOfOrNull { v ->
                            try {
                                if (v is JsonObject) parseCenterMember(v) else null
                            } catch (_: Exception) { null }
                        }
                } catch (_: Exception) { null }
            }
        }
    }

    /**
     * POST /backend/audit/customers/risk-details/ {center_id, as_on_date} — AutoFill wala endpoint.
     * Member lookup ka FALLBACK source (jab disbursements me member/code na mile).
     * Risk record me loan ID na bhi ho to bhi member ka naam + code mil jaata hai.
     */
    suspend fun getCenterCustomers(centerId: String, asOnDate: String): Result<List<CenterMember>> {
        val r = postElement(
            "/backend/audit/customers/risk-details/",
            mapOf("center_id" to centerId, "as_on_date" to asOnDate)
        )
        return r.map { el ->
            val arr = findArrayIn(
                el,
                listOf("customer_risks", "customers", "data", "results", "list", "items", "rows", "records")
            ) ?: throw Exception("Customer list samajh nahi aayi.")
            arr.mapNotNull { item ->
                try {
                    val o = item.jsonObject
                    parseCenterMember(o)
                        ?: o.values.firstNotNullOfOrNull { v ->
                            try {
                                if (v is JsonObject) parseCenterMember(v) else null
                            } catch (_: Exception) { null }
                        }
                } catch (_: Exception) { null }
            }
        }
    }

    /**
     * Ek disbursement/risk record se CenterMember. Code ke liye: pehle poora
     * customercode, na mile to center_code + member part se jodo.
     * ID "11506854.0" jaise string-form doubles ko "11506854" karta hai.
     */
    private fun parseCenterMember(o: JsonObject): CenterMember? {
        val code = o.str(
            "customercode", "customer_code", "client_code",
            "clientid", "client_id", "member_code", "membercode"
        )
        val centerCode = o.str("center_code", "centercode", "centerCode")
        val memberPart = o.str(
            "cust_id", "custid", "member_no", "memberno",
            "member_number", "client_number"
        )
        val fullCode = when {
            code.isNotBlank() -> code
            centerCode.isNotBlank() && memberPart.isNotBlank() -> "$centerCode:$memberPart"
            else -> ""
        }
        val disbId = normalizeId(
            o.str("disbursementid", "disbursement_id", "disbursementId", "loan_id", "loanid", "loanId")
        )
        val infoId = normalizeId(
            o.str("customerinfoid", "customer_info_id", "customerInfoId", "customer_id")
        )
        if (fullCode.isBlank() && disbId.isBlank() && infoId.isBlank()) return null
        return CenterMember(
            customerCode = fullCode,
            memberName = o.str(
                "applicantname", "applicant_name", "client_name",
                "customer_name", "borrower_name", "name"
            ),
            disbursementId = disbId,
            customerInfoId = infoId,
            disbursedAmount = o.str(
                "disbursedamt", "disbursed_amount", "disbursement_amount", "loan_amount"
            ),
            disbursementDate = o.str("disbursementdate", "disbursement_date", "disbursementDate"),
            centerId = normalizeId(o.str("center_id", "centerid", "centerId")),
            centerCode = centerCode
        )
    }

    /** "11506854.0" -> "11506854"; "0014" jaisi cheezon ko chhedta nahi. */
    private fun normalizeId(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || !t.contains(".")) return t
        val d = t.toDoubleOrNull() ?: return t
        return if (d == kotlin.math.floor(d)) d.toLong().toString() else t
    }

    // ================= Feedback read (verify) =================
    /**
     * GET /backend/audit/disbursement-checklist-feedback/?disbursement_id=<loan>&category=<cat>
     * Form khulte hi call hota hai (pehle se saved verification laane ke liye).
     * Fill se PEHLE skip-check aur save ke BAAD verify — dono ke liye yahi use hota hai.
     */
    suspend fun getFeedback(disbursementId: String, category: String): Result<JsonElement> =
        getElement("$BASE_URL/backend/audit/disbursement-checklist-feedback/?disbursement_id=$disbursementId&category=$category")

    /** Feedback response se parameter_code -> answer ka map. */
    fun feedbackAnswers(feedback: JsonElement): Map<String, String> {
        val out = mutableMapOf<String, String>()
        fun collect(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    val code = el["parameter_code"]?.jsonPrimitive?.contentOrNull
                    if (!code.isNullOrBlank()) {
                        val ans = el["answer"]?.jsonPrimitive?.contentOrNull
                            ?: el["value"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (ans.isNotBlank()) out[code] = ans
                    }
                    for ((_, v) in el) collect(v)
                }
                is JsonArray -> for (v in el) collect(v)
                else -> Unit
            }
        }
        collect(feedback)
        // Direct fields bhi ho sakte hain (object me code = answer)
        if (feedback is JsonObject) {
            for (code in VERIFY_CODES) {
                feedback[code]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { out[code] = it }
            }
        }
        return out
    }

    /** Pehle se verified hai? (koi bhi 7 me se answer bhara ho to verified maano) */
    fun isAlreadyVerified(feedback: JsonElement): Boolean {
        val answers = feedbackAnswers(feedback)
        if (answers.isEmpty()) return false
        if (feedback is JsonObject) {
            val flag = feedback["verified"]?.jsonPrimitive?.booleanOrNull
                ?: feedback["is_verified"]?.jsonPrimitive?.booleanOrNull
                ?: feedback["audited"]?.jsonPrimitive?.booleanOrNull
            if (flag == true) return true
        }
        return VERIFY_CODES.any { answers[it]?.isNotBlank() == true }
    }

    /**
     * Save ke baad verify: stored answers expected se milte hain?
     * Yes/No case-insensitive, amounts numeric compare (30300 == 30300.0).
     */
    fun verifySavedAnswers(feedback: JsonElement, expected: List<Pair<String, String>>): Pair<Boolean, String> {
        val answers = feedbackAnswers(feedback)
        val mismatches = mutableListOf<String>()
        for ((code, want) in expected) {
            val got = answers[code]
            if (got == null) {
                mismatches.add("$code missing")
                continue
            }
            val ok = if (want.equals("yes", ignoreCase = true) || want.equals("no", ignoreCase = true)) {
                got.equals(want, ignoreCase = true)
            } else if (want.toDoubleOrNull() != null && got.toDoubleOrNull() != null) {
                want.toDouble() == got.toDouble()
            } else {
                got.trim() == want.trim()
            }
            if (!ok) mismatches.add("$code: chaha '$want', mila '$got'")
        }
        return if (mismatches.isEmpty()) true to "Sab values sahi store hui"
        else false to mismatches.joinToString("; ")
    }

    // ================= Feedback save =================
    /**
     * POST /backend/audit/disbursement-checklist-feedback/save/
     * EXACT body (koi extra key nahi):
     * {audit_id (number), auditor_id (number), branch_id (STRING), center_id (number),
     *  clientCategory, client_name, customerinfoid (STRING), disbursementid (STRING),
     *  items: [{checklist_id, parameter_code, parameter_name, answer}],
     *  token, verification_mode: "Physical"}
     * Pehla item CALL_STATUS = "NA" (auto). Baaki 7 user-confirmed rules se.
     */
    suspend fun saveVerification(
        auditId: String,
        auditorId: String,
        branchId: String,
        customer: VerifyCustomer,
        category: String,
        values: VerifyFieldValues,
        checklistParams: List<ChecklistParameter>
    ): Result<JsonElement> = withContext(Dispatchers.IO) {
        try {
            val token = tokenOrFail()
                ?: return@withContext Result.failure(Exception("Prahari me login nahi ho. Dobara Connect karo."))
            val auditNum = auditId.toIntOrNull()
                ?: return@withContext Result.failure(Exception("Audit ID number nahi hai: $auditId"))
            val auditorNum = auditorId.toIntOrNull()
                ?: return@withContext Result.failure(
                    Exception("Auditor ID number nahi mila. Prahari Connect me Auditor ID dalo.")
                )
            val centerNum = customer.centerId.toIntOrNull()
                ?: return@withContext Result.failure(
                    Exception("Center ID nahi mila is record me (${customer.clientName}). List dobara load karo.")
                )
            if (customer.disbursementId.isBlank() || customer.customerInfoId.isBlank()) {
                return@withContext Result.failure(
                    Exception("Loan ID / Customer Info ID nahi mila (${customer.clientName}). List dobara load karo.")
                )
            }

            val idByCode = checklistParams.associate { it.parameterCode to it.checklistId }
            val nameByCode = checklistParams.associate { it.parameterCode to it.parameterName }
            val callStatusId = idByCode["CALL_STATUS"] ?: 1 // task: CALL_STATUS checklist_id = 1

            val missing = values.toCodeAnswerPairs().map { it.first }
                .filter { it !in idByCode }
            if (missing.isNotEmpty()) {
                return@withContext Result.failure(
                    Exception("Checklist master me ye fields nahi mile: ${missing.joinToString(", ")}. Master dobara load karo.")
                )
            }

            val items = buildJsonArray {
                addJsonObject {
                    put("checklist_id", callStatusId)
                    put("parameter_code", "CALL_STATUS")
                    put("parameter_name", nameByCode["CALL_STATUS"] ?: "Call Status")
                    put("answer", "NA")
                }
                for ((code, answer) in values.toCodeAnswerPairs()) {
                    addJsonObject {
                        put("checklist_id", idByCode[code]!!)
                        put("parameter_code", code)
                        put("parameter_name", nameByCode[code] ?: code)
                        put("answer", answer)
                    }
                }
            }

            val bodyJson = buildJsonObject {
                put("audit_id", auditNum)
                put("auditor_id", auditorNum)
                put("branch_id", branchId) // STRING (jaise "66")
                put("center_id", centerNum)
                put("clientCategory", category)
                put("client_name", customer.clientName)
                put("customerinfoid", customer.customerInfoId) // STRING
                put("disbursementid", customer.disbursementId) // STRING
                put("items", items)
                put("token", token)
                put("verification_mode", "Physical")
            }.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/backend/audit/disbursement-checklist-feedback/save/")
                .post(bodyJson)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception(plainError(resp.code, text, "save")))
                }
                try {
                    Result.success(json.parseToJsonElement(text))
                } catch (_: Exception) {
                    Result.success(buildJsonObject { put("raw", text) })
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}. VPN on hai? Internet check karo."))
        }
    }

    companion object {
        const val BASE_URL = "https://prahari.sonataindia.com"

        /** 7 verification fields (CALL_STATUS alag se auto lagta hai) */
        val VERIFY_CODES = listOf(
            "DISB_VERIFY_STATUS",
            "DISB_VERIFY_AMOUNT",
            "LOAN_PURPOSE_VERIFY",
            "LOAN_CARD_RECEIVED",
            "LOAN_AGREEMENT_DOC",
            "COMMISSION_THIRD_PARTY",
            "REMARK"
        )

        /** Categories — Prahari Assigned Activities wali */
        val CATEGORIES = listOf("NTB", "NTC", "ETB")
    }
}
