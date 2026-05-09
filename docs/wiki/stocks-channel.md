# Stocks channel — 밸류 픽셀

Channel ID: `stocks`. Container: `shorts-stocks-controller`.
Sources: MarketWatch, CNBC Finance, Reuters Business, plus Google News
RSS for trending tickers.

Schedule: daily batch at KST 17:30, upload window 18:00-20:00 (every
10 min check). Daily limit: 1 video. Long-form (1920×1080).

## Voice + prosody

Default Edge TTS (no horror-style override):
SunHi / +30% / +0Hz / +0% / BGM 0.20 / chars 8.0.

## Async flow (the only channel using it)

`useAsyncFlow=true` → `BatchScheduler.triggerBatchJob` publishes
`StockDiscoveryRequestedEvent` instead of running the standard RSS batch.
[`StockDiscoveryConsumer`](../../backend/shorts-api/src/main/kotlin/com/sciencepixel/consumer/StockDiscoveryConsumer.kt)
then branches:

- **Morning briefing path** — between 06:00 and 08:30 KST, runs
  `backend/market-collector/collector.py` (Python, scrapes overnight US
  market data into JSON), then `geminiService.writeMorningBriefingScript`.
- **Deep-dive path** (default) — calls `ContentProviderService.fetchStockNews`
  and takes the first item.

Both paths converge into the same `ScriptCreatedEvent` chain.

## 24h freshness window (commit `7a28bef`)

`fetchStockNews` enforces `cutoff = now - 24h` uniformly across:

1. Base business RSS (MarketWatch / CNBC / Reuters)
2. Headlines fed to `geminiService.extractTrendingTickers`
3. Each per-ticker Google News RSS pull

If nothing fresh comes back, the function returns `emptyList()` and the
caller skips generation rather than falling back to a stale story. This
replaced the legacy `startOfDay` filter that gave a 1-hour window if
batch ran near midnight, and that bypassed the per-ticker filter
entirely (caused 8 stale `COMPLETED` videos 145h-1401h old before the
fix).

See [log.md 2026-05-09 stocks 24h](log.md).

## Prompt v7 — NCI structure (Number → Cause → Implication)

Map the 14 scenes as **2-3-5-2-2** (Hook-Number-Cause-Implication-Outro):

- **Hook (1-2)** — 한글 회사명 + signed % + timeframe ("엔비디아 오늘 7%
  폭등했습니다") OR contrarian / earnings-surprise / record-threshold.
  No greetings. Scene 2 = orient (which exchange / session / move type).
- **Number (3-5)** — concrete data only, attributed to source (시총, PER,
  EPS, 골드만삭스 목표가). NO opinions yet.
- **Cause (6-10)** — chain: catalyst → mechanism → who's buying. At
  scene 8-9 deploy a "여기서 한 가지" twist (under-headline detail).
- **Implication (11-12)** — sector ripple + what to watch next. Use
  `주목됩니다` / `가능성이 있습니다` / `변수입니다`. NEVER `100%` /
  `확실합니다` / `보장`.
- **Outro (13-14)** —
  - **13**: compressed 면책 line ("본 영상은 단순 정보 제공 목적이며
    투자 권유가 아닙니다." OR "투자 판단과 책임은 본인에게 있습니다.")
  - **14**: watchlist tease + 밸류 픽셀이었습니다.

Sentence length: **24-36 chars/scene, target ~30**. ~52s rendered audio.

## Korean retail-investor compliance

The FSC's interpretation: ad-only YouTube channels (no paid memberships,
no individual paid consultations) **don't need** 유사투자자문업
registration. But they MUST avoid 부당권유, 단정적 판단 ("100% 오릅니다",
"필승"), and 목표 수익률 약속. 원금 손실 가능성 disclosure required.

Banned phrases enforced in prompt: `100% 오릅니다` / `확정` / `필승` /
`지금 사세요` / `절대 떨어지지 않습니다` / `보장합니다` / `강력 추천`.

Required scene 13 disclaimer phrasing (one of):

- "본 영상은 단순 정보 제공 목적이며 투자 권유가 아닙니다."
- "투자 판단과 책임은 본인에게 있습니다."

Sources:
- [Barber & Odean 2008 RFS — All That Glitters](https://faculty.haas.berkeley.edu/odean/papers%20current%20versions/allthatglitters_rfs_2008.pdf)
- [Barber et al. 2022 J. Finance — Attention-Induced Trading](https://onlinelibrary.wiley.com/doi/abs/10.1111/jofi.13183)
- [FSC 법령해석 유사투자자문업 신고 필요 여부](https://better.fsc.go.kr/fsc_new/replyCase/LawreqDetail.do?stNo=11&muNo=171&muGpNo=75&lawreqIdx=3509)
- [KOFIA 광고선전에관한지침](https://law.kofia.or.kr/service/law/lawFullScreenContent.do?seq=216&historySeq=591)

## Naming convention

한글 회사명 in narration (엔비디아 / 테슬라 / 애플 / 마이크로소프트 /
팔란티어). Tickers (NVDA / TSLA) only in description hashtags or as a
thumbnail badge — Korean viewers parse Hangul faster on mobile.

## Title + Pexels keyword

12 title formulas in [`DataInitializer`](../../backend/shorts-api/src/main/kotlin/com/sciencepixel/config/DataInitializer.kt)
stocks prompt — number-led, source-attributed, neutral verbs. Date stamp
early ("[11/9 마감 기준]" or "오늘"). Pexels keywords: `stock chart`,
`trading screen`, `wall street`, `bull statue`, `skyscraper`, `trader
portrait`, `dollar bills`. Avoid abstracts like `wealth` / `success` /
`money` (return generic stock).
