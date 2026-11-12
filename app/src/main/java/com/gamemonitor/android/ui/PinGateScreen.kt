package com.gamemonitor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamemonitor.android.data.PinManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 管理密码输入页（无“忘记密码 / 跳过”后门）。
 * 输错累计 3 次由 [PinManager] 写入 30 秒锁定，锁定期间输入框禁用并实时倒计时。
 */
@Composable
fun PinGateScreen(
    pinManager: PinManager,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var lockRemaining by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            lockRemaining = pinManager.lockoutRemaining()
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = pin,
            onValueChange = { input -> pin = input.filter { it.isDigit() }.take(8) },
            label = { Text("管理密码（4~8 位数字）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            enabled = lockRemaining <= 0L,
            modifier = Modifier.fillMaxWidth()
        )
        if (lockRemaining > 0L) {
            Text(
                "错误次数过多，请 ${(lockRemaining + 999L) / 1000L} 秒后再试",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        } else {
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val candidate = pin
                    scope.launch {
                        when (val result = pinManager.verify(candidate)) {
                            is PinManager.Result.Success -> {
                                message = null
                                pin = ""
                                onSuccess()
                            }

                            is PinManager.Result.NotConfigured -> {
                                message = "尚未设置管理密码，无法通过此入口操作"
                            }

                            is PinManager.Result.Wrong -> {
                                message = "密码错误，还可尝试 ${result.remainingAttempts} 次"
                            }

                            is PinManager.Result.Locked -> {
                                message = null
                                lockRemaining = result.remainingMillis
                            }
                        }
                    }
                },
                enabled = pin.length >= 4 && lockRemaining <= 0L
            ) {
                Text("确认")
            }
        }
    }
}
