package com.gamemonitor.android.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gamemonitor.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val AUTHOR = "君的笑颜"
private const val GITHUB_URL = "https://github.com/mimokini/GameMonitorAndroid"

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 取自实际安装包，不写死版本号
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "未知"
    }
    var showReward by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "版本",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(versionName, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        "作者",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(AUTHOR, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        "GitHub",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(
                        GITHUB_URL,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = { openBrowser(context, GITHUB_URL) })
                        }
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                Text(
                    "如果这个应用对你有帮助，可以请作者喝杯咖啡。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { showReward = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打赏作者", fontSize = 13.sp)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭", fontSize = 13.sp)
                }
            }
        }
    }

    if (showReward) {
        RewardQrDialog(onDismiss = { showReward = false })
    }
}

@Composable
private fun RewardQrDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("打赏作者", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "长按二维码可保存到相册",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Image(
                    painter = painterResource(R.drawable.reward_qr),
                    contentDescription = "打赏二维码",
                    modifier = Modifier
                        .width(220.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    if (saving) return@detectTapGestures
                                    saving = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { saveToPictures(context) }
                                        saving = false
                                        Toast.makeText(
                                            context,
                                            if (ok) "已保存到相册：Pictures/GameMonitor"
                                            else "保存失败，请检查存储空间后重试",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        }
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
            }
        }
    }
}

private fun openBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 保存到系统相册。minSdk = 29（Android 10），走 MediaStore + RELATIVE_PATH 即可，无需存储权限。
 */
private fun saveToPictures(context: Context): Boolean {
    val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.reward_qr) ?: return false
    val resolver = context.contentResolver
    val name = "gamemonitor_reward_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GameMonitor")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        null
    } ?: return false

    return try {
        val written = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            false
        } else {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            true
        }
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}
