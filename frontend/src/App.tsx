import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { Toaster } from 'sonner';
import type { VideoHistory, SystemPrompt } from './types';
import { RefreshCw } from 'lucide-react';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { translations, type Language } from './i18n';
import { ErrorBoundary } from './components/ErrorBoundary';
import { Sidebar } from './components/layout/Sidebar';
import { FilterBar } from './components/dashboard/FilterBar';
import { VideoCard } from './components/dashboard/VideoCard';
import { PromptEditor } from './components/dashboard/PromptEditor';
import { ToolsPanel } from './components/dashboard/ToolsPanel';
import { SettingsPanel } from './components/dashboard/SettingsPanel';
import { LogViewer } from './components/dashboard/LogViewer';
import YoutubeVideoList from './components/dashboard/YoutubeVideoList';
import BgmManager from './components/BgmManager';
import { showSuccess, showError, confirmAction } from './lib/toast';
import { getApiBase } from './lib/api';

function cn(...inputs: (string | undefined | null | false)[]) {
  return twMerge(clsx(inputs));
}



function App() {
  const [activeTab, setActiveTab] = useState<'videos' | 'prompts' | 'tools' | 'settings' | 'logs' | 'youtube' | 'bgm'>('videos');
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [selectedChannel, setSelectedChannel] = useState<string>(() => localStorage.getItem('selected-channel') || 'science');
  const [videos, setVideos] = useState<VideoHistory[]>([]);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [settings, setSettings] = useState<any[]>([]);
  const [toolsResult, setToolsResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('NOT_UPLOADED');
  const [nextPage, setNextPage] = useState<number | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [totalCount, setTotalCount] = useState<number>(0);

  // i18n & Theme states
  const [language, setLanguage] = useState<Language>(() => {
    const saved = localStorage.getItem('app-lang') as Language;
    if (saved) return saved;
    return navigator.language.startsWith('ko') ? 'ko' : 'en';
  });

  const [theme, setTheme] = useState<'light' | 'dark' | 'system'>(() => {
    return (localStorage.getItem('app-theme') as any) || 'system';
  });

  const channels = [
    { id: 'science', name: language === 'ko' ? '사이언스 픽셀' : 'Science Pixel', color: 'text-blue-400' },
    { id: 'horror', name: language === 'ko' ? '미스터리 픽셀' : 'Mystery Pixel', color: 'text-purple-400' },
    { id: 'stocks', name: language === 'ko' ? '밸류 픽셀' : 'Value Pixel', color: 'text-green-400' },
    { id: 'history', name: language === 'ko' ? '메모리 픽셀' : 'Memory Pixel', color: 'text-amber-400' },
  ];

  const [installPrompt, setInstallPrompt] = useState<any>(null);

  const t = translations[language];

  useEffect(() => {
    const handleBeforeInstallPrompt = (e: any) => {
      e.preventDefault();
      setInstallPrompt(e);
      console.log('Captured beforeinstallprompt event');
    };

    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt);

    return () => {
      window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt);
    };
  }, []);

  useEffect(() => {
    localStorage.setItem('app-lang', language);
    document.documentElement.lang = language;
  }, [language]);

  useEffect(() => {
    localStorage.setItem('app-theme', theme);
    const root = document.documentElement;
    const isDark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);

    if (isDark) {
      root.classList.remove('light-theme');
    } else {
      root.classList.add('light-theme');
    }
  }, [theme]);

  useEffect(() => {
    localStorage.setItem('selected-channel', selectedChannel);
    fetchData();
  }, [activeTab, selectedChannel]);

  // AbortController for canceling in-flight requests
  const abortRef = useRef<AbortController | null>(null);

  const fetchData = async (page: number = 0) => {
    // Cancel previous request
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const isInitial = page === 0;
    if (isInitial) {
      setLoading(true);
    } else {
      setLoadingMore(true);
    }

    try {
      if (activeTab === 'videos') {
        const res = await axios.get(`${getApiBase(selectedChannel)}/admin/videos?page=${page}&size=15&channelId=${selectedChannel}`, { signal: controller.signal });
        if (isInitial) {
          setVideos(res.data.videos);
        } else {
          setVideos(prev => [...prev, ...res.data.videos]);
        }
        setNextPage(res.data.nextPage);
        setTotalCount(res.data.totalCount);
      } else if (activeTab === 'prompts') {
        const res = await axios.get(`${getApiBase(selectedChannel)}/admin/prompts?channelId=${selectedChannel}`, { signal: controller.signal });
        setPrompts(res.data);
      } else if (activeTab === 'settings') {
        try {
          const res = await axios.get(`${getApiBase(selectedChannel)}/admin/settings?channelId=${selectedChannel}`, { signal: controller.signal });
          setSettings(res.data);
        } catch (e) { console.error(e); }
      }
    } catch (error) {
      if (axios.isCancel(error)) return; // Ignore canceled requests
      console.error("Failed to fetch data", error);
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  };

  const downloadVideo = async (id: string) => {
    try {
      const downloadUrl = `${getApiBase(selectedChannel)}/admin/videos/${id}/download`;
      window.location.href = downloadUrl;
    } catch (e) {
      showError("Download trigger failed");
    }
  };

  const onRetryVideo = async (id: string) => {
    if (!await confirmAction(language === 'ko' ? "이 영상을 재시도하시겠습니까? (기존 실패 기록이 초기화됩니다)" : "Retry this video? Previous failure records will be reset.")) return;
    setLoading(true);
    try {
      await axios.post(`${getApiBase(selectedChannel)}/admin/videos/${id}/retry`);
      showSuccess(language === 'ko' ? "재시도 요청이 성공했습니다." : "Retry requested successfully.");
      // Update local state instead of refetching
      setVideos(prev => prev.map(v => v.id === id ? { ...v, status: 'PENDING' } : v));
    } catch (e) {
      showError(language === 'ko' ? "재시도 요청 실패" : "Retry request failed");
    } finally {
      setLoading(false);
    }
  };

  const onRegenerateMetadata = async (id: string) => {
    if (!await confirmAction("메타데이터를 한글로 재생성하시겠습니까? (Gemini 쿼터 소모)")) return;
    setLoading(true);
    try {
      await axios.post(`${getApiBase(selectedChannel)}/admin/videos/${id}/metadata/regenerate`);
      // Update local state - mark as processing
      setVideos(prev => prev.map(v => v.id === id ? { ...v, status: 'PROCESSING' } : v));
    } catch (e) {
      showError("메타데이터 재생성 실패");
    } finally {
      setLoading(false);
    }
  };

  const onManualUpload = async (id: string) => {
    if (!await confirmAction(language === 'ko' ? "이 영상을 유튜브에 수동으로 업로드하시겠습니까?" : "Manual upload this video to YouTube?")) return;
    setLoading(true);
    try {
      await axios.post(`${getApiBase(selectedChannel)}/admin/videos/${id}/upload`);
      showSuccess(language === 'ko' ? "업로드 요청이 성공적으로 전달되었습니다." : "Upload request sent successfully.");
      // Update local state - mark as uploading
      setVideos(prev => prev.map(v => v.id === id ? { ...v, status: 'UPLOADING' } : v));
    } catch (e) {
      showError(language === 'ko' ? "업로드 요청 실패" : "Upload request failed");
    } finally {
      setLoading(false);
    }
  };

  const runBatchAction = async (action: 'rematch-files' | 'regenerate-all-metadata' | 'regenerate-missing-files' | 'sync-uploaded' | 'cleanup-sensitive' | 'upload-pending' | 'prune-deleted' | 'translate-uploaded' | 'growth-analysis' | 'regenerate-thumbnails' | 'clear-failed' | 'cleanup-workspaces' | 'refresh-prompts' | 'reset-sources' | 'trigger-manual-generate') => {
    if (!await confirmAction(`Run ${action}? This may take a while.`)) return;
    setLoading(true);
    setToolsResult(null);
    try {
      const apiBase = getApiBase(selectedChannel);
      const endpoint = action === 'sync-uploaded' ? `${apiBase}/admin/maintenance/sync-uploaded` :
        action === 'cleanup-sensitive' ? `${apiBase}/admin/videos/cleanup-sensitive` :
          action === 'prune-deleted' ? `${apiBase}/admin/maintenance/cleanup-deleted-youtube` :
            action === 'translate-uploaded' ? `${apiBase}/admin/maintenance/translate-uploaded-videos` :
              action === 'growth-analysis' ? `${apiBase}/admin/maintenance/growth-analysis` :
                action === 'regenerate-thumbnails' ? `${apiBase}/admin/maintenance/regenerate-thumbnails` :
                  action === 'clear-failed' ? `${apiBase}/admin/videos/history/clear-failed?channelId=${selectedChannel}` :
                    action === 'cleanup-workspaces' ? `${apiBase}/admin/maintenance/cleanup-workspaces` :
                      action === 'refresh-prompts' ? `${apiBase}/admin/maintenance/refresh-prompts?channelId=${selectedChannel}` :
                        action === 'reset-sources' ? `${apiBase}/admin/maintenance/reset-sources` :
                          action === 'trigger-manual-generate' ? `${apiBase}/manual/batch/trigger` :
                            `${apiBase}/admin/videos/${action}`;
      const res = await axios.post(endpoint);
      setToolsResult(res.data);
      showSuccess("Batch action completed!");
      // Batch actions affect multiple items, keep fetchData but user can scroll back
      await fetchData();
    } catch (e) {
      showError("Action failed");
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const updateVideoStatus = async (id: string, status: string, youtubeUrl?: string) => {
    try {
      await axios.put(`${getApiBase(selectedChannel)}/admin/videos/${id}/status`, { status, youtubeUrl });
      // Update local state
      setVideos(prev => prev.map(v => v.id === id ? { ...v, status, youtubeUrl: youtubeUrl || v.youtubeUrl } : v));
      showSuccess("Status updated successfully!");
    } catch (e) {
      showError("Failed to update status");
      console.error(e);
    }
  };

  const onDeleteVideo = async (id: string) => {
    if (!await confirmAction("이 영상을 정말 삭제하시겠습니까? (파일과 데이터가 영구 삭제됩니다)")) return;
    setLoading(true);
    try {
      await axios.delete(`${getApiBase(selectedChannel)}/admin/videos/${id}`);
      // Update local state - remove from list
      setVideos(prev => prev.filter(v => v.id !== id));
      setTotalCount(prev => prev - 1);
      showSuccess("영상 삭제 성공");
    } catch (e) {
      showError("영상 삭제 실패");
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const savePrompt = async (prompt: SystemPrompt) => {
    try {
      await axios.post(`${getApiBase(selectedChannel)}/admin/prompts`, prompt);
      showSuccess("Prompt saved!");
      // Prompts don't affect video list
    } catch (e) {
      showError("Failed to save prompt");
    }
  };

  const saveSetting = async (key: string, value: string, desc: string) => {
    try {
      await axios.post(`${getApiBase(selectedChannel)}/admin/settings`, { channelId: selectedChannel, key, value, description: desc });
      showSuccess("Setting saved!");
      // Settings don't affect video list
    } catch (e) {
      showError("Failed to save setting");
    }
  };

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

  // Infinite scroll observer
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

  return (
    <ErrorBoundary>
      <div className="min-h-screen bg-[var(--bg-color)] text-[var(--text-primary)] font-sans selection:bg-purple-500 selection:text-white overflow-x-hidden transition-colors duration-300">
        <Toaster position="top-right" richColors closeButton />
        {/* Background Decorative Elements */}
        <div className="fixed inset-0 overflow-hidden pointer-events-none -z-10">
          <div className="absolute -top-[10%] -left-[10%] w-[40%] h-[40%] bg-purple-600/10 blur-[120px] rounded-full" />
          <div className="absolute top-[20%] -right-[10%] w-[35%] h-[35%] bg-indigo-600/10 blur-[120px] rounded-full" />
        </div>

        <Sidebar
          activeTab={activeTab}
          setActiveTab={setActiveTab}
          isMobileMenuOpen={isMobileMenuOpen}
          setIsMobileMenuOpen={setIsMobileMenuOpen}
          language={language}
          setLanguage={setLanguage}
          theme={theme}
          setTheme={setTheme}
          t={t}
          installPrompt={installPrompt}
          setInstallPrompt={setInstallPrompt}
        />

        {/* Main Content */}
        <main className="md:ml-72 min-h-screen p-2 md:p-10 transition-all duration-300">
          <header className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 mb-10 pl-2 md:pl-0">
            <div>
              <h2 className="text-2xl md:text-4xl font-extrabold tracking-tight text-[var(--text-primary)] mb-1">
                {activeTab === 'videos' ? t.videos :
                  activeTab === 'prompts' ? t.prompts :
                    activeTab === 'settings' ? t.settings :
                      activeTab === 'logs' ? t.logs :
                        activeTab === 'youtube' ? t.youtubeVideos :
                          activeTab === 'bgm' ? (language === 'ko' ? 'BGM 관리' : 'AI BGM Manager') : t.tools}
              </h2>
              <p className="text-sm text-[var(--text-secondary)] font-medium italic">
                {activeTab === 'videos' ? (language === 'ko' ? `AI 영상 생성 파이프라인 실시간 관리 (총 ${totalCount}개)` : `Manage AI video pipeline (Total ${totalCount})`) :
                  activeTab === 'prompts' ? (language === 'ko' ? '콘텐츠 품질 향상을 위한 LLM 지침 설정' : 'Configure LLM instructions.') :
                    activeTab === 'settings' ? (language === 'ko' ? '전역 시스템 파라미터 및 제한값 설정' : 'Global params and limits.') :
                      activeTab === 'logs' ? (language === 'ko' ? '시스템 전체 이벤트 실시간 모니터링' : 'Real-time system events monitoring.') :
                        activeTab === 'youtube' ? (language === 'ko' ? '내 유튜브 채널의 실제 업로드 영상 및 실시간 통계' : 'Real-time YouTube channel statistics.') :
                          activeTab === 'bgm' ? (language === 'ko' ? 'AI가 음악을 분석하여 자동 분류합니다.' : 'Upload music, AI will listen and sort it.') : (language === 'ko' ? '인프라 점검 및 유지보수 도구' : 'Infrastructure tasks.')}
              </p>
            </div>

            <div className="flex flex-col md:flex-row items-stretch md:items-center gap-3 w-full md:w-auto">
              {/* Channel Selector */}
              <div className="flex bg-[var(--input-bg)] p-1 rounded-2xl border border-[var(--input-border)] backdrop-blur-md">
                {channels.map((ch) => (
                  <button
                    key={ch.id}
                    onClick={() => setSelectedChannel(ch.id)}
                    className={cn(
                      "px-4 py-2 text-xs font-bold rounded-xl transition-all duration-300",
                      selectedChannel === ch.id
                        ? "bg-black/5 dark:bg-white/10 text-black dark:text-white shadow-lg ring-1 ring-black/10 dark:ring-white/20"
                        : "text-[var(--text-secondary)] hover:text-black dark:hover:text-white"
                    )}
                  >
                    <span className={cn("mr-1.5", selectedChannel === ch.id ? ch.color : "text-gray-400 dark:text-gray-500")}>●</span>
                    {ch.name}
                  </button>
                ))}
              </div>

              <button
                onClick={() => fetchData(0)}
                disabled={loading}
                className="group flex items-center justify-center gap-2 px-5 py-2.5 bg-purple-600 hover:bg-purple-500 text-white text-sm font-bold rounded-xl shadow-lg shadow-purple-500/20 transition-all active:scale-95 disabled:opacity-50"
              >
                <RefreshCw size={16} className={cn("transition-transform duration-700", loading ? "animate-spin" : "group-hover:rotate-180")} />
                {t.syncData}
              </button>
            </div>
          </header>

          {activeTab === 'videos' && (
            <div className="grid gap-6">
              {/* View Mode Toggle (Active vs History) */}
              <div className="flex gap-4 border-b border-gray-200 dark:border-gray-800 pb-2">
                <button
                  onClick={() => { setStatusFilter('NOT_UPLOADED'); setSearchTerm(''); }}
                  className={cn(
                    "px-4 py-2 text-sm font-bold rounded-t-lg transition-all",
                    statusFilter !== 'UPLOADED'
                      ? "border-b-2 border-purple-500 text-purple-600 dark:text-purple-400 bg-purple-500/5"
                      : "text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
                  )}
                >
                  🚀 {language === 'ko' ? '진행 중' : 'Active'}
                </button>
                <button
                  onClick={() => { setStatusFilter('UPLOADED'); setSearchTerm(''); }}
                  className={cn(
                    "px-4 py-2 text-sm font-bold rounded-t-lg transition-all",
                    statusFilter === 'UPLOADED'
                      ? "border-b-2 border-green-500 text-green-600 dark:text-green-400 bg-green-500/5"
                      : "text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
                  )}
                >
                  ✅ {language === 'ko' ? '업로드 완료' : 'Uploaded'}
                </button>
              </div>

              <FilterBar
                searchTerm={searchTerm}
                setSearchTerm={setSearchTerm}
                statusFilter={statusFilter}
                setStatusFilter={setStatusFilter}
                videos={videos}
                t={t}
                hideStatusFilter={true} // Hide the dropdown status filter since we use tabs now
              />

              {filteredVideos.map((video, index) => {
                const isLast = index === filteredVideos.length - 1;
                return (
                  <VideoCard
                    key={video.id}
                    video={video}
                    onDownload={() => downloadVideo(video.id || '')}
                    onRegenerateMetadata={onRegenerateMetadata}
                    onManualUpload={onManualUpload}
                    onUpdateStatus={updateVideoStatus}
                    onDelete={onDeleteVideo}
                    onRetry={onRetryVideo}
                    t={t}
                    ref={isLast ? lastVideoRef : undefined}
                  />
                );
              })}
              {(loadingMore || loading) && videos.length > 0 && (
                <div className="flex justify-center p-10">
                  <RefreshCw className="w-8 h-8 animate-spin text-purple-400" />
                </div>
              )}
              {!nextPage && videos.length > 0 && (
                <div className="text-center py-10 text-gray-500 text-sm italic">
                  {language === 'ko' ? '모든 영상을 불러왔습니다.' : 'No more videos.'}
                </div>
              )}
              {videos.length === 0 && !loading && (
                <div className="text-center py-20 text-gray-500">
                  {statusFilter === 'UPLOADED'
                    ? (language === 'ko' ? '아직 업로드된 영상이 없습니다.' : 'No uploaded videos yet.')
                    : (language === 'ko' ? '진행 중인 영상이 없습니다.' : 'No active videos.')}
                </div>
              )}
            </div>
          )}

          {/* Prompt editor */}
          {activeTab === 'prompts' && (
            <PromptEditor prompts={prompts} t={t} onSave={savePrompt} />
          )}

          {activeTab === 'tools' && (
            <ToolsPanel
              t={t}
              runBatchAction={runBatchAction}
              loading={loading}
              toolsResult={toolsResult}
            />
          )}

          {activeTab === 'settings' && (
            <SettingsPanel
              t={t}
              settings={settings}
              saveSetting={saveSetting}
            />
          )}

          {activeTab === 'logs' && (
            <LogViewer t={t} />
          )}

          {activeTab === 'youtube' && (
            <YoutubeVideoList t={t} language={language} selectedChannel={selectedChannel} />
          )}

          {activeTab === 'bgm' && (
            <BgmManager selectedChannel={selectedChannel} />
          )}
        </main>
      </div>
    </ErrorBoundary>
  );
}

export default App;

