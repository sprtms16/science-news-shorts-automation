# 프론트엔드 개선점 분석 보고서

> 분석일: 2026-02-17
> 대상: `frontend/src/` 전체 컴포넌트
> 분석 도구: Claude Code

---

## 목차

1. [무한스크롤 중복 로딩 버그](#1-무한스크롤-중복-로딩-버그-critical)
2. [액션 후 페이지네이션 초기화](#2-액션-후-페이지네이션-초기화-high)
3. [요청 취소 미처리 (AbortController 부재)](#3-요청-취소-미처리-abortcontroller-부재-high)
4. [BgmManager 채널 하드코딩](#4-bgmmanager-채널-하드코딩-high)
5. [폴링 비효율](#5-폴링-비효율-medium)
6. [비동기 호출 await 누락](#6-비동기-호출-await-누락-medium)
7. [타입 안전성 문제](#7-타입-안전성-문제-medium)
8. [필터 로직 중복](#8-필터-로직-중복-medium)
9. [유틸 함수 중복 정의](#9-유틸-함수-중복-정의-low)
10. [수정 우선순위 및 권장 방안](#수정-우선순위-및-권장-방안)

---

## 1. 무한스크롤 중복 로딩 버그 (CRITICAL)

### 발생 위치

- `App.tsx:402-412` (영상 목록 무한스크롤)

### 현재 코드

```tsx
// App.tsx:389-416
{filteredVideos.map((video, index) => {
  const isLast = index === filteredVideos.length - 1;
  return (
    <VideoCard
      key={video.id}
      video={video}
      ref={isLast ? (node: any) => {
        if (loading || loadingMore) return;
        if (!nextPage) return;

        const observer = new IntersectionObserver(entries => {
          if (entries[0].isIntersecting) {
            fetchData(nextPage);
            observer.disconnect();
          }
        });
        if (node) observer.observe(node);
      } : undefined}
    />
  );
})}
```

### 원인 분석

| # | 원인 | 설명 |
|---|------|------|
| 1 | **매 렌더링마다 새 IntersectionObserver 생성** | ref 콜백이 인라인 함수이므로 React가 렌더할 때마다 이전 ref를 `null`로 호출하고, 새 ref를 node로 호출함. 이 과정에서 이전 observer의 `disconnect()`가 실행되지 않아 observer가 누적됨 |
| 2 | **클린업 부재** | 컴포넌트가 언마운트되거나 필터가 변경될 때 기존 observer를 정리하는 로직이 없음 |
| 3 | **filteredVideos 기반 isLast 판정** | `isLast`가 필터된 배열 기준이라 필터 변경 시 "마지막 카드"가 바뀌며 observer가 불필요하게 재생성됨 |
| 4 | **동시 요청 가드 부족** | `loadingMore` state 업데이트는 비동기(React batching)이므로 빠른 스크롤 시 여러 observer가 가드를 통과하여 같은 페이지를 중복 요청 |

### 재현 시나리오

1. 사용자가 영상 목록을 빠르게 스크롤
2. 마지막 카드의 IntersectionObserver가 fire → `fetchData(nextPage)` 호출
3. `loadingMore`가 `true`로 설정되기 전에 React 리렌더 발생
4. 새로운 observer가 생성되어 동일한 `nextPage`로 `fetchData` 재호출
5. 결과: **같은 데이터가 배열에 두 번 추가됨** (`setVideos(prev => [...prev, ...res.data.videos])`)

### 비교: YoutubeVideoList의 올바른 패턴

```tsx
// YoutubeVideoList.tsx:40-52 - 올바른 구현
const observer = useRef<IntersectionObserver | null>(null);
const lastVideoElementRef = useCallback((node: HTMLDivElement | null) => {
    if (loading || loadingMore) return;
    if (observer.current) observer.current.disconnect(); // ← 이전 observer 정리

    observer.current = new IntersectionObserver(entries => {
        if (entries[0].isIntersecting && nextPage) {
            fetchVideos(nextPage);
        }
    });

    if (node) observer.current.observe(node);
}, [loading, loadingMore, nextPage]);
```

### 권장 수정안

```tsx
// App.tsx에 적용할 패턴
const observer = useRef<IntersectionObserver | null>(null);
const lastVideoRef = useCallback((node: HTMLDivElement | null) => {
    if (loading || loadingMore) return;
    if (observer.current) observer.current.disconnect();

    observer.current = new IntersectionObserver(entries => {
        if (entries[0].isIntersecting && nextPage) {
            fetchData(nextPage);
        }
    });

    if (node) observer.current.observe(node);
}, [loading, loadingMore, nextPage]);

// 렌더 시:
<VideoCard
  ref={isLast ? lastVideoRef : undefined}
  // ...
/>
```

---

## 2. 액션 후 페이지네이션 초기화 (HIGH)

### 발생 위치

- `App.tsx:153, 166, 180, 208, 220, 233, 247, 257`

### 현재 코드

```tsx
// 모든 액션 핸들러에서 동일한 패턴
const onRetryVideo = async (id: string) => {
  // ...
  await fetchData(); // ← page=0으로 리셋됨
};

const onDeleteVideo = async (id: string) => {
  // ...
  await fetchData(); // ← page=0으로 리셋됨
};

const savePrompt = async (prompt: SystemPrompt) => {
  // ...
  fetchData(); // ← page=0으로 리셋 + await 누락
};
```

### 문제점

- `fetchData()`를 인자 없이 호출하면 `page=0`이 기본값으로 사용됨
- 사용자가 page 5까지 스크롤한 후 영상 하나를 삭제하면 **page 0으로 돌아감**
- 기존에 로드한 모든 데이터가 15개(1페이지)로 교체됨
- 스크롤 위치도 초기화되어 UX 저하

### 영향받는 함수 목록

| 함수명 | 라인 | 트리거 |
|--------|------|--------|
| `onRetryVideo` | 153 | 영상 재시도 |
| `onRegenerateMetadata` | 166 | 메타데이터 재생성 |
| `onManualUpload` | 180 | 수동 업로드 |
| `runBatchAction` | 208 | 배치 작업 |
| `updateVideoStatus` | 220 | 상태 변경 |
| `onDeleteVideo` | 233 | 영상 삭제 |
| `savePrompt` | 247 | 프롬프트 저장 |
| `saveSetting` | 257 | 설정 저장 |

### 권장 수정안

**방안 A: 해당 아이템만 로컬 상태에서 업데이트**

```tsx
const onDeleteVideo = async (id: string) => {
  // ...
  await axios.delete(...);
  setVideos(prev => prev.filter(v => v.id !== id));
  setTotalCount(prev => prev - 1);
};
```

**방안 B: 현재까지 로드된 전체 페이지 리페치**

```tsx
const refreshAllLoaded = async () => {
  const totalPages = Math.ceil(videos.length / 15);
  // 현재까지 로드된 범위를 한번에 조회
  const res = await axios.get(`...?page=0&size=${totalPages * 15}`);
  setVideos(res.data.videos);
};
```

---

## 3. 요청 취소 미처리 (AbortController 부재) (HIGH)

### 발생 위치

- `App.tsx:113` (fetchData)
- `YoutubeVideoList.tsx:67` (fetchVideos)
- `BgmManager.tsx:23` (fetchBgmList)

### 현재 상태

```tsx
// App.tsx - AbortController 없음
const fetchData = async (page: number = 0) => {
  const res = await axios.get(`...`); // 취소 불가
};

// LogViewer.tsx - 올바른 구현 (유일)
const abortRef = useRef<AbortController | null>(null);
const fetchLogs = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const res = await axios.get(`...`, { signal: controller.signal });
}, [page]);
```

### 문제점

- 채널 전환 시 (`science` → `horror`) 이전 채널 요청이 아직 진행 중
- 이전 요청이 늦게 도착하면 horror 채널 화면에 science 채널 데이터가 표시됨
- 탭 전환 시에도 동일한 문제 발생 가능

### 권장 수정안

```tsx
const abortRef = useRef<AbortController | null>(null);

const fetchData = async (page: number = 0) => {
  abortRef.current?.abort();
  const controller = new AbortController();
  abortRef.current = controller;

  try {
    const res = await axios.get(`...`, { signal: controller.signal });
    // ...
  } catch (error) {
    if (axios.isCancel(error)) return; // 취소된 요청은 무시
    console.error("Failed to fetch data", error);
  }
};
```

---

## 4. BgmManager 채널 하드코딩 (HIGH)

### 발생 위치

- `BgmManager.tsx:23, 74, 98, 114, 131`

### 현재 코드

```tsx
// 모든 API 호출에 'science'가 하드코딩
const res = await fetch('/api/science/admin/bgm/list');      // line 23
await fetch('/api/science/admin/bgm/upload', {...});          // line 74
await fetch(`/api/science/admin/bgm/retry/${id}`, {...});     // line 98
await fetch(`/api/science/admin/bgm/${id}`, { method: 'DELETE' }); // line 114
await fetch(`/api/science/admin/bgm/${bgm.id}`, {...});       // line 131
```

### 문제점

- 사용자가 horror, stocks, history 채널을 선택해도 항상 science 채널 BGM만 조회/수정됨
- `BgmManager` 컴포넌트가 `selectedChannel` prop을 받지 않음 (`App.tsx:468`에서 prop 전달 없음)

### 권장 수정안

```tsx
// App.tsx
<BgmManager selectedChannel={selectedChannel} />

// BgmManager.tsx
interface BgmManagerProps {
  selectedChannel: string;
}

const BgmManager: React.FC<BgmManagerProps> = ({ selectedChannel }) => {
  const apiBase = `/api/${selectedChannel}`;

  const fetchBgmList = useCallback(async () => {
    const res = await fetch(`${apiBase}/admin/bgm/list`);
    // ...
  }, [apiBase]);
};
```

---

## 5. 폴링 비효율 (MEDIUM)

### 발생 위치

- `BgmManager.tsx:33-37`

### 현재 코드

```tsx
useEffect(() => {
    fetchBgmList();
    const interval = setInterval(fetchBgmList, 3000); // 무조건 3초마다
    return () => clearInterval(interval);
}, [fetchBgmList]);
```

### 문제점

- PENDING/PROCESSING 상태의 항목이 없어도 3초마다 계속 요청
- 이전 요청이 3초 이상 걸리면 요청이 중첩됨
- BGM 탭을 보고 있지 않아도 (다른 탭으로 전환해도) 폴링이 지속됨
  - 단, `activeTab !== 'bgm'`이면 컴포넌트가 언마운트되어 cleanup 실행됨 → 이 부분은 정상

### 권장 수정안

```tsx
useEffect(() => {
    fetchBgmList();

    const hasPending = bgmList.some(b => b.status === 'PENDING' || b.status === 'PROCESSING');
    if (!hasPending) return; // 진행 중인 항목이 없으면 폴링 안 함

    const interval = setInterval(fetchBgmList, 3000);
    return () => clearInterval(interval);
}, [fetchBgmList, bgmList]);
```

---

## 6. 비동기 호출 await 누락 (MEDIUM)

### 발생 위치

- `App.tsx:247` (savePrompt)
- `App.tsx:257` (saveSetting)

### 현재 코드

```tsx
const savePrompt = async (prompt: SystemPrompt) => {
  try {
    await axios.post(`...`, prompt);
    showSuccess("Prompt saved!");
    fetchData();  // ← await 누락!
  } catch (e) {
    showError("Failed to save prompt");
  }
};

const saveSetting = async (key: string, value: string, desc: string) => {
  try {
    await axios.post(`...`, { ... });
    showSuccess("Setting saved!");
    fetchData();  // ← await 누락!
  } catch (e) {
    showError("Failed to save setting");
  }
};
```

### 문제점

- `fetchData()`가 await 없이 호출되어 fire-and-forget 상태
- 에러 발생 시 catch에서 잡히지 않음
- 사용자가 빠르게 연속 저장하면 race condition 발생 가능

---

## 7. 타입 안전성 문제 (MEDIUM)

### 발생 위치 및 내용

| 위치 | 현재 | 수정안 |
|------|------|--------|
| `App.tsx:402` | `(node: any)` | `(node: HTMLDivElement \| null)` |
| `App.tsx:36` | `useState<any[]>([])` | settings 타입 정의 필요 |
| `App.tsx:37` | `useState<any>(null)` | toolsResult 타입 정의 필요 |
| `App.tsx:63` | `useState<any>(null)` | `BeforeInstallPromptEvent` 타입 사용 |
| `YoutubeVideoList.tsx:13` | `videoId: String` (대문자) | `videoId: string` (소문자) |
| `YoutubeVideoList.tsx:23` | `t: any` | 번역 타입 정의 필요 |

### 설명

- TypeScript의 `String` (대문자)는 래퍼 객체 타입으로, 프리미티브 `string`과 다름
- `any` 타입은 타입 체크를 우회하여 런타임 오류 위험 증가

---

## 8. 필터 로직 중복 (MEDIUM)

### 발생 위치

- `App.tsx:263-272` (filteredVideos useMemo)
- `FilterBar.tsx` 내부 (동일한 필터링 로직으로 카운트 계산)

### 현재 코드

```tsx
// App.tsx:263-272
const filteredVideos = useMemo(() =>
  videos.filter(v => {
    const matchesSearch = v.title.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesMode = statusFilter === 'UPLOADED'
      ? v.status === 'UPLOADED'
      : v.status !== 'UPLOADED';
    return matchesSearch && matchesMode;
  }),
  [videos, searchTerm, statusFilter]
);

// FilterBar.tsx 내부 - 동일 로직 반복
const filteredCount = videos.filter(v => {
  const matchesSearch = ...;
  const matchesMode = ...;
  return matchesSearch && matchesMode;
}).length;
```

### 문제점

- 필터 조건이 두 곳에 존재하여 하나만 수정하면 불일치 발생
- FilterBar에 비필터 `videos` 배열 전체를 props로 전달 중

### 권장 수정안

- `filteredVideos.length`를 FilterBar에 직접 전달하거나
- 필터 로직을 공통 유틸 함수로 추출

---

## 9. 유틸 함수 중복 정의 (LOW)

### 발생 위치

- `App.tsx:26-28`
- `YoutubeVideoList.tsx:29-31`

### 현재 코드

```tsx
// 두 파일에 동일한 함수가 각각 정의됨
function getApiBase(channel: string): string {
  return `/api/${channel}`;
}
```

### 권장 수정안

공통 유틸 파일로 추출:

```tsx
// lib/api.ts
export function getApiBase(channel: string): string {
  return `/api/${channel}`;
}
```

---

## 수정 우선순위 및 권장 방안

| 순위 | 항목 | 심각도 | 예상 영향도 |
|------|------|--------|-------------|
| 1 | 무한스크롤 중복 로딩 수정 | CRITICAL | 사용자 체감 버그 즉시 해결 |
| 2 | AbortController 추가 | HIGH | 채널 전환 시 데이터 꼬임 방지 |
| 3 | 액션 후 페이지네이션 유지 | HIGH | UX 대폭 개선 |
| 4 | BgmManager 채널 하드코딩 수정 | HIGH | 멀티채널 기능 정상화 |
| 5 | await 누락 수정 | MEDIUM | 잠재적 race condition 방지 |
| 6 | 폴링 최적화 | MEDIUM | 불필요한 네트워크 요청 감소 |
| 7 | 타입 안전성 강화 | MEDIUM | 유지보수성 향상 |
| 8 | 필터 로직 통합 | MEDIUM | 코드 일관성 확보 |
| 9 | 유틸 함수 통합 | LOW | 코드 정리 |
