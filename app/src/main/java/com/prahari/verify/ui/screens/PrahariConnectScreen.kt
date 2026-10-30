package com.prahari.verify.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.prahari.verify.PrahariApp
import kotlinx.coroutines.launch

/**
 * Prahari Connect — Prahari username + password.
 * (AutoFill ke PrahariConnectScreen jaisa, lekin Gemini key yahan NAHI hai.)
 * Password phone me kabhi save NAHI hota, sirf JWT token SecureStorage me.
 *
 * Auditor ID: save payload ke liye number chahiye. Username AUDIT_<number> ho to
 * apne aap bhar jata hai; warna haath se dalo (ek baar, phir save rehta hai).
 */
@Composable
fun PrahariConnectScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PrahariApp
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf(app.secureStorage.getPrahariUsername() ?: "") }
    var password by remember { mutableStateOf("") }
    var auditorId by remember {
        mutableStateOf(
            app.secureStorage.getAuditorId()
                ?: Regex("^AUDIT_(\\d+)$").find(username.trim())?.groupValues?.get(1)
                ?: ""
        )
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // Username badle to Auditor ID bhi auto-derive karo (agar khaali hai ya pattern se tha)
    LaunchedEffect(username) {
        val derived = Regex("^AUDIT_(\\d+)$").find(username.trim())?.groupValues?.get(1)
        if (derived != null && auditorId.isBlank()) auditorId = derived
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Prahari Verify", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "NTB / NTC / ETB Disbursement Verification",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Phone me InstaSafe VPN ON hona chahiye",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Prahari Username") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Prahari Password (save nahi hoga)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = auditorId, onValueChange = { auditorId = it },
            label = { Text("Auditor ID (number, optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        info?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.primary) }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (username.isBlank() || password.isBlank()) {
                    error = "Username aur password dalo"; return@Button
                }
                loading = true; error = null; info = null
                scope.launch {
                    val r = app.prahariApi.login(username.trim(), password, auditorId.takeIf { it.isNotBlank() })
                    loading = false
                    if (r.isSuccess) {
                        info = "Connected! ✅"
                        onConnected()
                    } else {
                        error = "Prahari login failed: ${r.exceptionOrNull()?.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(), enabled = !loading
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("Connect Karo")
        }
    }
}
