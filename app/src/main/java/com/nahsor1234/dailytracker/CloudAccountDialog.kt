package com.nahsor1234.dailytracker

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Same surface language as the floating nav bar and the Progress cards.
private val SheetShape = RoundedCornerShape(32.dp)
private val FieldShape = RoundedCornerShape(18.dp)
private val SheetBrush = Brush.verticalGradient(listOf(Color(0xFF222E25), Color(0xFF161E18)))
private val Hairline = Color.White.copy(alpha = 0.10f)

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
    var creatingAccount by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    // One haptic per outcome: success confirms, an error is rejected.
    LaunchedEffect(message, messageIsError) {
        if (message.isNotBlank()) {
            if (messageIsError) haptics.reject() else haptics.confirm()
        }
    }

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

    // ---- actions (logic unchanged from the previous dialog) ---------------------------------
    fun submitSignIn() {
        if (!email.contains("@") || password.length < 6) {
            message = "Enter a valid email and a password of at least 6 characters."
            messageIsError = true
            return
        }
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

    fun submitSignUp() {
        if (!email.contains("@") || password.length < 6) {
            message = "Enter a valid email and a password of at least 6 characters."
            messageIsError = true
            return
        }
        runTask(
            task = { cloud.signUp(email, password) },
            onComplete = { result ->
                message = result.fold(
                    { "Account request submitted. Check your email for the verification link, then sign in." },
                    { it.message ?: "Account creation failed." }
                )
                messageIsError = result.isFailure
                if (result.isSuccess) creatingAccount = false
            }
        )
    }

    fun requestReset() {
        if (!email.contains("@")) {
            message = "Enter your email first."
            messageIsError = true
            return
        }
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

    fun resendVerification(target: String) {
        if (!target.contains("@")) {
            message = "Enter your email first."
            messageIsError = true
            return
        }
        runTask(
            task = { cloud.resendVerification(target) },
            onComplete = { result ->
                message = result.fold(
                    { "Verification email requested. Check your inbox." },
                    { it.message ?: "Could not resend the verification email." }
                )
                messageIsError = result.isFailure
            }
        )
    }

    fun submitNewPassword() {
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
    }

    fun signOut() {
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
    }

    // ---- UI ----------------------------------------------------------------------------------
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // The card eases in (scale + fade) on top of the dialog window's own fade.
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(Unit) { entrance.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
        Box(
            Modifier.graphicsLayer {
                val t = entrance.value
                scaleX = 0.93f + 0.07f * t
                scaleY = 0.93f + 0.07f * t
                alpha = t
            }.padding(horizontal = 20.dp).widthIn(max = 420.dp).fillMaxWidth()
                .clip(SheetShape)
                .background(SheetBrush)
                .border(1.dp, Hairline, SheetShape)
        ) {
            // The same soft top-corner glow the Progress screen uses.
            val glow = MaterialTheme.colorScheme.primary
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(Float.POSITIVE_INFINITY, 0f),
                        radius = 700f
                    )
                )
            )
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val signedIn = account
                when {
                    passwordRecoveryPending -> RecoveryContent(
                        email = signedIn?.email,
                        newPassword = newPassword,
                        confirmNewPassword = confirmNewPassword,
                        onNewPasswordChange = { newPassword = it },
                        onConfirmChange = { confirmNewPassword = it },
                        busy = busy,
                        showPassword = showPassword,
                        onToggleShow = { showPassword = !showPassword },
                        syncStatus = syncStatus,
                        onSubmit = ::submitNewPassword,
                        onClose = onDismiss
                    )
                    signedIn != null -> SignedInContent(
                        account = signedIn,
                        busy = busy,
                        syncStatus = syncStatus,
                        onSync = { onSignedIn(signedIn) },
                        onResendVerification = { resendVerification(signedIn.email) },
                        onSignOut = ::signOut,
                        onClose = onDismiss
                    )
                    else -> SignedOutContent(
                        email = email,
                        password = password,
                        onEmailChange = { email = it },
                        onPasswordChange = { password = it },
                        creating = creatingAccount,
                        onModeChange = {
                            creatingAccount = it
                            message = ""
                        },
                        busy = busy,
                        showPassword = showPassword,
                        onToggleShow = { showPassword = !showPassword },
                        syncStatus = syncStatus,
                        onSubmit = { if (creatingAccount) submitSignUp() else submitSignIn() },
                        onForgot = ::requestReset,
                        onResend = { resendVerification(email) },
                        onClose = onDismiss
                    )
                }

                if (busy) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    StatusBanner(message, messageIsError)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Content for each state
// ---------------------------------------------------------------------------------------------

@Composable
private fun SignedOutContent(
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    creating: Boolean,
    onModeChange: (Boolean) -> Unit,
    busy: Boolean,
    showPassword: Boolean,
    onToggleShow: () -> Unit,
    syncStatus: String,
    onSubmit: () -> Unit,
    onForgot: () -> Unit,
    onResend: () -> Unit,
    onClose: () -> Unit
) {
    AvatarBadge(initial = null)
    Spacer(Modifier.height(16.dp))
    Text(
        if (creating) "Create your account" else "Welcome back",
        color = MaterialTheme.colorScheme.onBackground,
        style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Sign in to back up your daily history and use it on your other devices.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(20.dp))

    ModeSwitch(creating = creating, enabled = !busy, onChange = onModeChange)
    Spacer(Modifier.height(18.dp))

    AuthField(
        value = email, onValueChange = onEmailChange, label = "Email",
        enabled = !busy, keyboardType = KeyboardType.Email
    )
    Spacer(Modifier.height(12.dp))
    AuthField(
        value = password, onValueChange = onPasswordChange, label = "Password",
        enabled = !busy, keyboardType = KeyboardType.Password,
        password = true, showPassword = showPassword, onToggleShow = onToggleShow
    )

    if (!creating) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LinkButton("Forgot password?", enabled = !busy, onClick = onForgot)
            LinkButton("Resend verification", enabled = !busy, onClick = onResend)
        }
    } else {
        Spacer(Modifier.height(6.dp))
        Text(
            "Use at least 6 characters. We'll email you a link to verify the address.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(10.dp))

    PrimaryButton(if (creating) "Create account" else "Sign in", enabled = !busy, onClick = onSubmit)
    if (syncStatus.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            syncStatus, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(4.dp))
    LinkButton("Not now", enabled = !busy, onClick = onClose, muted = true)
}

@Composable
private fun SignedInContent(
    account: DailyTrackerCloud.Account,
    busy: Boolean,
    syncStatus: String,
    onSync: () -> Unit,
    onResendVerification: () -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AvatarBadge(initial = account.email.firstOrNull()?.uppercaseChar()?.toString())
    Spacer(Modifier.height(16.dp))
    Text(
        account.email,
        color = scheme.onBackground,
        textAlign = TextAlign.Center,
        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal)
    )
    Spacer(Modifier.height(10.dp))
    Pill(
        text = if (account.verified) "Email verified" else "Verification pending",
        positive = account.verified
    )
    Spacer(Modifier.height(18.dp))

    InfoCard {
        Text(
            "SYNC",
            color = scheme.primary,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (syncStatus.isNotBlank()) syncStatus else "Your local history will sync with this account.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The first account used on this device is linked to its local history. Other accounts cannot sync this device's records.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(16.dp))

    if (account.verified) {
        PrimaryButton("Sync now", enabled = !busy, onClick = onSync)
    } else {
        PrimaryButton("Resend verification email", enabled = !busy, onClick = onResendVerification)
    }
    Spacer(Modifier.height(8.dp))
    SecondaryButton("Sign out", enabled = !busy, onClick = onSignOut, destructive = true)
    LinkButton("Close", enabled = !busy, onClick = onClose, muted = true)
}

@Composable
private fun RecoveryContent(
    email: String?,
    newPassword: String,
    confirmNewPassword: String,
    onNewPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    busy: Boolean,
    showPassword: Boolean,
    onToggleShow: () -> Unit,
    syncStatus: String,
    onSubmit: () -> Unit,
    onClose: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AvatarBadge(initial = null)
    Spacer(Modifier.height(16.dp))
    Text(
        "Set a new password",
        color = scheme.onBackground,
        style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
    )
    Spacer(Modifier.height(6.dp))
    Text(
        email ?: "Choose a new password for your account.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
    Spacer(Modifier.height(20.dp))
    AuthField(
        value = newPassword, onValueChange = onNewPasswordChange, label = "New password",
        enabled = !busy, keyboardType = KeyboardType.Password,
        password = true, showPassword = showPassword, onToggleShow = onToggleShow
    )
    Spacer(Modifier.height(12.dp))
    AuthField(
        value = confirmNewPassword, onValueChange = onConfirmChange, label = "Confirm new password",
        enabled = !busy, keyboardType = KeyboardType.Password,
        password = true, showPassword = showPassword, onToggleShow = onToggleShow
    )
    if (syncStatus.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            syncStatus, style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Update password", enabled = !busy, onClick = onSubmit)
    LinkButton("Close", enabled = !busy, onClick = onClose, muted = true)
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

/** Circular badge; shows the user's initial when signed in, otherwise the account glyph. */
@Composable
private fun AvatarBadge(initial: String?) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.30f), scheme.primary.copy(alpha = 0.14f))))
            .border(1.dp, scheme.primary.copy(alpha = 0.40f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (initial != null) {
            Text(initial, color = scheme.primary, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light))
        } else {
            val tint = scheme.primary
            Canvas(Modifier.size(28.dp)) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(tint, size.width * 0.20f, Offset(size.width / 2f, size.height * 0.32f), style = stroke)
                drawArc(
                    color = tint, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(size.width * 0.14f, size.height * 0.60f),
                    size = Size(size.width * 0.72f, size.height * 0.60f),
                    style = stroke
                )
            }
        }
    }
}

/** Sign in / Create account switch, styled like the Stopwatch / Countdown selector. */
@Composable
private fun ModeSwitch(creating: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(CircleShape)
            .border(1.dp, Hairline, CircleShape).padding(4.dp)
    ) {
        listOf(false to "Sign in", true to "Create account").forEach { (value, label) ->
            val selected = creating == value
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .background(if (selected) scheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable(enabled = enabled) {
                        if (!selected) haptics.select()
                        onChange(value)
                    }
                    .semantics { role = Role.Tab },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardType: KeyboardType,
    password: Boolean = false,
    showPassword: Boolean = false,
    onToggleShow: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        shape = FieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                Text(
                    if (showPassword) "Hide" else "Show",
                    color = scheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 6.dp).clip(CircleShape)
                        .clickable { haptics.tick(); onToggleShow() }.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.05f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContainerColor = Color.White.copy(alpha = 0.03f),
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = Hairline,
            disabledBorderColor = Color.White.copy(alpha = 0.06f),
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            cursorColor = scheme.primary,
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = { haptics.click(); onClick() },
        interactionSource = source,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = scheme.primary.copy(alpha = 0.30f),
            disabledContentColor = scheme.onPrimary.copy(alpha = 0.60f)
        ),
        modifier = Modifier.pressScale(source, 0.97f).fillMaxWidth().height(52.dp)
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit, destructive: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (destructive) scheme.error else scheme.onSurface
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = { if (destructive) haptics.heavy() else haptics.click(); onClick() },
        interactionSource = source,
        enabled = enabled,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (destructive) scheme.error.copy(alpha = 0.35f) else Hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint, disabledContentColor = tint.copy(alpha = 0.4f)),
        modifier = Modifier.pressScale(source, 0.97f).fillMaxWidth().height(48.dp)
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun LinkButton(text: String, enabled: Boolean, onClick: () -> Unit, muted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    TextButton(onClick = { haptics.tick(); onClick() }, enabled = enabled) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (muted) scheme.onSurfaceVariant else scheme.primary
        )
    }
}

@Composable
private fun Pill(text: String, positive: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (positive) scheme.primary else scheme.onSurfaceVariant
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.30f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun StatusBanner(message: String, isError: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (isError) scheme.error else scheme.primary
    val shape = RoundedCornerShape(16.dp)
    Text(
        message,
        color = tint,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.25f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}