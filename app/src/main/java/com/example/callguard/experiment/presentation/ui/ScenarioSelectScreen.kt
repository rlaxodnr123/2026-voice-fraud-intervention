package com.example.callguard.experiment.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.callguard.experiment.presentation.viewmodel.ExperimentViewModel

/**
 * 연구자용 설정 화면 — 통화 시작 전 서버/방 입력과 시나리오 무장(arm)을 처리한다.
 * 참가자에게 기기를 건네기 전에 연구자가 이 화면에서 세션을 준비한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioSelectScreen(viewModel: ExperimentViewModel) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf("ws://192.168.") }
    var roomId by remember { mutableStateOf("") }

    val isSttReady by viewModel.isSttReady.collectAsState()
    val armedScenario by viewModel.armedScenario.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 40.dp, bottom = 24.dp)
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "CallGuard 실험",
                    style = TextStyle(
                        brush = Brush.horizontalGradient(listOf(PrimaryCyan, PrimaryPurple)),
                        fontSize = 34.sp, fontWeight = FontWeight.ExtraBold
                    )
                )
                Text(
                    "개입 시나리오 테스트 도구 (연구자 전용 설정 화면)",
                    fontSize = 12.sp, color = TextDark, textAlign = TextAlign.Center
                )
            }
        }

        // ── 연결 설정 카드 ───────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("세션 연결", color = PrimaryCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("서버 주소", color = TextDark) },
                        placeholder = { Text("예: ws://192.168.0.10", color = TextDark) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = ThemeCardBg,
                            containerColor = ThemeBackground
                        ),
                        textStyle = LocalTextStyle.current.copy(color = TextLight),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = roomId,
                        onValueChange = { roomId = it },
                        label = { Text("방 코드 (공격자 클라이언트와 동일)", color = TextDark) },
                        placeholder = { Text("예: room-1234", color = TextDark) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = ThemeCardBg,
                            containerColor = ThemeBackground
                        ),
                        textStyle = LocalTextStyle.current.copy(color = TextLight),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            if (serverUrl.isNotBlank() && roomId.isNotBlank()) {
                                viewModel.joinRoom(context, serverUrl, roomId)
                            }
                        },
                        enabled = isSttReady,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(Brush.horizontalGradient(listOf(PrimaryCyan, PrimaryPurple))),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Call, null, tint = Color.Black)
                                Spacer(Modifier.width(8.dp))
                                Text("방 입장 / 세션 시작", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (!isSttReady) {
                        Text(
                            "STT 음성인식 모델 로딩 중... 완료 후 사용 가능합니다 (첫 실행 시 1~2분)",
                            color = WarningAmber,
                            fontSize = 11.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val monitorUrl = if (serverUrl.isNotBlank() && serverUrl != "ws://192.168.") {
                                val base = if (serverUrl.startsWith("ws://")) serverUrl else "ws://$serverUrl"
                                if (base.contains(":8080")) base.replace(":8080", ":8081")
                                else if (!base.contains(Regex(":\\d+$"))) "$base:8081"
                                else base
                            } else null
                            viewModel.startLoopbackCall(context, monitorUrl)
                        },
                        enabled = isSttReady,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, TextDark),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("루프백 벤치 테스트 (단일 기기)", fontSize = 13.sp)
                    }
                }
            }
        }

        // ── 시나리오 사전 무장 카드 ──────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = ThemeCardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("시나리오 무장 (통화 전 미리 선택 가능)", color = PrimaryPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "통화 시작 후 연구자 패널(화면 우상단 5회 탭)이나 원격 콘솔에서도 변경할 수 있습니다.",
                        color = TextDark, fontSize = 11.sp
                    )
                    viewModel.scenarioCatalog.forEach { scenario ->
                        val isArmed = armedScenario?.id == scenario.id
                        OutlinedButton(
                            onClick = { viewModel.armScenario(scenario.id) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isArmed) SafeGreen else TextLight
                            ),
                            border = BorderStroke(
                                if (isArmed) 2.dp else 1.dp,
                                if (isArmed) SafeGreen else TextDark.copy(alpha = 0.4f)
                            )
                        ) {
                            Text(
                                (if (isArmed) "✓ " else "") + scenario.label,
                                fontSize = 12.sp, maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectingScreen(viewModel: ExperimentViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = PrimaryCyan, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text("연결 중...", color = TextLight, fontSize = 16.sp)
        Text("상대(공격자 클라이언트)가 방에 입장하면 자동 통화됩니다.", color = TextDark, fontSize = 12.sp)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(
            onClick = { viewModel.endCall() },
            border = BorderStroke(1.dp, AccentRed),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
        ) {
            Text("취소")
        }
    }
}
