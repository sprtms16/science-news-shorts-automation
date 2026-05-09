# `ChannelBehavior` interface

Single Spring DI abstraction that captures every per-channel difference.
Implementations are activated by `@ConditionalOnProperty(SHORTS_CHANNEL_ID=...)`.

File: [`backend/shorts-core/.../config/ChannelBehavior.kt`](../../backend/shorts-core/src/main/kotlin/com/sciencepixel/config/ChannelBehavior.kt)

## Implementations

| `SHORTS_CHANNEL_ID` | Class | Used in |
|---|---|---|
| `science` (default) | `DefaultChannelBehavior` | `shorts-science-controller` |
| `horror` | `HorrorChannelBehavior` | `shorts-horror-controller` |
| `stocks` | `StocksChannelBehavior` | `shorts-stocks-controller` |
| `history` | `HistoryChannelBehavior` | `shorts-history-controller` |
| `renderer` | `RendererChannelBehavior` | `shorts-renderer` (worker) |

## Per-instance properties (read by code in the same channel container)

- `channelId`, `channelName`
- `isLongForm` (`true` for stocks/history landscape 1920×1080, `false` for
  Shorts 1080×1920)
- `dailyLimit` (`10` shorts channels, `1` long-form)
- `useAsyncFlow` (`true` only for stocks → uses `publishStockDiscoveryRequested`)
- `requiresStrictDateCheck` (stocks/history)
- `shouldAggregateNews` (stocks)
- `defaultTags`, `defaultHashtags`
- `getExtraPrompt(today)`, `getScriptSystemPrompt()`, `getBgmCategory()`
- `shouldSkipGeneration()` (`true` for renderer)
- `ttsVoice`, `ttsRate`, `ttsPitch`, `ttsVolume` — used only by the
  legacy sync `produceVideoFromScenes` flow inside the controller
  itself (manual create). **Not used by the SAGA flow.**

## Static `companion object` lookups (the only thing the renderer can use)

The renderer container injects `RendererChannelBehavior`, but it
processes jobs for every channel. So per-channel TTS/BGM has to be
looked up by the *job's* `channelId`, not the renderer's instance:

```kotlin
ChannelBehavior.ttsVoiceFor("horror")           // → "ko-KR-SunHiNeural"
ChannelBehavior.ttsRateFor("horror")            // → "-10%"
ChannelBehavior.ttsPitchFor("horror")           // → "-10Hz"
ChannelBehavior.ttsVolumeFor("horror")          // → "-5%"
ChannelBehavior.ttsCharsPerSecondFor("horror")  // → 5.5  (validator hint)
ChannelBehavior.ttsAudioPostFilterFor("horror") // → FFmpeg post-EQ chain
ChannelBehavior.bgmMixVolumeFor("horror")       // → 0.45 (others 0.20)
```

`ProductionService` calls these with the *effective* `channelId` carried
on each Kafka event (see [saga-flow.md](saga-flow.md)). This is the fix
for the bug we shipped first time around (commit `0fd30a8`,
[log.md](log.md#2026-05-08)) where SAGA-rendered horror videos got
SunHi defaults despite `HorrorChannelBehavior.ttsVoice` being InJoon.

## Channel summary

| Channel | Voice | Pitch | Rate | Volume | BGM mix | Page |
|---|---|---|---|---|---|---|
| science | ko-KR-SunHiNeural | +0Hz | +30% | +0% | 0.20 | [science-channel.md](science-channel.md) |
| **horror** | ko-KR-SunHiNeural | -10Hz | -10% | -5% | **0.45** | [horror-channel.md](horror-channel.md) |
| stocks | ko-KR-SunHiNeural | +0Hz | +30% | +0% | 0.20 | [stocks-channel.md](stocks-channel.md) |
| history | ko-KR-SunHiNeural | +0Hz | +30% | +0% | 0.20 | [history-channel.md](history-channel.md) |

Horror is the only channel currently customized — see
[horror-channel.md](horror-channel.md) for the full reasoning.
