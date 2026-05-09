# History channel — 메모리 픽셀

Channel ID: `history`. Container: `shorts-history-controller`.
Source: Wikipedia "On This Day" — every day produces ONE Short about a
historical event that happened on that calendar date.

Schedule: daily batch at KST 06:30, upload window 07:00-08:00 (every
10 min check). Daily limit: 1. Long-form (1920×1080).

## Voice + prosody

Default Edge TTS (no horror-style override):
SunHi / +30% / +0Hz / +0% / BGM 0.20 / chars 8.0.

## Prompt v7 — 5-beat causal chain + Wineburg 3-principles

Map the 14 scenes as **2-3-3-3-2-1** (Hook-Setup-Escalation-Climax-Aftermath-Outro).
Pick ONE named protagonist whose decisions/experience drive ≥8 of the
14 scenes.

- **Hook (1-2)** — date-anchored stake ("1597년 9월 16일, 단 12척으로
  333척과 마주한 자가 있었습니다") OR "정확히 N년 전 오늘". Scene 2 =
  name protagonist + impossible situation.
- **Setup (3-5)** — the world before the spark. WHEN/WHERE in concrete
  terms. Apply **CONTEXTUALIZATION**: what did the actors then believe?
  Avoid presentism — never judge medieval/colonial actors by 2026 morality.
- **Escalation (6-8)** — spark + immediate consequence. Use **MATERIAL
  grounding** (Megan Kate Nelson): name objects, weather, food, faces.
  ONE concrete event per scene in chronological sequence.
- **Climax (9-11)** — the decision moment. Show the protagonist's CHOICE,
  not just what happened. Active verbs (`결정했습니다`, `거절했습니다`,
  `마주했습니다`), NOT passive (`일어났습니다`). Death tolls: state ONCE,
  don't dwell ("약 X만 명이 희생되었습니다").
- **Aftermath (12-13)** — what changed. Apply **CORROBORATION**: stop
  where the historical record stops. If contested, signal it ("학계는
  OOO으로 보지만").
- **Outro (14)** — voice-of-the-time perspective ("당시 사람들에게 이
  사건은 ~"), modern parallel (sparingly, 1 in 5 max), or open question.
  Optionally end with "과거를 기억하는 곳, 메모리 픽셀이었습니다."

Sentence length: **24-36 chars/scene, target ~30** (tightened from
previous 33-43 to fit the 45-60s sweet spot).

## 외래어 표기법 (Wikipedia standard)

- **Pre-modern Chinese**: Sino-Korean reading (공자 / 진시황), NOT Pinyin
- **Modern Chinese**: Mandarin pronunciation (마오쩌둥, NOT 모택동)
- **Japanese (all eras)**: Japanese pronunciation (도요토미 히데요시,
  NOT 풍신수길)
- **Western**: 외래어 표기법 (나폴레옹, 처칠)
- Drop hanja parentheticals in narration; reserve for title/description

## Sensitive-topic discipline (식민지 / 전쟁 / 정치)

- Use SPECIFIC actors and dated facts, not blanket emotional adjectives.
  "1919년 3월 1일, 33인이 태화관에서 독립선언서를 낭독했습니다" beats
  "잔혹한 일제는...".
- For colonial-era topics, name **KOREAN AGENCY** explicitly (resisters,
  decisions) — don't position Koreans only as victims.
- For post-1948 contemporary politics, multiple perspectives or stop at
  the factual record. Avoid editorializing in the outro.
- War/death tolls: state ONCE, don't dwell. Concrete, not lurid.
- NEVER 충격 / 헉 / 소름 / 대박 (egregious-clickbait policy).
- NO presentism: never "현대인이라면 절대 하지 않을" or "야만적이고 무지한
  시대" framing.
- NO mono-causation overconfidence: signal compression with "가장 큰 원인
  중 하나는...".

## Korean : World ratio over time

Roughly **5:2 or 4:3 Korean-to-world**. Korean history outperforms world
history on Korean-language channels, but world history with a Korean
angle (Korean diaspora, Korea-adjacent events) extends reach without
alienating either pool.

## Title formula menu

Date-prominent or name-prominent. Numbers in 아라비아. One bracketed
prefix max. Banned: 충격 / 헉.

1. Date-anchored stake: "1592년 오늘, 조선이 무너지기 시작했습니다"
2. "오늘 X년 전": "정확히 432년 전 오늘, 한반도는 ___"
3. Person + verb-of-consequence: "이순신이 만든 23전 23승의 진짜 비밀"
4. Superlative: "역사상 가장 짧았던 전쟁"
5. Body-count opener: "12분 만에 5만 명이 사라진 날"
6. Counterfactual: "이 한 통의 편지가 없었다면"
7. Forgotten-cause reveal: "아무도 모르는, 임진왜란을 바꾼 한 사람"
8. "OOO이 만든 OOO의 운명": "세종이 만든 한글, 500년이 걸린 이유"
9. Bracketed date prefix: "[오늘의 역사] 1945년 8월 15일, 우리가 모르는 12시간"
10. Question hook: "왜 1894년 농민들은 죽음을 각오했을까요?"
11. Modern-parallel hook (use sparingly): "2026년 한국이 닮아가는 1929년의 그날"

## Pexels keyword convention

Concrete artifacts/symbols: `scroll`, `sword`, `ancient ruins`, `stone
statue`, `old map`, `throne`, `battlefield`, `candle`, `vintage portrait`,
`sepia photograph`. For Korean topics: `korean palace`, `joseon costume`,
`hangul scroll`, `buddha statue`, `dmz` — but expect mixed Pexels results;
fall back to `asian temple`, `ancient warrior` etc. Avoid abstracts
(`history`, `war`, `revolution`, `freedom`).

## Research backing

- [Sam Wineburg, Historical Thinking and Other Unnatural Acts](https://www.amazon.com/Historical-Thinking-Other-Unnatural-Acts/dp/1566398568) — sourcing / contextualizing / corroborating
- [Stanford History Education Group](https://historicalthinkingmatters.org/why/)
- [Megan Kate Nelson — material grounding](https://megankatenelson.com/)
- [Presentism — Wikipedia](https://en.wikipedia.org/wiki/Presentism_(historical_analysis))
- Korean reference channels: 최태성 1TV (warm classroom tone), 토크멘터리
  전쟁사 (conversational expert dialogue), Knowledgia (tight question-format).
  Avoid the [황현필 비판 패턴](https://namu.wiki/w/%ED%99%A9%ED%98%84%ED%95%84/%EB%B9%84%ED%8C%90%20%EB%B0%8F%20%EB%85%BC%EB%9E%80) — over-emotional anti-Japanese framing alienates younger viewers.
