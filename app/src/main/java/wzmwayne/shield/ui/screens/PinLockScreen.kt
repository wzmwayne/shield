package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import wzmwayne.shield.core.AppLock
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

/**
 * PIN 锁屏层：渲染在 MainActivity 内部（不是独立 Activity），因此无法通过 `am start` 绕过。
 * 覆盖整个界面（含底部导航），只有解锁后才渲染真实内容。
 */
@Composable
fun PinLockScreen(modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("锁盾已锁定", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "输入应用内 PIN 码继续",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { value ->
                input = value.filter { it.isDigit() }.take(8)
                error = null
            },
            label = { Text("PIN（4-8 位数字）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ShieldWhite,
                unfocusedTextColor = ShieldWhite,
                focusedBorderColor = ShieldWhite,
                unfocusedBorderColor = ShieldTextDim,
                focusedLabelColor = ShieldWhite,
                unfocusedLabelColor = ShieldTextSecondary,
                cursorColor = ShieldWhite,
            ),
        )
        if (busy) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp),
                    color = ShieldWhite,
                    strokeWidth = 2.dp,
                )
                Text("验证中…", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShieldWhite) }
        ShieldButton(
            text = "解锁",
            onClick = {
                val cooldown = AppLock.cooldownRemainingMs
                if (cooldown > 0) {
                    error = "错误次数过多，请 ${cooldown / 1000 + 1} 秒后再试"
                    return@ShieldButton
                }
                // PBKDF2 计算放后台线程，界面显示加载态，避免"假死"
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { AppLock.unlock(input) }
                    busy = false
                    if (!ok) {
                        error = "PIN 不正确"
                        input = ""
                    }
                }
            },
            enabled = !busy && input.length >= 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "此界面不可被外部启动，也无法通过切换页面跳过。",
            style = MaterialTheme.typography.labelSmall,
            color = ShieldTextDim,
        )
    }
}
