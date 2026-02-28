
import {
    RefreshCw,
    PlayCircle,
    AlertCircle,
    ShieldCheck,
    Trash2,
    Languages,
    TrendingUp,
    Image
} from 'lucide-react';

interface ToolsPanelProps {
    t: any;
    runBatchAction: (action: 'rematch-files' | 'regenerate-all-metadata' | 'regenerate-missing-files' | 'sync-uploaded' | 'cleanup-sensitive' | 'upload-pending' | 'prune-deleted' | 'translate-uploaded' | 'growth-analysis' | 'regenerate-thumbnails' | 'clear-failed' | 'cleanup-workspaces' | 'refresh-prompts' | 'reset-sources' | 'trigger-manual-generate') => void;
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
                    {/* Manual Generate Card */}
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-green-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-green-500/10 flex items-center justify-center text-green-500 shrink-0 group-hover:scale-105 transition-transform">
                                <PlayCircle size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-green-500 mb-1">{t.manualGenerateTitle || "Manual Video Generation"}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.manualGenerateDesc || "Fetch new news and forcefully start video generation immediately. Use this to trigger the batch without waiting for the scheduler."}
                                </p>
                                <button
                                    onClick={() => runBatchAction('trigger-manual-generate')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-green-600/10 hover:bg-green-600 text-green-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-green-500/20"
                                >
                                    {t.runManualGenerate || "Trigger Generation"}
                                </button>
                            </div>
                        </div>
                    </div>

                    {/* Growth Analysis Card */}
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-pink-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-pink-500/10 flex items-center justify-center text-pink-500 shrink-0 group-hover:scale-105 transition-transform">
                                <TrendingUp size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-pink-500 mb-1">{t.growthAnalysisTitle}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.growthAnalysisDesc}
                                </p>
                                <button
                                    onClick={() => runBatchAction('growth-analysis')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-pink-600/10 hover:bg-pink-600 text-pink-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-pink-500/20"
                                >
                                    {t.runGrowthAnalysis}
                                </button>
                            </div>
                        </div>
                    </div>

                    {/* Thumbnail Regeneration Card */}
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-cyan-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-cyan-500/10 flex items-center justify-center text-cyan-500 shrink-0 group-hover:scale-105 transition-transform">
                                <Image size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-cyan-500 mb-1">{t.regenThumbnailsTitle || "Thumbnail Auto-Regeneration"}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.regenThumbnailsDesc || "Automatically select and update thumbnails for existing videos based on their title and keywords using Pexels API."}
                                </p>
                                <button
                                    onClick={() => runBatchAction('regenerate-thumbnails')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-cyan-600/10 hover:bg-cyan-600 text-cyan-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-cyan-500/20"
                                >
                                    {t.runRegenThumbnails || "Regenerate Thumbnails"}
                                </button>
                            </div>
                        </div>
                    </div>


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

                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-violet-500/30 transition-all group">
                        <div className="w-12 h-12 rounded-xl bg-violet-500/10 flex items-center justify-center text-violet-400 mb-4 group-hover:scale-110 transition-transform">
                            <Languages size={24} />
                        </div>
                        <h4 className="text-lg font-bold text-violet-400 mb-2">{t.translateUploadedTitle}</h4>
                        <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-6">
                            {t.translateUploadedDesc}
                        </p>
                        <button
                            onClick={() => runBatchAction('translate-uploaded')}
                            disabled={loading}
                            className="w-full py-3 bg-violet-600/10 hover:bg-violet-600 text-violet-400 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-violet-600/20"
                        >
                            {t.translateUploadedBtn}
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

                    {/* Clear Failed Card */}
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-red-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-red-500/10 flex items-center justify-center text-red-500 shrink-0 group-hover:scale-105 transition-transform">
                                <AlertCircle size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-red-500 mb-1">{t.clearFailedTitle || "Clear Failed History"}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.clearFailedDesc || "Permanently delete all 'Failed' records and their associated files for the current channel."}
                                </p>
                                <button
                                    onClick={() => runBatchAction('clear-failed')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-red-600/10 hover:bg-red-600 text-red-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-red-500/20"
                                >
                                    {t.runClearFailed || "Clear Failed Records"}
                                </button>
                            </div>
                        </div>
                    </div>

                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-indigo-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-indigo-500/10 flex items-center justify-center text-indigo-500 shrink-0 group-hover:scale-105 transition-transform">
                                <Trash2 size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-indigo-500 mb-1">{t.pruneDeleted}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.pruneDescription}
                                </p>
                                <button
                                    onClick={() => runBatchAction('prune-deleted')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-indigo-600/10 hover:bg-indigo-600 text-indigo-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-indigo-500/20"
                                >
                                    {t.runPrune}
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

                    {/* Cleanup Workspaces Card */}
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-gray-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-gray-500/10 flex items-center justify-center text-gray-500 shrink-0 group-hover:scale-105 transition-transform">
                                <Trash2 size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-gray-400 mb-1">{t.cleanupWorkspacesTitle || "Cleanup Temp Workspaces"}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.cleanupWorkspacesDesc || "Aggressively delete all temporary files in 'workspace/' directory to free up disk space."}
                                </p>
                                <button
                                    onClick={() => runBatchAction('cleanup-workspaces')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-gray-600/10 hover:bg-gray-600 text-gray-400 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-gray-500/20"
                                >
                                    {t.runCleanupWorkspaces || "Run Cleanup"}
                                </button>
                            </div>
                        </div>
                    </div>

                    {/* Refresh Prompts Card */}
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-fuchsia-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-fuchsia-500/10 flex items-center justify-center text-fuchsia-500 shrink-0 group-hover:scale-105 transition-transform">
                                <ShieldCheck size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-fuchsia-400 mb-1">{t.refreshPromptsTitle || "Refresh System Prompts"}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.refreshPromptsDesc || "Reload system prompts from the codebase defaults. This will update the prompts in the database with the latest code changes."}
                                </p>
                                <button
                                    onClick={() => runBatchAction('refresh-prompts')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-fuchsia-600/10 hover:bg-fuchsia-600 text-fuchsia-400 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-fuchsia-500/20"
                                >
                                    {t.runRefreshPrompts || "Sync Prompts"}
                                </button>
                            </div>
                        </div>
                    </div>

                    {/* Reset Sources Card */}
                    <div className="p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)] hover:border-red-500/30 transition-all group md:col-span-2">
                        <div className="flex items-start gap-6">
                            <div className="w-14 h-14 rounded-2xl bg-red-500/10 flex items-center justify-center text-red-500 shrink-0 group-hover:scale-105 transition-transform">
                                <RefreshCw size={28} />
                            </div>
                            <div className="flex-1">
                                <h4 className="text-lg font-bold text-red-400 mb-1">{t.resetSourcesTitle || "Reset Default Sources"}</h4>
                                <p className="text-xs text-[var(--text-secondary)] leading-relaxed mb-4">
                                    {t.resetSourcesDesc || "Reset all news collection sources to factory defaults."}
                                </p>
                                <button
                                    onClick={() => runBatchAction('reset-sources')}
                                    disabled={loading}
                                    className="px-8 py-3 bg-red-600/10 hover:bg-red-600 text-red-500 hover:text-white disabled:opacity-50 rounded-xl font-bold transition-all border border-red-500/20"
                                >
                                    {t.runResetSources || "Run Reset"}
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
