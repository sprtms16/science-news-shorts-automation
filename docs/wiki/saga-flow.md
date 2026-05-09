# SAGA flow (Kafka pipeline)

How a single video gets from RSS source to YouTube. Kafka is the
backbone; each topic is consumed by exactly one container type.

## Topic chain

```
RSS / Reddit / Wikipedia
       │ (BatchScheduler in shorts-{channel}-controller)
       │  → VideoProcessor.publishRssNewItem
       ▼
[ rss-new-item ]
       │ (ScriptConsumer in shorts-{channel}-controller)
       │  → GeminiService.writeScript → publishScriptCreated
       ▼
[ video-script-created ]
       │ (SceneConsumer in shorts-renderer)
       │  → ProductionService.produceAssetsOnly
       │    (Pexels download + Edge TTS per scene + per-scene FFmpeg cut)
       │  → publishVideoAssetsReady
       ▼
[ video-assets-ready ]
       │ (RenderConsumer in shorts-renderer)
       │  → ProductionService.finalizeVideo
       │    (concat + SRT burn + BGM mix + final FFmpeg encode)
       │  → updates video_history.status = COMPLETED
       ▼
   COMPLETED  →  YoutubeUploadScheduler picks up at next cron tick
                 → YoutubeService.upload → status UPLOADED
```

Plus the `system-logs` topic that `shorts-log-service` consumes for the
operational log UI.

## Which container has which feature flag

Driven by `APP_FEATURE_*` env vars in `docker-compose.yml`:

| Container | `CONSUMER_SCRIPT` | `CONSUMER_SCENE` | `CONSUMER_RENDER` | `REMOTE_RENDERING` |
|---|---|---|---|---|
| shorts-{science,horror,stocks,history}-controller | ✅ | ❌ | ❌ | ✅ |
| shorts-renderer | ❌ | ✅ | ✅ | n/a |

The four channel controllers run the script step (Gemini), then publish
to Kafka; the GPU renderer runs the scene + render steps for every
channel. This is what makes [`channel-behavior.md`](channel-behavior.md)'s
companion-object lookup necessary — the renderer doesn't know "what
channel" until it reads the event.

## Where channelId lives in the event chain

- `RssNewItemEvent.channelId` — set by `VideoProcessor` from `RssSource.channelId`
- `ScriptCreatedEvent.channelId` — copied through, used by SceneConsumer
- `VideoAssetsReadyEvent.channelId` — copied through, used by RenderConsumer
- Inside `ProductionService.produceAssetsOnly`, the parameter
  `targetChannelId ?: channelId` resolves to `effectiveChannelId`. This
  is the value that must be threaded into every per-channel lookup
  (TTS voice, BGM mix volume, post-EQ chain).

## Async / sync flows

- **Async (SAGA)** — for `shorts` channels with `useAsyncFlow=false` *and*
  `APP_FEATURE_REMOTE_RENDERING=true`: traditional Spring Batch path
  through `VideoProcessor` → `publishRssNewItem`. This is the production
  path for science / horror.
- **Stocks-async** — `useAsyncFlow=true` triggers a different first event:
  `publishStockDiscoveryRequested` → `StockDiscoveryConsumer` → either
  `executeMorningBriefing` (Python market-collector) or
  `executeDeepDiveDiscovery` (RSS fetch). Then converges back into the
  same `ScriptCreatedEvent` chain.
- **Sync (legacy)** — `produceVideoFromScenes` inside ProductionService.
  Called by `/manual/create` REST endpoint. Runs entirely inside the
  controller container (no Kafka), so it does NOT have GPU access — the
  fallback `libx264` codec produces ~1.87 Mbps videos vs the SAGA
  flow's ~6 Mbps. Avoid this path for production output.

## Recovery

`BatchScheduler.recoverFailedJobs` runs every 10 min:

- `recoverFailedGenerations` — `FAILED` jobs older than 1h → `RETRY_QUEUED`
- `recoverOrphanedQueuedJobs` — stuck `QUEUED` items > 1h → re-publish
  `RssNewItemEvent`
- `processRetryQueue` — `RETRY_QUEUED` → re-publish
- `recoverStuckUploads` — `UPLOADING` items that hung
- `recoverMissedDailyBatch` — for stocks/history, if no video created
  today after the scheduled batch hour, re-trigger
