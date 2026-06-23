package com.example.callguard.domain.pipeline

import android.content.Context
import android.util.Log
import com.example.callguard.domain.interfaces.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    // 모델 로딩 완료 여부를 외부에서 관찰할 수 있도록 노출
    private val _isModelReady = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val isModelReady: SharedFlow<Boolean> = _isModelReady

    var modelLoaded = false
        private set

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
                modelLoaded = true
                Log.d(TAG, "✅ Vosk 모델 로드 완료 — 음성 인식 가능")
                _isModelReady.emit(true)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Vosk 모델 로드 실패: ${e.message}", e)
                _isModelReady.emit(false)
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

    private val matchedKeywords = mutableSetOf<String>()

    // 상대방이 사용하는 피싱 패턴 단어
    private val phishingKeywords = listOf(
        "검사", "검찰", "경찰", "수사", "수사관",
        "대포통장", "안전 계좌", "안전계좌", "금융감독원",
        "송금", "이체", "계좌", "비밀번호", "주민등록번호",
        "명의 도용", "명의도용", "범죄", "구속", "체포",
        "환급", "세금 환급", "보안 앱", "원격 제어"
    )

    override fun analyzeText(text: String) {
        var changed = false
        for (keyword in phishingKeywords) {
            if (text.contains(keyword) && matchedKeywords.add(keyword)) {
                changed = true
            }
        }
        if (changed || matchedKeywords.isNotEmpty()) emitRisk()
    }

    private fun emitRisk() {
        val count = matchedKeywords.size
        val (prob, level) = when {
            count >= 4 -> 0.95f to RiskLevel.SCAM
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

    // 이미 발동된 패턴은 같은 통화 내에서 반복 발동 방지
    private val alreadyTriggered = mutableSetOf<String>()

    /**
     * 민감 정보 패턴 목록.
     * 짧고 명확한 패턴을 우선 — partial STT에서 빠르게 매칭되도록.
     */
    private val sensitivePatterns = listOf(
        "비밀번호는"    to "비밀번호",
        "비밀번호가"    to "비밀번호",
        "카드 비밀번호" to "카드 비밀번호",
        "카드비밀번호"  to "카드 비밀번호",
        "인증번호는"    to "인증번호",
        "인증번호가"    to "인증번호",
        "인증번호를"    to "인증번호",
        "계좌번호는"    to "계좌번호",
        "계좌번호가"    to "계좌번호",
        "주민등록번호"  to "주민등록번호",
        "주민번호"      to "주민번호",
        "카드번호는"    to "카드번호",
        "카드번호가"    to "카드번호",
        "핀번호"        to "핀번호",
        "otp"           to "OTP 번호",
        "일회용 비밀"   to "일회용 비밀번호",
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
