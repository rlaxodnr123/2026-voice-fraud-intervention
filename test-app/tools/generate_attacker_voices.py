#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
공격자(상대방) 대사 음성 생성기 — ElevenLabs · Cartesia

왜 이 스크립트인가
------------------
앱은 INTERNET 권한이 없다. 참가자 음성이 기기 밖으로 나갈 수 없다는 것을 매니페스트
수준에서 보장하는 설계이므로, TTS API를 앱 안에서 부를 수 없다. 대신 앱에는 이미
`RECORDING` 재생 모드가 있다 — assets 에 넣어 둔 녹음을 순서대로 재생한다. 그래서
**PC에서 미리 음성을 만들어 assets에 넣는 것**이 유일하면서 동시에 실험적으로도 가장
나은 방법이다: 참가자마다 글자·억양·길이가 바이트 단위로 동일한 자극이 되고,
네트워크 실패로 세션이 날아갈 일이 없다.

목소리 6종
----------
연구자가 설정 화면에서 연령대(20·30·40대) × 성별(남·여) 중 하나를 고르고, 그 목소리가
그대로 재생된다. 폴더 구조가 그 선택과 1:1로 대응한다:

    assets/attacker/<시나리오ID>/<목소리ID>/<순번>.mp3
    예) assets/attacker/S1/f40/1.mp3      개입 전 대사
        assets/attacker/S1/f40/f1.mp3     개입 후 후속 대사

목소리ID 는 Kotlin 의 AttackerVoice enum 과 같은 값이다 (m20·f20·m30·f30·m40·f40).
어느 업체의 어느 목소리를 어느 ID에 쓸지는 `tools/voices.json` 에 업체별로 적는다.

업체 두 곳
----------
  cartesia    sonic-3.6 — 2026년 블라인드 청취 선호도에서 자연스러움 1위
  elevenlabs  eleven_v3 — 오디오 태그로 연기 지시가 되는 모델

어느 쪽이 이 대본에 맞는지는 귀로 판단해야 한다. `--sample` 로 같은 대사를 양쪽에서
뽑아 나란히 들어 보라 — 한 줄에 몇 센트도 들지 않는다.

대사 문구는 **AttackerScriptCatalog.kt 에서 직접 읽는다.** 여기에 문장을 따로 복사해 두면
대본을 고쳤을 때 음성만 옛날 문장으로 남아 실험이 조용히 오염된다. 목소리 폴더마다
manifest.json 을 남기므로, `--check` 로 그 어긋남을 언제든 찾아낼 수 있다.

주의: 경고 음성(개입 안내)은 이 스크립트로 만들지 않는다. 경고가 상대방과 비슷한
사람 목소리면 참가자가 경고를 "상대가 하는 말"로 오인해 개입이 사기의 일부처럼
받아들여진다. 경고는 앱의 기계적인 시스템 TTS로 두는 것이 맞다.

사용법
------
  # 0) 키 준비 (인자로 넘기지 말고 환경변수로 — 셸 기록에 남지 않게)
  $env:CARTESIA_API_KEY="..."        # 또는 ELEVENLABS_API_KEY

  # 1) 목소리 목록을 보고, 들어 볼 목소리 하나를 실제 대사로 뽑아 본다
  python tools/generate_attacker_voices.py -p cartesia --list-voices
  python tools/generate_attacker_voices.py -p cartesia --sample --voice-id <id>

  # 2) 쓸 만하면 voices.json 에 6종을 배정하고 전체 생성
  python tools/generate_attacker_voices.py --init-voices
  python tools/generate_attacker_voices.py -p cartesia
  python tools/generate_attacker_voices.py -p cartesia --variants m30,f40
  python tools/generate_attacker_voices.py -p cartesia --only S2

  # 3) 대본을 고친 뒤, 음성이 옛 문장으로 남아 있지 않은지 점검
  python tools/generate_attacker_voices.py --check

표준 라이브러리만 쓴다(설치 불필요). ffmpeg 이 있으면 `--telephone` 으로 전화 대역
필터를 걸 수 있다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ELEVENLABS_ROOT = "https://api.elevenlabs.io/v1"
CARTESIA_ROOT = "https://api.cartesia.ai"
# Cartesia 는 날짜 버전 헤더를 요구한다. 값이 틀리면 요청 자체가 거절된다.
CARTESIA_VERSION = "2026-08-14"

# 프로젝트 루트(test-app/) 기준 경로 — 이 파일은 test-app/tools/ 에 있다.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
CATALOG_PATH = (
    PROJECT_ROOT
    / "app/src/main/java/com/example/callguard/testapp/domain/script/AttackerScriptCatalog.kt"
)
VOICE_ENUM_PATH = (
    PROJECT_ROOT
    / "app/src/main/java/com/example/callguard/testapp/domain/script/AttackerVoice.kt"
)
ASSETS_ROOT = PROJECT_ROOT / "app/src/main/assets/attacker"
VOICES_CONFIG = Path(__file__).resolve().parent / "voices.json"
# 들어 보기용 샘플은 assets 와 떨어진 곳에 둔다 — 시험 삼아 뽑은 파일이 실험 자극으로
# 섞여 들어가면, 어느 세션이 어떤 음성을 들었는지 알 수 없게 된다.
SAMPLES_DIR = Path(__file__).resolve().parent / "samples"

# AttackerVoiceAssets.extensions 와 같은 순서여야 한다. 같은 순번에 확장자만 다른 파일이
# 함께 있으면 앞선 확장자가 이긴다 — 새로 만든 mp3가 묻히는 사고의 원인이다.
ASSET_EXTENSIONS = ["m4a", "mp3", "wav", "ogg"]

# 목소리 6종. Kotlin 의 AttackerVoice enum 과 같아야 하므로, 아래 verify_variants() 가
# enum 파일을 읽어 대조한다 — 한쪽만 고치면 앱이 못 찾는 폴더를 만들게 된다.
VARIANTS = [
    ("m20", "20대 남성"),
    ("f20", "20대 여성"),
    ("m30", "30대 남성"),
    ("f30", "30대 여성"),
    ("m40", "40대 남성"),
    ("f40", "40대 여성"),
]
VARIANT_LABELS = dict(VARIANTS)

# 업체별 정의. 100만 자당 USD 는 공식 요금제가 크레딧 단위라 어디까지나 **어림값**이고,
# 생성 전에 규모 감을 잡으라고 두는 것이지 청구액이 아니다.
PROVIDERS = {
    "cartesia": {
        "label": "Cartesia (sonic-3.6 — 자연스러움 블라인드 1위)",
        "env": "CARTESIA_API_KEY",
        "default_model": "sonic-3.6",
        "prices": {
            "sonic-3.6": 45.0,
            "sonic-3.5": 45.0,
            "sonic-3": 45.0,
        },
    },
    "elevenlabs": {
        "label": "ElevenLabs (eleven_v3 — 오디오 태그로 연기 지시)",
        "env": "ELEVENLABS_API_KEY",
        "default_model": "eleven_v3",
        "prices": {
            "eleven_v3": 100.0,
            "eleven_v4": 100.0,
            "eleven_multilingual_v2": 100.0,
            "eleven_flash_v2_5": 50.0,
        },
    },
}

ALL_MODELS = sorted(m for p in PROVIDERS.values() for m in p["prices"])


def provider_of_model(model: str) -> str | None:
    for name, spec in PROVIDERS.items():
        if model in spec["prices"]:
            return name
    return None

# 시나리오별 **어조** 설정. 누가 말하는가(목소리 6종)와는 별개다 —
# 화자는 voices.json 이 정하고, 여기서는 그 화자가 어떻게 연기할지만 정한다.
#
# 두 시나리오의 차이는 "아는 사람으로 보이는가" 하나뿐이고, 그 차이를 만드는 것이 어조다.
#   S1 지인      : stability 낮게 → 흔들리고 다급한 반말
#   S2 은행 사칭 : stability 높게 → 또박또박한 사무적 존댓말
SCRIPT_PROFILES = {
    "S1": {
        "note": "지인 — 편한 반말, 다급함",
        "elevenlabs": {
            "settings": {
                "stability": 0.35,
                "similarity_boost": 0.80,
                "style": 0.35,
                "speed": 1.02,
                "use_speaker_boost": True,
            },
            # eleven_v3/v4 전용 오디오 태그. 말로 읽히지 않고 연기 지시로만 쓰인다.
            "audio_tag": "[urgent]",
        },
        "cartesia": {"generation_config": {"speed": 1.05, "volume": 1.0}},
    },
    "S2": {
        "note": "은행 보안팀 사칭 — 사무적인 존댓말, 침착한 압박",
        "elevenlabs": {
            "settings": {
                "stability": 0.60,
                "similarity_boost": 0.80,
                "style": 0.10,
                "speed": 0.98,
                "use_speaker_boost": True,
            },
            "audio_tag": "[professional]",
        },
        "cartesia": {"generation_config": {"speed": 0.98, "volume": 1.0}},
    },
}

DEFAULT_PROFILE = {
    "note": "",
    "elevenlabs": {
        "settings": {
            "stability": 0.50,
            "similarity_boost": 0.80,
            "style": 0.20,
            "speed": 1.0,
            "use_speaker_boost": True,
        },
        "audio_tag": "",
    },
    "cartesia": {"generation_config": {"speed": 1.0, "volume": 1.0}},
}


def profile_of(script_id: str) -> dict:
    return SCRIPT_PROFILES.get(script_id, DEFAULT_PROFILE)


# ── 대본 파싱 ────────────────────────────────────────────────────────
#
# Kotlin 을 정규식 하나로 긁으면 주석 안의 예시 코드나 문장 속 괄호에 걸려 조용히
# 틀린 문장을 뽑는다. 문자열 리터럴과 주석을 제대로 구분하는 작은 스캐너를 쓴다.


def _read_string_literal(src: str, i: int) -> tuple[str, int]:
    """src[i] 가 여는 따옴표일 때, (내용, 닫는 따옴표 다음 위치) 를 돌려준다."""
    if src[i] != '"':
        raise ValueError("문자열 시작이 아닙니다")
    escapes = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "$": "$", "'": "'"}
    out: list[str] = []
    i += 1
    while i < len(src):
        c = src[i]
        if c == "\\" and i + 1 < len(src):
            nxt = src[i + 1]
            out.append(escapes.get(nxt, nxt))
            i += 2
            continue
        if c == '"':
            return "".join(out), i + 1
        out.append(c)
        i += 1
    raise ValueError("문자열 리터럴이 닫히지 않았습니다")


def _strip_comments(src: str) -> str:
    """주석만 지운다. 문자열 안의 // 나 /* 는 건드리지 않는다."""
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            _, j = _read_string_literal(src, i)
            out.append(src[i:j])
            i = j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _balanced_span(src: str, after_open: int) -> tuple[str, int]:
    """여는 괄호 바로 다음 위치에서 시작해, 짝이 맞는 닫는 괄호 직전까지를 돌려준다."""
    depth, i = 1, after_open
    while i < len(src):
        c = src[i]
        if c == '"':
            _, i = _read_string_literal(src, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return src[after_open:i], i + 1
        i += 1
    raise ValueError("괄호가 닫히지 않았습니다")


def _top_level_args(span: str) -> list[str]:
    args: list[str] = []
    buf: list[str] = []
    depth, i = 0, 0
    while i < len(span):
        c = span[i]
        if c == '"':
            _, j = _read_string_literal(span, i)
            buf.append(span[i:j])
            i = j
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    if "".join(buf).strip():
        args.append("".join(buf))
    return args


def _joined_literals(arg: str) -> str:
    """인자 안의 문자열 리터럴을 이어 붙인다 ("앞" + "뒤" 형태를 위해)."""
    parts: list[str] = []
    i = 0
    while i < len(arg):
        if arg[i] == '"':
            lit, j = _read_string_literal(arg, i)
            parts.append(lit)
            i = j
            continue
        i += 1
    return "".join(parts)


TOKEN_RE = re.compile(r'id\s*=\s*"(?P<sid>[A-Za-z0-9_]+)"|ScriptLine\s*\(')


def parse_catalog(path: Path) -> dict[str, dict[str, list[str]]]:
    """{시나리오ID: {"main": [대사...], "followup": [대사...]}} 를 돌려준다.

    main 의 순서가 곧 파일 순번이다 (AttackerScript.audioFileIndex = 인덱스 + 1).
    """
    src = _strip_comments(path.read_text(encoding="utf-8"))
    scripts: dict[str, dict[str, list[str]]] = {}
    current: str | None = None
    pos = 0
    while True:
        m = TOKEN_RE.search(src, pos)
        if m is None:
            break
        if m.group("sid"):
            current = m.group("sid")
            scripts.setdefault(current, {"main": [], "followup": []})
            pos = m.end()
            continue

        span, end = _balanced_span(src, m.end())
        pos = end
        if current is None:
            raise ValueError("시나리오 id 보다 ScriptLine 이 먼저 나왔습니다")

        args = _top_level_args(span)
        if not args:
            continue
        text = _joined_literals(args[0]).strip()
        if not text:
            continue
        after = any(re.search(r"afterIntervention\s*=\s*true", a) for a in args[1:])
        scripts[current]["followup" if after else "main"].append(text)

    return scripts


def verify_variants() -> list[str]:
    """목소리 ID 가 Kotlin enum 과 같은지 본다.

    한쪽만 고치면 앱이 찾지 않는 폴더에 음성을 쌓게 되고, 그 사실은 세션을 한 번
    돌려 보고 나서야 드러난다.
    """
    if not VOICE_ENUM_PATH.exists():
        return ["AttackerVoice.kt 를 찾을 수 없어 목소리 ID 를 대조하지 못했습니다"]
    src = VOICE_ENUM_PATH.read_text(encoding="utf-8")
    found = set(re.findall(r'\(\s*"([a-z]\d{2})"\s*,\s*\d+\s*,\s*VoiceGender', src))
    mine = {vid for vid, _ in VARIANTS}
    problems = []
    for extra in sorted(mine - found):
        problems.append("이 스크립트에만 있는 목소리: " + extra + " (앱이 재생하지 않습니다)")
    for missing in sorted(found - mine):
        problems.append("앱에만 있는 목소리: " + missing + " (이 스크립트가 만들지 않습니다)")
    return problems


# ── 목소리 매핑 ─────────────────────────────────────────────────────


def init_voices_config() -> None:
    if VOICES_CONFIG.exists():
        print("이미 있습니다: " + str(VOICES_CONFIG.relative_to(PROJECT_ROOT)))
        print("비워 두거나 지운 항목은 생성 대상에서 빠집니다.")
        return
    template = {
        "_읽어주세요": [
            "업체별로, 각 목소리 ID 에 쓸 voice_id 를 채우세요.",
            "-p <업체> --list-voices 로 voice_id 를 볼 수 있습니다.",
            "빈 값으로 두면 그 목소리는 만들지 않습니다 — 6종을 다 채울 필요는 없습니다.",
            "업체마다 voice_id 체계가 달라 칸이 따로 있습니다.",
        ],
    }
    for provider in PROVIDERS:
        template[provider] = {vid: "" for vid, _ in VARIANTS}
    VOICES_CONFIG.write_text(
        json.dumps(template, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print("만들었습니다: " + str(VOICES_CONFIG.relative_to(PROJECT_ROOT)))
    print("열어서 voice_id 를 채운 뒤 다시 실행하세요.")


def load_voices_config(provider: str) -> dict[str, str]:
    if not VOICES_CONFIG.exists():
        raise SystemExit(
            "목소리 매핑 파일이 없습니다: "
            + str(VOICES_CONFIG.relative_to(PROJECT_ROOT))
            + "\n--init-voices 로 틀을 만든 뒤 voice_id 를 채우세요."
        )
    raw = json.loads(VOICES_CONFIG.read_text(encoding="utf-8"))

    if provider in raw:
        table = raw[provider]
    elif "voices" in raw:
        # 업체가 하나였던 때의 구조 — ElevenLabs 로 본다.
        if provider != "elevenlabs":
            raise SystemExit(
                str(VOICES_CONFIG.relative_to(PROJECT_ROOT)) + " 에 \"" + provider
                + "\" 칸이 없습니다. --init-voices 로 새 틀을 보고 그 칸을 추가하세요."
            )
        table = raw["voices"]
    else:
        raise SystemExit(
            str(VOICES_CONFIG.relative_to(PROJECT_ROOT)) + " 에 \"" + provider + "\" 칸이 없습니다."
        )

    mapping: dict[str, str] = {}
    unknown = []
    for key, value in table.items():
        if key.startswith("_"):
            continue
        if key not in VARIANT_LABELS:
            unknown.append(key)
            continue
        if isinstance(value, str) and value.strip():
            mapping[key] = value.strip()
    if unknown:
        print("⚠ 모르는 목소리 ID 는 무시했습니다: " + ", ".join(sorted(unknown)))
    return mapping


# ── ElevenLabs ──────────────────────────────────────────────────────


def _request(url: str, headers: dict, provider: str, data: bytes | None = None) -> bytes:
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:600]
        # 키 자체는 절대 출력하지 않는다.
        raise SystemExit(provider + " 오류 " + str(e.code) + ": " + detail)
    except urllib.error.URLError as e:
        raise SystemExit(provider + " 연결 실패: " + str(e.reason))


# ── ElevenLabs ──


def _eleven_headers(api_key: str, accept: str, post: bool) -> dict:
    headers = {"xi-api-key": api_key, "Accept": accept}
    if post:
        headers["Content-Type"] = "application/json"
    return headers


def _eleven_list_voices(api_key: str) -> list[tuple[str, str, str]]:
    data = json.loads(
        _request(ELEVENLABS_ROOT + "/voices", _eleven_headers(api_key, "application/json", False),
                 "ElevenLabs")
    )
    out = []
    for v in data.get("voices", []):
        labels = v.get("labels") or {}
        tags = ", ".join(str(x) for x in labels.values() if x)
        # ElevenLabs 목소리는 다국어 모델과 함께 쓰므로 언어가 고정되어 있지 않다.
        out.append((str(v.get("voice_id")), "-", str(v.get("name")) + ("  (" + tags + ")" if tags else "")))
    return out


def _eleven_synthesize(api_key, voice_id, model_id, text, profile, no_tags) -> bytes:
    conf = profile["elevenlabs"]
    spoken = text
    if model_id in ("eleven_v3", "eleven_v4") and not no_tags and conf["audio_tag"]:
        spoken = conf["audio_tag"] + " " + text
    url = ELEVENLABS_ROOT + "/text-to-speech/" + voice_id + "?output_format=mp3_44100_128"
    body = json.dumps(
        {"text": spoken, "model_id": model_id, "voice_settings": conf["settings"]},
        ensure_ascii=False,
    ).encode("utf-8")
    audio = _request(url, _eleven_headers(api_key, "audio/mpeg", True), "ElevenLabs", body)
    return audio, spoken


# ── Cartesia ──


def _cartesia_headers(api_key: str, post: bool) -> dict:
    headers = {
        "Authorization": "Bearer " + api_key,
        "Cartesia-Version": CARTESIA_VERSION,
    }
    if post:
        headers["Content-Type"] = "application/json"
    return headers


def _cartesia_list_voices(api_key: str) -> list[tuple[str, str, str]]:
    """(voice_id, 언어, 설명) 목록.

    Cartesia 는 목소리를 **페이지로 나눠 준다** (has_more / next_page). 첫 페이지만 읽으면
    영어 목소리 10개만 보이고, 한국어 목소리가 하나도 없는 것처럼 보인다 — 그대로 믿고
    영어 화자에게 한국어를 읽히면 억양이 섞여 지인 조건이 무너진다.
    """
    items: list[dict] = []
    url = CARTESIA_ROOT + "/voices?limit=100"
    for _ in range(50):  # 안전 상한
        raw = json.loads(_request(url, _cartesia_headers(api_key, False), "Cartesia"))
        if isinstance(raw, list):
            items += raw
            break
        items += raw.get("data", raw.get("voices", []))
        if not (raw.get("has_more") and raw.get("next_page")):
            break
        url = CARTESIA_ROOT + "/voices?limit=100&starting_after=" + str(raw["next_page"])

    out = []
    seen = set()
    for v in items:
        vid = str(v.get("id", v.get("voice_id", "?")))
        if vid in seen:
            continue
        seen.add(vid)
        lang = str(v.get("language", "?"))
        bits = [str(v.get("name", "?"))]
        if v.get("gender"):
            bits.append(str(v["gender"]))
        if v.get("description"):
            bits.append(str(v["description"])[:64])
        out.append((vid, lang, "  ".join(bits)))
    return out


DEEPEN_RATE = 44100


def _deepen_wav(raw: bytes, semitones: float) -> bytes:
    """음높이만 낮춘다. 길이는 호출자가 speed 로 미리 보정해 둔다.

    Cartesia API 에는 pitch 조절이 없다. 그래서 **빠르게 생성한 뒤 느리게 되돌리는**
    방식을 쓴다 — 느리게 되돌리면 음높이가 같은 비율로 내려가고, 미리 그 비율만큼
    빠르게 뽑아 뒀으므로 최종 길이는 원래대로 돌아온다.

    포먼트까지 함께 내려가서 "체격이 큰 사람" 쪽으로 들린다. 그게 여기서 원하는 저음이다.
    """
    try:
        import numpy as np
    except ImportError:
        raise SystemExit("--deepen 에는 numpy 가 필요합니다: pip install numpy")

    import io as _io
    import wave as _wave

    with _wave.open(_io.BytesIO(raw), "rb") as w:
        channels, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        pcm = w.readframes(frames)
    if width != 2:
        raise SystemExit("예상과 다른 WAV 형식입니다 (sampwidth=" + str(width) + ")")

    x = np.frombuffer(pcm, dtype="<i2").astype(np.float64)
    if channels > 1:
        x = x.reshape(-1, channels).mean(axis=1)
    if x.size == 0:
        raise SystemExit("빈 오디오가 돌아왔습니다")

    # FFT 리샘플 — 스펙트럼을 0으로 채워 길이를 늘린다 (scipy.signal.resample 과 같은 방식).
    # scipy 를 쓰지 않는 이유: 이 환경의 scipy.signal 이 BLAS DLL 문제로 import 되지 않는다.
    ratio = 2.0 ** (semitones / 12.0)
    new_len = int(round(x.size * ratio))
    y = np.fft.irfft(np.fft.rfft(x), n=new_len) * (new_len / x.size)

    # FFT 리샘플은 신호가 주기적이라고 가정하므로 양 끝에서 톡 소리가 날 수 있다.
    fade = min(int(rate * 0.005), new_len // 2)
    if fade > 0:
        ramp = np.linspace(0.0, 1.0, fade)
        y[:fade] *= ramp
        y[-fade:] *= ramp[::-1]

    y = np.clip(np.rint(y), -32768, 32767).astype("<i2")

    out = _io.BytesIO()
    with _wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(y.tobytes())
    return out.getvalue()


def _cartesia_synthesize(api_key, voice_id, model_id, text, profile, args) -> bytes:
    conf = profile["cartesia"]
    gen = dict(conf["generation_config"])
    # emotion 의 허용값은 52가지라 여기서 검증하지 않고 그대로 보낸다.
    if args.emotion:
        gen["emotion"] = args.emotion

    deepen = getattr(args, "deepen", 0.0) or 0.0
    if deepen:
        # 느리게 되돌릴 비율만큼 미리 빠르게 뽑는다. speed 상한은 1.5 다.
        ratio = 2.0 ** (deepen / 12.0)
        gen["speed"] = round(min(1.5, gen.get("speed", 1.0) * ratio), 3)
        fmt = {"container": "wav", "sample_rate": DEEPEN_RATE, "encoding": "pcm_s16le"}
    else:
        fmt = {"container": "mp3", "sample_rate": 44100, "bit_rate": 128000}

    body = json.dumps(
        {
            "model_id": model_id,
            "transcript": text,
            "voice": {"id": voice_id},
            "language": "ko",
            "output_format": fmt,
            "generation_config": gen,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    audio = _request(CARTESIA_ROOT + "/tts/bytes", _cartesia_headers(api_key, True), "Cartesia", body)
    if deepen:
        audio = _deepen_wav(audio, deepen)
    return audio, text


# ── 공통 창구 ──


def list_voices(provider: str, api_key: str, lang: str = "") -> None:
    rows = (_cartesia_list_voices if provider == "cartesia" else _eleven_list_voices)(api_key)
    print(PROVIDERS[provider]["label"])
    print("쓸 수 있는 목소리 " + str(len(rows)) + "개")

    if lang:
        kept = [r for r in rows if r[1].lower().startswith(lang.lower())]
        print("그중 " + lang + " 목소리 " + str(len(kept)) + "개\n")
        rows = kept
    else:
        tally: dict[str, int] = {}
        for _, l, _d in rows:
            tally[l] = tally.get(l, 0) + 1
        print("언어 분포: " + ", ".join(k + " " + str(v) for k, v in sorted(tally.items()))[:300])
        print("")

    for vid, l, desc in rows:
        print("  " + vid)
        print("      [" + l + "] " + desc)
    print("")
    print("한국어 대사는 **한국어 목소리**로 읽히세요 (--lang ko). 다국어 모델이라 영어 목소리도")
    print("한국어를 읽지만, 억양이 섞여 지인 조건이 무너집니다.")
    print("연령대·성별을 보고 6종에 배정하세요:")
    for vid, label in VARIANTS:
        print("  " + vid + " = " + label)
    print("")
    print("고른 voice_id 를 " + str(VOICES_CONFIG.relative_to(PROJECT_ROOT))
          + " 의 \"" + provider + "\" 칸에 적으세요.")


def synthesize(provider, api_key, voice_id, model_id, text, profile, args) -> tuple[bytes, str]:
    """(오디오 바이트, 실제로 보낸 문장) — 보낸 문장은 manifest 에 남긴다."""
    if provider == "cartesia":
        return _cartesia_synthesize(api_key, voice_id, model_id, text, profile, args)
    return _eleven_synthesize(api_key, voice_id, model_id, text, profile, args.no_audio_tags)


def output_ext(args) -> str:
    """저음 변환은 WAV 로 나간다 (중간에 mp3 로 되돌릴 디코더가 없다)."""
    return "wav" if (getattr(args, "deepen", 0.0) or 0.0) else "mp3"


# ── 후처리 ──────────────────────────────────────────────────────────


def apply_telephone_filter(path: Path) -> bool:
    """전화 대역(300~3400Hz)으로 깎아 통화처럼 들리게 한다. ffmpeg 없으면 건너뛴다."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return False
    tmp = path.with_suffix(".filtered.mp3")
    cmd = [
        ffmpeg, "-y", "-loglevel", "error", "-i", str(path),
        "-af", "highpass=f=300,lowpass=f=3400,acompressor=threshold=-18dB:ratio=3,volume=2dB",
        "-ac", "1", "-ar", "44100", str(tmp),
    ]
    if subprocess.run(cmd).returncode != 0:
        tmp.unlink(missing_ok=True)
        return False
    tmp.replace(path)
    return True


# ── 경로 · manifest ─────────────────────────────────────────────────


def voice_dir(script_id: str, variant: str) -> Path:
    return ASSETS_ROOT / script_id / variant


def manifest_path(script_id: str, variant: str) -> Path:
    return voice_dir(script_id, variant) / "manifest.json"


def load_manifest(script_id: str, variant: str) -> dict:
    p = manifest_path(script_id, variant)
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return {}


def expected_stems(lines: dict) -> list[tuple[str, str]]:
    """[(파일명 어간, 문장)] — 앱의 AttackerVoiceAssets 와 같은 규칙."""
    stems = [(str(i), t) for i, t in enumerate(lines["main"], start=1)]
    stems += [("f" + str(i), t) for i, t in enumerate(lines["followup"], start=1)]
    return stems


def existing_asset(script_id: str, variant: str, stem: str) -> Path | None:
    """앱이 실제로 고를 파일 — 확장자 우선순위가 앞선 것이 이긴다."""
    for ext in ASSET_EXTENSIONS:
        p = voice_dir(script_id, variant) / (stem + "." + ext)
        if p.exists():
            return p
    return None


def make_sample(args, scripts: dict) -> int:
    """목소리 하나를 실제 대사로 한 줄만 뽑아 들어 본다.

    6종을 다 만들기 전에 "이 목소리가 쓸 만한가"를 먼저 판단하기 위한 모드다.
    voices.json 을 보지 않고, assets 를 건드리지 않는다.
    """
    if not args.voice_id:
        print("--voice-id 로 들어 볼 voice_id 를 주세요 (-p " + args.provider
              + " --list-voices 로 확인).", file=sys.stderr)
        return 1
    if not args.api_key:
        print("API 키가 없습니다. 환경변수 " + PROVIDERS[args.provider]["env"] + " 를 설정하세요.",
              file=sys.stderr)
        return 1

    sid = args.only or sorted(scripts.keys())[0]
    lines = scripts.get(sid)
    if not lines or not lines["main"]:
        print("대사를 찾을 수 없습니다: " + sid, file=sys.stderr)
        return 1

    # 기본값은 **실제 실험 대사**다. 아무 문장이나 들어 보면 판단이 어긋난다 —
    # 짧은 인사말은 어떤 모델이든 자연스럽게 들린다.
    text = args.sample_text or lines["main"][0]
    profile = profile_of(sid)

    print("업체: " + PROVIDERS[args.provider]["label"])
    print("모델: " + args.model)
    print("시나리오: " + sid + " (" + profile["note"] + ")")
    print("문장(" + str(len(text)) + "자): " + text)
    print("예상 비용: 약 $"
          + format(len(text) / 1_000_000 * PROVIDERS[args.provider]["prices"][args.model], ".4f"))
    print("")

    audio, _ = synthesize(
        args.provider, args.api_key, args.voice_id, args.model, text, profile, args
    )

    if args.sample_out:
        out = Path(args.sample_out)
    else:
        out = SAMPLES_DIR / (
            "sample_" + sid + "_" + args.provider + "_" + args.model + "_"
            + re.sub(r"[^A-Za-z0-9]", "", args.voice_id)[:8]
            + (("_deep" + str(args.deepen)) if args.deepen else "")
            + "." + output_ext(args)
        )
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(audio)

    if args.telephone and not apply_telephone_filter(out):
        print("(ffmpeg 이 없어 전화 대역 필터를 건너뜀)")

    print("만들었습니다: " + str(out))
    print("")
    print("쓸 만하면 이 voice_id 를 " + str(VOICES_CONFIG.relative_to(PROJECT_ROOT))
          + " 의 \"" + args.provider + "\" 칸에 적고, -p " + args.provider
          + " 로 전체를 만드세요.")
    return 0


def check_stale(scripts: dict) -> int:
    """앱이 실제로 재생할 파일이 지금 대본과 같은 문장인지, 목소리별로 본다."""
    problems = 0

    for problem in verify_variants():
        print("⚠ " + problem)
        problems += 1
    if problems:
        print("")

    for sid, lines in sorted(scripts.items()):
        stems = expected_stems(lines)
        print("[" + sid + "] 개입 전 " + str(len(lines["main"])) + "줄 · 후속 "
              + str(len(lines["followup"])) + "줄")

        for variant, label in VARIANTS:
            entries = {e["file"]: e for e in load_manifest(sid, variant).get("files", [])}
            missing: list[str] = []
            mismatch: list[str] = []
            unknown: list[str] = []
            for stem, text in stems:
                found = existing_asset(sid, variant, stem)
                if found is None:
                    missing.append(stem)
                elif found.name not in entries:
                    unknown.append(found.name)
                elif entries[found.name].get("text") != text:
                    mismatch.append(found.name)

            main_total = len(lines["main"])
            main_missing = sum(1 for s in missing if not s.startswith("f"))
            head = "   " + variant + "  " + label + "  "

            if len(missing) == len(stems):
                print(head + "없음")
                continue
            if mismatch:
                print(head + "⚠ 대본과 어긋남: " + ", ".join(mismatch))
                for name in mismatch:
                    text = dict(stems)[name.rsplit(".", 1)[0]]
                    print("        음성: " + entries[name].get("text", "")[:48] + "...")
                    print("        대본: " + text[:48] + "...")
                problems += 1
            elif main_missing:
                print(head + "⚠ 개입 전 대사 " + str(main_total - main_missing) + "/"
                      + str(main_total) + " — 나머지는 시스템 TTS로 나갑니다")
                problems += 1
            elif missing:
                print(head + "개입 전 완비 · 후속 " + str(len(lines["followup"]) - len(missing))
                      + "/" + str(len(lines["followup"])))
            else:
                print(head + "완비")

            if unknown:
                print("        (이 스크립트가 만들지 않은 파일: " + ", ".join(unknown)
                      + " — 문장을 확인할 수 없습니다)")

        # 목소리 폴더로 옮기기 전의 구조로 남은 파일은 이제 재생되지 않는다.
        flat = [
            p.name
            for p in sorted((ASSETS_ROOT / sid).glob("*"))
            if p.is_file() and p.suffix.lstrip(".") in ASSET_EXTENSIONS
        ]
        if flat:
            print("   ⚠ 예전 구조(목소리 폴더 없음)로 남은 파일: " + ", ".join(flat))
            print("     이제 attacker/" + sid + "/<목소리ID>/ 만 재생됩니다 — 옮기거나 지우세요.")
            problems += 1
        print("")

    return problems


# ── 메인 ────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(
        description="AttackerScriptCatalog.kt 의 대사를 목소리 6종으로 만들어 assets 에 넣는다",
    )
    ap.add_argument("-p", "--provider", default="cartesia", choices=sorted(PROVIDERS),
                    help="어느 업체로 만들까 (기본 cartesia)")
    ap.add_argument("--api-key", default="",
                    help="기본값은 업체별 환경변수 (CARTESIA_API_KEY / ELEVENLABS_API_KEY) — "
                         "셸 기록에 남지 않게 환경변수를 권장")
    ap.add_argument("--init-voices", action="store_true",
                    help="tools/voices.json 틀을 만들고 끝낸다")
    ap.add_argument("--list-voices", action="store_true", help="계정의 목소리 목록만 출력하고 끝낸다")
    ap.add_argument("--lang", default="",
                    help="--list-voices 를 이 언어로 거른다 (예: ko). 한국어 대사는 한국어 목소리로")
    ap.add_argument("--sample", action="store_true",
                    help="목소리 하나를 실제 대사 한 줄로 뽑아 tools/samples/ 에 저장한다 "
                         "(assets 를 건드리지 않는다). --voice-id 와 함께 쓴다")
    ap.add_argument("--voice-id", default="",
                    help="--sample 로 들어 볼 voice_id (해당 업체의 것)")
    ap.add_argument("--sample-text", default="",
                    help="--sample 에서 읽을 문장. 비우면 그 시나리오의 첫 대사를 쓴다")
    ap.add_argument("--sample-out", default="", help="--sample 결과를 저장할 경로")
    ap.add_argument("--check", action="store_true",
                    help="생성하지 않고, 목소리별 녹음본이 지금 대본과 맞는지만 본다")
    ap.add_argument("--variants", default="",
                    help="만들 목소리만 콤마로 (예: m30,f40). 비우면 voices.json 에 채워진 전부")
    ap.add_argument("--model", default="",
                    choices=[""] + ALL_MODELS,
                    help="비우면 업체 기본 모델 (cartesia=sonic-3.6, elevenlabs=eleven_v3)")
    ap.add_argument("--emotion", default="",
                    help="Cartesia 전용 — generation_config.emotion 에 그대로 보낸다 (52가지)")
    ap.add_argument("--deepen", type=float, default=0.0, metavar="반음",
                    help="Cartesia 전용 — 목소리를 이만큼 낮춘다 (예: 2.5). API 에 pitch 조절이 "
                         "없어 빠르게 뽑은 뒤 느리게 되돌리는 방식이며, 결과는 .wav 로 나온다. "
                         "numpy 필요")
    ap.add_argument("--only", default="", help="특정 시나리오만 (예: S2)")
    ap.add_argument("--no-followups", action="store_true",
                    help="개입 후 후속 대사(f1...)를 만들지 않는다. 단 그러면 참가자가 "
                         "[통화 이어가기]를 누른 직후 목소리가 시스템 TTS로 바뀐다")
    ap.add_argument("--no-audio-tags", action="store_true", help="오디오 태그를 붙이지 않는다")
    ap.add_argument("--telephone", action="store_true", help="전화 대역 필터를 건다 (ffmpeg 필요)")
    ap.add_argument("--replace-existing", action="store_true",
                    help="같은 순번의 다른 확장자 파일(예: 예전 1.m4a)을 지운다")
    ap.add_argument("--yes", action="store_true", help="비용 확인을 묻지 않는다")
    args = ap.parse_args()

    if args.init_voices:
        init_voices_config()
        return 0

    # 모델을 주면 그 모델의 업체를 따른다 — "-p cartesia --model eleven_v3" 처럼 엇갈린
    # 조합으로 엉뚱한 곳에 요청을 보내 실패하는 일을 막는다.
    spec = PROVIDERS[args.provider]
    if args.model:
        owner = provider_of_model(args.model)
        if owner != args.provider:
            print("모델 " + args.model + " 은 " + str(owner) + " 의 모델입니다. "
                  + "-p " + str(owner) + " 로 주세요.", file=sys.stderr)
            return 1
    else:
        args.model = spec["default_model"]

    if not args.api_key:
        args.api_key = os.environ.get(spec["env"], "")

    if not CATALOG_PATH.exists():
        print("대본 파일을 찾을 수 없습니다: " + str(CATALOG_PATH), file=sys.stderr)
        return 1

    scripts = parse_catalog(CATALOG_PATH)
    if args.only:
        scripts = {k: v for k, v in scripts.items() if k == args.only}
        if not scripts:
            print("그런 시나리오가 없습니다: " + args.only, file=sys.stderr)
            return 1

    if args.check:
        return 1 if check_stale(scripts) else 0

    if args.sample:
        return make_sample(args, scripts)

    if args.list_voices:
        if not args.api_key:
            print("API 키가 없습니다. 환경변수 " + spec["env"] + " 를 설정하세요.", file=sys.stderr)
            return 1
        list_voices(args.provider, args.api_key, args.lang)
        return 0

    for problem in verify_variants():
        print("⚠ " + problem, file=sys.stderr)

    mapping = load_voices_config(args.provider)
    if args.variants:
        wanted = [v.strip() for v in args.variants.split(",") if v.strip()]
        bad = [v for v in wanted if v not in VARIANT_LABELS]
        if bad:
            print("모르는 목소리 ID: " + ", ".join(bad), file=sys.stderr)
            print("가능한 값: " + ", ".join(v for v, _ in VARIANTS), file=sys.stderr)
            return 1
        unset = [v for v in wanted if v not in mapping]
        if unset:
            print("voices.json 의 \"" + args.provider + "\" 칸에 voice_id 가 비어 있습니다: "
                  + ", ".join(unset), file=sys.stderr)
            return 1
        variants = wanted
    else:
        variants = [v for v, _ in VARIANTS if v in mapping]

    if not variants:
        print("만들 목소리가 없습니다. " + str(VOICES_CONFIG.relative_to(PROJECT_ROOT))
              + " 의 \"" + args.provider + "\" 칸에 voice_id 를 채우세요.", file=sys.stderr)
        return 1

    if not args.api_key:
        print("API 키가 없습니다. 환경변수 " + spec["env"] + " 를 설정하거나 --api-key 를 주세요.",
              file=sys.stderr)
        return 1

    # 만들 목록을 먼저 확정한다 — 비용을 보여 주고 물어보기 위해.
    ext = output_ext(args)
    jobs: list[tuple[str, str, str, str]] = []  # (시나리오, 목소리, 파일명, 문장)
    for sid, lines in sorted(scripts.items()):
        stems = expected_stems(lines)
        if args.no_followups:
            stems = [(s, t) for s, t in stems if not s.startswith("f")]
        for variant in variants:
            for stem, text in stems:
                jobs.append((sid, variant, stem + "." + ext, text))

    total_chars = sum(len(t) for _, _, _, t in jobs)
    est = total_chars / 1_000_000 * spec["prices"][args.model]

    print("업체: " + spec["label"])
    print("모델: " + args.model)
    print("목소리: " + ", ".join(v + "(" + VARIANT_LABELS[v] + ")" for v in variants))
    print("시나리오: " + ", ".join(sorted(scripts.keys())))
    print("만들 파일: " + str(len(jobs)) + "개, 전체 " + str(total_chars) + "자")
    print("예상 비용: 약 $" + format(est, ".3f") + " (어림값 — 실제 청구는 크레딧 기준)")
    print("")
    for sid, variant, name, text in jobs:
        print("  " + sid + "/" + variant + "/" + name + "  "
              + text[:40] + ("..." if len(text) > 40 else ""))
    print("")

    # 같은 순번에 우선순위가 높은 확장자가 남아 있으면, 새로 만든 mp3가 묻힌다.
    collisions: list[Path] = []
    for sid, variant, name, _ in jobs:
        stem = name.rsplit(".", 1)[0]
        for other in ASSET_EXTENSIONS:
            if other == ext:
                continue
            p = voice_dir(sid, variant) / (stem + "." + other)
            if p.exists():
                collisions.append(p)
    if collisions:
        print("같은 순번의 다른 확장자 파일이 있습니다. 앱은 m4a > mp3 > wav 순으로 찾으므로")
        print("이대로 두면 새로 만든 ." + ext + " 가 재생되지 않습니다:")
        for p in collisions:
            print("   " + str(p.relative_to(PROJECT_ROOT)))
        if not args.replace_existing:
            print("\n--replace-existing 를 주면 이 파일들을 지우고 진행합니다.")
            return 1
        print("")

    if not args.yes:
        try:
            if input("진행할까요? [y/N] ").strip().lower() not in ("y", "yes"):
                print("취소했습니다.")
                return 0
        except EOFError:
            print("확인을 받을 수 없습니다. --yes 를 주세요.", file=sys.stderr)
            return 1

    if args.replace_existing:
        for p in collisions:
            p.unlink()
            print("지움: " + str(p.relative_to(PROJECT_ROOT)))

    # (시나리오, 목소리) -> manifest 항목
    generated: dict[tuple[str, str], list[dict]] = {}
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")

    for sid, variant, name, text in jobs:
        profile = profile_of(sid)
        # spoken 은 실제로 업체에 보낸 문장이다 (ElevenLabs 는 앞에 연기 태그가 붙는다).
        # manifest 의 text 에는 **원문**을 남겨야 --check 가 대본과 비교할 수 있다.
        audio, spoken = synthesize(
            args.provider, args.api_key, mapping[variant], args.model, text, profile, args
        )
        out = voice_dir(sid, variant) / name
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(audio)

        filtered = apply_telephone_filter(out) if args.telephone else False
        if args.telephone and not filtered:
            print("   (ffmpeg 이 없어 전화 대역 필터를 건너뜀)")

        print("만듦: " + sid + "/" + variant + "/" + name + "  " + str(len(audio) // 1024) + "KB")
        generated.setdefault((sid, variant), []).append({
            "file": name,
            "text": text,
            "spoken": spoken,
            "provider": args.provider,
            "voice_id": mapping[variant],
            "model_id": args.model,
            "tuning": profile[args.provider],
            # 저음 변환 반음 수 — 재현에 필요하다. 0 이면 변환하지 않은 원본이다.
            "deepen_semitones": args.deepen,
            "telephone_filter": filtered,
            "generated_at": now,
        })

    for (sid, variant), files in generated.items():
        manifest_path(sid, variant).write_text(
            json.dumps(
                {
                    "scriptId": sid,
                    "voiceId": variant,
                    "voiceLabel": VARIANT_LABELS[variant],
                    "provider": args.provider,
                    "note": profile_of(sid)["note"],
                    "files": files,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    print("")
    print("끝났습니다. 다음을 확인하세요:")
    print("  1) 앱 재빌드 (assets 는 빌드 때 APK에 들어갑니다)")
    print("  2) 설정 화면 ④에서 만든 목소리가 '녹음본 완비'로 보이는지")
    print("  3) 설정 화면 ⑤의 재생 방식을 '녹음본 재생'으로 — 다른 모드면 목소리가 적용되지 않습니다")
    print("  4) 세션 로그의 ttsFallbackCount 가 0 인지")
    print("  5) 대본을 고친 뒤에는 --check 로 음성이 옛 문장으로 남아 있지 않은지 점검")
    return 0


if __name__ == "__main__":
    sys.exit(main())
