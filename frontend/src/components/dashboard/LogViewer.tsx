import React, { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { clsx } from 'clsx';
import { Terminal, AlertCircle, Info, AlertTriangle, ChevronLeft, ChevronRight } from 'lucide-react';

interface LogEntry {
    id: string;
    serviceName: string;
    level: string;
    message: string;
    details?: string;
    timestamp: string;
    traceId?: string;
}

export const LogViewer: React.FC<{ t: any }> = ({ t }) => {
    const [logs, setLogs] = useState<LogEntry[]>([]);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(false);
    const [totalPages, setTotalPages] = useState(0);

    const abortRef = useRef<AbortController | null>(null);

    const fetchLogs = useCallback(async () => {
        abortRef.current?.abort();
        const controller = new AbortController();
        abortRef.current = controller;
        try {
            setLoading(true);
            const res = await axios.get(`/logs-api/api/logs?page=${page}&size=20`, {
                signal: controller.signal,
            });
            setLogs(res.data.content);
            setTotalPages(res.data.totalPages);
        } catch (e) {
            if (!axios.isCancel(e)) {
                console.error("Failed to fetch logs", e);
            }
        } finally {
            setLoading(false);
        }
    }, [page]);

    useEffect(() => {
        fetchLogs();
        const interval = setInterval(fetchLogs, 5000);
        return () => {
            clearInterval(interval);
            abortRef.current?.abort();
        };
    }, [fetchLogs]);

    const getLevelColor = (level: string) => {
        switch (level) {
            case 'ERROR': return 'text-red-400 bg-red-400/10 border-red-400/20';
            case 'WARN': return 'text-yellow-400 bg-yellow-400/10 border-yellow-400/20';
            case 'INFO': return 'text-blue-400 bg-blue-400/10 border-blue-400/20';
            default: return 'text-gray-400 bg-gray-400/10 border-gray-400/20';
        }
    };

    const getLevelIcon = (level: string) => {
        switch (level) {
            case 'ERROR': return <AlertCircle size={14} />;
            case 'WARN': return <AlertTriangle size={14} />;
            default: return <Info size={14} />;
        }
    };

    return (
        <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
            <div className="bg-[var(--card-bg)] border border-[var(--card-border)] rounded-3xl overflow-hidden shadow-xl backdrop-blur-md">
                <div className="p-6 border-b border-[var(--card-border)] flex justify-between items-center">
                    <div className="flex items-center gap-3">
                        <div className="p-2 bg-purple-500/20 rounded-xl">
                            <Terminal className="text-purple-400" size={20} />
                        </div>
                        <h3 className="text-xl font-bold font-display tracking-tight">{t.logs || 'System Logs'}</h3>
                    </div>
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => setPage(p => Math.max(0, p - 1))}
                            disabled={page === 0}
                            className="p-2 hover:bg-white/10 rounded-lg disabled:opacity-30 transition-colors"
                        >
                            <ChevronLeft size={20} />
                        </button>
                        <span className="text-sm font-medium text-[var(--text-secondary)]">
                            Page {page + 1} of {totalPages}
                        </span>
                        <button
                            onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                            disabled={page >= totalPages - 1}
                            className="p-2 hover:bg-white/10 rounded-lg disabled:opacity-30 transition-colors"
                        >
                            <ChevronRight size={20} />
                        </button>
                    </div>
                </div>

                <div className="overflow-x-auto">
                    <table className="w-full text-left border-collapse">
                        <thead>
                            <tr className="bg-white/5 text-[var(--text-secondary)] text-xs font-bold uppercase tracking-wider">
                                <th className="px-6 py-4">Time</th>
                                <th className="px-6 py-4">Service</th>
                                <th className="px-6 py-4">Level</th>
                                <th className="px-6 py-4">Message</th>
                                <th className="px-6 py-4">TraceID</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-white/5">
                            {logs.map((log) => (
                                <tr key={log.id} className="hover:bg-white/5 transition-colors group">
                                    <td className="px-6 py-4 text-xs font-mono text-[var(--text-secondary)] whitespace-nowrap">
                                        {new Date(log.timestamp).toLocaleString()}
                                    </td>
                                    <td className="px-6 py-4">
                                        <span className="text-sm font-semibold px-2 py-1 bg-white/5 rounded-md border border-white/10">
                                            {log.serviceName}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        <div className={clsx(
                                            "flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[10px] font-bold border uppercase tracking-wide inline-flex",
                                            getLevelColor(log.level)
                                        )}>
                                            {getLevelIcon(log.level)}
                                            {log.level}
                                        </div>
                                    </td>
                                    <td className="px-6 py-4">
                                        <div className="flex flex-col gap-1">
                                            <span className="text-sm font-medium text-[var(--text-primary)] group-hover:text-white transition-colors">
                                                {log.message}
                                            </span>
                                            {log.details && (
                                                <span className="text-xs text-[var(--text-secondary)] truncate max-w-md italic">
                                                    {log.details}
                                                </span>
                                            )}
                                        </div>
                                    </td>
                                    <td className="px-6 py-4 text-xs font-mono text-purple-400/70">
                                        {log.traceId?.substring(0, 8)}...
                                    </td>
                                </tr>
                            ))}
                            {logs.length === 0 && !loading && (
                                <tr>
                                    <td colSpan={5} className="text-center py-20 text-gray-500 font-medium">
                                        No logs collected yet.
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    );
};
