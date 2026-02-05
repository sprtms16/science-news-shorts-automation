
import React from 'react';
import {
    CheckCircle2,
    RefreshCw,
    AlertCircle,
    Youtube,
    Clock,
    Download,
    Trash2,
    ShieldCheck
} from 'lucide-react';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import type { VideoHistory } from '../../types';

function cn(...inputs: (string | undefined | null | false)[]) {
    return twMerge(clsx(inputs));
}

interface VideoCardProps {
    video: VideoHistory;
    onDownload: () => void;
    onRegenerateMetadata: (id: string) => void;
    onManualUpload: (id: string) => void;
    onUpdateStatus: (id: string, status: string, url?: string) => void;
    onDelete: (id: string) => void;
    onRetry?: (id: string) => void; // Added
    t: any;
}

export const VideoCard = React.forwardRef<HTMLDivElement, VideoCardProps>(({ video, onDownload, onRegenerateMetadata, onManualUpload, onUpdateStatus, onDelete, onRetry, t }, ref) => {
    const statusColors: Record<string, { bg: string, text: string, border: string, icon: any, label: string }> = {
        'CREATING': { bg: 'bg-amber-500/10', text: 'text-amber-400', border: 'border-amber-500/20', icon: <RefreshCw size={12} className="animate-spin" />, label: t.statusCreating },
        'FAILED': { bg: 'bg-rose-500/10', text: 'text-rose-400', border: 'border-rose-500/20', icon: <AlertCircle size={12} />, label: t.statusFailed },
        'COMPLETED': { bg: 'bg-emerald-500/10', text: 'text-emerald-400', border: 'border-emerald-500/20', icon: <CheckCircle2 size={12} />, label: t.statusCompleted },
        'UPLOAD_FAILED': { bg: 'bg-orange-500/10', text: 'text-orange-400', border: 'border-orange-500/20', icon: <AlertCircle size={12} />, label: t.statusUploadFailed || 'Upload Failed' },
        'UPLOADED': { bg: 'bg-sky-500/10', text: 'text-sky-400', border: 'border-sky-500/20', icon: <Youtube size={12} />, label: t.statusUploaded },
    };

    const currentStatus = statusColors[video.status] || { bg: 'bg-slate-500/10', text: 'text-slate-400', border: 'border-slate-500/20', icon: <AlertCircle size={12} />, label: video.status };
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
        <div ref={ref} className="glass-morphism rounded-3xl border border-[var(--glass-border)] overflow-hidden group hover:border-purple-500/30 transition-all duration-500 shadow-2xl">
            <div className="p-5 md:p-8">
                <div className="flex flex-col lg:flex-row justify-between items-start gap-6">
                    <div className="flex-1 space-y-4 max-w-full overflow-hidden">
                        <div className="flex flex-wrap items-center gap-2">
                            <span className={cn(
                                "inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold border tracking-wider uppercase",
                                currentStatus.bg, currentStatus.text, currentStatus.border
                            )}>
                                {currentStatus.icon}
                                {currentStatus.label}
                            </span>
                            {!isKoreanTitle && (
                                <span className="px-3 py-1 bg-orange-500/10 text-orange-400 text-[10px] font-bold rounded-full border border-orange-500/20 tracking-wider uppercase">{t.needsLocalization}</span>
                            )}
                            {(video.status === 'FAILED' || video.status === 'UPLOAD_FAILED') && video.errorMessage && (
                                <div className="w-full mt-2 p-3 bg-rose-500/5 rounded-xl border border-rose-500/10 flex flex-col gap-1">
                                    <div className="flex items-center gap-2">
                                        <span className="px-1.5 py-0.5 bg-rose-500/20 text-rose-500 text-[8px] font-black rounded uppercase leading-none">Step: {video.failureStep}</span>
                                        <span className="text-[10px] font-bold text-rose-400/80 uppercase tracking-tighter">Diagnostic Data</span>
                                        {video.regenCount > 0 && <span className="px-1.5 py-0.5 bg-orange-500/20 text-orange-400 text-[8px] font-black rounded uppercase leading-none">Retry: {video.regenCount}/3</span>}
                                    </div>
                                    <p className="text-[11px] text-rose-300 font-medium leading-tight">
                                        {video.errorMessage}
                                    </p>
                                </div>
                            )}
                        </div>

                        <div className="space-y-2">
                            <div className="flex items-center gap-3">
                                <h3 className="text-xl md:text-2xl font-bold text-[var(--text-primary)] tracking-tight leading-tight group-hover:text-purple-400 transition-colors">
                                    {video.title}
                                </h3>
                                <button onClick={copyTitle} className="p-2 hover:bg-[var(--input-bg)] rounded-xl transition-all text-[var(--text-secondary)] hover:text-[var(--text-primary)]" title={t.copyTitle}>
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
                        {(video.status === 'FAILED' || video.status === 'UPLOAD_FAILED') && onRetry && (
                            <button
                                onClick={() => onRetry(video.id || '')}
                                className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 bg-orange-500/10 hover:bg-orange-600 text-orange-400 hover:text-white rounded-xl border border-orange-500/20 transition-all text-xs font-bold"
                            >
                                <RefreshCw size={14} /> Retry
                            </button>
                        )}
                        {(video.status === 'COMPLETED' || video.status === 'UPLOAD_FAILED') && (
                            <button
                                onClick={() => onManualUpload(video.id || '')}
                                className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 bg-emerald-500/10 hover:bg-emerald-500 text-emerald-400 hover:text-white rounded-xl border border-emerald-500/20 transition-all text-xs font-bold animate-pulse"
                            >
                                <Youtube size={14} /> {t.manualUpload}
                            </button>
                        )}
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
                    <div className="mt-8 bg-[var(--input-bg)] rounded-2xl p-5 border border-[var(--input-border)]">
                        <div className="flex justify-between items-center mb-3">
                            <span className="text-[10px] font-bold text-[var(--text-secondary)] uppercase tracking-widest">{t.masterScript}</span>
                            <button onClick={copyDescription} className="flex items-center gap-1.5 px-3 py-1 bg-emerald-500/10 hover:bg-emerald-500 text-emerald-400 hover:text-white rounded-lg text-[10px] font-bold transition-all border border-emerald-500/20">
                                <ShieldCheck size={12} /> {t.copyFullMeta}
                            </button>
                        </div>
                        <p className="text-sm text-[var(--text-secondary)] leading-relaxed font-normal">{video.description}</p>
                    </div>
                )}

                {/* Intelligence Grid */}
                <div className="mt-6 grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div className="bg-[var(--input-bg)] p-4 rounded-2xl border border-[var(--input-border)]">
                        <span className="text-[10px] font-bold text-[var(--text-secondary)] uppercase tracking-widest block mb-3">{t.aiContextTags}</span>
                        <div className="flex flex-wrap gap-2">
                            {video.tags?.slice(0, 10).map(tag => (
                                <span key={tag} className="px-2.5 py-1 bg-[var(--input-bg)] text-[var(--text-secondary)] text-[10px] font-medium rounded-lg border border-[var(--glass-border)] hover:border-purple-500/30 transition-colors cursor-default">#{tag}</span>
                            ))}
                            {(!video.tags || video.tags.length === 0) && <span className="text-xs text-[var(--text-secondary)] italic">No tags detected</span>}
                        </div>
                    </div>
                    <div className="bg-[var(--input-bg)] p-4 rounded-2xl border border-[var(--input-border)]">
                        <span className="text-[10px] font-bold text-[var(--text-secondary)] uppercase tracking-widest block mb-3">{t.verificationSources}</span>
                        <div className="grid grid-cols-1 gap-2">
                            {video.sources?.map((source, idx) => (
                                <a key={idx} href={getSourceUrl(source)} target="_blank" rel="noreferrer" className="flex items-center gap-2 text-[11px] text-sky-400 hover:text-sky-300 transition-colors truncate">
                                    <Clock size={12} className="hidden" />
                                    {source}
                                </a>
                            ))}
                            {(!video.sources || video.sources.length === 0) && <span className="text-xs text-[var(--text-secondary)] italic">No sources linked</span>}
                        </div>
                    </div>
                </div>

                <div className="mt-8 pt-6 border-t border-[var(--input-border)] flex flex-col md:flex-row justify-between items-center gap-4 text-[10px] font-bold text-[var(--text-secondary)] uppercase tracking-wider">
                    <div className="flex flex-wrap items-center justify-center gap-6">
                        <span className="flex items-center gap-2"><Clock size={12} className="text-purple-500" /> {new Date(video.createdAt).toLocaleString()}</span>
                        {video.youtubeUrl && (
                            <a href={video.youtubeUrl} target="_blank" rel="noreferrer" className="flex items-center gap-1.5 text-rose-500 hover:text-rose-400 transition-colors">
                                <Youtube size={14} /> {t.watchOnYoutube}
                            </a>
                        )}
                        {!video.youtubeUrl && video.status !== 'UPLOADED' && (
                            <div className="flex items-center gap-3">
                                <span className="text-[var(--text-secondary)]">{t.syncLink}:</span>
                                <input
                                    type="text"
                                    placeholder={t.pasteYoutubeUrl}
                                    className="bg-[var(--input-bg)] border border-[var(--input-border)] rounded-lg px-3 py-1.5 text-[10px] w-48 focus:border-purple-500/50 outline-none transition-all lowercase"
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
});

VideoCard.displayName = 'VideoCard';
