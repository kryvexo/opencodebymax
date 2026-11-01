package com.example.gemini.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import com.example.gemini.theme.isAppInDarkTheme

/** Soft, blurred color fields under the app content; blur falls back to a static gradient below Android 12. */
@Composable
fun LiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF8B5CF6)
) {
    val blurRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 66.dp else 22.dp
    Box(modifier = modifier.clip(RectangleShape)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-105).dp, y = (-100).dp)
                .size(330.dp)
                .blur(blurRadius)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0.03f), Color.Transparent)), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 95.dp, y = 100.dp)
                .size(300.dp)
                .blur(blurRadius)
                .background(Brush.radialGradient(listOf(Color(0xFF42C7D8).copy(alpha = 0.19f), Color(0xFF42C7D8).copy(alpha = 0.02f), Color.Transparent)), CircleShape)
        )
    }
}

/** A translucent, softly elevated glass material with a directional specular edge. */
@Composable
fun Modifier.liquidGlassSurface(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    tint: Color,
    alpha: Float = 0.78f,
    elevation: Dp = 14.dp
): Modifier {
    val dark = isAppInDarkTheme()
    val topSheen = Color.White.copy(alpha = if (dark) 0.22f else 0.55f)
    val base = tint.copy(alpha = alpha)
    val fill = Brush.linearGradient(
        colors = listOf(base, base.copy(alpha = (alpha * 0.82f).coerceIn(0f, 1f)), base.copy(alpha = (alpha * 0.93f).coerceIn(0f, 1f)))
    )
    val edge = Brush.linearGradient(
        colors = listOf(topSheen, Color.White.copy(alpha = if (dark) 0.06f else 0.24f), tint.copy(alpha = 0.18f))
    )
    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .background(fill)
        .border(BorderStroke(1.dp, edge), shape)
}
