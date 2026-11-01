package com.nahsor1234.dailytracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

@Composable
internal fun CloudAccountDialog(
    cloud: DailyTrackerCloud,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<DailyTrackerCloud.Account?>(null) }
    val worker = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(cloud) {
        worker.execute {
            val result = runCatching { cloud.currentAccount() }.getOrNull()
            account = result
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "DailyTracker account" else "Account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (account != null) {
                    Text(account!!.email)
                    Text(if (account!!.verified) "Email verified" else "Email verification pending")
                    Text("Cloud sync is available for this account.")
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (account != null) {
                TextButton(onClick = {
                    busy = true
                    worker.execute {
                        val result = runCatching { cloud.signOut() }
                        busy = false
                        account = null
                        message = result.exceptionOrNull()?.message.orEmpty()
                    }
                }, enabled = !busy) { Text("Sign out") }
            } else {
                TextButton(onClick = {
                    if (email.isBlank() || password.length < 6) {
                        message = "Enter a valid email and a password of at least 6 characters."
                    } else {
                        busy = true
                        message = ""
                        worker.execute {
                            val result = runCatching { cloud.signIn(email, password) }
                            account = result.getOrNull()
                            message = result.exceptionOrNull()?.message
                                ?: if (account != null) "Signed in." else "Check your email to verify your account."
                            busy = false
                        }
                    }
                }, enabled = !busy) { Text("Sign in") }
            }
        },
        dismissButton = {
            if (account == null) {
                Row {
                    TextButton(onClick = {
                        if (email.isBlank() || password.length < 6) {
                            message = "Enter an email and a password of at least 6 characters."
                        } else {
                            busy = true
                            worker.execute {
                                val result = runCatching { cloud.signUp(email, password) }
                                message = result.exceptionOrNull()?.message
                                    ?: "Account created. Check your email for the verification link."
                                busy = false
                            }
                        }
                    }, enabled = !busy) { Text("Create account") }
                    TextButton(onClick = {
                        if (email.isBlank()) message = "Enter your email first."
                        else {
                            busy = true
                            worker.execute {
                                val result = runCatching { cloud.requestPasswordReset(email) }
                                message = result.exceptionOrNull()?.message
                                    ?: "Password reset email requested."
                                busy = false
                            }
                        }
                    }, enabled = !busy) { Text("Reset password") }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
