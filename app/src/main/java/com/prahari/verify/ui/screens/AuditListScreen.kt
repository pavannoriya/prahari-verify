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
import com.prahari.verify.data.models.Audit
import com.prahari.verify.ui.navigation.VerifySession
import kotlinx.coroutines.launch

/**
 * Audit list — AutoFill ke AuditListScreen jaisa, lekin select karne par
 * verification list (NTB/NTC/ETB) khulti hai, center list nahi.
 */
@Composable
fun AuditListScreen(
    onAuditSelected: (auditId: String, branchId: String) -> Unit,
    onChatFill: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PrahariApp
    val scope = rememberCoroutineScope()

    var audits by remember { mutableStateOf<List<Audit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; error = null
        scope.launch {
            val r = app.prahariApi.getAudits()
            loading = false
            if (r.isSuccess) audits = r.getOrNull() ?: emptyList()
            else error = r.exceptionOrNull()?.message
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mere Audits", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onChatFill) { Text("Chat Fill") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onLogout) { Text("Logout") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Audit chuno — uske NTB / NTC / ETB verification khulenge",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { load() }) { Text("Dobara try karo") }
            }
            audits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Koi audit assign nahi hai")
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(audits) { audit ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            VerifySession.auditId = audit.id
                            VerifySession.auditName = audit.name
                            VerifySession.branchId = audit.branchId
                            VerifySession.branchName = audit.branchName
                            onAuditSelected(audit.id, audit.branchId)
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(audit.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("Branch: ${audit.branchName}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${audit.startDate} → ${audit.endDate}",
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
