# Science channel — 사이언스 픽셀

Channel ID: `science`. Container: `shorts-science-controller`.
Sources: Wired, Science Daily, Nature, Hacker News.

Schedule: hourly batch at `:10, :40` (KST). Daily limit: 10 shorts.

## Voice + prosody

Default Edge TTS settings (no horror-style override):

- Voice `ko-KR-SunHiNeural` (female, Friendly+Positive)
- Rate `+30%` (news pace), Pitch `+0Hz`, Volume `+0%`
- BGM mix volume `0.20` (ambient bed under voice)
- chars/sec hint: `8.0`

No FFmpeg post-EQ (only horror gets the warm close-mic recipe).

## Prompt v7 — research-grounded narrative architecture

Map the 14 scenes as **2-9-2-1** (Hook-Body-Implication-Outro). Pick ONE
body structure per video — never blend.

- **Hook (1-2)** — counter-intuitive claim / sincere weird question /
  specific number. NO greetings, NO "오늘 알아볼 주제는".
- **Body (3-11, nine scenes)** — pick ONE:
  - **Problem → Mechanism → Implication (NMI)**: the news-driven default.
  - **Story-of-discovery**: named scientist + place + insight moment.
  - **Scale comparison**: anchor abstract numbers to physical reference.
  
  Sustain ONE extended analogy across at least 4 body scenes (Kurzgesagt
  signature). Don't introduce a new analogy mid-flight.
- **Implication (12-13)** — what this means for daily life or the next
  10 years. Forward-looking but earnest.
- **Outro (14)** — question to viewer / future-tease / callback to hook.

Sentence length: **24-36 chars/scene, target ~30**. At 8.0 chars/s this
lands at 14×30/8.0 ≈ 52s rendered audio.

## Voice register rules

Primary register: **합니다체** (-습니다 / -입니다 / -합니다) for ~85-90%
of scenes. May use `-거든요` / `-죠` / `-네요` in scenes 1-2 (hook reaction)
or scene 14 (outro) for intimacy — max 2 such scenes per script. NEVER
use 반말 / 평서체 (-다, -야, -해, -지).

## Banned hook words (YouTube egregious-clickbait policy)

`충격` / `헉` / `소름` / `미친` / `대박` / `실화냐` — flagged by both
YouTube's policy and the 2025 AI-content monetization policy. Replace
with `의외로` / `사실은` / `알고 보면` for softer curiosity.

## Title formula menu

Pick ONE per video, ≤40 chars, front-load the payoff. Numbers in 아라비아
digits (10년 not 십년):

1. Reversal: "X가 사실은 Y였습니다"
2. Time-pressure: "10년 안에 사라질 ~"
3. Counter-question: "왜 ~는 ~할까요?"
4. Scale anchor: "지구보다 N배 큰 ~"
5. Recent breakthrough: "이번 주 발표된 ~"
6. Specific-number claim: "단 0.001초의 비밀"
7. Personal stakes: "당신의 X가 사실은 ~"
8. Open mystery: "아직 아무도 풀지 못한 ~"
9. Negation/warning: "~하면 절대 안 되는 이유"
10. Authority anchor: "NASA가 발견한 ~", "MIT 연구팀이 만든 ~"

## Pexels keyword convention

Each scene's `keyword` field becomes a Pexels search; scene 1's keyword
is the thumbnail. Concrete physical noun + at most 1 visual modifier
(lighting/color):

- Good: `telescope`, `microscope`, `neuron`, `galaxy`, `dna`, `lab`,
  `spacecraft`, `robot arm`, `circuit board`, `dark lab`, `neon brain`
- Bad: `innovation`, `discovery`, `future`, `amazing`, `exciting`
- For face-bias: append `portrait` or `close-up`

## Research backing

- [Dahlstrom 2014, PNAS](https://www.pnas.org/doi/10.1073/pnas.1320645111) —
  narrative > expository for non-experts
- [Bullock et al. — jargon disrupts processing fluency](https://pubmed.ncbi.nlm.nih.gov/31354058/)
- Kurzgesagt 4-part / Vsauce 3-act / MIT ABT method analyses
- Korean reference channels: 1분과학 (rap-cadence pace), 안될과학 (expert
  register), 과학쿠키 (warmer discovery framing)
