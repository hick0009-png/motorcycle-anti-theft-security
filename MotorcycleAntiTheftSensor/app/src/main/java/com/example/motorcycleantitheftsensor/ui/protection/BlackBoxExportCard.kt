package com.example.motorcycleantitheftsensor.ui.protection

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.motorcycleantitheftsensor.protection.BlackBoxExportResult
import com.example.motorcycleantitheftsensor.protection.BlackBoxExporter
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntimeGraph
import com.example.motorcycleantitheftsensor.theme.OutlineStrong
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The way the record gets out of the phone.
 *
 * It records continuously and it is kept where no other app can read it, which on any phone
 * that is not rooted also means a cable cannot reach it. That is the right place for it to
 * sit and a useless place for it to stay on the day something happens, so there has to be a
 * deliberate act that hands it over — this button, and the owner choosing where it goes.
 *
 * The app never sends it anywhere itself. It writes one compressed copy and opens the share
 * sheet; what happens next is the owner's decision, made in a dialog they can cancel.
 */
@Composable
fun BlackBoxExportCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, OutlineStrong),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "บันทึกกล่องดำ",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "เครื่องจดไว้นาทีละบรรทัดว่าเฝ้าอยู่หรือไม่ และเซ็นเซอร์เห็นอะไร " +
                    "เก็บไว้ในเครื่องอย่างเดียว 60 วัน แอปอื่นอ่านไม่ได้",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "ช่วงที่ไม่มีบรรทัดเลย คือช่วงที่แอปถูกระบบปิดไป ซึ่งเป็นช่วงที่ไม่มีการปกป้องจริง",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    if (working) return@OutlinedButton
                    working = true
                    scope.launch {
                        // Off the main thread: this copies the record and compresses it, and
                        // it waits for the recorder's own lock while doing so.
                        val result = withContext(Dispatchers.IO) { exportBlackBox(context) }
                        working = false
                        status = describe(result)
                        if (result is BlackBoxExportResult.Ready) {
                            shareExport(context, result.file)
                        }
                    }
                },
                enabled = !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("blackbox-export-button"),
                border = BorderStroke(1.dp, OutlineStrong),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (working) "กำลังเตรียมไฟล์…" else "ส่งออกบันทึกกล่องดำ",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("blackbox-export-status"),
                )
            }
        }
    }
}

private fun exportBlackBox(context: Context): BlackBoxExportResult {
    val writer = ProtectionRuntimeGraph.from(context).blackBoxWriter
        ?: return BlackBoxExportResult.Failed("no recorder")
    return BlackBoxExporter(
        writer = writer,
        directory = File(context.cacheDir, BlackBoxExporter.DIRECTORY),
        wallClockMs = System::currentTimeMillis,
    ).export()
}

private fun describe(result: BlackBoxExportResult): String = when (result) {
    is BlackBoxExportResult.Ready ->
        "เตรียมไฟล์แล้ว ${result.dayCount} วัน ขนาด ${result.bytes / 1024L} KB — เลือกปลายทางได้เลย"
    BlackBoxExportResult.Empty ->
        "ยังไม่มีบันทึกให้ส่งออก บันทึกจะเริ่มเมื่อบริการเฝ้าระวังทำงาน"
    is BlackBoxExportResult.Failed ->
        "ส่งออกไม่สำเร็จ (${result.reason})"
}

/**
 * Hands one file to whichever app the owner picks, and grants read on that one file only.
 * The day files themselves are never shared — the copy is.
 */
private fun shareExport(context: Context, file: File) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.blackbox", file)
    }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gzip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "ส่งออกบันทึกกล่องดำ")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
