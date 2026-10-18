package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import wzmwayne.shield.core.Policy
import wzmwayne.shield.core.PolicyGroup
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldBorder
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

/** 保护策略：逐项自由开关 */
@Composable
fun PoliciesScreen(
    groups: List<Triple<PolicyGroup, List<Pair<Policy, Boolean>>, Int>>,
    busy: Boolean,
    onToggle: (Policy, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("保护策略", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "默认全开，可逐项关闭；关闭后对应逻辑立即失效。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        ShieldButton("返回设置", onBack, filled = false, modifier = Modifier.fillMaxWidth())

        groups.forEach { (group, items, enabledCount) ->
            ShieldCard(title = "${group.title}（$enabledCount/${items.size} 已开启）") {
                items.forEach { (policy, enabled) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(policy.title, style = MaterialTheme.typography.bodyMedium, color = ShieldWhite)
                        Text(
                            text = policy.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) ShieldTextSecondary else ShieldTextDim,
                        )
                    }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { value -> onToggle(policy, value) },
                            enabled = !busy,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ShieldBlack,
                                checkedTrackColor = ShieldWhite,
                                checkedBorderColor = ShieldWhite,
                                uncheckedThumbColor = ShieldTextDim,
                                uncheckedTrackColor = Color.Transparent,
                                uncheckedBorderColor = ShieldBorder,
                            ),
                        )
                    }
                }
            }
        }
    }
}
