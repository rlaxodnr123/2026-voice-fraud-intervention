package com.example.callguard.experiment.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.callguard.experiment.presentation.viewmodel.ExperimentViewModel

/**
 * 참가자에게 보이는 통화 화면 — 일반 전화앱 모양을 유지하고 내부 분석 정보는
 * 일절 노출하지 않는다 (기만 설계 유지). 시나리오가 발동하면 텍스트 배너(S7·S8)나
 * 텍스트 설문(S8)만 위에 표시된다.
 *
 * 화면 우상단 투명 영역을 5회 연속 탭하면 연구자 패널이 열린다 (§5.2 온디바이스 경로).
 */
@Composable
fun ParticipantCallScreen(viewModel: ExperimentViewModel) {
    val callDuration by viewModel.callDuration.collectAsState()
    val isLocalMuted by viewModel.isLocalMuted.collectAsState()
    val isScenarioMicBlocked by viewModel.isScenarioMicBlocked.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()
    val noticeBanner by viewModel.noticeBanner.collectAsState()
    val textSurveyActive by viewModel.textSurveyActive.collectAsState()
    val textSurveyIndex by viewModel.textSurveyQuestionIndex.collectAsState()

    // 참가자가 자신의 마이크가 실제로 꺼져 있음을 인지할 수 있게, 버튼은 본인 음소거와
    // 시나리오 차단 중 하나라도 걸리면 '꺼짐'으로 보여준다.
    val micEffectivelyOff = isLocalMuted || isScenarioMicBlocked

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))

            Text("통화 중", color = Color(0xFF8E8E93), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("알 수 없는 번호", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(callDuration, color = Color(0xFF8E8E93), fontSize = 16.sp)

            // 마이크 차단 인지 표시 — 시나리오가 내 마이크를 차단 중이면 눈에 띄게 알린다
            if (isScenarioMicBlocked) {
                Spacer(Modifier.height(14.dp))
                MicBlockedIndicator()
            }

            Spacer(Modifier.weight(1f))

            // 아바타 (위험도 링 없음 — 참가자에게 상태 힌트를 주지 않는다)
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3C)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // 통화 컨트롤
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 시나리오 차단 중이면 빨간색으로, 본인 음소거면 흰색으로 '꺼짐'을 구분해 보여준다
                    val micBg = when {
                        isScenarioMicBlocked -> AccentRed
                        isLocalMuted -> Color.White
                        else -> Color(0xFF3A3A3C)
                    }
                    val micTint = when {
                        isScenarioMicBlocked -> Color.White
                        isLocalMuted -> Color.Black
                        else -> Color.White
                    }
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(micBg)
                            .clickable { viewModel.toggleLocalMute() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (micEffectivelyOff) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null,
                            tint = micTint,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isScenarioMicBlocked) "차단됨" else "음소거",
                        color = if (isScenarioMicBlocked) AccentRed else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isScenarioMicBlocked) FontWeight.Bold else FontWeight.Normal
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AccentRed)
                            .clickable { viewModel.endCall() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CallEnd,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("종료", color = Color.White, fontSize = 12.sp)
                }

                // 스피커폰 토글 (통화 중 AI 음성·상대 대사가 크게 들리도록)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerOn) Color.White else Color(0xFF3A3A3C))
                            .clickable { viewModel.toggleSpeakerphone() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = null,
                            tint = if (isSpeakerOn) Color.Black else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("스피커", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // ── 텍스트 안내 배너 (S7·S8, §4.5) ─────────────────────
        noticeBanner?.let { banner ->
            NoticeBanner(
                message = banner.message,
                requireAck = banner.requireAck,
                onAck = { viewModel.dismissNoticeBanner() }
            )
        }

        // ── 텍스트 설문 오버레이 (S8, §4.4) ─────────────────────
        if (textSurveyActive && textSurveyIndex >= 0) {
            TextSurveyOverlay(
                questionIndex = textSurveyIndex,
                viewModel = viewModel
            )
        }

        // ── 연구자 패널 숨김 진입 영역 (우상단, 투명) ─────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(72.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null   // 리플 효과 없음 — 참가자에게 노출 안 됨
                ) { viewModel.onSecretCornerTap() }
        )
    }
}

/**
 * 마이크 차단 인지 표시 — 시나리오가 참가자 마이크를 차단 중일 때 통화 상단에 상시 노출된다.
 * 안내 배너(NoticeBanner)와 달리 확인 버튼 없이 현재 '차단 상태' 자체를 지속적으로 알린다.
 */
@Composable
fun MicBlockedIndicator() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0A0A)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, AccentRed, RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.MicOff, contentDescription = null, tint = AccentRed, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("마이크 차단됨", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "내 목소리가 상대방에게 전달되지 않습니다",
                    color = TextLight, fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * 일반화된 텍스트 안내 배너 (§4.5) — 원본 SuspiciousWarningBanner의 일반화 버전.
 * requireAck=true면 참가자가 확인 버튼을 눌러야 사라진다 (확인 여부가 로그에 남는다).
 */
@Composable
fun BoxScope.NoticeBanner(message: String, requireAck: Boolean, onAck: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2200)),
            modifier = Modifier.fillMaxWidth().border(1.5.dp, WarningAmber, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("안내", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Text(message, color = TextLight, fontSize = 14.sp, lineHeight = 20.sp)
                if (requireAck) {
                    Button(
                        onClick = onAck,
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("확인", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 텍스트 설문 (S8) — 질문 1개씩 예/아니오 버튼으로 진행 */
@Composable
fun BoxScope.TextSurveyOverlay(questionIndex: Int, viewModel: ExperimentViewModel) {
    val questions = listOf(
        "전화를 건 사람이 본인이 직접 아는 분인가요?",
        "가족이나 지인과의 실제 상황인가요?"
    )
    val question = questions.getOrNull(questionIndex) ?: return

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0005)),
            modifier = Modifier.fillMaxWidth().border(2.dp, AccentRed, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "상황 확인 설문 (${questionIndex + 1}/${questions.size})",
                    color = WarningAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    question,
                    color = TextLight, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, lineHeight = 24.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.answerTextSurvey(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("예", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Button(
                        onClick = { viewModel.answerTextSurvey(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("아니오", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
