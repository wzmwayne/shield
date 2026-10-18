package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import wzmwayne.shield.core.ShieldEvent
import wzmwayne.shield.ui.components.EventRow
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

@Composable
fun EventsScreen(
    events: List<ShieldEvent>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("事件日志", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "共 ${events.size} 条（最多保留 200 条，仅存本机）",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        ShieldButton("清空日志", onClear, filled = false, modifier = Modifier.fillMaxWidth())
        if (events.isEmpty()) {
            Text("暂无事件。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events) { event -> EventRow(event) }
            }
        }
    }
}
