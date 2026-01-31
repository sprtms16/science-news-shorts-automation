import { useEffect, useState } from 'react';
import axios from 'axios';
import type { VideoHistory, SystemPrompt } from './types';
import { RefreshCw } from 'lucide-react';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { translations, type Language } from './i18n';
import { Sidebar } from './components/layout/Sidebar';
import { FilterBar } from './components/dashboard/FilterBar';
import { VideoCard } from './components/dashboard/VideoCard';
import { PromptEditor } from './components/dashboard/PromptEditor';
import { ToolsPanel } from './components/dashboard/ToolsPanel';
import { SettingsPanel } from './components/dashboard/SettingsPanel';
import { LogViewer } from './components/dashboard/LogViewer';
import YoutubeVideoList from './components/dashboard/YoutubeVideoList';

function cn(...inputs: (string | undefined | null | false)[]) {
  return twMerge(clsx(inputs));
}

function App() {
  const [activeTab, setActiveTab] = useState<'videos' | 'prompts' | 'tools' | 'settings' | 'logs' | 'youtube'>('videos');
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [videos, setVideos] = useState<VideoHistory[]>([]);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [settings, setSettings] = useState<any[]>([]);
  const [toolsResult, setToolsResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
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
    fetchData();
  }, [activeTab]);

  const fetchData = async (page: number = 0) => {
    const isInitial = page === 0;
    if (isInitial) {
      setLoading(true);
    } else {
      setLoadingMore(true);
    }

    try {
      if (activeTab === 'videos') {
        const res = await axios.get(`/admin/videos?page=${page}&size=15`);
        if (isInitial) {
          setVideos(res.data.videos);
        } else {
          setVideos(prev => [...prev, ...res.data.videos]);
        }
        setNextPage(res.data.nextPage);
        setTotalCount(res.data.totalCount);
      } else if (activeTab === 'prompts') {
        const res = await axios.get('/admin/prompts');
        setPrompts(res.data);
      } else if (activeTab === 'settings') {
        try {
          const res = await axios.get('/admin/settings');
          setSettings(res.data);
        } catch (e) { console.error(e); }
      }
    } catch (error) {
      console.error("Failed to fetch data", error);
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  };

  const downloadVideo = async (id: string) => {
    // Direct link download is more reliable for Mobile/PWA and preserves filename from server headers
    try {
      // Construct the absolute URL (or relative) to trigger browser download
      const downloadUrl = `/admin/videos/${id}/download`;

      // Navigate to the URL - since Content-Disposition is attachment, 
      // the browser will stay on the current page and start the download.
      window.location.href = downloadUrl;
    } catch (e) {
      alert("Download trigger failed");
    }
  };

  const onRegenerateMetadata = async (id: string) => {
    if (!confirm("메타데이터를 한글로 재생성하시겠습니까? (Gemini 쿼터 소모)")) return;
    setLoading(true);
    try {
      await axios.post(`/admin/videos/${id}/metadata/regenerate`);
      await fetchData();
    } catch (e) {
      alert("메타데이터 재생성 실패");
    } finally {
      setLoading(false);
    }
  };

  const runBatchAction = async (action: 'rematch-files' | 'regenerate-all-metadata' | 'regenerate-missing-files' | 'sync-uploaded' | 'cleanup-sensitive' | 'upload-pending') => {
    if (!confirm(`Run ${action}? This may take a while.`)) return;
    setLoading(true);
    setToolsResult(null);
    try {
      const endpoint = action === 'sync-uploaded' ? `/admin/maintenance/sync-uploaded` :
        action === 'cleanup-sensitive' ? `/admin/videos/cleanup-sensitive` :
          `/admin/videos/${action}`;
      const res = await axios.post(endpoint);
      setToolsResult(res.data);
      alert("Batch action completed!");
      await fetchData();
    } catch (e) {
      alert("Action failed");
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const updateVideoStatus = async (id: string, status: string, youtubeUrl?: string) => {
    try {
      await axios.put(`/admin/videos/${id}/status`, { status, youtubeUrl });
      await fetchData();
      alert("Status updated successfully!");
    } catch (e) {
      alert("Failed to update status");
      console.error(e);
    }
  };

  const onDeleteVideo = async (id: string) => {
    if (!confirm("이 영상을 정말 삭제하시겠습니까? (파일과 데이터가 영구 삭제됩니다)")) return;
    setLoading(true);
    try {
      await axios.delete(`/admin/videos/${id}`);
      await fetchData();
      alert("영상 삭제 성공");
    } catch (e) {
      alert("영상 삭제 실패");
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const saveSetting = async (key: string, value: string, desc: string) => {
    try {
      await axios.post('/admin/settings', { key, value, description: desc });
      alert("Setting saved!");
      fetchData();
    } catch (e) {
      alert("Failed to save setting");
    }
  };

  return (
    <div className="min-h-screen bg-[var(--bg-color)] text-[var(--text-primary)] font-sans selection:bg-purple-500 selection:text-white overflow-x-hidden transition-colors duration-300">
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
      <main className="md:ml-72 min-h-screen p-4 md:p-10 transition-all duration-300">
        <header className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 mb-10">
          <div>
            <h2 className="text-2xl md:text-4xl font-extrabold tracking-tight text-[var(--text-primary)] mb-1">
              {activeTab === 'videos' ? t.videos :
                activeTab === 'prompts' ? t.prompts :
                  activeTab === 'settings' ? t.settings :
                    activeTab === 'logs' ? t.logs :
                      activeTab === 'youtube' ? t.youtubeVideos : t.tools}
            </h2>
            <p className="text-sm text-[var(--text-secondary)] font-medium italic">
              {activeTab === 'videos' ? (language === 'ko' ? `AI 영상 생성 파이프라인 실시간 관리 (총 ${totalCount}개)` : `Manage AI video pipeline (Total ${totalCount})`) :
                activeTab === 'prompts' ? (language === 'ko' ? '콘텐츠 품질 향상을 위한 LLM 지침 설정' : 'Configure LLM instructions.') :
                  activeTab === 'settings' ? (language === 'ko' ? '전역 시스템 파라미터 및 제한값 설정' : 'Global params and limits.') :
                    activeTab === 'logs' ? (language === 'ko' ? '시스템 전체 이벤트 실시간 모니터링' : 'Real-time system events monitoring.') :
                      activeTab === 'youtube' ? (language === 'ko' ? '내 유튜브 채널의 실제 업로드 영상 및 실시간 통계' : 'Real-time YouTube channel statistics.') : (language === 'ko' ? '인프라 점검 및 유지보수 도구' : 'Infrastructure tasks.')}
            </p>
          </div>
          <button
            onClick={() => fetchData(0)}
            disabled={loading}
            className="group flex items-center gap-2 px-5 py-2.5 bg-[var(--input-bg)] hover:bg-white/10 text-sm font-semibold rounded-xl border border-[var(--input-border)] transition-all active:scale-95 disabled:opacity-50"
          >
            <RefreshCw size={16} className={cn("transition-transform duration-700", loading ? "animate-spin" : "group-hover:rotate-180")} />
            {t.syncData}
          </button>
        </header>

        {activeTab === 'videos' && (
          <div className="grid gap-6">
            <FilterBar
              searchTerm={searchTerm}
              setSearchTerm={setSearchTerm}
              statusFilter={statusFilter}
              setStatusFilter={setStatusFilter}
              videos={videos}
              t={t}
            />

            {videos.filter(v => {
              const matchesSearch = v.title.toLowerCase().includes(searchTerm.toLowerCase());
              const matchesStatus = statusFilter === 'ALL' ? true :
                statusFilter === 'UPLOADED' ? v.status === 'UPLOADED' :
                  statusFilter === 'NOT_UPLOADED' ? v.status !== 'UPLOADED' :
                    v.status === statusFilter;
              return matchesSearch && matchesStatus;
            }).map((video, index, filteredArray) => {
              const isLast = index === filteredArray.length - 1;
              return (
                <VideoCard
                  key={video.id}
                  video={video}
                  onDownload={() => downloadVideo(video.id || '')}
                  onRegenerateMetadata={onRegenerateMetadata}
                  onUpdateStatus={updateVideoStatus}
                  onDelete={onDeleteVideo}
                  t={t}
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
              <div className="text-center py-20 text-gray-500">No videos found.</div>
            )}
          </div>
        )}

        {/* Prompt editor */}
        {activeTab === 'prompts' && (
          <PromptEditor prompts={prompts} t={t} />
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
          <YoutubeVideoList t={t} language={language} />
        )}
      </main>
    </div >
  );
}

export default App;

