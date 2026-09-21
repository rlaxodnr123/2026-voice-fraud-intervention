package com.example.callguard.testapp.presentation.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.callguard.testapp.domain.intervention.InterventionCatalog
import com.example.callguard.testapp.domain.intervention.InterventionId
import com.example.callguard.testapp.domain.script.AttackerScriptCatalog
import com.example.callguard.testapp.domain.script.CallerRelationship
import com.example.callguard.testapp.domain.script.PlaybackMode
import com.example.callguard.testapp.domain.session.ExperimentSessionService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 화면과 [ExperimentSessionService]를 잇는다.
 *
 * 세션 상태를 ViewModel이 들고 있지 않고 서비스에 두는 이유: 화면 회전이나
 * 참가자가 홈으로 나갔다 돌아오는 상황에서도 통화·녹음·대본 재생이 끊기면 안 되기 때문이다.
 */
class TestAppViewModel(app: Application) : AndroidViewModel(app) {

    private val _service = MutableStateFlow<ExperimentSessionService?>(null)
    val service: StateFlow<ExperimentSessionService?> = _service.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessage: SharedFlow<String> = _userMessage

    // ── 연구자 설정값 ─────────────────────────────────────────────
    val participantId = MutableStateFlow("")
    val trialOrder = MutableStateFlow(1)
    val interventionId = MutableStateFlow(InterventionId.POPUP_TTS)
    val scriptId = MutableStateFlow(AttackerScriptCatalog.scripts.first().id)
    val playbackMode = MutableStateFlow(PlaybackMode.AUTO_TTS)
    val recordAudio = MutableStateFlow(true)

    /**
     * 지인 조건에서 통화 화면에 띄울 이름. 비우면 시나리오 기본값을 쓴다.
     * 참가자가 20대인데 "아들"이 뜨면 관계 조건 자체가 무력해지므로 연구자가 바꿀 수 있어야 한다.
     */
    val callerNameOverride = MutableStateFlow("")

    // ── 화면 전환 ─────────────────────────────────────────────────
    private val _researcherPanelVisible = MutableStateFlow(false)
    val researcherPanelVisible: StateFlow<Boolean> = _researcherPanelVisible.asStateFlow()

    private val _logScreenVisible = MutableStateFlow(false)
    val logScreenVisible: StateFlow<Boolean> = _logScreenVisible.asStateFlow()

    /** 세션을 닫고 파일을 확정하는 중 — 이 동안 종료 버튼을 잠근다 */
    private val _savingSession = MutableStateFlow(false)
    val savingSession: StateFlow<Boolean> = _savingSession.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ExperimentSessionService.LocalBinder)?.getService()
            _service.value = svc
            svc?.let { s ->
                viewModelScope.launch {
                    s.message.collect { _userMessage.emit(it) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
        }
    }

    /**
     * bind만 한다. 마이크 타입 포그라운드 서비스로의 승격은 마이크 권한을 확보한
     * 세션 시작 시점에 서비스가 스스로 처리한다 — 여기서 미리 승격하면 첫 실행에서
     * 권한 없이 승격을 시도하게 된다.
     */
    fun bindService(context: Context) {
        val intent = Intent(context, ExperimentSessionService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        runCatching { context.unbindService(connection) }
        _service.value = null
    }

    // ── 세션 조작 ─────────────────────────────────────────────────

    fun startSession() {
        val svc = _service.value ?: run {
            notify("서비스가 아직 연결되지 않았습니다. 잠시 후 다시 시도하세요.")
            return
        }
        if (participantId.value.isBlank()) {
            notify("참가자 ID를 입력하세요.")
            return
        }
        svc.startSession(
            participantId = participantId.value.trim(),
            trialOrder = trialOrder.value,
            interventionId = interventionId.value,
            scriptId = scriptId.value,
            mode = playbackMode.value,
            recordAudio = recordAudio.value,
            callerNameOverride = callerNameOverride.value.trim()
        )
    }

    /**
     * 세션을 닫고 로그·녹음을 확정한다.
     *
     * 파일 확정은 녹음 스레드 정리와 기록 스레드 대기를 포함해 수 초가 걸릴 수 있다.
     * 메인 스레드에서 하면 그동안 화면이 얼어붙고, 길어지면 ANR로 앱이 죽는다.
     * 그래서 IO로 넘기고 그동안 버튼을 잠근다.
     */
    fun finishSession(reason: String) {
        val svc = _service.value ?: run {
            notify("저장할 세션이 없습니다.")
            return
        }
        if (_savingSession.value) return
        _savingSession.value = true
        viewModelScope.launch {
            val dir = withContext(Dispatchers.IO) { svc.finishSession(reason) }
            _savingSession.value = false
            notify(if (dir != null) "세션 저장 완료: " + dir.name else "저장할 세션이 없습니다.")
            _researcherPanelVisible.value = false
            // 다음 통화는 같은 참가자의 다음 순번이므로 자동으로 올려 둔다 (연구자 실수 방지).
            // 한 참가자는 시나리오 4종을 한 번씩 겪으므로 4에서 멈춘다.
            if (dir != null) trialOrder.value = (trialOrder.value + 1).coerceAtMost(4)
        }
    }

    /** 통화 화면 우상단 버튼 — 연구자 패널을 열고 닫는다 */
    fun toggleResearcherPanel() {
        _researcherPanelVisible.value = !_researcherPanelVisible.value
    }

    fun openResearcherPanel() { _researcherPanelVisible.value = true }
    fun closeResearcherPanel() { _researcherPanelVisible.value = false }
    fun openLogScreen() { _logScreenVisible.value = true }
    fun closeLogScreen() { _logScreenVisible.value = false }

    /**
     * 연구자 패널에서 개입 조건을 고른다.
     * 통화 중이면 서비스에 재무장을 요청하고, 거절당하면(이미 발동) 선택도 되돌린다.
     */
    fun selectIntervention(id: InterventionId) {
        val svc = _service.value
        val inCall = svc?.callState?.value == ExperimentSessionService.CallState.IN_CALL
        if (inCall) {
            if (svc != null && !svc.rearmIntervention(id)) return
        }
        interventionId.value = id
    }

    /** 연구자 패널에서 시나리오를 고른다 (통화 중에는 바꿀 수 없다) */
    fun selectScript(id: String) {
        val inCall = _service.value?.callState?.value == ExperimentSessionService.CallState.IN_CALL
        if (inCall) {
            notify("통화 중에는 시나리오를 바꿀 수 없습니다. 세션을 저장하고 종료한 뒤 고르세요.")
            return
        }
        scriptId.value = id
    }

    val interventionOptions = InterventionCatalog.interventions
    val scriptOptions = AttackerScriptCatalog.scripts
    val playbackOptions = PlaybackMode.values().toList()

    /** 선택된 시나리오가 지인 조건인가 — 발신자 이름 입력란 노출 여부 */
    val selectedScriptIsAcquaintance: Boolean
        get() = AttackerScriptCatalog.findById(scriptId.value)?.relationship ==
            CallerRelationship.ACQUAINTANCE

    /** 저장된 세션 폴더 목록 (최신순) */
    fun listSessions(): List<File> {
        val root = _service.value?.sessionRoot
            ?: File(getApplication<Application>().getExternalFilesDir(null), "sessions")
        if (!root.exists()) return emptyList()
        return root.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun notify(message: String) {
        viewModelScope.launch { _userMessage.emit(message) }
    }
}
