package com.example.callguard.testapp.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.callguard.testapp.domain.session.ExperimentSessionService
import com.example.callguard.testapp.presentation.viewmodel.TestAppViewModel

/**
 * 참가자 통화 화면.
 *
 * 일반 전화앱처럼 보여야 한다 — 시나리오·조건·대본 위치 같은 내부 정보는 절대 노출하지 않는다.
 * 참가자가 "실험 중이라 앱이 곧 개입할 것"을 화면에서 읽어내면 측정이 무너진다.
 *
 * 지인 조건에서는 연락처에 저장된 것처럼 **이름**이, 모르는 사람 조건에서는 **번호**가 뜬다.
 * 이름이 뜨는 것 자체가 경계를 낮추는 실험 자극이다.
 */
@Composable
fun CallScreen(viewModel: TestAppViewModel, service: ExperimentSessionService) {
    val duration by service.callDuration.collectAsState()
    val micBlocked by service.micBlocked.collectAsState()
    val muted by service.participantMuted.collectAsState()
    val speakerOn by service.speakerOn.collectAsState()
    val callerName by service.callerName.collectAsState()
    val callerNumber by service.callerNumber.collectAsState()
    val popupMessage by service.decisionPopup.collectAsState()
    val remoteBlocked by service.remoteAudioBlocked.collectAsState()

    val micOff = micBlocked || muted

    Box(modifier = Modifier.fillMaxSize().background(CallBackground)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))
            Text("통화 중", color = CallSubText, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            if (callerName.isNotBlank()) {
                Text(callerName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(callerNumber, color = CallSubText, fontSize = 14.sp)
            } else {
                Text(callerNumber, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(6.dp))
            Text(duration, color = CallSubText, fontSize = 16.sp)

            if (micBlocked || remoteBlocked) {
                Spacer(Modifier.height(16.dp))
                BlockedBanner(remoteBlocked = remoteBlocked, micBlocked = micBlocked)
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier.size(140.dp).clip(CircleShape).background(CallControl),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = CallSubText,
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallControlButton(
                    icon = if (micOff) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (micBlocked) "차단됨" else "음소거",
                    // 차단(앱이 건 것)과 음소거(참가자가 건 것)를 색으로 구분한다.
                    // 참가자가 자기가 누른 것인지 앱이 건 것인지 헷갈리면 개입 인지 측정이 흐려진다.
                    background = when {
                        micBlocked -> AccentRed
                        muted -> Color.White
                        else -> CallControl
                    },
                    tint = when {
                        micBlocked -> Color.White
                        muted -> Color.Black
                        else -> Color.White
                    },
                    labelColor = if (micBlocked) AccentRed else Color.White,
                    size = 64.dp,
                    onClick = { service.toggleParticipantMute() }
                )

                CallControlButton(
                    icon = Icons.Filled.CallEnd,
                    label = "종료",
                    background = AccentRed,
                    tint = Color.White,
                    labelColor = Color.White,
                    size = 72.dp,
                    onClick = { service.endCallByParticipant() }
                )

                CallControlButton(
                    icon = if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    label = "스피커",
                    background = if (speakerOn) Color.White else CallControl,
                    tint = if (speakerOn) Color.Black else Color.White,
                    labelColor = Color.White,
                    size = 64.dp,
                    onClick = { service.toggleSpeaker() }
                )
            }
        }

        // ── 개입 1의 선택 팝업 ─────────────────────────────────
        popupMessage?.let { message ->
            DecisionPopup(
                message = message,
                onContinue = { service.resumeAfterIntervention() },
                onEnd = { service.endCallFromPopup() }
            )
        }

        ResearcherPanelButton(
            onClick = { viewModel.toggleResearcherPanel() },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

/**
 * 연구자 패널 열기·닫기 버튼.
 *
 * 화면 우상단에 작게 둔다. 참가자 눈에 띄긴 하지만 통화 UI의 일부처럼 보이도록
 * 낮은 대비로 두었다 — 실험 중 연구자가 확실히 누를 수 있는 것이 우선이다.
 */
@Composable
fun ResearcherPanelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(12.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = "연구자 패널",
            tint = Color(0xB3FFFFFF),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    tint: Color,
    labelColor: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(background).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.44f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = labelColor, fontSize = 12.sp)
    }
}

/**
 * 차단 상태 표시 — 두 개입 조건이 공통으로 보여 주는 시각 신호.
 *
 * 스피커 차단(상대 말이 안 들림)과 마이크 차단(내 말이 안 나감)은 참가자가 겪는 일이
 * 서로 달라서 한 문장으로 뭉뚱그리면 무엇이 막힌 건지 알 수 없다.
 */
@Composable
fun BlockedBanner(remoteBlocked: Boolean, micBlocked: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A0D0D))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("통화가 잠시 중단되었습니다", color = AccentRed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (remoteBlocked) {
                Text("상대방 음성이 차단되었습니다", color = Color(0xFFE5A9A9), fontSize = 12.sp)
            }
            if (micBlocked) {
                Text("내 목소리도 전달되지 않습니다", color = Color(0xFFE5A9A9), fontSize = 12.sp)
            }
        }
    }
}

/**
 * 개입 1의 선택 팝업.
 *
 * [통화 계속하기]를 고르면 마이크 차단이 풀려 실제로 대화를 이어갈 수 있다.
 * 차단이 풀리지 않으면 "계속하기"가 허울이 되어 조건 1이 사실상 조건 2와 같아진다.
 *
 * 애매한 시나리오(S2·S4)에서는 계속하기가 **올바른 선택**이다. 그래서 두 버튼의
 * 시각적 무게를 비슷하게 두고 한쪽으로 유도하지 않는다 — 어느 쪽이 정답인지는
 * 시나리오에 따라 달라지고, 그 판단이 바로 측정 대상이다.
 */
@Composable
fun DecisionPopup(message: String, onContinue: () -> Unit, onEnd: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF2A2A2E))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = WarnAmber,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onEnd,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("통화 종료", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onContinue,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("통화 계속하기", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

/**
 * 통화 종료 화면.
 *
 * 개입 2(강제 종료)에서는 종료 사유와 안전 안내가 남는다 — 왜 끊겼는지 모르면
 * "이유 없이 끊긴 통화"가 되어 조건의 의미가 사라진다.
 * 나머지 경우에는 참가자가 직접 끊은 것이므로 일반 통화 종료 화면만 보인다.
 */
@Composable
fun CallEndedScreen(viewModel: TestAppViewModel, service: ExperimentSessionService) {
    val message by service.terminationMessage.collectAsState()
    val duration by service.callDuration.collectAsState()
    val saving by viewModel.savingSession.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(CallBackground)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (message != null) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    message.orEmpty(),
                    color = Color.White,
                    fontSize = 18.sp,
                    lineHeight = 28.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text("통화 종료", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("통화 시간 " + duration, color = CallSubText, fontSize = 15.sp)
            }

            Spacer(Modifier.height(40.dp))

            // 통화가 끝난 뒤이므로 연구자가 여기서 바로 세션을 닫아도 기만 설계에 영향이 없다.
            Button(
                onClick = { viewModel.finishSession("researcher_finished") },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = CallControl),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    if (saving) "저장 중…" else "세션 저장하고 종료",
                    color = Color.White, fontSize = 16.sp
                )
            }
        }

        ResearcherPanelButton(
            onClick = { viewModel.toggleResearcherPanel() },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
