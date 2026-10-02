package com.example.callguard.testapp.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.callguard.testapp.domain.script.AttackerScriptCatalog
import com.example.callguard.testapp.domain.script.AttackerVoice
import com.example.callguard.testapp.domain.script.AttackerVoiceAssets
import com.example.callguard.testapp.domain.script.CallerRelationship
import com.example.callguard.testapp.domain.script.PlaybackMode
import com.example.callguard.testapp.domain.script.ScamLevel
import com.example.callguard.testapp.domain.script.VoiceAssetStatus
import com.example.callguard.testapp.domain.session.ExperimentSessionService
import com.example.callguard.testapp.presentation.viewmodel.TestAppViewModel

/**
 * 연구자 설정 화면 — 세션 시작 전에만 보인다. 참가자에게 노출될 일이 없으므로
 * 조건 이름과 내부 정보를 그대로 보여 준다.
 */
@Composable
fun SetupScreen(viewModel: TestAppViewModel, service: ExperimentSessionService) {
    val participantId by viewModel.participantId.collectAsState()
    val trialOrder by viewModel.trialOrder.collectAsState()
    val interventionId by viewModel.interventionId.collectAsState()
    val scriptId by viewModel.scriptId.collectAsState()
    val mode by viewModel.playbackMode.collectAsState()
    val voice by viewModel.attackerVoice.collectAsState()
    val record by viewModel.recordAudio.collectAsState()
    val callerName by viewModel.callerNameOverride.collectAsState()
    val sttReady by service.sttReady.collectAsState()
    val sttStatus by service.sttStatus.collectAsState()

    val selectedScript = AttackerScriptCatalog.findById(scriptId)
    val isAcquaintance = selectedScript?.relationship == CallerRelationship.ACQUAINTANCE

    // 어느 목소리에 녹음본이 갖춰졌는지를 **고르는 순간** 보여 준다. 녹음본이 없는 목소리를
    // 고르면 앱이 조용히 시스템 TTS로 대체하는데, 세션이 끝난 뒤 로그를 봐야 알게 되고
    // 그때는 이미 그 참가자의 세션이 날아간 상태다.
    // asset은 APK에 박혀 있어 실행 중에 바뀌지 않으므로 시나리오당 한 번만 조회한다.
    val context = LocalContext.current
    val voiceStatuses = remember(scriptId) {
        selectedScript?.let { AttackerVoiceAssets.statuses(context, it) } ?: emptyList()
    }
    val selectedVoiceStatus = voiceStatuses.firstOrNull { it.voice == voice }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("CallGuard 실험 세션", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("통화 1회 = 세션 1건. 조건을 고르고 시작하세요.", color = CallSubText, fontSize = 13.sp)

        Spacer(Modifier.height(20.dp))

        SectionCard("① 참가자") {
            OutlinedTextField(
                value = participantId,
                onValueChange = { viewModel.participantId.value = it },
                label = { Text("참가자 ID") },
                placeholder = { Text("예: P07") },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text("이 참가자의 몇 번째 통화인가", color = CallSubText, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { n ->
                    ChoiceChip(
                        text = n.toString() + "번째",
                        selected = trialOrder == n,
                        onClick = { viewModel.trialOrder.value = n },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "시나리오 2종 × 개입 2종 = 4가지 조합입니다. 순서는 참가자마다 다르게 배정하고, " +
                    "첫 노출과 반복 노출의 차이가 핵심 대비이므로 순번을 반드시 맞춰 주세요.",
                color = CallSubText, fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard("② 개입 조건") {
            viewModel.interventionOptions.forEach { option ->
                OptionRow(
                    title = option.label,
                    subtitle = option.researcherNote,
                    selected = interventionId == option.id,
                    onClick = { viewModel.interventionId.value = option.id }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard("③ 시나리오 (발신자 관계)") {
            viewModel.scriptOptions.forEach { script ->
                ScenarioRow(
                    scamLevel = script.scamLevel,
                    relationship = script.relationship,
                    title = script.label,
                    subtitle = script.situation,
                    requested = script.requestedInfo,
                    selected = scriptId == script.id,
                    onClick = { viewModel.scriptId.value = script.id }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "두 시나리오 모두 진짜 사기입니다. 달라지는 것은 발신자가 " +
                    "저장된 지인으로 보이는지 여부 하나뿐입니다.",
                color = CallSubText, fontSize = 11.sp
            )
        }

        if (isAcquaintance) {
            Spacer(Modifier.height(14.dp))
            SectionCard("③-1 발신자 표시 이름 (지인 조건)") {
                OutlinedTextField(
                    value = callerName,
                    onValueChange = { viewModel.callerNameOverride.value = it },
                    label = { Text("통화 화면에 뜰 이름") },
                    placeholder = { Text(selectedScript?.defaultCallerName.orEmpty()) },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "비워 두면 기본값(" + selectedScript?.defaultCallerName.orEmpty() + ")을 씁니다. " +
                        "참가자 연령·가족 구성과 맞지 않는 이름이 뜨면 지인 조건 자체가 무력해지므로 " +
                        "참가자에 맞춰 바꿔 주세요.",
                    color = CallSubText, fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard("④ 상대방 목소리 (연령대 × 성별)") {
            AttackerVoice.ageBands.forEachIndexed { rowIndex, band ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    viewModel.voiceOptions.filter { it.ageBand == band }.forEach { option ->
                        VoiceChoice(
                            voice = option,
                            status = voiceStatuses.firstOrNull { it.voice == option },
                            selected = voice == option,
                            onClick = { viewModel.attackerVoice.value = option },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "같은 대사라도 누가 말하는가에 따라 참가자가 느끼는 신뢰도와 압박감이 달라집니다. " +
                    "특히 지인 시나리오는 \"내가 아는 그 사람일 수 있다\"고 받아들여져야 성립하므로, " +
                    "참가자 연령·성별과 어울리는 목소리를 골라 주세요.",
                color = CallSubText, fontSize = 11.sp
            )

            // 목소리가 실제로 들리는 조건을 여기서 못 알려 주면, 연구자는 고른 대로
            // 나간다고 믿은 채 세션을 쌓게 된다.
            if (mode != PlaybackMode.RECORDING) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ 지금 재생 방식(" + mode.label + ")에서는 이 선택이 소리에 반영되지 않습니다. " +
                        "시스템 TTS가 읽으므로 목소리는 기기 설정대로 나갑니다.\n" +
                        "⑤에서 '녹음본 재생'을 골라야 고른 목소리가 들립니다 " +
                        "(로그에 attackerVoiceApplied=false 로 남습니다).",
                    color = WarnAmber, fontSize = 12.sp
                )
            } else if (selectedVoiceStatus != null && !selectedVoiceStatus.isComplete) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ " + voice.label + " 녹음본이 부족합니다 (" +
                        selectedVoiceStatus.mainFound + "/" + selectedVoiceStatus.mainTotal +
                        "). 없는 대사는 시스템 TTS로 나가 통화 중에 목소리가 바뀝니다.\n" +
                        "assets/" + voice.assetDir(scriptId) + "/ 에 녹음본을 넣고 앱을 다시 빌드하세요 " +
                        "(tools/generate_attacker_voices.py).",
                    color = AccentRed, fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard("⑤ 상대방 음성 재생 방식") {
            viewModel.playbackOptions.forEach { option ->
                OptionRow(
                    title = option.label,
                    subtitle = option.description,
                    selected = mode == option,
                    onClick = { viewModel.playbackMode.value = option }
                )
            }
            if (mode == PlaybackMode.LIVE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "라이브 모드에서는 연구자가 패널(우상단 ⚙ 버튼)을 열어 대본을 보며 읽고, " +
                        "줄마다 [다음 대사]를 눌러야 개입 지점에서 개입이 발동합니다.\n" +
                        "⚠ 사람이 버튼을 누르는 지연이 반응시간에 섞이므로, 자동 재생 세션과 " +
                        "반응시간을 직접 비교하지 마세요 (로그의 triggerLatencyControlled=false).",
                    color = WarnAmber, fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard("⑥ 기록") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = record, onCheckedChange = { viewModel.recordAudio.value = it })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("통화 녹음 저장", color = Color.White, fontSize = 15.sp)
                    Text(
                        "음성 인식이 틀렸을 때 되돌아가 확인할 근거. 기기 밖으로 나가지 않습니다.",
                        color = CallSubText, fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sttReady) SafeGreen else WarnAmber)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "음성 인식: " + sttStatus,
                    color = if (sttReady) Color.White else WarnAmber,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { viewModel.startSession() },
            colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("통화 시작", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = { viewModel.openLogScreen() },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("저장된 세션 기록 보기 · 내보내기", color = Color.White)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CallSurface)
            .padding(16.dp)
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun OptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) InfoBlue.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) InfoBlue else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = CallSubText, fontSize = 11.sp)
            }
        }
    }
}

/** 시나리오 항목 — 2×2 셀을 색 배지로 한눈에 구분한다 */
@Composable
private fun ScenarioRow(
    scamLevel: ScamLevel,
    relationship: CallerRelationship,
    title: String,
    subtitle: String,
    requested: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) InfoBlue.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) InfoBlue else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(
                    text = scamLevel.label,
                    color = if (scamLevel == ScamLevel.REAL_SCAM) AccentRed else WarnAmber
                )
                Badge(
                    text = relationship.label,
                    color = if (relationship == CallerRelationship.ACQUAINTANCE) SafeGreen else InfoBlue
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = CallSubText, fontSize = 11.sp)
            Text("요구: " + requested, color = CallSubText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/**
 * 목소리 선택 칸 — 이름과 **녹음본이 갖춰졌는지**를 같이 보여 준다.
 *
 * 녹음본 상태를 함께 띄우는 이유: 선택은 되지만 소리가 안 나오는 목소리가 있을 수 있고,
 * 그 사실은 세션이 끝난 뒤 로그를 봐야 드러난다. 고르는 자리에서 보이지 않으면
 * 연구자가 알 길이 없다.
 */
@Composable
private fun VoiceChoice(
    voice: AttackerVoice,
    status: VoiceAssetStatus?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        status == null -> CallSubText
        status.isComplete -> SafeGreen
        status.isEmpty -> CallSubText
        else -> WarnAmber
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) InfoBlue.copy(alpha = 0.22f) else CallControl)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) InfoBlue else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            voice.label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.height(3.dp))
        Text(status?.summary ?: "-", color = statusColor, fontSize = 10.sp)
    }
}

@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) InfoBlue else CallControl)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = InfoBlue,
    unfocusedBorderColor = CallControl,
    focusedLabelColor = InfoBlue,
    unfocusedLabelColor = CallSubText,
    cursorColor = InfoBlue
)
