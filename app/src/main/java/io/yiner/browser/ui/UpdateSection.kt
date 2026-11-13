package io.yiner.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.yiner.browser.BuildConfig
import io.yiner.browser.MainActivity
import io.yiner.browser.core.DownloadNaming
import io.yiner.browser.core.update.UpdateCandidate
import io.yiner.browser.update.UpdateInstaller
import io.yiner.browser.update.UpdateStatus
import kotlinx.coroutines.launch

/** 设置页中的“更新”分区。 */
@Composable
fun UpdateSection(activity: MainActivity) {
    val manager = activity.app.updates
    val status by manager.status.collectAsState()
    val settings by manager.settings.collectAsState()
    val scope = rememberCoroutineScope()

    Text(
        "当前版本 ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）· 来源：github.com/${BuildConfig.UPDATE_REPO} 正式版 Release",
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(describe(status), style = MaterialTheme.typography.bodyMedium)

        candidateOf(status)?.let { c -> CandidateCard(activity, c) }

        val s = status
        if (s is UpdateStatus.Downloading) {
            LinearProgressIndicator(
                progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                is UpdateStatus.Available -> {
                    Button(onClick = { manager.startDownload(allowMetered = true) }) { Text("下载") }
                }
                is UpdateStatus.Failed -> if (s.candidate != null) {
                    Button(onClick = { manager.startDownload(allowMetered = true) }) { Text("重新下载") }
                }
                is UpdateStatus.Queued -> {
                    if (s.waitingForUnmetered) {
                        Button(onClick = { manager.startDownload(allowMetered = true) }) { Text("用移动数据下载") }
                    }
                    OutlinedButton(onClick = { manager.cancelDownload() }) { Text("取消") }
                }
                is UpdateStatus.Downloading -> OutlinedButton(onClick = { manager.cancelDownload() }) { Text("取消下载") }
                is UpdateStatus.Ready, is UpdateStatus.Installing -> {
                    Button(onClick = { UpdateInstaller.install(activity) }) { Text("安装") }
                    OutlinedButton(onClick = { manager.discard(null) }) { Text("删除安装包") }
                }
                else -> Unit
            }
            val checking = s is UpdateStatus.Checking || s is UpdateStatus.Downloading ||
                s is UpdateStatus.Verifying
            OutlinedButton(
                enabled = !checking,
                onClick = { scope.launch { activity.toast(manager.check(manual = true)) } },
            ) { Text("检查更新") }
        }
    }

    SwitchItem("启动时检查更新", settings.checkOnStartup, subtitle = "距上次检查超过 12 小时才会真正请求 GitHub") { v ->
        manager.updateSettings { it.copy(checkOnStartup = v) }
    }
    SwitchItem("定期检查更新", settings.periodicCheck, subtitle = "约每天一次，在后台联网时进行") { v ->
        manager.updateSettings { it.copy(periodicCheck = v) }
    }
    SwitchItem("自动下载新版本", settings.autoDownload, subtitle = "下载完成后通知你；安装仍需你在系统对话框中确认") { v ->
        manager.updateSettings { it.copy(autoDownload = v) }
    }
    if (settings.autoDownload) {
        SwitchItem("仅在 Wi-Fi 下自动下载", settings.autoDownloadWifiOnly, subtitle = "安装包约 200 MB；手动点“下载”不受此限制") { v ->
            manager.updateSettings { it.copy(autoDownloadWifiOnly = v) }
        }
    }
}

@Composable
private fun CandidateCard(activity: MainActivity, c: UpdateCandidate) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "新版本 ${c.versionName}（versionCode ${c.versionCode}）· ${DownloadNaming.formatSize(c.size)}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (c.releaseName.isNotBlank() && c.releaseName != c.tag) {
                Text(c.releaseName, style = MaterialTheme.typography.bodySmall)
            }
            if (c.notes.isNotBlank()) {
                Text(
                    c.notes,
                    Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            c.releasePage?.let { page ->
                OutlinedButton(onClick = { activity.openInNewTab(page) }, modifier = Modifier.padding(top = 6.dp)) {
                    Text("在 GitHub 查看发布说明")
                }
            }
        }
    }
}

private fun candidateOf(s: UpdateStatus): UpdateCandidate? = when (s) {
    is UpdateStatus.Available -> s.candidate
    is UpdateStatus.Queued -> s.candidate
    is UpdateStatus.Downloading -> s.candidate
    is UpdateStatus.Verifying -> s.candidate
    is UpdateStatus.Ready -> s.candidate
    is UpdateStatus.Installing -> s.candidate
    is UpdateStatus.Failed -> s.candidate
    else -> null
}

private fun describe(s: UpdateStatus): String = when (s) {
    UpdateStatus.Idle -> "尚未检查更新"
    UpdateStatus.Checking -> "正在检查更新…"
    is UpdateStatus.UpToDate -> "已是最新版本（GitHub 最新正式版：${s.remoteVersionName}）"
    UpdateStatus.NoRelease -> "仓库还没有发布正式版"
    is UpdateStatus.Available -> "发现新版本 ${s.candidate.versionName}"
    is UpdateStatus.Queued -> s.note ?: if (s.waitingForUnmetered) "等待连接 Wi-Fi 后自动下载" else "等待开始下载…"
    is UpdateStatus.Downloading -> "正在下载 ${DownloadNaming.formatSize(s.done)} / ${DownloadNaming.formatSize(s.total)}"
    is UpdateStatus.Verifying -> "正在校验安装包（哈希、包名、版本号、签名）…"
    is UpdateStatus.Ready -> "${s.candidate.versionName} 已下载并通过校验，可以安装"
    is UpdateStatus.Installing -> "已打开系统安装确认；如果没有看到，请再点一次“安装”"
    is UpdateStatus.Failed -> s.message
}
