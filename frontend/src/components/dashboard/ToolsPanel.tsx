
import {
    RefreshCw,
    PlayCircle,
    AlertCircle,
    ShieldCheck
} from 'lucide-react';

interface ToolsPanelProps {
    t: any;
    runBatchAction: (action: 'rematch-files' | 'regenerate-all-metadata' | 'regenerate-missing-files' | 'sync-uploaded' | 'cleanup-sensitive' | 'upload-pending') => void;
    loading: boolean;
    toolsResult: any;
}

export function ToolsPanel({ t, runBatchAction, loading, toolsResult }: ToolsPanelProps) {
    return (
        <div className="grid gap-8 max-w-5xl">
            <div className="glass-morphism p-8 md:p-10 rounded-3xl border border-[var(--glass-border)]">
                <div className="mb-10">
                    <h3 className="text-2xl font-bold text-[var(--text-primary)] mb-2">{t.systemMaintenance}</h3>
                    <p className="text-sm text-[var(--text-secondary)] font-medium">{t.maintenanceDescription}</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-blue-500/30 transition-all group">
                        <div className="w-12 h-12 rounded-xl bg-blue-500/10 flex items-center justify-center text-blue-400 mb-4 group-hover:scale-110 transition-transform">
                            <RefreshCw size={24} />
                        </div>
                        <h4 className="text-lg font-bold text-blue-400 mb-2">{t.rematchFiles}</h4>
                        <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-6">
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

                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-emerald-500/30 transition-all group">
                        <div className="w-12 h-12 rounded-xl bg-emerald-500/10 flex items-center justify-center text-emerald-400 mb-4 group-hover:scale-110 transition-transform">
                            <PlayCircle size={24} />
                        </div>
                        <h4 className="text-lg font-bold text-emerald-400 mb-2">{t.localizationBatch}</h4>
                        <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-6">
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

                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-orange-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-orange-500/10 flex items-center justify-center text-orange-400 shrink-0 group-hover:scale-105 transition-transform">
                                <AlertCircle size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-orange-400 mb-1">{t.deepArchiveRepair}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
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

                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-rose-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-rose-500/10 flex items-center justify-center text-rose-500 shrink-0 group-hover:scale-105 transition-transform">
                                <ShieldCheck size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-rose-500 mb-1">{t.safetyPurge}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
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

                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-indigo-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-indigo-500/10 flex items-center justify-center text-indigo-500 shrink-0 group-hover:scale-105 transition-transform">
                                <PlayCircle size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-indigo-500 mb-1">{t.forceUploadTitle}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.forceUploadDesc}
                                </p>
                                <button
                                    onClick={() => runBatchAction('upload-pending')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-indigo-600/10 hover:bg-indigo-600 text-indigo-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-indigo-500/20"
                                >
                                    {t.triggerUpload}
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
    );
}
