# Backend SRP 리팩토링 분석서

> 작성일: 2026-03-15
> 대상: `backend/` 모듈 전체 (shorts-core, shorts-api, shorts-worker)
> 목적: 4개 YouTube 채널 운영을 위한 중복 로직 제거 및 단일 책임 원칙(SRP) 준수

---

## 1. 현재 아키텍처 개요

### 1.1 멀티채널 설계

동일한 Docker 이미지를 채널별 컨테이너로 실행하며, `SHORTS_CHANNEL_ID` 환경변수로 채널을 구분합니다.

| 채널 ID | 채널명 | 포트 | 스케줄 | 형식 |
|---------|--------|------|--------|------|
| `science` | 사이언스 픽셀 | 8080 | 매시간 :10, :40 | Shorts |
| `horror` | 미스터리 픽셀 | 8081 | 매시간 :20, :50 | Shorts |
| `stocks` | 밸류 픽셀 | 8083 | 매일 17:30 | Long Form |
| `history` | 메모리 픽셀 | 8084 | 매일 06:30 | Long Form |

### 1.2 채널 추상화 현황

`ChannelBehavior` 인터페이스 (`shorts-core/.../config/ChannelBehavior.kt`)가 Spring DI 기반 채널 분기의 핵심 추상화 계층으로 존재합니다.

```
ChannelBehavior (interface)
├── DefaultChannelBehavior   (@ConditionalOnProperty = "science")
├── HorrorChannelBehavior    (@ConditionalOnProperty = "horror")
├── StocksChannelBehavior    (@ConditionalOnProperty = "stocks")
├── HistoryChannelBehavior   (@ConditionalOnProperty = "history")
└── RendererChannelBehavior  (@ConditionalOnProperty = "renderer")
```

**현재 제공 속성:**

| 속성 | 타입 | 설명 |
|------|------|------|
| `channelId` | String | 채널 식별자 |
| `channelName` | String | 채널 표시 이름 |
| `isLongForm` | Boolean | Long Form 여부 |
| `dailyLimit` | Int | 일일 생성 제한 |
| `useAsyncFlow` | Boolean | 비동기 플로우 사용 |
| `requiresStrictDateCheck` | Boolean | 오늘 생성 영상만 업로드 |
| `shouldAggregateNews` | Boolean | 뉴스 집계 여부 |
| `defaultTags` | List\<String\> | YouTube 태그 |
| `defaultHashtags` | String | 설명란 해시태그 |
| `getExtraPrompt(today)` | fun | 채널 특화 프롬프트 |
| `getScriptSystemPrompt()` | fun | 스크립트 시스템 프롬프트 |
| `getBgmCategory()` | fun | BGM 카테고리 |
| `shouldSkipGeneration()` | fun | 생성 스킵 조건 |

---

## 2. SRP 위반 상세 분석

### 2.1 위반 요약표

총 **7개 파일**, **14개 `when(channelId)` 블록**이 `ChannelBehavior`를 우회하여 채널별 로직을 하드코딩하고 있습니다.

| # | 파일 | 라인 | 메서드/속성 | 위반 유형 | 심각도 |
|---|------|------|-------------|-----------|--------|
| 1 | GeminiService.kt | 35-42 | `getChannelName()` | 채널명 매핑 중복 | Medium |
| 2 | GeminiService.kt | 389-435 | `getDefaultScriptPrompt()` | niche context 하드코딩 | High |
| 3 | GeminiService.kt | 437-443 | `getDefaultScriptPrompt()` 내부 | 채널명 매핑 재중복 | Medium |
| 4 | GeminiService.kt | 492-500 | `getMoodExamples()` | BGM mood 하드코딩 | Medium |
| 5 | YoutubeService.kt | 41-47 | `credentialFolder` | 인증 폴더 매핑 | Medium |
| 6 | YoutubeService.kt | 195-201 | `channelEnvName` | 환경변수명 매핑 | Medium |
| 7 | YoutubeService.kt | 220-226 | `targetCredentialFolder` | 인증 폴더 매핑 재중복 | Medium |
| 8 | ProductionService.kt | 158-164 | `fallbackKeyword` | Pexels 폴백 키워드 | Medium |
| 9 | BatchScheduler.kt | 195-198 | `recoverMissedDailyBatch()` | 채널 타입 분기 | Low |
| 10 | BatchScheduler.kt | 201-205 | `recoverMissedDailyBatch()` | 배치 시간 하드코딩 | High |
| 11 | YoutubeUploadScheduler.kt | 47-51 | `uploadPendingVideos()` | 업로드 간격 하드코딩 | Medium |
| 12 | YoutubeUploadScheduler.kt | 66 | `uploadPendingVideos()` | `requiresStrictDateCheck` 미사용 | Low |
| 13 | ContentProviderService.kt | 123 | `fetchWikipediaOnThisDay()` | `"history"` 하드코딩 | High |
| 14 | DataInitializer.kt | 292-299 | `seedSettings()` | 업로드 간격 하드코딩 | Low |

### 2.2 위반 상세

#### 위반 #1-4: GeminiService.kt (1,198줄)

**문제:** 단일 서비스 클래스가 4가지 책임을 동시에 수행합니다.

- Gemini API 호출 및 쿼타 관리
- 채널별 프롬프트 생성
- 스크립트 안전성 검사
- 채널명/mood 매핑

**코드 예시 - `getChannelName()` (라인 35-42):**
```kotlin
private fun getChannelName(targetChannelId: String? = null): String {
    return when (targetChannelId ?: channelId) {
        "science" -> "사이언스 픽셀"
        "horror" -> "미스터리 픽셀"
        "stocks" -> "밸류 픽셀"
        "history" -> "메모리 픽셀"
        else -> "AI 쇼츠 마스터"
    }
}
```

**문제점:** `ChannelBehavior.channelName`이 이미 동일한 매핑을 제공하지만, `targetChannelId` 파라미터로 다른 채널 조회가 필요한 경우에 사용 불가.

**코드 예시 - `getDefaultScriptPrompt()` (라인 389-435):**
```kotlin
val nicheContext = when (effectiveChannelId) {
    "science" -> """[Role] You are the Lead Communicator for 'Science Pixel'..."""
    "horror" -> """[Role] You are a Korean Storyteller for 'Mystery Pixel'..."""
    "stocks" -> """[Role] You are the Head Analyst for 'Value Pixel'..."""
    "history" -> """[Role] You are 'Memory Pixel', a humanist historian..."""
    else -> "You are a creative content creator."
}
```

**문제점:** 각 채널의 프롬프트 컨텍스트가 서비스 로직 내부에 하드코딩되어 있어, 프롬프트 수정 시 코드 배포가 필요합니다.

---

#### 위반 #5-7: YoutubeService.kt (461줄)

**문제:** 인증 관련 설정값이 3곳에서 중복 매핑됩니다.

**코드 예시 - 인증 폴더 매핑 (라인 41-47, 220-226 동일):**
```kotlin
private val credentialFolder = when(channelId) {
    "science" -> "SciencePixel"
    "horror" -> "MysteryPixel"
    "stocks" -> "ValuePixel"
    "history" -> "HistoryPixel"
    else -> "SciencePixel"
}
```

**코드 예시 - 환경변수명 매핑 (라인 195-201):**
```kotlin
val channelEnvName = when(effectiveChannelId) {
    "science" -> "SECRET_SCIENCE_B64"
    "horror" -> "SECRET_HORROR_B64"
    "stocks" -> "SECRET_STOCKS_B64"
    "history" -> "SECRET_HISTORY_B64"
    else -> "SECRET_SCIENCE_B64"
}
```

**문제점:** 동일한 매핑이 메서드 내에서 2회 반복되며, 새 채널 추가 시 3곳을 동시에 수정해야 합니다.

---

#### 위반 #8: ProductionService.kt (792줄)

**코드 예시 - 폴백 키워드 (라인 158-164):**
```kotlin
val fallbackKeyword = when (effectiveChannelId) {
    "science" -> "science technology"
    "stocks" -> "business finance"
    "horror" -> "dark mystery"
    "history" -> "ancient civilization"
    else -> "technology innovation"
}
```

**문제점:** 채널별 Pexels 검색 폴백 키워드가 `ChannelBehavior`에 없어 서비스에 하드코딩.

---

#### 위반 #9-10: BatchScheduler.kt (360줄)

**코드 예시 - 일일 배치 복구 (라인 195-205):**
```kotlin
private fun recoverMissedDailyBatch() {
    when(channelId) {
        "stocks", "history" -> { /* daily channels only */ }
        else -> return
    }
    val batchHour = when(channelId) {
        "history" -> 6    // 06:30 배치
        "stocks" -> 17    // 17:30 배치
        else -> return
    }
    // ...
}
```

**문제점:** `ChannelBehavior.dailyLimit == 1`로 일일 채널 여부를 이미 판별할 수 있고, 배치 시간은 `ChannelBehavior`에 속성으로 추가해야 합니다.

---

#### 위반 #11-12: YoutubeUploadScheduler.kt (130줄)

**코드 예시 - 업로드 간격 (라인 47-51, 66):**
```kotlin
val minIntervalHours = settingOpt?.value?.toLongOrNull() ?: when(channelId) {
    "stocks", "history" -> 24L
    "science", "horror" -> 3L
    else -> 12L
}
// ...
if (minIntervalHours >= 24L || channelId == "history" || channelId == "stocks") { ... }
```

**문제점:**
1. 업로드 간격 기본값이 `ChannelBehavior`에 없어 `when`으로 분기
2. `channelId == "history" || channelId == "stocks"` 조건은 이미 존재하는 `channelBehavior.requiresStrictDateCheck`와 동일한 의미

---

#### 위반 #13: ContentProviderService.kt (286줄)

**코드 예시 - 하드코딩된 채널 ID (라인 123):**
```kotlin
if (videoHistoryRepository.existsByChannelIdAndLink("history", link)) {
    continue
}
```

**문제점:** `"history"` 문자열이 하드코딩되어 있어, 다른 채널에서 Wikipedia 소스를 사용할 경우 중복 체크가 작동하지 않습니다. `channelId` 파라미터를 전달받아야 합니다.

---

## 3. 리팩토링 방안 비교

### 3.1 방안 A: ChannelBehavior 확장 (추천)

기존 `@ConditionalOnProperty` 패턴을 유지하면서 누락된 속성을 추가합니다.

**장점:**
- 기존 아키텍처와 완전히 호환
- 변경 범위가 최소 (인터페이스 + 구현체 + 서비스 참조 변경)
- 컴파일 타임에 누락 속성 검출
- 새 채널 추가 시 구현체 1개만 추가하면 완료

**단점:**
- 인터페이스가 커질 수 있음 (현재 13개 → 20개 속성)
- `targetChannelId`로 다른 채널 조회 시 추가 설계 필요

**추가할 속성:**

```kotlin
interface ChannelBehavior {
    // 기존 속성 유지...

    // 신규 추가
    val credentialFolderName: String        // YoutubeService용
    val secretEnvVarName: String            // YoutubeService용
    val fallbackPexelsKeyword: String       // ProductionService용
    val moodExamples: String                // GeminiService용
    val nichePromptContext: String          // GeminiService용
    val batchHour: Int?                     // BatchScheduler용 (null = 시간 단위 채널)
    val defaultUploadIntervalHours: Long    // YoutubeUploadScheduler용
}
```

### 3.2 방안 B: Spring Profile 기반

`application-{channelId}.yml` 파일로 채널별 설정을 분리합니다.

**장점:**
- Spring 표준 패턴
- 설정값을 코드 외부에서 관리

**단점:**
- 이미 `@ConditionalOnProperty`로 구현된 패턴을 Profile로 전환하는 비용이 큼
- 프롬프트 같은 복잡한 문자열은 YAML에 적합하지 않음
- `ChannelBehavior` 인터페이스 + DI 방식 대비 타입 안전성 낮음

### 3.3 방안 C: DB 설정화 확장

모든 채널별 설정을 `system_setting` 컬렉션에 저장합니다.

**장점:**
- 런타임 변경 가능 (재배포 불필요)
- 이미 `SystemSetting` 인프라 존재

**단점:**
- 인증 폴더명, 환경변수명 등 정적 설정까지 DB화는 과도함
- 기동 시 DB 의존성 추가
- 타입 안전성 없음 (String 기반)

### 3.4 방안 D: Enum + Registry 패턴

```kotlin
enum class Channel(val id: String, val displayName: String, ...) {
    SCIENCE("science", "사이언스 픽셀", ...),
    HORROR("horror", "미스터리 픽셀", ...),
    // ...
}
```

**장점:**
- 타입 안전성 최고
- 모든 설정값이 한 곳에 집중

**단점:**
- 기존 Spring DI 패턴(`@ConditionalOnProperty`)과 충돌
- `ChannelBehavior` 인터페이스의 다형성 활용 불가
- 새 채널 추가 시 Enum 수정 + 재컴파일 필요 (DI와 동일하지만 유연성 낮음)

### 3.5 결론

**방안 A (ChannelBehavior 확장)** 를 추천합니다. 이유:
1. 기존 패턴과 완전히 호환되어 변경 비용이 가장 낮음
2. 컴파일 타임 안전성 보장
3. `targetChannelId` 문제는 별도의 `ChannelRegistry` 보조 컴포넌트로 해결 가능

---

## 4. `targetChannelId` 문제 해결

### 4.1 문제 정의

`GeminiService.getChannelName(targetChannelId)`, `YoutubeService.getFlow(targetChannelId)` 등의 메서드는 **현재 채널이 아닌 다른 채널**의 설정을 조회해야 합니다.

`ChannelBehavior`는 `@ConditionalOnProperty`로 **현재 컨테이너의 채널 1개만** 주입되므로, 다른 채널의 속성을 조회할 수 없습니다.

### 4.2 해결: ChannelRegistry

모든 채널 설정을 정적으로 등록하는 레지스트리를 추가합니다.

```kotlin
@Component
class ChannelRegistry {
    private val channels = mapOf(
        "science" to ChannelProperties(
            channelName = "사이언스 픽셀",
            credentialFolderName = "SciencePixel",
            secretEnvVarName = "SECRET_SCIENCE_B64"
        ),
        "horror" to ChannelProperties(...),
        "stocks" to ChannelProperties(...),
        "history" to ChannelProperties(...)
    )

    fun getProperties(channelId: String): ChannelProperties =
        channels[channelId] ?: channels["science"]!!
}
```

**사용 범위:** `YoutubeService.getFlow(targetChannelId)`, `GeminiService.getChannelName(targetChannelId)` 등 **cross-channel 조회가 필요한 메서드에만** 사용합니다. 나머지는 모두 `ChannelBehavior` 직접 참조.

---

## 5. 성능 개선 포인트

### 5.1 불필요한 런타임 분기 제거

| 현재 | 개선 후 | 영향 |
|------|---------|------|
| `getChannelName()` 호출 시 매번 `when` 분기 | `channelBehavior.channelName` 직접 참조 | 미미 (가독성 개선) |
| `getDefaultScriptPrompt()` 내 `when` 2개 | `channelBehavior.nichePromptContext` 참조 | 미미 (가독성 개선) |
| `recoverMissedDailyBatch()` 채널 필터링 | `channelBehavior.batchHour != null` | 미미 (가독성 개선) |

### 5.2 중복 쿼리 제거

| 위치 | 현재 | 개선 |
|------|------|------|
| `YoutubeUploadScheduler:66` | `minIntervalHours >= 24L \|\| channelId == "history" \|\| channelId == "stocks"` | `channelBehavior.requiresStrictDateCheck` (이미 동일 의미) |
| `BatchScheduler:195` | `when(channelId) { "stocks", "history" -> ... }` | `channelBehavior.batchHour != null` |

### 5.3 GeminiService 분리 (선택적)

현재 1,198줄의 GeminiService를 아래와 같이 분리하면 유지보수성이 향상됩니다:

```
GeminiService (1,198줄)
├── GeminiApiClient        - API 호출, 모델 선택, 재시도
├── GeminiQuotaManager     - RPM/TPM/RPD 쿼타 관리
├── ScriptPromptBuilder    - 채널별 프롬프트 조합
└── ContentSafetyChecker   - 민감도 검사
```

> 이 분리는 현재 태스크의 범위 밖이며, 별도 작업으로 진행을 권장합니다.

---

## 6. 구현 계획

### Phase 1: ChannelBehavior 확장

**대상 파일:** `shorts-core/.../config/ChannelBehavior.kt`

1. 인터페이스에 7개 속성 추가 (기본값 포함)
2. 5개 구현체(`Default`, `Horror`, `Stocks`, `History`, `Renderer`)에 값 설정
3. `ChannelRegistry` 컴포넌트 추가 (cross-channel 조회용)

### Phase 2: 서비스 리팩토링

| 파일 | 변경 내용 |
|------|-----------|
| GeminiService.kt | `getChannelName()` → `channelBehavior.channelName` 또는 `channelRegistry` 사용 |
| GeminiService.kt | `getDefaultScriptPrompt()` → `channelBehavior.nichePromptContext` 사용 |
| GeminiService.kt | `getMoodExamples()` → `channelBehavior.moodExamples` 사용 |
| YoutubeService.kt | `credentialFolder` → `channelBehavior.credentialFolderName` 사용 |
| YoutubeService.kt | `channelEnvName` → `channelRegistry.getProperties().secretEnvVarName` 사용 |
| ProductionService.kt | `fallbackKeyword` → `channelBehavior.fallbackPexelsKeyword` 사용 |
| BatchScheduler.kt | `recoverMissedDailyBatch()` → `channelBehavior.batchHour` 사용 |
| YoutubeUploadScheduler.kt | 간격 기본값 → `channelBehavior.defaultUploadIntervalHours` 사용 |
| YoutubeUploadScheduler.kt | 하드코딩된 채널 체크 → `channelBehavior.requiresStrictDateCheck` 사용 |
| ContentProviderService.kt | `"history"` 하드코딩 → `channelId` 파라미터 전달 |

### Phase 3: 테스트 (TDD)

- `ChannelBehaviorTest` - 각 구현체의 속성값 검증
- `ChannelRegistryTest` - cross-channel 조회 검증
- 기존 서비스 테스트에서 `ChannelBehavior` mock 주입 검증

### Phase 4: QA 체크리스트

- [ ] 모든 `when(channelId)` 또는 `when(effectiveChannelId)` 패턴이 제거되었는지 확인
- [ ] `ChannelBehavior` 구현체별 속성값이 기존 `when` 블록의 값과 동일한지 확인
- [ ] `targetChannelId` 사용 메서드에서 `ChannelRegistry` 정상 동작 확인
- [ ] 빌드 성공 (`./gradlew build`)
- [ ] 기존 테스트 통과 (`./gradlew test`)

---

## 7. 영향 범위

### 변경 파일 목록

| 모듈 | 파일 | 변경 유형 |
|------|------|-----------|
| shorts-core | `config/ChannelBehavior.kt` | 수정 (인터페이스 + 구현체) |
| shorts-core | `config/ChannelRegistry.kt` | **신규** |
| shorts-core | `service/GeminiService.kt` | 수정 |
| shorts-core | `service/YoutubeService.kt` | 수정 |
| shorts-core | `service/ProductionService.kt` | 수정 |
| shorts-core | `service/ContentProviderService.kt` | 수정 |
| shorts-api | `service/BatchScheduler.kt` | 수정 |
| shorts-api | `service/YoutubeUploadScheduler.kt` | 수정 |
| shorts-core | `test/...` | **신규** (테스트) |

### 변경하지 않는 파일

- `DataInitializer.kt` - 데이터 시드 코드로, 채널별 하드코딩이 불가피
- `StockDiscoveryConsumer.kt` - stocks 전용 컨슈머로, 채널 체크가 안전장치
- `AdminController.kt` - API 파라미터 기반 채널 선택으로 SRP 위반이 아님

---

## 8. 참고: DB 설정 현황

### MongoDB Collections (Database: `sciencepixel`)

| Collection | 채널 격리 | 인덱스 |
|------------|-----------|--------|
| `video_history` | `channelId` 복합 인덱스 | `(channelId, link)` unique, `(channelId, status, createdAt)`, `(channelId, status, updatedAt)` |
| `system_prompt` | `channelId` 복합 인덱스 | `(channelId, promptKey)` unique |
| `system_setting` | `channelId` 복합 인덱스 | `(channelId, key)` unique |
| `youtube_videos` | `channelId` 복합 인덱스 | `(channelId, title)` |
| `pexels_cache` | 선택적 `channelId` | `(keyword)`, `(pexelsVideoId)` unique |
| `rss_sources` | `channelId` 복합 인덱스 | `(channelId, url)` unique |
| `bgm_library` | 공유 | 없음 |
| `quota_usage` | 공유 | 없음 |

### 주요 System Settings (per channel)

| Key | 기본값 | 설명 |
|-----|--------|------|
| `MAX_GENERATION_LIMIT` | 10 | 활성 파이프라인 슬롯 수 |
| `UPLOAD_INTERVAL_HOURS` | science/horror: 12, stocks/history: 24 | 업로드 간격 |
| `YOUTUBE_DAILY_QUOTA_LIMIT` | 10000 | YouTube API 일일 쿼타 |
| `YOUTUBE_UPLOAD_SUSPENDED` | false | 업로드 중단 플래그 |
