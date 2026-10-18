package wzmwayne.shield.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import wzmwayne.shield.core.CheckItem
import wzmwayne.shield.core.CheckState
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.ShieldEvent
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldBorder
import wzmwayne.shield.ui.theme.ShieldSurface
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShieldCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ShieldSurface, RoundedCornerShape(2.dp))
            .border(1.dp, ShieldBorder, RoundedCornerShape(2.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ShieldWhite,
            )
        }
        content()
    }
}

/** 反白 chip 表示「需要处理」；描边 chip 表示「正常」 */
@Composable
fun StateChip(text: String, filled: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(if (filled) ShieldWhite else Color.Transparent, RoundedCornerShape(2.dp))
            .border(1.dp, if (filled) ShieldWhite else ShieldBorder, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) ShieldBlack else ShieldTextSecondary,
        )
    }
}

@Composable
fun ShieldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val background = when {
        !filled -> Color.Transparent
        enabled -> ShieldWhite
        else -> ShieldBorder
    }
    val contentColor = when {
        !filled -> ShieldWhite
        enabled -> ShieldBlack
        else -> ShieldTextDim
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(2.dp))
            .border(1.dp, if (enabled) ShieldWhite else ShieldBorder, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = ShieldWhite) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
            modifier = Modifier.width(104.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun CheckRow(item: CheckItem, onAction: ((String) -> Unit)? = null) {
    val pending = item.state == CheckState.BLOCK || item.state == CheckState.WARN
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ShieldBorder, RoundedCornerShape(2.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = ShieldWhite,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StateChip(text = item.state.label, filled = pending)
            val action = item.action
            if (action != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                ShieldButton(
                    text = item.actionLabel,
                    onClick = { onAction(action) },
                    filled = false,
                )
            }
        }
        Text(
            text = item.detail,
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
    }
}

@Composable
fun EventRow(event: ShieldEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ShieldBorder, RoundedCornerShape(2.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TIME_FORMAT.format(Date(event.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = ShieldTextDim,
            )
            Spacer(Modifier.width(8.dp))
            StateChip(text = event.level.label, filled = event.level != RiskLevel.L1)
            Spacer(Modifier.width(8.dp))
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = ShieldWhite,
                modifier = Modifier.weight(1f),
            )
        }
        if (event.detail.isNotEmpty()) {
            Text(
                text = event.detail,
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
        }
    }
}

/**
 * 流式执行过程：显示最近若干行（最新在最下），✘/⚠ 行反白强调。
 */
@Composable
fun TraceBlock(lines: List<String>, maxLines: Int = 16, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val shown = lines.takeLast(maxLines)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ShieldBorder, RoundedCornerShape(2.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        shown.forEach { line ->
            val attention = line.contains("✘") || line.contains("⚠")
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (attention) ShieldWhite else ShieldTextSecondary,
            )
        }
        if (lines.size > shown.size) {
            Text(
                text = "（仅显示最近 ${shown.size} 行，共 ${lines.size} 行）",
                style = MaterialTheme.typography.labelSmall,
                color = ShieldTextDim,
            )
        }
    }
}

@Composable
fun MonospaceBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = ShieldTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ShieldBorder, RoundedCornerShape(2.dp))
            .padding(8.dp),
    )
}

@Composable
fun SectionSpacer(height: Int = 12) {
    Spacer(Modifier.height(height.dp))
}

val CheckState.label: String
    get() = when (this) {
        CheckState.PASS -> "通过"
        CheckState.WARN -> "待处理"
        CheckState.BLOCK -> "阻断"
        CheckState.UNKNOWN -> "未知"
    }

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
