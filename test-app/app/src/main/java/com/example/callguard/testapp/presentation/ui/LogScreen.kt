package com.example.callguard.testapp.presentation.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.callguard.testapp.presentation.viewmodel.TestAppViewModel
import org.json.JSONObject
import java.io.File

/**
 * 저장된 세션 목록·요약·내보내기.
 *
 * 분석에 필요한 값(개입 발동 시각, 반응시간, 유출 여부)을 여기서 바로 확인할 수 있게 해
 * 연구자가 세션 직후 이상 여부를 발견하도록 한다 — 세션이 다 끝난 뒤 PC에서야
 * "이 세션은 개입이 안 걸렸네"를 발견하면 참가자를 다시 부를 수 없다.
 */
@Composable
fun LogScreen(viewModel: TestAppViewModel) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf(viewModel.listSessions()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CallBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("세션 기록", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { viewModel.closeLogScreen() }) {
                Text("닫기", color = InfoBlue, fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "저장 위치: Android/data/com.example.callguard.testapp/files/sessions/",
            color = CallSubText, fontSize = 11.sp
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { sessions = viewModel.listSessions() },
                colors = ButtonDefaults.buttonColors(containerColor = CallControl),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("새로고침", color = Color.White, fontSize = 13.sp) }
            Button(
                onClick = {
                    val files = sessions.flatMap { it.listFiles()?.toList() ?: emptyList() }
                    if (files.isEmpty()) viewModel.notify("내보낼 파일이 없습니다.")
                    else shareFiles(context, files, "실험 세션 전체 (" + sessions.size + "건)")
                },
                colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("전체 내보내기", color = Color.White, fontSize = 13.sp) }
        }

        Spacer(Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            Text("저장된 세션이 없습니다.", color = CallSubText, fontSize = 14.sp)
        }

        sessions.forEach { dir ->
            SessionCard(dir, context, viewModel)
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SessionCard(dir: File, context: Context, viewModel: TestAppViewModel) {
    val summary = remember(dir) { readSummary(dir) }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CallSurface)
            .padding(14.dp)
    ) {
        Text(dir.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        if (summary == null) {
            Text("session.json을 읽지 못했습니다.", color = WarnAmber, fontSize = 12.sp)
        } else {
            SummaryLine("조건", summary.optString("interventionId", "-"))
            SummaryLine("순번", summary.opt("trialOrder")?.toString() ?: "-")
            SummaryLine("개입 발동", if (summary.has("interventionFiredAt") && !summary.isNull("interventionFiredAt")) "예 (" + summary.optString("interventionSource") + ")" else "아니오")
            SummaryLine("요구→개입 지연", msText(summary, "demandToInterventionMs"))
            SummaryLine("반응시간", msText(summary, "reactionTimeMs"))
            SummaryLine(
                "유출",
                if (summary.optBoolean("leaked")) "있음 (" + summary.optString("leakConfidence") + " / " + summary.optString("leakSource") + ")" else "없음",
                if (summary.optBoolean("leaked")) AccentRed else SafeGreen
            )
            SummaryLine("거부 표현", if (summary.optBoolean("refusalDetected")) "감지됨" else "없음")
            SummaryLine("종료", summary.optString("endedBy", "-"))
            SummaryLine("통화 길이", msText(summary, "sessionDurationMs"))

            val notes = summary.optJSONArray("observationNotes")
            if (notes != null && notes.length() > 0) {
                Spacer(Modifier.height(6.dp))
                Text("관찰: " + (0 until notes.length()).joinToString(", ") { notes.optString(it) },
                    color = CallSubText, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val files = dir.listFiles()?.toList().orEmpty()
                    if (files.isEmpty()) viewModel.notify("파일이 없습니다.")
                    else shareFiles(context, files, dir.name)
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("내보내기", color = Color.White, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) { Text(if (expanded) "파일 숨기기" else "파일 보기", color = Color.White, fontSize = 12.sp) }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                Text(
                    "• " + f.name + "  (" + (f.length() / 1024) + " KB)",
                    color = CallSubText, fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, valueColor: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = CallSubText, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = valueColor, fontSize = 12.sp)
    }
}

private fun msText(summary: JSONObject, key: String): String {
    if (!summary.has(key) || summary.isNull(key)) return "-"
    val ms = summary.optLong(key)
    return if (ms >= 1000) String.format("%.2f초", ms / 1000.0) else ms.toString() + "ms"
}

private fun readSummary(dir: File): JSONObject? = try {
    val f = File(dir, "session.json")
    if (!f.exists()) null else JSONObject(f.readText()).optJSONObject("summary")
} catch (e: Exception) {
    null
}

/**
 * FileProvider로 파일을 공유한다. 앱 전용 외부 저장소는 다른 앱이 직접 읽을 수 없어
 * content:// URI로 권한을 넘겨야 카톡·메일·드라이브로 보낼 수 있다.
 */
private fun shareFiles(context: Context, files: List<File>, title: String) {
    val authority = context.packageName + ".fileprovider"
    val uris = ArrayList<Uri>()
    files.forEach { f ->
        runCatching { FileProvider.getUriForFile(context, authority, f) }.getOrNull()?.let { uris.add(it) }
    }
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
