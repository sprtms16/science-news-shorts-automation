import { useEffect, useState } from 'react';
import axios from 'axios';
import type { VideoHistory, SystemPrompt } from './types';
import {
  LayoutDashboard,
  FileText,
  RefreshCw,
  Download,
  ExternalLink,
  AlertCircle,
  CheckCircle2,
  Clock,
  PlayCircle,
  Settings,
  Trash2,
  Menu,
  X,
  Search,
  Youtube,
  ChevronRight,
  ShieldCheck,
  Languages,
  Sun,
  Moon,
  Monitor
} from 'lucide-react';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { translations, type Language } from './i18n';

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
    <div className="min-h-screen bg-[#0f172a] text-[#e2e8f0] font-sans selection:bg-purple-500 selection:text-white overflow-x-hidden">
      {/* Background Decorative Elements */}
      <div className="fixed inset-0 overflow-hidden pointer-events-none -z-10">
        <div className="absolute -top-[10%] -left-[10%] w-[40%] h-[40%] bg-purple-600/10 blur-[120px] rounded-full" />
        <div className="absolute top-[20%] -right-[10%] w-[35%] h-[35%] bg-indigo-600/10 blur-[120px] rounded-full" />
      </div>

      {/* Mobile Header */}
      <header className="md:hidden sticky top-0 z-50 glass-morphism px-4 py-3 flex justify-between items-center border-b border-white/5">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-purple-500 to-indigo-600 flex items-center justify-center">
            <Youtube size={18} className="text-white" />
          </div>
          <h1 className="text-lg font-bold accent-text">SciencePixels</h1>
        </div>
        <button
          onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}
          className="p-2 hover:bg-white/5 rounded-lg transition-colors"
        >
          {isMobileMenuOpen ? <X size={24} /> : <Menu size={24} />}
        </button>
      </header>

      {/* Sidebar Overlay (Mobile) */}
      {isMobileMenuOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/60 backdrop-blur-sm md:hidden"
          onClick={() => setIsMobileMenuOpen(false)}
        />
      )}

      {/* Sidebar */}
      <aside className={cn(
        "fixed left-0 top-0 h-full w-72 z-50 glass-morphism border-r border-white/5 flex flex-col transition-transform duration-300 ease-in-out md:translate-x-0",
        isMobileMenuOpen ? "translate-x-0" : "-translate-x-full"
      )}>
        <div className="p-8">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-purple-500 to-indigo-600 flex items-center justify-center shadow-lg shadow-purple-500/20">
              <Youtube size={24} className="text-white" />
            </div>
            <div>
              <h1 className="text-xl font-bold tracking-tight accent-text">
                SciencePixels
              </h1>
              <p className="text-[10px] text-gray-500 font-medium uppercase tracking-widest">Shorts Automation</p>
            </div>
          </div>
        </div>

        <nav className="flex-1 px-4 space-y-1.5 overflow-y-auto custom-scrollbar">
          <NavItem
            icon={<LayoutDashboard size={18} />}
            label={t.dashboard}
            active={activeTab === 'videos'}
            onClick={() => { setActiveTab('videos'); setIsMobileMenuOpen(false); }}
          />
          <NavItem
            icon={<FileText size={18} />}
            label={t.prompts}
            active={activeTab === 'prompts'}
            onClick={() => { setActiveTab('prompts'); setIsMobileMenuOpen(false); }}
          />
          <NavItem
            icon={<RefreshCw size={18} />}
            label={t.tools}
            active={activeTab === 'tools'}
            onClick={() => { setActiveTab('tools'); setIsMobileMenuOpen(false); }}
          />
          <NavItem
            icon={<Settings size={18} />}
            label={t.settings}
            active={activeTab === 'settings'}
            onClick={() => { setActiveTab('settings'); setIsMobileMenuOpen(false); }}
          />
        </nav>

        {/* Language & Theme Selectors */}
        <div className="px-4 py-6 space-y-4 border-t border-white/5">
          <div className="space-y-2">
            <label className="flex items-center gap-2 text-[10px] font-bold text-gray-500 uppercase tracking-widest ml-1">
              <Languages size={12} /> {t.language}
            </label>
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => setLanguage('ko')}
                className={cn(
                  "px-3 py-1.5 rounded-lg text-xs font-bold transition-all border",
                  language === 'ko' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-white/5 text-gray-500 border-transparent hover:bg-white/10"
                )}
              >
                KO
              </button>
              <button
                onClick={() => setLanguage('en')}
                className={cn(
                  "px-3 py-1.5 rounded-lg text-xs font-bold transition-all border",
                  language === 'en' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-white/5 text-gray-500 border-transparent hover:bg-white/10"
                )}
              >
                EN
              </button>
            </div>
          </div>

          <div className="space-y-2">
            <label className="flex items-center gap-2 text-[10px] font-bold text-gray-500 uppercase tracking-widest ml-1">
              <Monitor size={12} /> {t.theme}
            </label>
            <div className="grid grid-cols-3 gap-2">
              <button
                onClick={() => setTheme('light')}
                className={cn(
                  "p-2 rounded-lg transition-all border flex justify-center",
                  theme === 'light' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-white/5 text-gray-500 border-transparent hover:bg-white/10"
                )}
                title={t.light}
              >
                <Sun size={14} />
              </button>
              <button
                onClick={() => setTheme('dark')}
                className={cn(
                  "p-2 rounded-lg transition-all border flex justify-center",
                  theme === 'dark' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-white/5 text-gray-500 border-transparent hover:bg-white/10"
                )}
                title={t.dark}
              >
                <Moon size={14} />
              </button>
              <button
                onClick={() => setTheme('system')}
                className={cn(
                  "p-2 rounded-lg transition-all border flex justify-center",
                  theme === 'system' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-white/5 text-gray-500 border-transparent hover:bg-white/10"
                )}
                title={t.system}
              >
                <Monitor size={14} />
              </button>
            </div>
          </div>
        </div>

        <div className="p-6 border-t border-white/5">
          <div className="flex items-center gap-3 p-3 rounded-xl bg-white/5 border border-white/5">
            <div className="w-8 h-8 rounded-full bg-gray-700 flex items-center justify-center">
              <ShieldCheck size={16} className="text-purple-400" />
            </div>
            <div className="flex-1 overflow-hidden">
              <p className="text-xs font-semibold truncate">Admin Console</p>
              <p className="text-[10px] text-gray-500 truncate">v2.1.0-Modern</p>
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content */}
      <main className="md:ml-72 min-h-screen p-4 md:p-10 transition-all duration-300">
        <header className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 mb-10">
          <div>
            <h2 className="text-2xl md:text-4xl font-extrabold tracking-tight text-var(--text-primary) mb-1">
              {activeTab === 'videos' ? t.videos :
                activeTab === 'prompts' ? t.prompts :
                  activeTab === 'settings' ? t.settings : t.tools}
            </h2>
            <p className="text-sm text-gray-500 font-medium italic">
              {activeTab === 'videos' ? (language === 'ko' ? 'AI 영상 생성 파이프라인 실시간 관리' : 'Manage your AI video pipeline.') :
                activeTab === 'prompts' ? (language === 'ko' ? '콘텐츠 품질 향상을 위한 LLM 지침 설정' : 'Configure LLM instructions.') :
                  activeTab === 'settings' ? (language === 'ko' ? '전역 시스템 파라미터 및 제한값 설정' : 'Global params and limits.') : (language === 'ko' ? '인프라 점검 및 유지보수 도구' : 'Infrastructure tasks.')}
            </p>
          </div>
          <button
            onClick={fetchData}
            disabled={loading}
            className="group flex items-center gap-2 px-5 py-2.5 bg-white/5 hover:bg-white/10 text-sm font-semibold rounded-xl border border-white/10 transition-all active:scale-95 disabled:opacity-50"
          >
            <RefreshCw size={16} className={cn("transition-transform duration-700", loading ? "animate-spin" : "group-hover:rotate-180")} />
            {t.syncData}
          </button>
        </header>

        {activeTab === 'videos' && (
          <div className="grid gap-6">
            {/* Filter Bar */}
            <div className="glass-morphism p-5 rounded-2xl border border-white/5 flex flex-col lg:flex-row items-stretch lg:items-end gap-5">
              <div className="flex-1">
                <label className="block text-[10px] font-bold text-gray-500 uppercase tracking-widest mb-2 ml-1">Search Pipeline</label>
                <div className="relative group">
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500 group-focus-within:text-purple-400 transition-colors">
                    <Search size={16} />
                  </span>
                  <input
                    type="text"
                    placeholder={t.searchPlaceholder}
                    className="w-full bg-white/5 border border-white/10 rounded-xl pl-10 pr-4 py-2.5 text-sm text-var(--text-primary) focus:border-purple-500/50 focus:ring-4 focus:ring-purple-500/10 outline-none transition-all placeholder:text-gray-600"
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                  />
                  {searchTerm && (
                    <button
                      onClick={() => setSearchTerm('')}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-white transition-colors"
                    >
                      <X size={14} />
                    </button>
                  )}
                </div>
              </div>

              <div className="lg:w-64">
                <label className="block text-[10px] font-bold text-gray-500 uppercase tracking-widest mb-2 ml-1">Status Intelligence</label>
                <div className="relative">
                  <select
                    className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-2.5 text-sm text-var(--text-primary) focus:border-purple-500/50 outline-none cursor-pointer appearance-none transition-all"
                    value={statusFilter}
                    onChange={(e) => setStatusFilter(e.target.value)}
                    style={{ backgroundImage: 'url("data:image/svg+xml;charset=UTF-8,%3csvg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'%2364748b\' stroke-width=\'2\' stroke-linecap=\'round\' stroke-linejoin=\'round\'%3e%3cpolyline points=\'6 9 12 15 18 9\'%3e%3c/polyline%3e%3c/svg%3e")', backgroundRepeat: 'no-repeat', backgroundPosition: 'right 1rem center', backgroundSize: '1rem' }}
                  >
                    <option value="ALL" className="bg-gray-900">{t.statusAll}</option>
                    <option value="UPLOADED" className="bg-gray-900">✅ {t.statusUploaded}</option>
                    <option value="NOT_UPLOADED" className="bg-gray-900">⏳ {t.statusNotUploaded}</option>
                    <option value="PROCESSING" className="bg-gray-900">⚙️ AI Processing</option>
                    <option value="COMPLETED" className="bg-gray-900">📦 Asset Ready</option>
                    <option value="ERROR" className="bg-gray-900">❌ Fatal Error</option>
                    <option value="REGENERATING" className="bg-gray-900">🔄 Healing/Regen</option>
                  </select>
                </div>
              </div>

              <div className="flex items-center justify-between lg:justify-end gap-3 lg:pb-0.5">
                <div className="px-4 py-2.5 rounded-xl bg-white/5 border border-white/5 text-[11px] font-bold text-gray-500">
                  {t.totalFiltered}: <span className="text-purple-400 font-mono text-xs">{videos.filter(v => {
                    const matchesSearch = v.title.toLowerCase().includes(searchTerm.toLowerCase());
                    const matchesStatus = statusFilter === 'ALL' ? true :
                      statusFilter === 'UPLOADED' ? v.status === 'UPLOADED' :
                        statusFilter === 'NOT_UPLOADED' ? v.status !== 'UPLOADED' :
                          v.status === statusFilter;
                    return matchesSearch && matchesStatus;
                  }).length}</span>
                </div>
                <div className="px-4 py-2.5 rounded-xl bg-purple-500/10 border border-purple-500/20 text-[11px] font-bold text-purple-400">
                  {t.totalSystem}: <span className="font-mono text-xs">{videos.length}</span>
                </div>
              </div>
            </div>

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
          <div className="grid gap-8 max-w-5xl">
            {prompts.map(prompt => (
              <div key={prompt.id} className="glass-morphism p-8 rounded-3xl border border-white/5 space-y-6 text-var(--text-primary)">
                <div className="flex justify-between items-center">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-xl bg-purple-500/10 flex items-center justify-center text-purple-400 border border-purple-500/20">
                      <FileText size={20} />
                    </div>
                    <div>
                      <h3 className="text-xl font-bold text-white tracking-tight">{prompt.id}</h3>
                      <p className="text-[10px] text-gray-500 font-bold uppercase tracking-widest">{t.instructionEngine}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 px-3 py-1 bg-white/5 rounded-full border border-white/5">
                    <Clock size={12} className="text-gray-500" />
                    <span className="text-[10px] font-bold text-gray-400">{new Date(prompt.updatedAt).toLocaleString()}</span>
                  </div>
                </div>
                <textarea
                  className="w-full h-80 bg-black/40 border border-white/10 rounded-2xl p-6 font-mono text-sm text-gray-300 focus:border-purple-500/50 focus:ring-4 focus:ring-purple-500/10 outline-none resize-y transition-all"
                  defaultValue={prompt.content}
                />
                <div className="flex justify-end">
                  <button className="px-8 py-3 bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 text-white rounded-xl font-bold shadow-lg shadow-purple-500/20 transition-all active:scale-95">
                    {t.updateProcessor}
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {activeTab === 'tools' && (
          <div className="grid gap-8 max-w-5xl">
            <div className="glass-morphism p-8 md:p-10 rounded-3xl border border-white/5">
              <div className="mb-10">
                <h3 className="text-2xl font-bold text-white mb-2">{t.systemMaintenance}</h3>
                <p className="text-sm text-gray-500 font-medium">{t.maintenanceDescription}</p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                <div className="p-6 bg-white/5 rounded-2xl border border-white/5 hover:border-blue-500/30 transition-all group">
                  <div className="w-12 h-12 rounded-xl bg-blue-500/10 flex items-center justify-center text-blue-400 mb-4 group-hover:scale-110 transition-transform">
                    <RefreshCw size={24} />
                  </div>
                  <h4 className="text-lg font-bold text-blue-400 mb-2">{t.rematchFiles}</h4>
                  <p className="text-xs text-gray-500 leading-relaxed mb-6">
                    {t.rematchDescription}
                  </p>
                  <button
                    onClick={() => runBatchAction('rematch-files')}
                    disabled={loading}
                    className="w-full py-3 bg-blue-600/10 hover:bg-blue-600 text-blue-400 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-blue-600/20"
                  >
                    {t.flashSync}
                  </button>
                </div>

                <div className="p-6 bg-white/5 rounded-2xl border border-white/5 hover:border-emerald-500/30 transition-all group">
                  <div className="w-12 h-12 rounded-xl bg-emerald-500/10 flex items-center justify-center text-emerald-400 mb-4 group-hover:scale-110 transition-transform">
                    <PlayCircle size={24} />
                  </div>
                  <h4 className="text-lg font-bold text-emerald-400 mb-2">{t.localizationBatch}</h4>
                  <p className="text-xs text-gray-500 leading-relaxed mb-6">
                    {t.localizationDescription}
                  </p>
                  <button
                    onClick={() => runBatchAction('regenerate-all-metadata')}
                    disabled={loading}
                    className="w-full py-3 bg-emerald-600/10 hover:bg-emerald-600 text-emerald-400 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-emerald-600/20"
                  >
                    {t.translateAll}
                  </button>
                </div>

                <div className="p-6 bg-white/5 rounded-2xl border border-white/5 hover:border-orange-500/30 transition-all group md:col-span-2">
                  <div className="flex items-start gap-6">
                    <div className="w-14 h-14 rounded-2xl bg-orange-500/10 flex items-center justify-center text-orange-400 shrink-0 group-hover:scale-105 transition-transform">
                      <AlertCircle size={28} />
                    </div>
                    <div className="flex-1">
                      <h4 className="text-lg font-bold text-orange-400 mb-1">{t.deepArchiveRepair}</h4>
                      <p className="text-xs text-gray-500 leading-relaxed mb-4">
                        {t.repairDescription}
                      </p>
                      <button
                        onClick={() => runBatchAction('regenerate-missing-files')}
                        disabled={loading}
                        className="px-8 py-3 bg-orange-600/10 hover:bg-orange-600 text-orange-400 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-orange-500/20"
                      >
                        {t.launchRepair}
                      </button>
                    </div>
                  </div>
                </div>

                <div className="p-6 bg-white/5 rounded-2xl border border-white/5 hover:border-rose-500/30 transition-all group md:col-span-2">
                  <div className="flex items-start gap-6">
                    <div className="w-14 h-14 rounded-2xl bg-rose-500/10 flex items-center justify-center text-rose-500 shrink-0 group-hover:scale-105 transition-transform">
                      <ShieldCheck size={28} />
                    </div>
                    <div className="flex-1">
                      <h4 className="text-lg font-bold text-rose-500 mb-1">{t.safetyPurge}</h4>
                      <p className="text-xs text-gray-500 leading-relaxed mb-4">
                        {t.purgeDescription}
                      </p>
                      <button
                        onClick={() => runBatchAction('cleanup-sensitive')}
                        disabled={loading}
                        className="px-8 py-3 bg-rose-600/10 hover:bg-rose-600 text-rose-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-rose-500/20"
                      >
                        {t.executePurge}
                      </button>
                    </div>
                  </div>
                </div>
              </div>

              {toolsResult && (
                <div className="mt-12 p-8 bg-black/40 rounded-3xl border border-purple-500/20 shadow-inner">
                  <div className="flex items-center gap-2 mb-4">
                    <div className="w-2 h-2 rounded-full bg-purple-500 animate-pulse" />
                    <h4 className="text-xs font-bold text-purple-400 uppercase tracking-widest">{t.executionLog}</h4>
                  </div>
                  <pre className="text-[11px] text-gray-500 overflow-auto max-h-80 font-mono scrollbar-hide">
                    {JSON.stringify(toolsResult, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        )}

        {activeTab === 'settings' && (
          <div className="max-w-4xl grid gap-8">
            <div className="glass-morphism p-8 md:p-10 rounded-3xl border border-white/5">
              <div className="mb-10">
                <h3 className="text-2xl font-bold text-white mb-2">{t.engineConstraints}</h3>
                <p className="text-sm text-gray-500 font-medium">{t.settingsDescription}</p>
              </div>

              <div className="space-y-10">
                <div className="flex flex-col md:flex-row md:items-center gap-6 p-6 bg-white/5 rounded-2xl border border-white/5">
                  <div className="flex-1">
                    <h4 className="text-base font-bold text-var(--text-primary) mb-1">{t.queueBufferSize}</h4>
                    <p className="text-xs text-gray-500">{t.bufferDescription}</p>
                  </div>
                  <div className="flex gap-3 shrink-0">
                    <input
                      type="number"
                      className="bg-black/40 border border-white/10 rounded-xl px-4 py-2 text-white w-24 focus:border-purple-500/50 outline-none font-mono font-bold transition-all text-center"
                      defaultValue={settings.find(s => s.key === 'MAX_GENERATION_LIMIT')?.value || '10'}
                      id="maxGenInput"
                    />
                    <button
                      onClick={() => {
                        const val = (document.getElementById('maxGenInput') as HTMLInputElement).value;
                        saveSetting('MAX_GENERATION_LIMIT', val, 'Max unuploaded videos to keep buffered');
                      }}
                      className="px-6 py-2 bg-purple-600 hover:bg-purple-500 text-white rounded-xl font-bold shadow-lg shadow-purple-500/20 transition-all active:scale-95"
                    >
                      {t.commit}
                    </button>
                  </div>
                </div>

                <div className="pt-10 border-t border-white/5">
                  <h4 className="text-base font-bold text-var(--text-primary) mb-4">{t.youtubeQuotaShield}</h4>
                  {settings.find(s => s.key === 'UPLOAD_BLOCKED_UNTIL') ? (
                    <div className="p-6 bg-rose-500/10 border border-rose-500/20 rounded-2xl flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
                      <div>
                        <div className="flex items-center gap-2 text-rose-400 font-bold mb-1">
                          <AlertCircle size={18} />
                          <span>{t.uploadBlockActive}</span>
                        </div>
                        <p className="text-xs text-rose-400/70">
                          {t.autoResumeScheduled}: <span className="font-mono">{new Date(settings.find(s => s.key === 'UPLOAD_BLOCKED_UNTIL')?.value).toLocaleString()}</span>
                        </p>
                      </div>
                      <button
                        onClick={() => saveSetting('UPLOAD_BLOCKED_UNTIL', '', 'Force Unblock')}
                        className="px-5 py-2.5 bg-rose-600 hover:bg-rose-500 text-white text-xs font-bold rounded-xl shadow-lg shadow-rose-500/20 transition-all active:scale-95"
                      >
                        {t.forceBypass}
                      </button>
                    </div>
                  ) : (
                    <div className="p-6 bg-emerald-500/10 border border-emerald-500/20 rounded-2xl flex items-center gap-4">
                      <div className="w-10 h-10 rounded-full bg-emerald-500/20 flex items-center justify-center text-emerald-400">
                        <CheckCircle2 size={24} />
                      </div>
                      <div>
                        <p className="text-emerald-400 font-bold">{t.systemsOperational}</p>
                        <p className="text-[10px] text-emerald-400/60 uppercase tracking-widest font-bold">{t.quotaAvailable}</p>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

function NavItem({ icon, label, active, onClick }: { icon: any, label: string, active: boolean, onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "w-full flex items-center justify-between px-5 py-3.5 rounded-2xl text-sm font-bold transition-all duration-300 group",
        active
          ? "bg-gradient-to-r from-purple-600/20 to-indigo-600/20 text-purple-400 border border-purple-500/20 shadow-lg shadow-purple-500/10"
          : "text-gray-500 hover:bg-white/5 hover:text-gray-200 border border-transparent"
      )}
    >
      <div className="flex items-center gap-4">
        <span className={cn("transition-colors duration-300", active ? "text-purple-400" : "text-gray-600 group-hover:text-gray-400")}>
          {icon}
        </span>
        {label}
      </div>
      {active && <ChevronRight size={14} className="text-purple-500/50" />}
    </button>
  );
}

function VideoCard({ video, onDownload, onRegenerateMetadata, onUpdateStatus, onDelete, t }: { video: VideoHistory, onDownload: () => void, onRegenerateMetadata: (id: string) => void, onUpdateStatus: (id: string, status: string, url?: string) => void, onDelete: (id: string) => void, t: any }) {
  const statusColors: Record<string, { bg: string, text: string, border: string, icon: any }> = {
    'COMPLETED': { bg: 'bg-emerald-500/10', text: 'text-emerald-400', border: 'border-emerald-500/20', icon: <CheckCircle2 size={12} /> },
    'PROCESSING': { bg: 'bg-amber-500/10', text: 'text-amber-400', border: 'border-amber-500/20', icon: <RefreshCw size={12} className="animate-spin" /> },
    'REGENERATING': { bg: 'bg-purple-500/10', text: 'text-purple-400', border: 'border-purple-500/20', icon: <RefreshCw size={12} className="animate-spin" /> },
    'ERROR': { bg: 'bg-rose-500/10', text: 'text-rose-400', border: 'border-rose-500/20', icon: <AlertCircle size={12} /> },
    'UPLOADED': { bg: 'bg-sky-500/10', text: 'text-sky-400', border: 'border-sky-500/20', icon: <Youtube size={12} /> },
    'QUOTA_EXCEEDED': { bg: 'bg-orange-500/10', text: 'text-orange-400', border: 'border-orange-500/20', icon: <AlertCircle size={12} /> },
    'RETRY_PENDING': { bg: 'bg-indigo-500/10', text: 'text-indigo-400', border: 'border-indigo-500/20', icon: <Clock size={12} /> },
    'FILE_NOT_FOUND': { bg: 'bg-pink-500/10', text: 'text-pink-400', border: 'border-pink-500/20', icon: <AlertCircle size={12} /> },
  };

  const currentStatus = statusColors[video.status] || { bg: 'bg-slate-500/10', text: 'text-slate-400', border: 'border-slate-500/20', icon: <AlertCircle size={12} /> };
  const isKoreanTitle = /[가-힣]/.test(video.title);

  const copyTitle = () => {
    navigator.clipboard.writeText(video.title);
    alert(t.copyTitle);
  };

  const copyDescription = () => {
    const tagsStr = video.tags?.map(t => `#${t}`).join(' ') || '';
    const sourcesStr = video.sources?.length ? `\n\n📚 출처: ${video.sources.join(', ')}` : '';
    const fullText = `${video.description || video.summary}${sourcesStr}\n\n${tagsStr}`;
    navigator.clipboard.writeText(fullText);
    alert('설명+출처+태그가 복사되었습니다!');
  };

  const getSourceUrl = (source: string) => {
    const lowerSource = source.toLowerCase();
    if (lowerSource.includes('nature')) return 'https://www.nature.com';
    if (lowerSource.includes('nasa')) return 'https://www.nasa.gov';
    if (lowerSource.includes('science')) return 'https://www.sciencedaily.com';
    if (lowerSource.includes('arxiv')) return 'https://arxiv.org';
    if (lowerSource.includes('mit')) return 'https://news.mit.edu';
    return `https://www.google.com/search?q=${encodeURIComponent(source)}`;
  };

  return (
    <div className="glass-morphism rounded-3xl border border-white/5 overflow-hidden group hover:border-purple-500/30 transition-all duration-500 shadow-2xl">
      <div className="p-5 md:p-8">
        <div className="flex flex-col lg:flex-row justify-between items-start gap-6">
          <div className="flex-1 space-y-4 max-w-full overflow-hidden">
            <div className="flex flex-wrap items-center gap-2">
              <span className={cn(
                "inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold border tracking-wider uppercase",
                currentStatus.bg, currentStatus.text, currentStatus.border
              )}>
                {currentStatus.icon}
                {video.status}
              </span>
              {!isKoreanTitle && (
                <span className="px-3 py-1 bg-orange-500/10 text-orange-400 text-[10px] font-bold rounded-full border border-orange-500/20 tracking-wider uppercase">{t.needsLocalization}</span>
              )}
            </div>

            <div className="space-y-2">
              <div className="flex items-center gap-3">
                <h3 className="text-xl md:text-2xl font-bold text-white tracking-tight leading-tight group-hover:text-purple-300 transition-colors">
                  {video.title}
                </h3>
                <button onClick={copyTitle} className="p-2 hover:bg-white/10 rounded-xl transition-all text-gray-500 hover:text-white" title={t.copyTitle}>
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2" /><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" /></svg>
                </button>
              </div>
              <p className="text-sm text-gray-400 leading-relaxed line-clamp-2 font-medium" dangerouslySetInnerHTML={{ __html: video.summary }} />
            </div>
          </div>

          <div className="flex flex-row lg:flex-col gap-2 w-full lg:w-32 shrink-0">
            <button
              onClick={() => onRegenerateMetadata(video.id || '')}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 bg-blue-500/10 hover:bg-blue-500 text-blue-400 hover:text-white rounded-xl border border-blue-500/20 transition-all text-xs font-bold"
            >
              <RefreshCw size={14} /> {t.meta}
            </button>
            <button
              onClick={onDownload}
              disabled={!video.filePath}
              className={cn(
                "flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl border transition-all text-xs font-bold",
                video.filePath
                  ? "bg-purple-500/10 hover:bg-purple-500 text-purple-400 hover:text-white border-purple-500/20"
                  : "bg-gray-800/50 text-gray-600 border-white/5 cursor-not-allowed"
              )}
            >
              <Download size={14} /> {t.getMp4}
            </button>
            <button
              onClick={() => onDelete(video.id || '')}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 bg-rose-500/10 hover:bg-rose-500 text-rose-500 hover:text-white rounded-xl border border-rose-500/20 transition-all text-xs font-bold"
            >
              <Trash2 size={14} /> {t.kill}
            </button>
          </div>
        </div>

        {/* Dynamic Content */}
        {video.description && (
          <div className="mt-8 bg-black/20 rounded-2xl p-5 border border-white/5">
            <div className="flex justify-between items-center mb-3">
              <span className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">{t.masterScript}</span>
              <button onClick={copyDescription} className="flex items-center gap-1.5 px-3 py-1 bg-emerald-500/10 hover:bg-emerald-500 text-emerald-400 hover:text-white rounded-lg text-[10px] font-bold transition-all border border-emerald-500/20">
                <ShieldCheck size={12} /> {t.copyFullMeta}
              </button>
            </div>
            <p className="text-sm text-gray-300 leading-relaxed font-normal">{video.description}</p>
          </div>
        )}

        {/* Intelligence Grid */}
        <div className="mt-6 grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="bg-white/5 p-4 rounded-2xl border border-white/5">
            <span className="text-[10px] font-bold text-gray-500 uppercase tracking-widest block mb-3">{t.aiContextTags}</span>
            <div className="flex flex-wrap gap-2">
              {video.tags?.slice(0, 10).map(tag => (
                <span key={tag} className="px-2.5 py-1 bg-white/5 text-gray-400 text-[10px] font-medium rounded-lg border border-white/5 hover:border-purple-500/30 transition-colors cursor-default">#{tag}</span>
              ))}
              {(!video.tags || video.tags.length === 0) && <span className="text-xs text-gray-600 italic">No tags detected</span>}
            </div>
          </div>
          <div className="bg-white/5 p-4 rounded-2xl border border-white/5">
            <span className="text-[10px] font-bold text-gray-500 uppercase tracking-widest block mb-3">{t.verificationSources}</span>
            <div className="grid grid-cols-1 gap-2">
              {video.sources?.map((source, idx) => (
                <a key={idx} href={getSourceUrl(source)} target="_blank" rel="noreferrer" className="flex items-center gap-2 text-[11px] text-sky-400 hover:text-sky-300 transition-colors truncate">
                  <ExternalLink size={12} /> {source}
                </a>
              ))}
              {(!video.sources || video.sources.length === 0) && <span className="text-xs text-gray-600 italic">No sources linked</span>}
            </div>
          </div>
        </div>

        <div className="mt-8 pt-6 border-t border-white/5 flex flex-col md:flex-row justify-between items-center gap-4 text-[10px] font-bold text-gray-500 uppercase tracking-wider">
          <div className="flex flex-wrap items-center justify-center gap-6">
            <span className="flex items-center gap-2"><Clock size={12} className="text-purple-500" /> {new Date(video.createdAt).toLocaleString()}</span>
            {video.youtubeUrl && (
              <a href={video.youtubeUrl} target="_blank" rel="noreferrer" className="flex items-center gap-1.5 text-rose-500 hover:text-rose-400 transition-colors">
                <Youtube size={14} /> {t.watchOnYoutube}
              </a>
            )}
            {!video.youtubeUrl && video.status !== 'UPLOADED' && (
              <div className="flex items-center gap-3">
                <span className="text-gray-600">{t.syncLink}:</span>
                <input
                  type="text"
                  placeholder={t.pasteYoutubeUrl}
                  className="bg-black/40 border border-white/10 rounded-lg px-3 py-1.5 text-[10px] w-48 focus:border-purple-500/50 outline-none transition-all lowercase"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      const url = (e.target as HTMLInputElement).value;
                      if (url) onUpdateStatus(video.id || '', 'UPLOADED', url);
                    }
                  }}
                />
              </div>
            )}
          </div>
          <div className="font-mono opacity-40">{t.nodeId}: {video.id}</div>
        </div>
      </div>
    </div>
  );
}

export default App;

