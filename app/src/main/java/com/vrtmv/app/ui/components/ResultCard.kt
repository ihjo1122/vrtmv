package com.vrtmv.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrtmv.app.domain.model.DetectedObject
import com.vrtmv.app.domain.model.InferenceState
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.ui.theme.SurfaceElevated
import com.vrtmv.app.ui.theme.SurfaceOverlay
import com.vrtmv.app.ui.theme.TextPrimary

private sealed class CardType {
    data object HINT : CardType()
    data class ERROR(val message: String) : CardType()
    data object NONE : CardType()
}

@Composable
fun ResultCard(
    inferenceState: InferenceState,
    selectedObject: DetectedObject?,
    modifier: Modifier = Modifier
) {
    val cardType = when {
        inferenceState is InferenceState.Idle && selectedObject == null -> CardType.HINT
        inferenceState is InferenceState.Error -> CardType.ERROR(inferenceState.message ?: "추론 실패")
        else -> CardType.NONE
    }

    AnimatedContent(
        targetState = cardType,
        transitionSpec = {
            (slideInVertically(initialOffsetY = { it }) + fadeIn())
                .togetherWith(slideOutVertically(targetOffsetY = { it }) + fadeOut())
        },
        modifier = modifier,
        label = "resultCard"
    ) { type ->
        when (type) {
            is CardType.HINT -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SurfaceElevated.copy(alpha = 0.85f)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = ArCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CenterFocusWeak,
                            contentDescription = null,
                            tint = ArCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "객체를 터치하여 탐지하세요",
                            color = TextPrimary.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            is CardType.ERROR -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SurfaceOverlay.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = StatusError.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .background(StatusError, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = type.message,
                            color = StatusError,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            is CardType.NONE -> { }
        }
    }
}
