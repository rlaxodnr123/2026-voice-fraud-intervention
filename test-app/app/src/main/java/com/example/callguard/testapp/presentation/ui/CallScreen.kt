package com.example.callguard.testapp.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 *
 * 개입이 발동하면 [InterventionScreen]이 이 화면 위를 덮는다 (TestApp 루트에서 그린다).
 * 차단 상태 배너를 여기 두지 않는 이유: 개입 화면이 이미 그 사실을 문장으로 말하고 있고,
 * 덮인 아래쪽에 또 표시해 봐야 보이지 않는다.
 */
@Composable
fun CallScreen(viewModel: TestAppViewModel, service: ExperimentSessionService) {
    val duration by service.callDuration.collectAsState()
    val micBlocked by service.micBlocked.collectAsState()
    val muted by service.participantMuted.collectAsState()
    val speakerOn by service.speakerOn.collectAsState()
    val callerName by service.callerName.collectAsState()
    val callerNumber by service.callerNumber.collectAsState()

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
                    label = "음소거",
                    background = if (muted) Color.White else CallControl,
                    tint = if (muted) Color.Black else Color.White,
                    size = 64.dp,
                    onClick = { service.toggleParticipantMute() }
                )

                CallControlButton(
                    icon = Icons.Filled.CallEnd,
                    label = "종료",
                    background = AccentRed,
                    tint = Color.White,
                    size = 72.dp,
                    onClick = { service.endCallByParticipant() }
                )

                CallControlButton(
                    icon = if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    label = "스피커",
                    background = if (speakerOn) Color.White else CallControl,
                    tint = if (speakerOn) Color.Black else Color.White,
                    size = 64.dp,
                    onClick = { service.toggleSpeaker() }
                )
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    tint: Color,
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
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/**
 * 개입 화면 — 개입이 발동한 순간 뜨고, 안내가 끝난 뒤에도 **같은 화면이 그대로 유지**된다.
 *
 * 화면에 적힌 문장과 TTS가 읽는 문장이 같다. 귀로 들은 것과 눈으로 본 것이 다르면
 * 참가자가 무엇을 근거로 판단했는지 알 수 없어진다.
 *
 * 선택 버튼은 안내가 끝나야 눌린다(`choiceEnabled`). 안내를 다 듣기 전에 고를 수 있으면
 * "안내를 듣고 판단한다"는 조건 자체가 성립하지 않는다. 버튼을 숨겼다 나타내지 않고
 * 흐리게 두었다가 켜는 이유도 같다 — 요소가 새로 생기면 그것이 또 하나의 화면 전환이 된다.
 */
@Composable
fun InterventionScreen(
    state: ExperimentSessionService.InterventionScreen,
    onContinue: () -> Unit,
    onEnd: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xF2000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(28.dp))
            Text(
                state.message,
                color = Color.White,
                fontSize = 20.sp,
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )

            if (state.offerChoice) {
                Spacer(Modifier.height(36.dp))
                Button(
                    onClick = onEnd,
                    enabled = state.choiceEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed,
                        disabledContainerColor = AccentRed.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        "통화 종료",
                        color = Color.White.copy(alpha = if (state.choiceEnabled) 1f else 0.5f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onContinue,
                    enabled = state.choiceEnabled,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        "통화 이어가기",
                        color = Color.White.copy(alpha = if (state.choiceEnabled) 1f else 0.5f),
                        fontSize = 17.sp
                    )
                }
            }
        }
    }
}

/**
 * 통화 종료 화면.
 *
 * 개입 2에서는 이 화면이 [InterventionScreen]에 덮여 참가자에게 보이지 않는다 —
 * 통화가 끊겼다고 화면이 바뀌면 "한 화면으로 끝낸다"는 설계가 깨지기 때문이다.
 * 참가자가 직접 끊은 경우에만 이 화면이 보인다.
 */
@Composable
fun CallEndedScreen(viewModel: TestAppViewModel, service: ExperimentSessionService) {
    val duration by service.callDuration.collectAsState()
    val saving by viewModel.savingSession.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(CallBackground)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("통화 종료", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("통화 시간 " + duration, color = CallSubText, fontSize = 15.sp)

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
    }
}

/**
 * 연구자 패널 열기·닫기 버튼.
 *
 * TestApp 루트에서 **개입 화면보다 위에** 그린다. 개입 2는 개입 화면이 통화 종료 뒤에도
 * 남아 있으므로, 이 버튼이 그 아래 깔리면 연구자가 세션을 닫을 길이 없어진다.
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
