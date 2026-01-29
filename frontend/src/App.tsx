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
  Trash2
} from 'lucide-react';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

function cn(...inputs: (string | undefined | null | false)[]) {
  return twMerge(clsx(inputs));
}

function App() {
  const [activeTab, setActiveTab] = useState<'videos' | 'prompts' | 'tools' | 'settings'>('videos');
  const [videos, setVideos] = useState<VideoHistory[]>([]);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [settings, setSettings] = useState<any[]>([]);
  const [toolsResult, setToolsResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

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
    <div className="min-h-screen bg-[#1a1a1a] text-white font-sans selection:bg-purple-500 selection:text-white">
      {/* Sidebar */}
      <aside className="fixed left-0 top-0 h-full w-64 bg-[#242424] border-r border-[#333] flex flex-col">
        <div className="p-6">
          <h1 className="text-2xl font-bold bg-gradient-to-r from-purple-400 to-pink-600 bg-clip-text text-transparent">
            SciencePixels
          </h1>
          <p className="text-xs text-gray-500 mt-1">Shorts Automation Admin</p>
        </div>

        <nav className="flex-1 px-4 space-y-2">
          <NavItem
            icon={<LayoutDashboard size={20} />}
            label="Videos"
            active={activeTab === 'videos'}
            onClick={() => setActiveTab('videos')}
          />
          <NavItem
            icon={<FileText size={20} />}
            label="Prompts"
            active={activeTab === 'prompts'}
            onClick={() => setActiveTab('prompts')}
          />
          <NavItem
            icon={<RefreshCw size={20} />}
            label="Batch Tools"
            active={activeTab === 'tools'}
            onClick={() => setActiveTab('tools')}
          />
          <NavItem
            icon={<Settings size={20} />}
            label="Settings"
            active={activeTab === 'settings'}
            onClick={() => setActiveTab('settings')}
          />
        </nav>

        <div className="p-4 border-t border-[#333]">
          <div className="text-xs text-gray-600 text-center">v2.1.0 (React)</div>
        </div>
      </aside>

      {/* Main Content */}
      <main className="ml-64 p-8">
        <header className="flex justify-between items-center mb-8">
          <h2 className="text-3xl font-bold text-gray-100">
            {activeTab === 'videos' ? 'Video Management' :
              activeTab === 'prompts' ? 'System Prompts' :
                activeTab === 'settings' ? 'System Configuration' : 'Maintenance Tools'}
          </h2>
          <button
            onClick={fetchData}
            disabled={loading}
            className="flex items-center gap-2 px-4 py-2 bg-[#333] hover:bg-[#444] rounded-lg transition-all active:scale-95 disabled:opacity-50"
          >
            <RefreshCw size={18} className={loading ? "animate-spin" : ""} />
            Refresh
          </button>
        </header>

        {activeTab === 'videos' && (
          <div className="grid gap-6">
            {/* Filter Bar */}
            <div className="flex flex-wrap gap-4 bg-[#2a2a2a] p-4 rounded-xl border border-[#333] shadow-md">
              <div className="flex-1 min-w-[200px]">
                <label className="block text-xs font-semibold text-gray-500 uppercase mb-1.5 ml-1">Search Title</label>
                <div className="relative">
                  <input
                    type="text"
                    placeholder="영상 제목으로 검색..."
                    className="w-full bg-[#1a1a1a] border border-[#333] rounded-lg px-4 py-2 text-sm text-gray-100 focus:border-purple-500 outline-none transition-all"
                    value={searchTerm}
                    onChange={(e) => setSearchTerm(e.target.value)}
                  />
                  {searchTerm && (
                    <button
                      onClick={() => setSearchTerm('')}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-white"
                    >
                      <Trash2 size={14} />
                    </button>
                  )}
                </div>
              </div>

              <div className="w-48">
                <label className="block text-xs font-semibold text-gray-500 uppercase mb-1.5 ml-1">Status / Upload</label>
                <select
                  className="w-full bg-[#1a1a1a] border border-[#333] rounded-lg px-3 py-2 text-sm text-gray-100 focus:border-purple-500 outline-none cursor-pointer appearance-none"
                  value={statusFilter}
                  onChange={(e) => setStatusFilter(e.target.value)}
                  style={{ backgroundImage: 'url("data:image/svg+xml;charset=UTF-8,%3csvg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'white\' stroke-width=\'2\' stroke-linecap=\'round\' stroke-linejoin=\'round\'%3e%3cpolyline points=\'6 9 12 15 18 9\'%3e%3c/polyline%3e%3c/svg%3e")', backgroundRepeat: 'no-repeat', backgroundPosition: 'right 0.75rem center', backgroundSize: '1rem' }}
                >
                  <option value="ALL">전체 보기</option>
                  <option value="UPLOADED">✅ 유튜브 업로드 완료</option>
                  <option value="NOT_UPLOADED">⏳ 미업로드 영상</option>
                  <option value="PROCESSING">⚙️ 제작 중 (Processing)</option>
                  <option value="COMPLETED">📦 제작 완료 (대기 중)</option>
                  <option value="ERROR">❌ 에러 발생</option>
                  <option value="REGENERATING">🔄 재생성 중</option>
                </select>
              </div>

              <div className="flex items-end pb-1">
                <div className="text-xs text-gray-500 bg-[#333] px-3 py-2 rounded-lg border border-[#444]">
                  Total: <span className="text-purple-400 font-bold">{videos.filter(v => {
                    const matchesSearch = v.title.toLowerCase().includes(searchTerm.toLowerCase());
                    const matchesStatus = statusFilter === 'ALL' ? true :
                      statusFilter === 'UPLOADED' ? v.status === 'UPLOADED' :
                        statusFilter === 'NOT_UPLOADED' ? v.status !== 'UPLOADED' :
                          v.status === statusFilter;
                    return matchesSearch && matchesStatus;
                  }).length}</span> / {videos.length}
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
              />
            ))}
            {videos.length === 0 && !loading && (
              <div className="text-center py-20 text-gray-500">No videos found.</div>
            )}
          </div>
        )}

        {/* Prompt editor placeholder for now */}
        {activeTab === 'prompts' && (
          <div className="grid gap-6">
            {prompts.map(prompt => (
              <div key={prompt.id} className="bg-[#2a2a2a] p-6 rounded-xl border border-[#333]">
                <div className="flex justify-between mb-4">
                  <h3 className="text-xl font-bold text-purple-400">{prompt.id}</h3>
                  <span className="text-xs text-gray-500 flex items-center gap-1">
                    <Clock size={12} /> {new Date(prompt.updatedAt).toLocaleString()}
                  </span>
                </div>
                <textarea
                  className="w-full h-64 bg-[#1a1a1a] border border-[#333] rounded-lg p-4 font-mono text-sm text-gray-300 focus:border-purple-500 outline-none resize-y"
                  defaultValue={prompt.content}
                />
                <div className="mt-4 flex justify-end">
                  <button className="bg-purple-600 hover:bg-purple-700 text-white px-6 py-2 rounded-lg font-medium transition-colors">
                    Save Changes
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        {activeTab === 'tools' && (
          <div className="grid gap-8 max-w-4xl">
            <div className="bg-[#2a2a2a] p-8 rounded-2xl border border-[#333] shadow-xl">
              <h3 className="text-xl font-bold mb-6 text-purple-400">데이터 정화 및 배치 작업</h3>

              <div className="grid grid-cols-2 gap-6">
                <div className="p-6 bg-[#222] rounded-xl border border-[#333] hover:border-blue-500/30 transition-all">
                  <p className="text-sm text-gray-400 mb-4">
                    DB에는 있지만 filePath가 끊긴 항목들을 `/app/shared-data` 디렉토리의 파일들과 다시 대조하여 매핑합니다.
                  </p>
                  <button
                    onClick={() => runBatchAction('rematch-files')}
                    disabled={loading}
                    className="w-full py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded-lg font-bold transition-colors"
                  >
                    매핑 시작
                  </button>
                </div>

                <div className="p-6 bg-[#222] rounded-xl border border-[#333] hover:border-green-500/30 transition-all">
                  <h4 className="font-bold text-green-400 mb-2 flex items-center gap-2">
                    <PlayCircle size={18} /> 일괄 한글화 (10개)
                  </h4>
                  <p className="text-sm text-gray-400 mb-4">
                    제목이 영어인 영상들을 찾아 최대 10개까지 한글 메타데이터로 일괄 전환합니다.
                  </p>
                  <button
                    onClick={() => runBatchAction('regenerate-all-metadata')}
                    disabled={loading}
                    className="w-full py-2 bg-green-600 hover:bg-green-700 disabled:opacity-50 text-white rounded-lg font-bold transition-colors"
                  >
                    한글화 시작
                  </button>
                </div>

                <div className="p-6 bg-[#222] rounded-xl border border-[#333] hover:border-orange-500/30 transition-all col-span-2">
                  <h4 className="font-bold text-orange-400 mb-2 flex items-center gap-2">
                    <AlertCircle size={18} /> 누락 파일 일괄 재생성
                  </h4>
                  <p className="text-sm text-gray-400 mb-4">
                    파일이 삭제되거나 유실된 영상(UPLOADED 제외)들을 찾아 비디오 파일 생성을 다시 실행합니다.
                  </p>
                  <button
                    onClick={() => runBatchAction('regenerate-missing-files')}
                    disabled={loading}
                    className="w-full py-2 bg-orange-600 hover:bg-orange-700 disabled:opacity-50 text-white rounded-lg font-bold transition-colors"
                  >
                    재생성 시작
                  </button>
                </div>

                <div className="p-6 bg-[#222] rounded-xl border border-[#333] hover:border-red-500/30 transition-all col-span-2">
                  <h4 className="font-bold text-red-500 mb-2 flex items-center gap-2">
                    <AlertCircle size={18} /> 민감 영상 소급 정리 (Safety Cleanup)
                  </h4>
                  <p className="text-sm text-gray-400 mb-4">
                    모든 영상을 스캔하여 정치/종교/사회 갈등 유발 가능성이 있는 영상을 즉시 삭제합니다. (30분 주기 자동 실행됨)
                  </p>
                  <button
                    onClick={() => runBatchAction('cleanup-sensitive')}
                    disabled={loading}
                    className="w-full py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 text-white rounded-lg font-bold transition-colors"
                  >
                    소급 정리 시작
                  </button>
                </div>

                <div className="p-6 bg-[#222] rounded-xl border border-[#333] hover:border-blue-400/30 transition-all col-span-2">
                  <h4 className="font-bold text-blue-400 mb-2 flex items-center gap-2">
                    <RefreshCw size={18} /> 유튜브 업로드 상태 동기화
                  </h4>
                  <p className="text-sm text-gray-400 mb-4">
                    유튜브 링크가 입력되어 있는 영상들의 상태를 일괄적으로 `UPLOADED`로 변경하고 용량을 차지하는 로컬 비디오 파일을 삭제합니다.
                  </p>
                  <button
                    onClick={() => runBatchAction('sync-uploaded')}
                    disabled={loading}
                    className="w-full py-2 bg-blue-500 hover:bg-blue-600 disabled:opacity-50 text-white rounded-lg font-bold transition-colors"
                  >
                    동기화 실행
                  </button>
                </div>
              </div>

              {toolsResult && (
                <div className="mt-8 p-6 bg-[#1a1a1a] rounded-xl border border-purple-500/20">
                  <h4 className="font-bold text-purple-400 mb-4">실행 결과</h4>
                  <pre className="text-xs text-gray-400 overflow-auto max-h-96 font-mono bg-black/30 p-4 rounded-lg">
                    {JSON.stringify(toolsResult, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        )}

        {activeTab === 'settings' && (
          <div className="max-w-2xl grid gap-6">
            <div className="bg-[#2a2a2a] p-8 rounded-2xl border border-[#333]">
              <h3 className="text-xl font-bold mb-6 text-purple-400">영상 생성 설정</h3>

              <div className="space-y-6">
                <div>
                  <label className="block text-sm font-medium text-gray-400 mb-2">최대 생성 유지 개수 (Buffer Size)</label>
                  <div className="flex gap-4">
                    <input
                      type="number"
                      className="bg-[#1a1a1a] border border-[#333] rounded px-4 py-2 text-white w-32 focus:border-purple-500 outline-none"
                      defaultValue={settings.find(s => s.key === 'MAX_GENERATION_LIMIT')?.value || '10'}
                      id="maxGenInput"
                    />
                    <button
                      onClick={() => {
                        const val = (document.getElementById('maxGenInput') as HTMLInputElement).value;
                        saveSetting('MAX_GENERATION_LIMIT', val, 'Max unuploaded videos to keep buffered');
                      }}
                      className="px-6 py-2 bg-purple-600 hover:bg-purple-700 text-white rounded-lg font-bold"
                    >
                      저장
                    </button>
                  </div>
                  <p className="text-xs text-gray-500 mt-2">
                    * 업로드 되지 않은(COMPLETED) 영상이 이 개수보다 적으면 자동으로 생성을 시작합니다.<br />
                    * 이 개수에 도달하면 생성을 멈추고 대기합니다.
                  </p>
                </div>

                <div className="pt-6 border-t border-[#333]">
                  <label className="block text-sm font-medium text-gray-400 mb-2">현재 업로드 차단 (Quota Limit)</label>
                  {settings.find(s => s.key === 'UPLOAD_BLOCKED_UNTIL') ? (
                    <div className="p-4 bg-red-900/20 border border-red-500/30 rounded-lg">
                      <p className="text-red-400 font-bold mb-1">⛔ 업로드가 차단됨</p>
                      <p className="text-sm text-gray-400">
                        해제 예정 시간: {new Date(settings.find(s => s.key === 'UPLOAD_BLOCKED_UNTIL')?.value).toLocaleString()}
                      </p>
                      <button
                        onClick={() => saveSetting('UPLOAD_BLOCKED_UNTIL', '', 'Force Unblock')}
                        className="mt-3 px-3 py-1 bg-red-600 hover:bg-red-700 text-white text-xs rounded"
                      >
                        강제 해제 (Force Unblock)
                      </button>
                    </div>
                  ) : (
                    <div className="p-4 bg-green-900/20 border border-green-500/30 rounded-lg">
                      <p className="text-green-400 font-bold">✅ 정상 (업로드 가능)</p>
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
        "w-full flex items-center gap-3 px-4 py-3 rounded-lg text-sm font-medium transition-all duration-200",
        active
          ? "bg-purple-500/10 text-purple-400 border border-purple-500/20 shadow-[0_0_15px_rgba(168,85,247,0.1)]"
          : "text-gray-400 hover:bg-[#2a2a2a] hover:text-gray-200 border border-transparent"
      )}
    >
      {icon}
      {label}
    </button>
  );
}

function VideoCard({ video, onDownload, onRegenerateMetadata, onUpdateStatus, onDelete }: { video: VideoHistory, onDownload: () => void, onRegenerateMetadata: (id: string) => void, onUpdateStatus: (id: string, status: string, url?: string) => void, onDelete: (id: string) => void }) {
  const statusColors: Record<string, string> = {
    'COMPLETED': 'text-green-400 bg-green-400/10 border-green-400/20',
    'PENDING_PROCESSING': 'text-yellow-400 bg-yellow-400/10 border-yellow-400/20',
    'PROCESSING': 'text-yellow-400 bg-yellow-400/10 border-yellow-400/20',
    'REGENERATING': 'text-purple-400 bg-purple-400/10 border-purple-400/20',
    'ERROR': 'text-red-400 bg-red-400/10 border-red-400/20',
    'UPLOADED': 'text-blue-400 bg-blue-400/10 border-blue-400/20',
    'QUOTA_EXCEEDED': 'text-orange-400 bg-orange-400/10 border-orange-400/20',
    'RETRY_PENDING': 'text-indigo-400 bg-indigo-400/10 border-indigo-400/20',
    'FILE_NOT_FOUND': 'text-pink-400 bg-pink-400/10 border-pink-400/20',
  };

  // Check if title contains Korean characters (if not, it needs regeneration)
  const isKoreanTitle = /[가-힣]/.test(video.title);

  const copyTitle = () => {
    navigator.clipboard.writeText(video.title);
    alert('제목이 복사되었습니다!');
  };

  const copyDescription = () => {
    const tagsStr = video.tags?.map(t => `#${t}`).join(' ') || '';
    const sourcesStr = video.sources?.length ? `\n\n📚 출처: ${video.sources.join(', ')}` : '';
    const fullText = `${video.description || video.summary}${sourcesStr}\n\n${tagsStr}`;
    navigator.clipboard.writeText(fullText);
    alert('설명+출처+태그가 복사되었습니다!');
  };

  // Source URL guess (for clickable links)
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
    <div className="bg-[#2a2a2a] rounded-xl border border-[#333] overflow-hidden hover:border-purple-500/30 transition-all duration-300 shadow-lg">
      <div className="p-6">
        <div className="flex justify-between items-start mb-4">
          <div className="flex-1">
            <div className="flex items-center gap-2 mb-3">
              <div className={cn("inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold border", statusColors[video.status] || 'text-gray-400')}>
                {video.status === 'COMPLETED' || video.status === 'UPLOADED' ? <CheckCircle2 size={12} /> :
                  video.status === 'REGENERATING' || video.status.includes('PENDING') ? <RefreshCw size={12} className="animate-spin-slow" /> :
                    <AlertCircle size={12} />}
                {video.status}
              </div>
              {!isKoreanTitle && (
                <span className="px-2 py-0.5 bg-orange-500/20 text-orange-400 text-xs rounded border border-orange-500/30">영어 제목</span>
              )}
            </div>
            <div className="flex items-center gap-2 mb-2">
              <h3 className="text-xl font-bold text-gray-100 leading-tight">{video.title}</h3>
              <button onClick={copyTitle} className="p-1 hover:bg-[#444] rounded transition-colors" title="제목 복사">
                <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-gray-400 hover:text-white"><rect width="14" height="14" x="8" y="8" rx="2" ry="2" /><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" /></svg>
              </button>
            </div>
            <div
              className="text-sm text-gray-400 line-clamp-2"
              dangerouslySetInnerHTML={{ __html: video.summary }}
            />
          </div>
          <div className="flex gap-2 ml-4">
            {/* Meta button shows if NOT Korean title OR always for regeneration */}
            <button
              onClick={() => onRegenerateMetadata(video.id || '')}
              className="group flex items-center gap-2 px-3 py-2 bg-blue-600/10 hover:bg-blue-600 text-blue-400 hover:text-white rounded-lg border border-blue-600/20 transition-all"
              title="메타데이터 재생성 (한글)"
            >
              <RefreshCw size={16} className="group-hover:rotate-180 transition-transform duration-500" />
              <span className="font-semibold text-xs">Meta</span>
            </button>
            <button
              onClick={onDownload}
              disabled={!video.filePath}
              className={cn(
                "group flex items-center gap-2 px-3 py-2 rounded-lg border transition-all",
                video.filePath
                  ? "bg-purple-600/10 hover:bg-purple-600 text-purple-400 hover:text-white border-purple-600/20"
                  : "bg-gray-700 text-gray-400 border-gray-600 cursor-not-allowed opacity-75 hover:opacity-100"
              )}
              title={video.filePath ? "Download MP4" : "File not found (Generating...)"}
            >
              <Download size={16} className={video.filePath ? "group-hover:scale-110 transition-transform" : ""} />
              <span className="font-semibold text-xs">Download</span>
            </button>
            <button
              onClick={() => onDelete(video.id || '')}
              className="flex items-center gap-2 px-3 py-2 bg-red-600/10 hover:bg-red-600 text-red-500 hover:text-white rounded-lg border border-red-500/20 transition-all"
              title="영상 삭제"
            >
              <Trash2 size={16} />
              <span className="font-semibold text-xs">Delete</span>
            </button>
          </div>
        </div>

        {/* Description with Copy Button */}
        {video.description && (
          <div className="bg-[#222] p-4 rounded-lg mb-4">
            <div className="flex justify-between items-start mb-2">
              <span className="text-xs font-medium text-gray-500 uppercase tracking-wider">설명</span>
              <button onClick={copyDescription} className="flex items-center gap-1 px-2 py-1 bg-green-600/10 hover:bg-green-600 text-green-400 hover:text-white rounded text-xs transition-all" title="설명+출처+태그 복사">
                <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2" /><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" /></svg>
                복사
              </button>
            </div>
            <p className="text-sm text-gray-300 leading-relaxed">{video.description}</p>
          </div>
        )}

        {/* Metadata Grid */}
        <div className="grid grid-cols-2 gap-4 bg-[#222] p-4 rounded-lg">
          <div>
            <span className="text-xs font-medium text-gray-500 uppercase tracking-wider block mb-1">Tags</span>
            <div className="flex flex-wrap gap-1.5">
              {video.tags?.slice(0, 8).map(tag => (
                <span key={tag} className="px-2 py-0.5 bg-[#333] text-gray-300 text-xs rounded">#{tag}</span>
              ))}
              {(!video.tags || video.tags.length === 0) && <span className="text-xs text-gray-600">-</span>}
            </div>
          </div>
          <div>
            <span className="text-xs font-medium text-gray-500 uppercase tracking-wider block mb-1">Sources</span>
            <div className="space-y-1">
              {video.sources?.map((source, idx) => (
                <a key={idx} href={getSourceUrl(source)} target="_blank" rel="noreferrer" className="flex items-center gap-1 text-xs text-blue-400 hover:text-blue-300 hover:underline cursor-pointer truncate">
                  <ExternalLink size={10} /> {source}
                </a>
              ))}
              {(!video.sources || video.sources.length === 0) && <span className="text-xs text-gray-600">-</span>}
            </div>
          </div>
        </div>

        <div className="mt-4 pt-4 border-t border-[#333] flex justify-between items-center text-xs text-gray-500">
          <div className="flex items-center gap-4">
            <span>Created: {new Date(video.createdAt).toLocaleString()}</span>
            {video.youtubeUrl && (
              <a href={video.youtubeUrl} target="_blank" rel="noreferrer" className="flex items-center gap-1 text-red-400 hover:text-red-300 hover:underline">
                <PlayCircle size={14} /> Watch on YouTube
              </a>
            )}
            {!video.youtubeUrl && video.status !== 'UPLOADED' && (
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  placeholder="Paste YouTube URL"
                  className="bg-[#333] border border-[#444] rounded px-2 py-1 text-xs w-40 focus:border-purple-500 outline-none"
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
          <div>ID: {video.id}</div>
        </div>
      </div>
    </div>
  );
}

export default App;

