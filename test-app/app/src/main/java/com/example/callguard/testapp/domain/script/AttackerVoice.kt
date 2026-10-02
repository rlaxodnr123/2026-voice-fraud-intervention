package com.example.callguard.testapp.domain.script

import android.content.Context
import android.util.Log

/** 상대방 목소리의 성별 */
enum class VoiceGender(val label: String, val short: String) {
    MALE("남성", "남"),
    FEMALE("여성", "여")
}

/**
 * 상대방(공격자) 목소리 6종 — 연령대(20·30·40대) × 성별(남·여).
 *
 * 왜 목소리를 조건으로 두는가
 * -------------------------
 * 같은 대사를 20대 남성이 말하는가 40대 여성이 말하는가에 따라 참가자가 느끼는 신뢰도와
 * 압박감이 달라진다. 특히 지인 시나리오(S1)는 참가자가 "내가 아는 그 사람일 수 있다"고
 * 받아들여야 성립하므로, 참가자 연령·성별과 어울리는 목소리를 고를 수 있어야 한다.
 * 40대 참가자에게 20대 목소리가 "난데"라고 하면 관계 조건 자체가 무너진다.
 *
 * 목소리는 **세션 시작 전에 고정된다.** 통화 중에 바꿀 수 있게 하면 한 세션 안에서
 * 상대방이 다른 사람으로 바뀌어, 그 세션이 어떤 자극이었는지 말할 수 없게 된다.
 *
 * [id]가 곧 asset 폴더명이다:
 *
 *   assets/attacker/<시나리오ID>/<목소리ID>/<순번>.<확장자>
 *   예) assets/attacker/S1/m40/1.mp3
 *
 * 목소리별로 폴더를 나누는 이유: 한 폴더에 섞어 두면 파일명 규칙으로 구분해야 하고,
 * 규칙을 한 번 잘못 쓰면 연구자가 고른 것과 다른 목소리가 조용히 재생된다.
 */
enum class AttackerVoice(
    /** asset 폴더명 겸 로그 식별자 */
    val id: String,
    val ageBand: Int,
    val gender: VoiceGender
) {
    M20("m20", 20, VoiceGender.MALE),
    F20("f20", 20, VoiceGender.FEMALE),
    M30("m30", 30, VoiceGender.MALE),
    F30("f30", 30, VoiceGender.FEMALE),
    M40("m40", 40, VoiceGender.MALE),
    F40("f40", 40, VoiceGender.FEMALE);

    /** 설정 화면·패널에 쓰는 표기 (예: "40대 여성") */
    val label: String get() = ageBand.toString() + "대 " + gender.label

    /** 짧은 표기 (예: "40여") — 좁은 칸에 쓴다 */
    val short: String get() = ageBand.toString() + gender.short

    fun assetDir(scriptId: String): String = "attacker/" + scriptId + "/" + id

    companion object {
        fun findById(id: String): AttackerVoice? = values().firstOrNull { it.id == id }

        /** 설정 화면이 3행 2열로 그리기 위한 연령대 목록 */
        val ageBands: List<Int> get() = values().map { it.ageBand }.distinct().sorted()
    }
}

/**
 * 한 시나리오 × 한 목소리의 녹음본이 얼마나 갖춰졌는가.
 *
 * 설정 화면이 이것을 보여 주는 이유: 녹음본이 없는 목소리를 고르면 앱이 조용히 시스템
 * TTS로 대체해 버린다. 세션이 끝난 뒤 로그의 ttsFallbackCount를 봐야 알게 되는데,
 * 그때는 이미 그 참가자의 세션이 날아간 상태다. **고르는 순간 보이게** 해야 한다.
 */
data class VoiceAssetStatus(
    val voice: AttackerVoice,
    /** 개입 전 대사 중 녹음본이 있는 개수 */
    val mainFound: Int,
    val mainTotal: Int,
    /** 개입 후 후속 대사 중 녹음본이 있는 개수 */
    val followUpFound: Int,
    val followUpTotal: Int
) {
    val isComplete: Boolean get() = mainTotal > 0 && mainFound == mainTotal
    val isEmpty: Boolean get() = mainFound == 0

    /** 설정 화면에 띄우는 한 줄 */
    val summary: String
        get() = when {
            isEmpty -> "녹음본 없음"
            isComplete && followUpFound == followUpTotal -> "녹음본 완비"
            isComplete -> "대사 완비 · 후속 " + followUpFound + "/" + followUpTotal
            else -> "대사 " + mainFound + "/" + mainTotal + "만 있음"
        }
}

/**
 * 녹음본 asset을 찾는 **단 하나의 창구**.
 *
 * 재생기와 설정 화면이 각자 경로 규칙을 들고 있으면 언젠가 어긋난다 — 화면은 "완비"라고
 * 하는데 재생기는 못 찾는 상황이 바로 그 어긋남이고, 세션을 날린 뒤에야 알게 된다.
 * 그래서 경로와 확장자 우선순위를 여기 한 곳에만 둔다.
 */
object AttackerVoiceAssets {

    private const val TAG = "VoiceAssets"

    /** 이 순서로 찾는다 — 같은 순번에 여러 확장자가 있으면 앞선 것이 이긴다 */
    val extensions: List<String> = listOf("m4a", "mp3", "wav", "ogg")

    /** 개입 전 대사 파일명(확장자 제외) — 1부터 */
    fun mainStem(fileIndex: Int): String = fileIndex.toString()

    /** 개입 후 후속 대사 파일명(확장자 제외) — f1부터 */
    fun followUpStem(fileIndex: Int): String = "f" + fileIndex

    /**
     * 해당 대사의 녹음본 asset 경로. 없으면 null — 호출자가 TTS로 대체하고 로그를 남긴다.
     * (무음으로 건너뛰면 참가자가 요구를 듣지 못한 채 개입만 발동해 세션이 무효가 된다)
     */
    fun find(context: Context, scriptId: String, voice: AttackerVoice, stem: String): String? {
        val dir = voice.assetDir(scriptId)
        val names = listNames(context, dir)
        for (ext in extensions) {
            val name = stem + "." + ext
            if (name in names) return dir + "/" + name
        }
        return null
    }

    fun status(context: Context, script: AttackerScript, voice: AttackerVoice): VoiceAssetStatus {
        val names = listNames(context, voice.assetDir(script.id))

        fun has(stem: String): Boolean = extensions.any { (stem + "." + it) in names }

        val mainTotal = script.mainLines.size
        val mainFound = (1..mainTotal).count { has(mainStem(it)) }

        val followUpTotal = script.followUpLines.size
        val followUpFound = (1..followUpTotal).count { has(followUpStem(it)) }

        return VoiceAssetStatus(voice, mainFound, mainTotal, followUpFound, followUpTotal)
    }

    fun statuses(context: Context, script: AttackerScript): List<VoiceAssetStatus> =
        AttackerVoice.values().map { status(context, script, it) }

    private fun listNames(context: Context, dir: String): Set<String> = try {
        context.assets.list(dir)?.toSet() ?: emptySet()
    } catch (e: Exception) {
        Log.w(TAG, "녹음본 폴더 조회 실패: " + dir, e)
        emptySet()
    }
}
