# MongoDB collections

DB name: `sciencepixel` (NOT `shorts_db`). All collections have a
compound index on `channelId` for multi-channel isolation.

## `video_history`

The lifecycle record for a single video. Status field walks the
[SAGA flow](saga-flow.md) state machine.

```
QUEUED → SCRIPTING → ASSETS_QUEUED → ASSETS_GENERATING → RENDER_QUEUED →
RENDERING → COMPLETED → UPLOADING → UPLOADED
                                  ↘ UPLOAD_FAILED
            ↘ FAILED → RETRY_QUEUED ↗
            ↘ BLOCKED  (Gemini safety filter rejected, terminal)
```

Key fields:

| field | type | notes |
|---|---|---|
| `_id` | ObjectId | also used as `videoId` in Kafka events |
| `channelId` | String | `science` / `horror` / `stocks` / `history` |
| `title` | String | initially RSS title, replaced after Gemini script |
| `link` | String | original source URL OR `manual-trigger-…` synthetic |
| `status` | enum | see state machine above |
| `failureStep` | String | `SCRIPT` / `ASSETS` / `RENDER` / `UPLOAD` / `SAFETY` / `DUPLICATE` |
| `errorMessage` | String | last failure reason (truncated to 500 chars) |
| `description` | String | YouTube description (Korean) |
| `tags` | List | YouTube tags |
| `sources` | List | source URLs cited |
| `validationErrors` | List | per-attempt validation reasons |
| `retryCount` | Int | upload retries |
| `regenCount` | Int | regeneration attempts |
| `filePath` | String | path inside `shared-data/videos/{channelId}/`; cleaned after `UPLOADED` |
| `youtubeUrl` | String | `https://youtu.be/...` after upload |
| `thumbnailPath` | String | path to Pexels-sourced thumbnail |
| `rssTitle` | String | original RSS title for dedup |
| `scenes` | List<Scene> | persisted script (sentence + Pexels keyword × 14) |
| `progress` | Int | 0-100 for the dashboard |
| `currentStep` | String | human-readable current activity |
| `createdAt` / `updatedAt` | LocalDateTime | KST |

## `system_prompt`

Channel-specific Gemini prompts. Seeded by `DataInitializer.seedSystemPrompts`
on every controller startup (delete-then-save → always overwrites).

Compound key: `(channelId, promptKey)` where `promptKey = script_prompt_v6`.
Current `description` field encodes the version (`v6.9` for horror,
`v7` for the other three) — see [horror-channel.md](horror-channel.md) etc.

## `bgm_entity`

Metadata for tracks in `shared-data/bgm/{category}/`. Used by the
admin BGM management UI. Categories used by `AudioService.generateBgm`
mood mapping: `futuristic`, `suspense`, `corporate`, `epic`, `calm`.

## `quota_usage`

Single-record (`_id = "youtube_upload"` etc.) daily counter for
external API budgets. `QuotaTracker` increments per call, resets at
KST midnight via `CleanupScheduler`.

## `system_setting`

Key-value config that can be hot-changed without code deploy. Notable
keys:

| key | scope | default | used by |
|---|---|---|---|
| `UPLOAD_INTERVAL_HOURS` | per-channel | 12 (shorts), 24 (stocks/history) | `YoutubeUploadScheduler` |
| `MAX_GENERATION_LIMIT` | per-channel | 10 | `BatchScheduler` (active+pending cap) |
| `VIDEO_BUFFER_LIMIT` | global | 10 | legacy buffer alert |

## `youtube_video`

External-state mirror: views/likes/publishedAt for already-uploaded
videos, refreshed periodically by `YoutubeSyncService`. Read-only from
the dashboard's perspective.
