package com.prahari.verify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.prahari.verify.ui.navigation.Routes
import com.prahari.verify.ui.navigation.VerifySession
import com.prahari.verify.ui.screens.AuditListScreen
import com.prahari.verify.ui.screens.ChatFillScreen
import com.prahari.verify.ui.screens.PrahariConnectScreen
import com.prahari.verify.ui.screens.VerificationDetailScreen
import com.prahari.verify.ui.screens.VerificationListScreen
import com.prahari.verify.ui.theme.PrahariTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PrahariTheme {
                PrahariNavHost()
            }
        }
    }
}

@Composable
fun PrahariNavHost() {
    val navController = rememberNavController()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as PrahariApp

    // Start: Prahari login hai to seedha audit list, warna Connect
    var startDest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startDest = if (app.secureStorage.isPrahariLoggedIn()) Routes.AUDIT_LIST
        else Routes.PRAHARI_CONNECT
    }

    if (startDest == null) return // splash

    NavHost(navController = navController, startDestination = startDest!!) {
        composable(Routes.PRAHARI_CONNECT) {
            PrahariConnectScreen(
                onConnected = {
                    navController.navigate(Routes.AUDIT_LIST) {
                        popUpTo(Routes.PRAHARI_CONNECT) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.AUDIT_LIST) {
            AuditListScreen(
                onAuditSelected = { auditId, branchId ->
                    navController.navigate(Routes.verifyList(auditId, branchId))
                },
                onChatFill = {
                    navController.navigate(Routes.chatFill())
                },
                onLogout = {
                    app.secureStorage.clearPrahariToken()
                    navController.navigate(Routes.PRAHARI_CONNECT) {
                        popUpTo(Routes.AUDIT_LIST) { inclusive = true }
                    }
                }
            )
        }
        composable(
            Routes.VERIFY_LIST,
            arguments = listOf(
                navArgument("auditId") { type = NavType.StringType },
                navArgument("branchId") { type = NavType.StringType }
            )
        ) { backStack ->
            val auditId = backStack.arguments?.getString("auditId") ?: ""
            val branchId = backStack.arguments?.getString("branchId") ?: ""
            VerificationListScreen(
                auditId = auditId,
                branchId = branchId,
                onCustomerSelected = { customer ->
                    VerifySession.selectedCustomer = customer
                    navController.navigate(Routes.VERIFY_DETAIL)
                },
                onChatFill = {
                    navController.navigate(Routes.chatFillWithAudit(auditId, branchId))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.CHAT_FILL) {
            ChatFillScreen(
                preselectAuditId = "",
                preselectBranchId = "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Routes.CHAT_FILL_WITH_AUDIT,
            arguments = listOf(
                navArgument("auditId") { type = NavType.StringType },
                navArgument("branchId") { type = NavType.StringType }
            )
        ) { backStack ->
            ChatFillScreen(
                preselectAuditId = backStack.arguments?.getString("auditId") ?: "",
                preselectBranchId = backStack.arguments?.getString("branchId") ?: "",
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.VERIFY_DETAIL) {
            val customer = VerifySession.selectedCustomer
            if (customer == null) {
                // Seedha detail khula to wapas list par
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                VerificationDetailScreen(
                    customer = customer,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
        }
    }
}
