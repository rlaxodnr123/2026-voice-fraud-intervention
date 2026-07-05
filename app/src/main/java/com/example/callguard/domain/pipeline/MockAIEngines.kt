package com.example.callguard.domain.pipeline

import android.content.Context
import android.util.Log
import com.example.callguard.domain.interfaces.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Vosk 기반 오프라인 한국어 STT.
 * 모델 로딩 전에는 RMS 에너지만 계산한다.
 */
class MockSpeechRecognizer(private val context: Context) : SpeechRecognizer {
    private val TAG = "SpeechRecognizer"
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _transcript = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val transcript: SharedFlow<String> = _transcript

    private val _partialTranscript = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val partialTranscript: SharedFlow<String> = _partialTranscript

    // 모델 로딩 완료 여부를 외부에서 관찰할 수 있도록 노출.
    // StateFlow라 구독 시점과 무관하게 항상 최신 값을 받는다 (구독 전 emit 누락 없음).
    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady

    val modelLoaded: Boolean
        get() = _isModelReady.value

    private var isSpeaking = false
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    init {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Vosk 모델 복사 시작 (첫 실행 시 시간 소요)...")
                val destDir = File(context.filesDir, "model-ko")

                // 이미 복사된 경우 그대로 사용
                if (!destDir.exists() || !File(destDir, "am/final.mdl").exists()) {
                    destDir.mkdirs()
                    copyAssetFolder(context, "model-ko", destDir)
                    Log.d(TAG, "모델 파일 복사 완료: ${destDir.absolutePath}")
                } else {
                    Log.d(TAG, "기존 모델 재사용: ${destDir.absolutePath}")
                }

                val m = Model(destDir.absolutePath)
                model = m
                Log.d(TAG, "✅ Vosk 모델 로드 완료 — 음성 인식 가능")
                _isModelReady.value = true

            } catch (e: Exception) {
                Log.e(TAG, "❌ Vosk 모델 로드 실패: ${e.message}", e)
                _isModelReady.value = false
            }
        }
    }

    /**
     * assets 폴더를 기기 저장소로 재귀 복사한다.
     * macOS 쓰레기 파일(__MACOSX, ._*)은 건너뛴다.
     */
    private fun copyAssetFolder(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetPath) ?: return

        for (fileName in files) {
            // macOS 메타데이터 파일 제외
            if (fileName.startsWith("._") || fileName == "__MACOSX") continue

            val subAssetPath = "$assetPath/$fileName"
            val destFile = File(destDir, fileName)

            val subFiles = assetManager.list(subAssetPath)
            if (!subFiles.isNullOrEmpty()) {
                // 하위 폴더 → 재귀
                destFile.mkdirs()
                copyAssetFolder(context, subAssetPath, destFile)
            } else {
                // 파일 → 복사
                assetManager.open(subAssetPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.v(TAG, "복사: $subAssetPath")
            }
        }
    }

    override fun feedAudio(pcmData: ByteArray, sampleRate: Int, channels: Int) {
        val currentModel = model
        if (currentModel == null) {
            calculateEnergy(pcmData)
            return
        }

        if (recognizer == null) {
            try {
                recognizer = Recognizer(currentModel, sampleRate.toFloat())
            } catch (e: Exception) {
                Log.e(TAG, "Recognizer 초기화 실패: ${e.message}")
                return
            }
        }

        val rec = recognizer ?: return
        if (rec.acceptWaveForm(pcmData, pcmData.size)) {
            val text = parseVoskJson(rec.result, "text")
            if (text.isNotBlank()) {
                Log.d(TAG, "STT 최종: $text")
                scope.launch { _transcript.emit(text) }
            }
        } else {
            val partial = parseVoskJson(rec.partialResult, "partial")
            if (partial.isNotBlank()) {
                scope.launch { _partialTranscript.emit(partial) }
            }
        }
    }

    private fun calculateEnergy(pcmData: ByteArray) {
        var sum = 0.0
        for (i in 0 until pcmData.size step 2) {
            if (i + 1 < pcmData.size) {
                val sample = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toDouble()
                sum += sample * sample
            }
        }
        val rms = sqrt(sum / (pcmData.size / 2))
        if (rms > 500.0 && !isSpeaking) {
            isSpeaking = true
            Log.d(TAG, "음성 활동 감지 (모델 로딩 중...)")
        } else if (rms <= 500.0) {
            isSpeaking = false
        }
    }

    private fun parseVoskJson(json: String, key: String): String {
        val match = """"$key"\s*:\s*"([^"]*)"""".toRegex().find(json)
        return match?.groups?.get(1)?.value ?: ""
    }

    /** 테스트용: 특정 문장을 직접 주입 */
    fun injectPhrase(phrase: String) {
        scope.launch { _transcript.emit(phrase) }
    }

    override fun reset() {
        recognizer?.reset()
        isSpeaking = false
    }

    override fun release() {
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null
        model = null
        _isModelReady.value = false
        scope.cancel()
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * 상대방(원격) 발화에서 피싱 의도 키워드를 감지한다.
 *
 * 위험도 판정:
 *   키워드 4개 이상 → SCAM (95%)
 *   키워드 2~3개   → SUSPICIOUS (65%)
 *   키워드 1개     → SUSPICIOUS (35%)
 */
class MockScamDetector : ScamDetector {
    private val TAG = "ScamDetector"
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _riskState = MutableSharedFlow<RiskScore>(extraBufferCapacity = 64)
    override val riskState: SharedFlow<RiskScore> = _riskState

    // analyzeText(오디오 처리 스레드)와 reset(메인/IO 스레드)이 동시 접근하므로 스레드 안전 셋 사용
    private val matchedKeywords: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // 상대방이 사용하는 피싱 패턴 단어
    private val phishingKeywords = listOf(
        // 기관 사칭
        "검사", "검찰", "경찰", "수사", "수사관",
        "금융감독원", "금감원", "건강보험", "건강보험공단",
        "국세청", "법원", "검찰청", "경찰청",

        // 금융 범죄 키워드
        "대포통장", "안전 계좌", "안전계좌",
        "송금", "이체", "계좌", "비밀번호", "주민등록번호",
        "명의 도용", "명의도용", "범죄", "구속", "체포",
        "환급", "세금 환급", "보험료 환급", "과납",
        "보안 앱", "원격 제어", "팀뷰어", "애니데스크",

        // 가족 납치·사고 사칭
        "납치", "사고났어", "사고가 났어", "합의금",
        "병원비", "입원했어", "다쳤어", "긴급",

        // 택배·기타 사칭
        "택배", "미수령", "반송", "통관",

        // 협박·압박
        "공범", "처벌", "벌금", "기밀", "수사 기밀",
        "압수", "압류", "자산 동결",

        // 은행·금융기관 사칭
        "고객보호센터", "고객센터", "은행", "이상 거래", "이상거래",
        "본인 확인", "본인확인", "계좌 정지", "계좌정지",
        "지급 정지", "지급정지", "이상 출금", "이상출금",

        // 개인정보 요구
        "생년월일", "주소", "성함", "성명",

        // 긴급성·압박
        "지금 바로", "즉시", "빠르게", "시간이 없습니다",
        "끊으시면", "피해", "협조"
    )

    override fun analyzeText(text: String) {
        var changed = false
        for (keyword in phishingKeywords) {
            if (text.contains(keyword) && matchedKeywords.add(keyword)) {
                changed = true
            }
        }
        // 새 키워드가 추가됐을 때만 위험도를 재발사한다. 변화가 없는데도 매 STT마다
        // emit하면 riskScoreFlow 콜렉터가 불필요하게 반복 실행된다.
        if (changed) emitRisk()
    }

    private fun emitRisk() {
        val count = matchedKeywords.size
        val (prob, level) = when {
            count >= 6 -> 0.95f to RiskLevel.SCAM
            count >= 4 -> 0.80f to RiskLevel.SCAM
            count >= 2 -> 0.65f to RiskLevel.SUSPICIOUS
            count >= 1 -> 0.35f to RiskLevel.SUSPICIOUS
            else -> 0.05f to RiskLevel.SAFE
        }
        scope.launch {
            _riskState.emit(RiskScore(prob, level, matchedKeywords.toList()))
            Log.d(TAG, "위험도 업데이트: $level (${matchedKeywords.size}개 키워드)")
        }
    }

    override fun reset() {
        matchedKeywords.clear()
        scope.launch { _riskState.emit(RiskScore(0f, RiskLevel.SAFE, emptyList())) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * 로컬(내 발화) 개인정보 누출 감지기.
 *
 * Partial STT(말하는 도중)를 실시간으로 검사해서,
 * 민감 정보 패턴이 감지되면 즉시 onLeakDetected 콜백을 호출한다.
 *
 * 감지 패턴 예시:
 *   "비밀번호는", "카드 비밀번호", "인증번호는", "계좌번호는", "주민등록번호는"
 */
class LocalLeakDetector(
    private val onLeakDetected: (triggerPhrase: String, partialText: String) -> Unit
) {
    private val TAG = "LocalLeakDetector"

    // 이미 발동된 패턴은 같은 통화 내에서 반복 발동 방지.
    // analyze(오디오 처리 스레드)와 reset(메인 스레드, resumeAfterFalseAlarm 경로)이 동시 접근하므로
    // MockScamDetector.matchedKeywords와 동일하게 스레드 안전 셋을 사용한다.
    private val alreadyTriggered: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 민감 정보 패턴 목록.
     * 짧고 명확한 패턴을 우선 — partial STT에서 빠르게 매칭되도록.
     */
    private val sensitivePatterns = listOf(
        // 비밀번호
        "비밀번호는"    to "비밀번호",
        "비밀번호가"    to "비밀번호",
        "비밀번호를"    to "비밀번호",
        "카드 비밀번호" to "카드 비밀번호",
        "카드비밀번호"  to "카드 비밀번호",
        "핀번호"        to "핀번호",
        "pin번호"       to "핀번호",

        // 인증번호 / OTP
        "인증번호는"    to "인증번호",
        "인증번호가"    to "인증번호",
        "인증번호를"    to "인증번호",
        "인증번호"      to "인증번호",
        "otp"           to "OTP 번호",
        "일회용 비밀"   to "일회용 비밀번호",
        "일회용비밀"    to "일회용 비밀번호",

        // 계좌·카드번호
        "계좌번호는"    to "계좌번호",
        "계좌번호가"    to "계좌번호",
        "계좌번호를"    to "계좌번호",
        "카드번호는"    to "카드번호",
        "카드번호가"    to "카드번호",
        "카드번호를"    to "카드번호",

        // 신분증
        "주민등록번호"  to "주민등록번호",
        "주민번호"      to "주민번호",
        "생년월일은"    to "생년월일",
        "여권번호"      to "여권번호",

        // 금융 계좌 이체
        "보내드릴게요"  to "송금 시도",
        "입금할게요"    to "송금 시도",
        "이체할게요"    to "송금 시도",
        "송금할게요"    to "송금 시도",
    )

    /**
     * Partial 또는 Final 텍스트를 검사한다.
     * 매칭 즉시 콜백 호출 (같은 패턴은 reset() 전까지 1회만).
     */
    fun analyze(text: String) {
        val lower = text.lowercase()
        for ((pattern, label) in sensitivePatterns) {
            if (lower.contains(pattern) && alreadyTriggered.add(label)) {
                Log.w(TAG, "개인정보 누출 감지! 패턴: '$pattern' | 발화: '$text'")
                onLeakDetected(label, text)
                return  // 한 발화에 하나의 이벤트만 발동
            }
        }
    }

    fun reset() {
        alreadyTriggered.clear()
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * 공격자가 민감정보를 직접 요구한 직후, 피해자가 숫자를 발화하는 "실제 유출 순간"을 감지한다.
 *
 * LocalLeakDetector는 피해자가 "주민등록번호는" 같은 트리거 문구를 스스로 말해야만 감지되므로,
 * 공격자의 질문에 그냥 숫자로만 답하는 경우(예: "칠공구...")는 놓친다. 이 감지기는 그 공백을 메운다.
 *
 * 흐름: 원격 발화에서 민감정보 명사+요구 동사 조합 감지 → TTL 동안 "요구 활성" 상태 유지
 *       → 그 사이 로컬 발화에서 연속 숫자(3자리 이상) 감지 시 즉시 콜백.
 */
class SensitiveDisclosureDetector(
    private val onDisclosureDetected: (triggerLabel: String, partialText: String) -> Unit
) {
    private val TAG = "SensitiveDisclosureDetector"

    companion object {
        // partial STT마다(초당 여러 번) 호출되는 핫 패스이므로 정규식을 매번 컴파일하지 않고 재사용한다.
        private val DIGIT_RUN_REGEX = Regex("[0-9]{3,}")
    }

    // 민감정보 명사 → 표시용 라벨 (LocalLeakDetector와 동일 어휘 사용)
    private val sensitiveNouns = listOf(
        "주민등록번호" to "주민등록번호", "주민번호" to "주민등록번호",
        "계좌번호" to "계좌번호",
        "카드 비밀번호" to "카드 비밀번호", "카드비밀번호" to "카드 비밀번호", "카드번호" to "카드번호",
        "비밀번호" to "비밀번호",
        "인증번호" to "인증번호", "otp" to "OTP 번호",
        "여권번호" to "여권번호",
        "생년월일" to "생년월일"
    )

    // 공격자가 정보를 "요구"할 때 함께 쓰는 동사/어미
    private val requestVerbs = listOf(
        "말씀해", "말해주세요", "말해줘", "불러주세요", "불러줘", "불러",
        "알려주세요", "알려줘", "적어주세요", "읽어주세요",
        "입력해", "입력하세요", "뒷자리", "뒤 7자리", "뒤 4자리", "앞자리",
        "전부 말해", "다 말해"
    )

    // 요구 감지 후 이 시간(ms) 동안만 숫자 발화를 "유출 시도"로 간주한다.
    private val requestTtlMs = 20_000L

    @Volatile private var activeLabel: String? = null
    @Volatile private var activeUntil: Long = 0L

    /**
     * 원격(공격자) 발화를 검사해 민감정보 요구 패턴이면 TTL을 활성화한다.
     *
     * 이미 요구가 활성 상태라도 스캔은 매번 수행한다 — 공격자가 20초 이내에 요구 대상을
     * 바꾸는 경우(예: 주민등록번호를 묻다가 계좌번호로 전환)까지 감지해야 하므로, "이미 활성"
     * 이유로 스캔을 건너뛰면 두 번째 요구를 놓친다. 명사×동사 목록이 작아 스캔 비용 자체는
     * partial STT 빈도에서도 무시할 수준이라 정확성을 우선한다.
     */
    fun analyzeRemoteText(text: String) {
        val lower = text.lowercase()
        for ((noun, label) in sensitiveNouns) {
            if (lower.contains(noun) && requestVerbs.any { lower.contains(it) }) {
                activeLabel = label
                activeUntil = System.currentTimeMillis() + requestTtlMs
                Log.w(TAG, "민감정보 요구 감지: $label (${requestTtlMs}ms 이내 숫자 발화 감시) | '$text'")
                return
            }
        }
    }

    /** 로컬(피해자) 발화를 검사해 요구 활성 상태에서 숫자 연속 발화가 나오면 즉시 콜백한다. */
    fun analyzeLocalText(text: String) {
        val label = activeLabel ?: return
        if (System.currentTimeMillis() > activeUntil) {
            activeLabel = null
            return
        }
        if (DIGIT_RUN_REGEX.containsMatchIn(text)) {
            Log.w(TAG, "민감정보 발화 감지! 요구: $label | 발화: '$text'")
            activeLabel = null
            onDisclosureDetected(label, text)
        }
    }

    fun reset() {
        activeLabel = null
        activeUntil = 0L
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * 개입 엔진 — 위험 감지 시 상위 레이어(CallService)에 콜백으로 알린다.
 */
class MockInterventionEngine(
    private val onInterventionTriggered: (RiskLevel) -> Unit
) : InterventionEngine {

    override fun executeIntervention(level: RiskLevel) {
        Log.d("InterventionEngine", "개입 실행: $level")
        onInterventionTriggered(level)
    }

    override fun reset() {}
}
