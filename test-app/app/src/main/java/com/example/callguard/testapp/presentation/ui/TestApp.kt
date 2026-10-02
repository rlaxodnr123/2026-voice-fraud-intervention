package com.example.callguard.testapp.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.callguard.testapp.domain.session.ExperimentSessionService
import com.example.callguard.testapp.presentation.viewmodel.TestAppViewModel

/**
 * 앱 루트.
 *
 *  - IDLE     : 연구자 설정 화면
 *  - IN_CALL  : 참가자 통화 화면 (일반 전화앱처럼 보여야 한다)
 *  - ENDED    : 통화 종료 화면
 *
 * **개입 화면은 통화 화면·종료 화면보다 위에서 그린다.** 개입 2는 안내가 끝나면 통화가
 * 끊겨 아래 화면이 IN_CALL → ENDED로 바뀌는데, 개입 화면이 그 위를 덮고 있으므로
 * 참가자 눈에는 아무 변화가 없다 — 개입은 처음부터 끝까지 한 화면이다.
 *
 * 오버레이(개입 화면·연구자 패널)가 덮고 있는 동안에는 **아래 화면을 그리지 않는다.**
 * 반투명으로 가리기만 하면 아래 통화 화면의 [종료]·[음소거]가 그대로 눌려, 참가자가
 * 개입 화면의 버튼 게이트를 우회할 수 있고 로그로도 구분이 안 된다. 오버레이에서
 * 포인터 이벤트를 삼켜 막는 방법은 쓰지 않는다 — 오버레이 자신의 버튼까지 죽는다.
 *
 * 연구자 패널 버튼은 그보다도 위에 둔다. 개입 2에서 개입 화면이 계속 남아 있으므로,
 * 버튼이 그 아래 깔리면 연구자가 세션을 닫을 길이 없어진다.
 *
 * composable 람다에서 이른 `return`을 쓰지 않는다 — Compose는 그런 비지역 반환에서
 * 그룹 종료 호출을 맞춰 내보내지 못해 슬롯 테이블이 깨지고 첫 컴포지션에서 죽는다.
 */
@Composable
fun TestApp(viewModel: TestAppViewModel) {
    val service by viewModel.service.collectAsState()
    val panelVisible by viewModel.researcherPanelVisible.collectAsState()
    val logVisible by viewModel.logScreenVisible.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CallBackground)) {
        val svc = service
        if (svc == null) {
            ConnectingPlaceholder()
        } else {
            val callState by svc.callState.collectAsState()
            val intervention by svc.interventionScreen.collectAsState()

            // 오버레이가 전면을 덮는 동안 아래 화면은 아예 구성하지 않는다
            val overlayCovers = !logVisible && (intervention != null || panelVisible)

            when {
                logVisible -> LogScreen(viewModel)
                overlayCovers -> {}
                callState == ExperimentSessionService.CallState.IDLE -> SetupScreen(viewModel, svc)
                callState == ExperimentSessionService.CallState.IN_CALL -> CallScreen(viewModel, svc)
                else -> CallEndedScreen(viewModel, svc)
            }

            // 통화/종료 화면 위 — 상태가 바뀌어도 참가자에게는 같은 화면으로 보인다.
            // [통화 종료]는 두 조건 모두 통화 종료 화면으로 넘어가고, 거기서 세션을
            // 저장하면 처음 화면으로 돌아간다.
            if (!logVisible) {
                intervention?.let { state ->
                    InterventionScreen(
                        state = state,
                        onContinue = { svc.resumeAfterIntervention() },
                        onEnd = { svc.endCallFromInterventionScreen() }
                    )
                }
            }

            if (panelVisible) {
                ResearcherPanel(viewModel, svc)
            } else if (callState != ExperimentSessionService.CallState.IDLE && !logVisible) {
                // 개입 화면보다 위 — 개입 2에서도 연구자가 패널에 들어갈 수 있어야 한다
                ResearcherPanelButton(
                    onClick = { viewModel.toggleResearcherPanel() },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    }
}

@Composable
private fun ConnectingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("세션 서비스를 연결하는 중…", color = CallSubText)
    }
}
