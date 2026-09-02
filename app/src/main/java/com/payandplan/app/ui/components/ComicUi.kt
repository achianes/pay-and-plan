package com.payandplan.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.payandplan.app.ui.theme.ComicFont
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont

/** Flat fill + fat ink outline + hard offset shadow: the whole look in one modifier. */
fun Modifier.comicSurface(
    background: Color,
    radius: Dp = 20.dp,
    stroke: Dp = 3.dp,
    shadow: Dp = 5.dp,
    shadowColor: Color = Ink
): Modifier = this
    .drawBehind {
        if (shadow.value > 0f) {
            val r = radius.toPx()
            drawRoundRect(
                color = shadowColor,
                topLeft = Offset(shadow.toPx(), shadow.toPx()),
                size = size,
                cornerRadius = CornerRadius(r, r)
            )
        }
    }
    .background(background, RoundedCornerShape(radius))
    .border(stroke, Ink, RoundedCornerShape(radius))

@Composable
fun ComicCard(
    modifier: Modifier = Modifier,
    color: Color = Paper,
    radius: Dp = 20.dp,
    shadow: Dp = 5.dp,
    stroke: Dp = 3.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val drop by animateDpAsState(
        targetValue = if (pressed && onClick != null) 1.dp else shadow,
        animationSpec = spring(), label = "drop"
    )
    val shift = shadow - drop
    Box(
        modifier = modifier
            .offset(x = shift, y = shift)
            .comicSurface(color, radius, stroke, drop)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(contentPadding)
    ) {
        Column(content = content)
    }
}

@Composable
fun ComicButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = if (compact) 4.dp else 5.dp
    val drop by animateDpAsState(if (pressed) 1.dp else base, spring(), label = "btn")
    val shift = base - drop
    val bg = if (enabled) color else Color(0xFFDCDCDC)
    Row(
        modifier = modifier
            .offset(x = shift, y = shift)
            .comicSurface(bg, radius = if (compact) 14.dp else 18.dp, stroke = 3.dp, shadow = drop)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(
                horizontal = if (compact) 12.dp else 18.dp,
                vertical = if (compact) 8.dp else 12.dp
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(if (compact) 16.dp else 20.dp))
            Box(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ComicIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Paper,
    size: Dp = 44.dp,
    contentDescription: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val drop by animateDpAsState(if (pressed) 1.dp else 4.dp, spring(), label = "iconbtn")
    val shift = 4.dp - drop
    Box(
        modifier = modifier
            .offset(x = shift, y = shift)
            .size(size)
            .drawBehind {
                drawRoundRect(
                    color = Ink,
                    topLeft = Offset(drop.toPx(), drop.toPx()),
                    size = this.size,
                    cornerRadius = CornerRadius(size.toPx() / 2f, size.toPx() / 2f)
                )
            }
            .background(color, CircleShape)
            .border(3.dp, Ink, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Ink, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun ComicChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary
) {
    Box(
        modifier = modifier
            .comicSurface(
                background = if (selected) color else Paper,
                radius = 14.dp,
                stroke = if (selected) 3.dp else 2.dp,
                shadow = if (selected) 4.dp else 2.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Ink,
            maxLines = 1
        )
    }
}

/** Comic style label used for the big screen titles. */
@Composable
fun PosterTitle(text: String, modifier: Modifier = Modifier, color: Color = Ink) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = PosterFont),
        color = color
    )
}

@Composable
fun SpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Paper,
    emoji: String = "💬"
) {
    Column(
        modifier = modifier
            .comicSurface(color, radius = 22.dp)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, style = MaterialTheme.typography.displayMedium)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = Ink
        )
    }
}

@Composable
fun ComicField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    minLines: Int = 1,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            modifier = modifier,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            leadingIcon = leading,
            trailingIcon = trailing,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = ComicFont),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ink,
                unfocusedBorderColor = Ink,
                focusedContainerColor = Paper,
                unfocusedContainerColor = Paper,
                focusedLabelColor = Ink,
                unfocusedLabelColor = Ink,
                cursorColor = Ink
            )
        )
    }
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier, color: Color = Ink) {
    Box(
        modifier
            .padding(vertical = 6.dp)
            .border(BorderStroke(1.dp, color), RoundedCornerShape(1.dp))
    )
}
