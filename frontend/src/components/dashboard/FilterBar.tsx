
import { Search, X } from 'lucide-react';
import type { VideoHistory } from '../../types';

interface FilterBarProps {
    searchTerm: string;
    setSearchTerm: (term: string) => void;
    statusFilter: string;
    setStatusFilter: (status: string) => void;
    videos: VideoHistory[];
    t: any;
}

export function FilterBar({ searchTerm, setSearchTerm, statusFilter, setStatusFilter, videos, t }: FilterBarProps) {

    const filteredCount = videos.filter(v => {
        const matchesSearch = v.title.toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === 'ALL' ? true :
            statusFilter === 'UPLOADED' ? v.status === 'UPLOADED' :
                statusFilter === 'NOT_UPLOADED' ? v.status !== 'UPLOADED' :
                    v.status === statusFilter;
        return matchesSearch && matchesStatus;
    }).length;

    return (
        <div className="glass-morphism p-5 rounded-2xl border border-[var(--glass-border)] flex flex-col lg:flex-row items-stretch lg:items-end gap-5">
            <div className="flex-1">
                <label className="block text-[10px] font-bold text-[var(--text-secondary)] uppercase tracking-widest mb-2 ml-1">Search Pipeline</label>
                <div className="relative group">
                    <span className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-secondary)] group-focus-within:text-purple-400 transition-colors">
                        <Search size={16} />
                    </span>
                    <input
                        type="text"
                        placeholder={t.searchPlaceholder}
                        className="w-full bg-[var(--input-bg)] border border-[var(--input-border)] rounded-xl pl-10 pr-4 py-2.5 text-sm text-[var(--text-primary)] focus:border-purple-500/50 focus:ring-4 focus:ring-purple-500/10 outline-none transition-all placeholder-[var(--text-secondary)]"
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
                        className="w-full bg-[var(--input-bg)] border border-[var(--input-border)] rounded-xl px-4 py-2.5 text-sm text-[var(--text-primary)] focus:border-purple-500/50 outline-none cursor-pointer appearance-none transition-all"
                        value={statusFilter}
                        onChange={(e) => setStatusFilter(e.target.value)}
                        style={{ backgroundImage: 'url("data:image/svg+xml;charset=UTF-8,%3csvg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'%2364748b\' stroke-width=\'2\' stroke-linecap=\'round\' stroke-linejoin=\'round\'%3e%3cpolyline points=\'6 9 12 15 18 9\'%3e%3c/polyline%3e%3c/svg%3e")', backgroundRepeat: 'no-repeat', backgroundPosition: 'right 1rem center', backgroundSize: '1rem' }}
                    >
                        <option value="ALL" className="bg-slate-900 text-white dark:bg-slate-900">{t.statusAll}</option>
                        <option value="UPLOADED" className="bg-slate-900 text-white dark:bg-slate-900">✅ {t.statusUploaded}</option>
                        <option value="COMPLETED" className="bg-slate-900 text-white dark:bg-slate-900">📦 {t.statusCompleted}</option>
                        <option value="CREATING" className="bg-slate-900 text-white dark:bg-slate-900">⚙️ {t.statusCreating}</option>
                        <option value="FAILED" className="bg-slate-900 text-white dark:bg-slate-900">❌ {t.statusFailed}</option>
                    </select>
                </div>
            </div>

            <div className="flex items-center justify-between lg:justify-end gap-3 lg:pb-0.5">
                <div className="px-4 py-2.5 rounded-xl bg-[var(--input-bg)] border border-[var(--input-border)] text-[11px] font-bold text-[var(--text-secondary)]">
                    {t.totalFiltered}: <span className="text-purple-400 font-mono text-xs">{filteredCount}</span>
                </div>
                <div className="px-4 py-2.5 rounded-xl bg-purple-500/10 border border-purple-500/20 text-[11px] font-bold text-purple-400">
                    {t.totalSystem}: <span className="font-mono text-xs">{videos.length}</span>
                </div>
            </div>
        </div>
    );
}
