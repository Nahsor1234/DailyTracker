package com.nahsor1234.dailytracker

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role

/**
 * One place for every haptic in the app, so the feel is consistent and a single file decides
 * which platform constant is used on which Android version.
 *
 *  - tick():    lightest tick, for continuous or very frequent changes
 *  - select():  a segment/selection moved (tabs, chips, month change, day pick)
 *  - click():   a plain button press
 *  - toggle():  a switch/checkbox flipped (distinct on/off feel on Android 14+)
 *  - confirm(): something succeeded (saved, signed in, timer started)
 *  - reject():  something was refused (validation error, failed request)
 *  - heavy():   a weighty or destructive action (reset, delete)
 */
internal class Haptics(private val view: View) {
    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }

    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun select() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK
    )

    fun click() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT >= 34 ->
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
    )

    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.LONG_PRESS
    )

    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
    )

    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)
}

@Composable
internal fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

@Composable
internal fun rememberPressSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * Springy press-down effect. Put this FIRST in the modifier chain (before size/clip/background)
 * so the whole element shrinks, and share the same [source] with [hapticClickable].
 * The scale is only read while drawing, so a press never recomposes anything.
 */
internal fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.93f): Modifier =
    composed {
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
            label = "press-scale"
        )
        this.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }

/** A clickable that fires a haptic before the action. Pair with [pressScale] via the same source. */
internal fun Modifier.hapticClickable(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    haptic: Haptics.() -> Unit = { click() },
    onClick: () -> Unit
): Modifier = composed {
    val haptics = rememberHaptics()
    this.clickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role
    ) {
        haptics.haptic()
        onClick()
    }
}