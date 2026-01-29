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

function cn(...inputs: (string | undefined | null | false)[]) {
  return twMerge(clsx(inputs));
}

function App() {
  const [activeTab, setActiveTab] = useState<'videos' | 'prompts' | 'tools' | 'settings'>('videos');
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [videos, setVideos] = useState<VideoHistory[]>([]);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [settings, setSettings] = useState<any[]>([]);
  const [toolsResult, setToolsResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // i18n & Theme states
  const [language, setLanguage] = useState<Language>(() => {
    const saved = localStorage.getItem('app-lang') as Language;
    if (saved) return saved;
    return navigator.language.startsWith('ko') ? 'ko' : 'en';
  });

  const [theme, setTheme] = useState<'light' | 'dark' | 'system'>(() => {
    return (localStorage.getItem('app-theme') as any) || 'system';
  });

  const t = translations[language];

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

  const fetchData = async () => {
    setLoading(true);
    try {
      if (activeTab === 'videos') {
        const res = await axios.get('/admin/videos');
        setVideos(res.data);
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
    }
  };

  const downloadVideo = async (id: string, title: string) => {
    try {
      const response = await axios.get(`/admin/videos/${id}/download`, {
        responseType: 'blob',
      });
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `${title.replace(/[^a-z0-9]/gi, '_').substring(0, 50)}.mp4`);
      document.body.appendChild(link);
      link.click();
      link.remove();
    } catch (e) {
      alert("Download failed via API (File might be missing on server)");
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

  const runBatchAction = async (action: 'rematch-files' | 'regenerate-all-metadata' | 'regenerate-missing-files' | 'sync-uploaded' | 'cleanup-sensitive') => {
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
      />

      {/* Main Content */}
      <main className="md:ml-72 min-h-screen p-4 md:p-10 transition-all duration-300">
        <header className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 mb-10">
          <div>
            <h2 className="text-2xl md:text-4xl font-extrabold tracking-tight text-[var(--text-primary)] mb-1">
              {activeTab === 'videos' ? t.videos :
                activeTab === 'prompts' ? t.prompts :
                  activeTab === 'settings' ? t.settings : t.tools}
            </h2>
            <p className="text-sm text-[var(--text-secondary)] font-medium italic">
              {activeTab === 'videos' ? (language === 'ko' ? 'AI 영상 생성 파이프라인 실시간 관리' : 'Manage your AI video pipeline.') :
                activeTab === 'prompts' ? (language === 'ko' ? '콘텐츠 품질 향상을 위한 LLM 지침 설정' : 'Configure LLM instructions.') :
                  activeTab === 'settings' ? (language === 'ko' ? '전역 시스템 파라미터 및 제한값 설정' : 'Global params and limits.') : (language === 'ko' ? '인프라 점검 및 유지보수 도구' : 'Infrastructure tasks.')}
            </p>
          </div>
          <button
            onClick={fetchData}
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
            }).map(video => (
              <VideoCard
                key={video.id}
                video={video}
                onDownload={() => downloadVideo(video.id || '', video.title)}
                onRegenerateMetadata={onRegenerateMetadata}
                onUpdateStatus={updateVideoStatus}
                onDelete={onDeleteVideo}
                t={t}
              />
            ))}
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
      </main>
    </div>
  );
}

export default App;

