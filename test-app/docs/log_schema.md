# 세션 로그 스키마

세션 하나당 `sessions/<세션ID>/session.json` 파일 하나가 만들어집니다.

```json
{
  "meta":    { ... 조건 ... },
  "summary": { ... 계산된 분석 지표 ... },
  "events":  [ { "t": 1758170412345, "time": "2026-09-21 14:30:12.345",
                 "type": "intervention_fired", "data": { ... } }, ... ]
}
```

- `events`가 **원본 사실**이고, `summary`는 그 이벤트로부터 **다시 계산되는 파생값**입니다.
  요약 정의가 나중에 바뀌어도 원본만 있으면 재계산할 수 있습니다.
- 파일 쓰기는 **전용 단일 스레드**에서만 일어납니다. `log()`는 마이크 워커·TTS 콜백·메인
  스레드에서 동시에 불리는데, 거기서 곧바로 디스크에 쓰면 오디오 읽기 루프가 막혀
  녹음이 끊깁니다.
- 파일은 이벤트가 들어올 때마다 **통째로 다시 씁니다.** 앱이 중간에 죽어도 그 시점까지가
  온전한 JSON으로 남습니다(이어쓰기 방식은 중단 시 깨진 JSON이 됩니다).

---

## meta — 세션 조건

| 필드 | 의미 |
|---|---|
| `participantId` | 참가자 ID |
| `trialOrder` | 이 참가자의 몇 번째 통화인가 (1~4) |
| `interventionId` | `POPUP_TTS` / `FORCE_TERMINATE` / `NONE` |
| `interventionLabel` | 조건 표시 이름 |
| `scriptId`, `scriptLabel` | 시나리오 (`S1`~`S4`) |
| `scamLevel` | `REAL_SCAM` / `AMBIGUOUS` — 2×2의 한 축 |
| `relationship` | `ACQUAINTANCE` / `STRANGER` — 2×2의 다른 축 |
| `callerDisplayed` | 통화 화면에 실제로 띄운 발신자 (지인은 이름, 모르는 사람은 번호) |
| `playbackMode` | `AUTO_TTS` / `RECORDING` / `LIVE` |
| `startedAt`, `startedAtText` | 세션 시작 시각 |

---

## summary — 분석 지표

### 조건
`participantId` · `trialOrder` · `interventionId` · `scriptId` · **`scamLevel`** · **`relationship`** · `playbackMode`

### 개입 타이밍

| 필드 | 의미 |
|---|---|
| `interventionPointAt` | 개입 지점 대사가 끝난 시각 |
| `interventionFiredAt` | 개입이 발동한 시각 (통제 조건에서도 기록됨) |
| `interventionSource` | `auto`(개입 지점 자동) / `manual`(연구자 버튼) |
| `pointToInterventionMs` | 개입 지점 → 발동 지연. **설계상 0에 가까워야 함.** 크게 벌어진 세션은 재생·트리거 문제로 보고 제외 여부를 검토 |
| `lineShownToInterventionMs` | 개입 지점 **대사가 뜬 시각** → 발동. 라이브 모드에서 연구자가 읽고 버튼을 누르기까지의 사람 지연 |
| `triggerLatencyControlled` | **false면 라이브 모드** — 자동 모드 세션과 반응시간을 직접 비교하면 안 됨 |

### 반응시간

| 필드 | 의미 |
|---|---|
| `firstReactionAt` / `firstReactionType` | 개입 이후 참가자의 첫 관찰 가능한 행동과 그 종류 |
| `reactionTimeMs` | **개입 반응시간** = 개입 발동 → 첫 반응 |

반응으로 세는 이벤트는 넷뿐입니다:
`participant_speech_onset`(발화 개시) · `participant_chose_continue`(팝업 선택) ·
`user_action`(화면 조작) · `call_ended_by_participant`(직접 종료).

- **발화 개시(onset)를 쓰는 이유**: 최종 인식(`participant_speech`)은 발화가 끝나고 무음이
  이어져야 나옵니다. 그것을 반응시각으로 쓰면 발화 길이와 무음 대기가 통째로 더해집니다.
- **제외되는 것**: `participant_speech_gated`(스피커 음성이 마이크로 되들어온 구간),
  `participant_speech_manual`(연구자 타이핑 — 참가자가 말한 시각이 아님).
- 버튼만 누르고 말없이 끝낸 참가자가 무응답으로 기록되지 않도록 네 가지를 모두 봅니다.

### 유출 (진짜 사기 조건의 주 지표)

| 필드 | 의미 |
|---|---|
| `leaked` | 유출 여부 |
| `leakConfidence` | `CONFIRMED`(아라비아 숫자 연속 또는 연구자 확정) / `SUSPECTED`(한글 숫자 음절 — 연구자 확인 필요) |
| `leakSource` | `stt` / `stt_partial` / `researcher_injected` / `researcher` |
| `leakAfterIntervention` | 개입 이후에 유출됐는가 = **개입이 막지 못했는가** |
| `pointToLeakMs` | 개입 지점 → 유출까지 걸린 시간 |
| `refusalDetected`, `refusalAt` | 거부 표현 감지 여부·시각 |

### 개입에 대한 선택 (애매 조건의 주 지표)

| 필드 | 의미 |
|---|---|
| `choseContinue` | 팝업에서 [통화 계속하기]를 골랐는가 |
| `choseContinueAt` / `continueDecisionMs` | 선택 시각과 고민 시간 |

> **`scamLevel`과 함께 읽어야 합니다.** 애매한 시나리오(S2·S4)에서는 계속하기가
> **올바른 선택**이고, 진짜 사기(S1·S3)에서는 개입을 무시한 것입니다.

### 통화 종료

| 필드 | 의미 |
|---|---|
| `callEndedBy` | `participant`(참가자가 끊음) / `app`(강제 종료) / `none`(끝까지 안 끊음) |
| `callEndedAt` | 통화가 끊긴 시각 |
| `callDurationMs` | 통화 길이 |
| `interventionToCallEndMs` | 개입 → 통화 종료까지 |

`sessionDurationMs`(세션 전체)와 다릅니다 — 세션에는 통화 종료 후 연구자가 패널을 만진 시간이 포함됩니다.

### 품질 점검

| 필드 | 의미 |
|---|---|
| `gatedSpeechCount` | 스피커 음성이 마이크로 되들어와 제외된 인식 건수. 크면 에코가 심한 환경 |
| `micErrorCount` | 마이크 오류 — 발화 기록이 불완전할 수 있음 |
| `ttsFallbackCount` | 녹음본이 없어 TTS로 대체된 대사 수 — 자극 불일치 |
| `observationNotes` | 연구자가 원클릭으로 남긴 관찰 목록 |
| `sessionEndReason`, `eventCount` | 세션 종료 사유, 이벤트 총 개수 |

---

## events — 이벤트 종류

### 세션·대본

| type | 언제 | 주요 data |
|---|---|---|
| `session_started` | 세션 시작 | meta 전체 |
| `script_started` | 대본 재생 시작 | `scriptId`, `mode`, `lineCount`, `interventionPointIndex`, `scamLevel`, `relationship`, `requestedInfo` |
| `attacker_line_start` / `attacker_line_end` | 상대방 대사 시작/종료 | `index`, `isInterventionPoint`, `text`, `live` |
| `attacker_line_fallback_tts` | 녹음본이 없어 TTS로 대체됨 | `index`, `expected` |
| `attacker_audio_error` | 녹음본 재생 실패 | `asset`, `what`/`error` |
| `attacker_followup` | 개입 후 후속 대사 재생 | `index`, `text` |
| `intervention_point_reached` | **개입 지점 도달 — 개입 기준점** | `scriptId`, `requestedInfo`, `liveManualAdvance` |
| `script_finished` | 대본 끝까지 재생됨 | — |
| `session_finished` | 세션 종료 | `reason`, `recordingFile`, `recordingDurationMs` |

### 개입

| type | 언제 | 주요 data |
|---|---|---|
| `intervention_armed` | 세션 시작 시 조건 무장 | `config`(적용 파라미터 전체) |
| `intervention_fired` | **개입 발동** | `source`, `reason`, `config` |
| `intervention_common_applied` | 공통 개입 적용 | `tone`, `vibrate`, `micBlocked`, `popup` |
| `intervention_tts_done` | 안내 음성 종료 | — |
| `intervention_control_no_action` | 통제 조건이라 아무 동작 안 함 | — |
| `intervention_duplicate_ignored` | 이미 발동한 뒤 또 요청됨 | `source` |
| `intervention_skipped` | 무장 없이 발동 요청됨 | `why` |
| `intervention_reset` | 세션 종료 시 초기화 | — |
| `tts_watchdog_fired` | TTS 완료 콜백이 오지 않아 강제 완료 처리 | `utteranceId`, `afterMs` |

### 참가자

| type | 언제 | 반응시간에 포함 |
|---|---|:-:|
| `participant_speech_onset` | 개입 이후 첫 발화 **개시** | ✅ |
| `participant_speech` | 발화 최종 인식 | — |
| `participant_speech_gated` | 스피커 음성이 마이크로 되들어온 구간 | — |
| `participant_speech_manual` | 연구자가 대신 입력 | — |
| `participant_chose_continue` | 팝업에서 [통화 계속하기] 선택 | ✅ |
| `user_action` | 음소거·스피커 토글 | ✅ |
| `call_ended_by_participant` | 참가자가 통화 종료 (`how`: `call_button` / `popup_end`) | ✅ |
| `call_ended_by_app` | 앱이 강제 종료 (개입 2) | — |
| `leak_detected` / `leak_marked_none` | 유출 감지·확정 / 유출 없음 확정 | — |
| `refusal_detected` | 거부 표현 감지 | — |
| `observation` | 연구자 원클릭 관찰 | — |

### 기기

| type | 주요 data |
|---|---|
| `mic_capture_started` | `ok`, `recording`, `sttReady` |
| `mic_error` | `message` |
| `foreground_promote_failed` | `error` |

---

## 분석 시 확인할 것

1. `pointToInterventionMs`가 비정상적으로 큰 세션 — 재생·트리거 문제
2. `triggerLatencyControlled: false` 세션 — 라이브 모드. 반응시간을 자동 모드와 섞지 말 것
3. `ttsFallbackCount > 0` — 녹음본 조건인데 일부가 TTS로 나감 (자극 불일치)
4. `micErrorCount > 0` — 발화 기록이 불완전
5. `gatedSpeechCount`가 큰 세션 — 에코가 심함. 참가자가 겹쳐 말한 구간이 제외됐을 수 있으니
   `recording.wav`로 확인
6. `leakConfidence: SUSPECTED`인데 연구자 확정(`leakSource: researcher`)이 없는 세션 — 판정 미확정
