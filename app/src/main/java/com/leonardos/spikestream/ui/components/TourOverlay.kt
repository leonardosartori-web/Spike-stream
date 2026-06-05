package com.leonardos.spikestream.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leonardos.spikestream.R
import com.leonardos.spikestream.utils.TourManager


// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One step in the guided tour.
 *
 * @param id           Unique key – must match the key passed to [Modifier.tourHighlight].
 * @param emoji        Large icon shown at the top of the card.
 * @param title        Bold headline of the step.
 * @param body         Longer explanation shown below the title.
 * @param highlightKey Optional: the key of the UI element that should pulse during this step.
 *                     Pass null if no element needs to be highlighted.
 */
data class TourStep(
    val id: String,
    val emoji: String,
    val title: String,
    val body: String,
    val highlightKey: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Controller
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Holds all mutable state for a tour.
 * Create one with [rememberTourController].
 */
class TourController internal constructor(
    val steps: List<TourStep>,
    initiallyVisible: Boolean,
    private val onCompleted: () -> Unit
) {
    var currentStepIndex by mutableIntStateOf(0)
        private set

    var isVisible by mutableStateOf(initiallyVisible)
        private set

    val currentStep: TourStep? get() = steps.getOrNull(currentStepIndex)
    val isLastStep: Boolean get() = currentStepIndex >= steps.lastIndex
    val totalSteps: Int get() = steps.size

    /** The [TourStep.highlightKey] of the current step, or null. */
    val activeHighlightKey: String? get() = currentStep?.highlightKey

    fun next() {
        if (isLastStep) complete() else currentStepIndex++
    }

    fun skip() = complete()

    private fun complete() {
        isVisible = false
        onCompleted()
    }
}

/**
 * Creates and remembers a [TourController].
 * The tour is shown only once per [tourKey]; subsequent launches are skipped automatically.
 */
@Composable
fun rememberTourController(
    tourKey: String,
    steps: List<TourStep>,
    onCompleted: () -> Unit = {}
): TourController {
    val context = LocalContext.current
    val alreadyCompleted = remember { TourManager.isCompleted(context, tourKey) }
    return remember {
        TourController(
            steps = steps,
            initiallyVisible = !alreadyCompleted,
            onCompleted = {
                TourManager.markCompleted(context, tourKey)
                onCompleted()
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Highlight modifier
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Adds a pulsing primary-coloured border to a composable when the tour is at the step
 * whose [TourStep.highlightKey] matches [key].
 *
 * Usage:
 * ```kotlin
 * Button(modifier = Modifier.tourHighlight(controller, "my_button")) { … }
 * ```
 */
@Composable
fun Modifier.tourHighlight(
    controller: TourController,
    key: String,
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier {
    val isActive = controller.isVisible && controller.activeHighlightKey == key
    val infiniteTransition = rememberInfiniteTransition(label = "tourHighlight_$key")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tourAlpha_$key"
    )
    val primaryColor = MaterialTheme.colorScheme.primary
    return if (isActive) this.border(2.5.dp, primaryColor.copy(alpha = alpha), shape)
    else this
}

// ─────────────────────────────────────────────────────────────────────────────
// Overlay composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen guided-tour overlay.
 *
 * Drop this at the END of a [BoxScope] so it renders on top of all other content:
 * ```kotlin
 * SpikeStreamScreen {
 *     // … your normal screen content …
 *     TourOverlay(controller = tourController)
 * }
 * ```
 */
@Composable
fun TourOverlay(
    controller: TourController,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = controller.isVisible,
        enter  = fadeIn(tween(350)),
        exit   = fadeOut(tween(350)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {

            // ── Dim scrim ──────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
            )

            // ── Sliding card ───────────────────────────────────────────────
            AnimatedContent(
                targetState = controller.currentStepIndex,
                transitionSpec = {
                    val enter = if (targetState > initialState)
                        slideInHorizontally { it } + fadeIn()
                    else
                        slideInHorizontally { -it } + fadeIn()
                    val exit = if (targetState > initialState)
                        slideOutHorizontally { -it } + fadeOut()
                    else
                        slideOutHorizontally { it } + fadeOut()
                    enter togetherWith exit
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                label = "tourCardTransition"
            ) { idx ->
                val step = controller.steps.getOrNull(idx) ?: return@AnimatedContent
                TourCard(
                    step       = step,
                    stepIndex  = idx,
                    totalSteps = controller.totalSteps,
                    isLast     = controller.isLastStep,
                    onNext     = { controller.next() },
                    onSkip     = { controller.skip() }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TourCard(
    step: TourStep,
    stepIndex: Int,
    totalSteps: Int,
    isLast: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation  = 8.dp,
        shadowElevation = 24.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Drag handle ────────────────────────────────────────────────
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )

            Spacer(Modifier.height(16.dp))

            // ── Big emoji icon ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Text(step.emoji, fontSize = 32.sp)
            }

            Spacer(Modifier.height(14.dp))

            // ── Title ──────────────────────────────────────────────────────
            Text(
                text  = step.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            // ── Body ───────────────────────────────────────────────────────
            Text(
                text  = step.body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(24.dp))

            // ── Step dots ──────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(totalSteps) { i ->
                    val isCurrent = i == stepIndex
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (isCurrent) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            )
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // ── Action row ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        stringResource(R.string.tour_skip),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = onNext,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isLast) stringResource(R.string.tour_start) else stringResource(R.string.tour_next),
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
