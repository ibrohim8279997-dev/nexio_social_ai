package com.nexiolab.socialai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.nexiolab.socialai.ui.theme.Brand
import com.nexiolab.socialai.ui.theme.Nexio

val CardShape = RoundedCornerShape(16.dp)

@Composable
fun NexioCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(CardShape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CardShape,
        color = Nexio.colors.card,
        border = BorderStroke(1.dp, Nexio.colors.outline),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Standard vertical page: consistent gutter and spacing across every screen. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = Brand.Accent,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(6.dp),
            )
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Brand.Accent,
    onClick: (() -> Unit)? = null,
) {
    NexioCard(modifier, onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Nexio.colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(value, style = MaterialTheme.typography.titleLarge, color = Nexio.colors.text, maxLines = 1)
        }
    }
}

@Composable
fun NexioProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Brand.Primary, secondColor: Color = Brand.Accent) {
    Box(
        modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Nexio.colors.cardHigh),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight10()
                .background(Brush.horizontalGradient(listOf(color, secondColor))),
        )
    }
}

private fun Modifier.fillMaxHeight10() = this.height(10.dp)

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Nexio.colors.textMuted, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Nexio.colors.text, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Nexio.colors.textMuted, textAlign = TextAlign.Center)
        action?.let {
            Spacer(Modifier.height(4.dp))
            it()
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Brand.Accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Nexio.colors.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nexio.colors.textMuted)
        }
        trailing?.invoke(this)
    }
}
