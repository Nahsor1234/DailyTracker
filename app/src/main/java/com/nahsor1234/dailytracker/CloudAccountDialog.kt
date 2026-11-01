package com.nahsor1234.dailytracker

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun CloudAccountDialog(
    cloud: DailyTrackerCloud,
    syncStatus: String,
    passwordRecoveryPending: Boolean,
    onSignedIn: (DailyTrackerCloud.Account) -> Unit,
    onPasswordRecoveryComplete: (DailyTrackerCloud.Account) -> Unit,
    onSignedOut: () -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var account by remember { mutableStateOf<DailyTrackerCloud.Account?>(null) }

    val worker = remember(cloud) {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "dailytracker-auth").apply { isDaemon = true }
        }
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val active = remember { AtomicBoolean(true) }

    DisposableEffect(worker) {
        onDispose {
            active.set(false)
            worker.shutdownNow()
        }
    }

    fun <T> runTask(task: () -> T, onComplete: (Result<T>) -> Unit) {
        busy = true
        worker.execute {
            val result = runCatching(task)
            mainHandler.post {
                if (active.get()) {
                    busy = false
                    onComplete(result)
                }
            }
        }
    }

    LaunchedEffect(cloud) {
        runTask(
            task = { cloud.currentAccount() },
            onComplete = { result ->
                account = result.getOrNull()
                account?.let(onSignedIn)
                result.exceptionOrNull()?.let {
                    message = it.message ?: "Could not read the saved account session."
                    messageIsError = true
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when {
                    passwordRecoveryPending -> "Set a new password"
                    account == null -> "DailyTracker account"
                    else -> "Account"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (passwordRecoveryPending) {
                    Text(
                        account?.email ?: "Choose a new password for your account.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (syncStatus.isNotBlank()) {
                        Text(syncStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = confirmNewPassword,
                        onValueChange = { confirmNewPassword = it },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (account != null) {
                    Text(account!!.email, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (account!!.verified) "Email verified" else "Email verification pending",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!account!!.verified) {
                        TextButton(
                            onClick = {
                                val verificationEmail = account?.email.orEmpty()
                            runTask(
                                    task = { cloud.resendVerification(verificationEmail) },
                                    onComplete = { result ->
                                        message = result.fold(
                                            { "Verification email requested. Check your inbox." },
                                            { it.message ?: "Could not resend the verification email." }
                                        )
                                        messageIsError = result.isFailure
                                    }
                                )
                            },
                            enabled = !busy
                        ) { Text("Resend verification email") }
                    }
                    if (syncStatus.isNotBlank()) {
                        Text(syncStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            "Your local history will sync with this account.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "The first account used on this device is linked to its local history. Other accounts cannot sync this device's records.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (account!!.verified) {
                        TextButton(onClick = { onSignedIn(account!!) }, enabled = !busy) {
                            Text("Sync now")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Use the same account on your other devices to access synced history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (syncStatus.isNotBlank()) {
                        Text(syncStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (message.isNotBlank()) {
                    Text(
                        message,
                        color = if (messageIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (passwordRecoveryPending) {
                TextButton(
                    onClick = {
                        when {
                            newPassword.length < 6 -> {
                                message = "Password must contain at least 6 characters."
                                messageIsError = true
                            }
                            newPassword != confirmNewPassword -> {
                                message = "The passwords do not match."
                                messageIsError = true
                            }
                            else -> runTask(
                                task = { cloud.updatePassword(newPassword) },
                                onComplete = { result ->
                                    result.onSuccess {
                                        account = it
                                        password = ""
                                        newPassword = ""
                                        confirmNewPassword = ""
                                        message = "Password updated."
                                        messageIsError = false
                                        onPasswordRecoveryComplete(it)
                                    }.onFailure {
                                        message = it.message ?: "Could not update the password."
                                        messageIsError = true
                                    }
                                }
                            )
                        }
                    },
                    enabled = !busy
                ) { Text("Update password") }
            } else if (account != null) {
                TextButton(
                    onClick = {
                        runTask(
                            task = { cloud.signOut() },
                            onComplete = { result ->
                                account = null
                                password = ""
                                message = result.exceptionOrNull()?.message
                                    ?: "Signed out. Local history remains on this device."
                                messageIsError = result.isFailure
                                if (result.isSuccess) onSignedOut()
                            }
                        )
                    },
                    enabled = !busy
                ) { Text("Sign out") }
            } else {
                TextButton(
                    onClick = {
                        if (!email.contains("@") || password.length < 6) {
                            message = "Enter a valid email and a password of at least 6 characters."
                            messageIsError = true
                        } else {
                            message = ""
                            runTask(
                                task = { cloud.signIn(email, password) },
                                onComplete = { result ->
                                    result.onSuccess {
                                        account = it
                                        message = "Signed in."
                                        messageIsError = false
                                        onSignedIn(it)
                                    }.onFailure {
                                        message = it.message ?: "Sign-in failed."
                                        messageIsError = true
                                    }
                                }
                            )
                        }
                    },
                    enabled = !busy
                ) { Text("Sign in") }
            }
        },
        dismissButton = {
            if (passwordRecoveryPending) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
            } else if (account == null) {
                Row {
                    TextButton(
                        onClick = {
                            if (!email.contains("@") || password.length < 6) {
                                message = "Enter a valid email and a password of at least 6 characters."
                                messageIsError = true
                            } else {
                                runTask(
                                    task = { cloud.signUp(email, password) },
                                    onComplete = { result ->
                                        message = result.fold(
                                            { "Account request submitted. Check your email for the verification link, then sign in." },
                                            { it.message ?: "Account creation failed." }
                                        )
                                        messageIsError = result.isFailure
                                    }
                                )
                            }
                        },
                        enabled = !busy
                    ) { Text("Create account") }
                    TextButton(
                        onClick = {
                            if (!email.contains("@")) {
                                message = "Enter your email first."
                                messageIsError = true
                            } else {
                                runTask(
                                    task = { cloud.requestPasswordReset(email) },
                                    onComplete = { result ->
                                        message = result.fold(
                                            { "Password reset email requested. Follow the link in your inbox." },
                                            { it.message ?: "Could not request a password reset." }
                                        )
                                        messageIsError = result.isFailure
                                    }
                                )
                            }
                        },
                        enabled = !busy
                    ) { Text("Reset password") }
                    TextButton(
                        onClick = {
                            if (!email.contains("@")) {
                                message = "Enter your email first."
                                messageIsError = true
                            } else {
                                runTask(
                                    task = { cloud.resendVerification(email) },
                                    onComplete = { result ->
                                        message = result.fold(
                                            { "Verification email requested. Check your inbox." },
                                            { it.message ?: "Could not resend the verification email." }
                                        )
                                        messageIsError = result.isFailure
                                    }
                                )
                            }
                        },
                        enabled = !busy
                    ) { Text("Resend email") }
                }
            } else {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
            }
        }
    )
}
