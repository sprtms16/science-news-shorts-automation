
import { useState } from 'react';
import { AlertCircle, CheckCircle2 } from 'lucide-react';

interface SettingsPanelProps {
    t: any;
    settings: any[];
    saveSetting: (key: string, value: string, desc: string) => void;
}

export function SettingsPanel({ t, settings, saveSetting }: SettingsPanelProps) {
    const [maxGenValue, setMaxGenValue] = useState(
        settings.find(s => s.key === 'MAX_GENERATION_LIMIT')?.value || '10'
    );
    const [intervalValue, setIntervalValue] = useState(
        settings.find(s => s.key === 'UPLOAD_INTERVAL_HOURS')?.value || '1'
    );

    return (
        <div className="max-w-4xl grid gap-8">
            <div className="glass-morphism p-8 md:p-10 rounded-3xl border border-[var(--glass-border)]">
                <div className="mb-10">
                    <h3 className="text-2xl font-bold text-[var(--text-primary)] mb-2">{t.engineConstraints}</h3>
                    <p className="text-sm text-[var(--text-secondary)] font-medium">{t.settingsDescription}</p>
                </div>

                <div className="space-y-10">
                    <div className="flex flex-col md:flex-row md:items-center gap-6 p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)]">
                        <div className="flex-1">
                            <h4 className="text-base font-bold text-[var(--text-primary)] mb-1">{t.queueBufferSize}</h4>
                            <p className="text-xs text-[var(--text-secondary)]">{t.bufferDescription}</p>
                        </div>
                        <div className="flex gap-3 shrink-0">
                            <input
                                type="number"
                                className="bg-[var(--input-bg)] border border-[var(--input-border)] rounded-xl px-4 py-2 text-[var(--text-primary)] w-24 focus:border-purple-500/50 outline-none font-mono font-bold transition-all text-center"
                                value={maxGenValue}
                                onChange={(e) => setMaxGenValue(e.target.value)}
                            />
                            <button
                                onClick={() => {
                                    saveSetting('MAX_GENERATION_LIMIT', maxGenValue, 'Max unuploaded videos to keep buffered');
                                }}
                                className="px-6 py-2 bg-purple-600 hover:bg-purple-500 text-white rounded-xl font-bold shadow-lg shadow-purple-500/20 transition-all active:scale-95"
                            >
                                {t.commit}
                            </button>
                        </div>
                    </div>

                    <div className="flex flex-col md:flex-row md:items-center gap-6 p-6 bg-[var(--input-bg)] rounded-2xl border border-[var(--input-border)]">
                        <div className="flex-1">
                            <h4 className="text-base font-bold text-[var(--text-primary)] mb-1">
                                {t.uploadInterval}
                            </h4>
                            <p className="text-xs text-[var(--text-secondary)]">
                                {t.uploadIntervalDescription}
                            </p>
                        </div>
                        <div className="flex gap-3 shrink-0">
                            <input
                                type="number"
                                step="0.1"
                                className="bg-[var(--input-bg)] border border-[var(--input-border)] rounded-xl px-4 py-2 text-[var(--text-primary)] w-24 focus:border-purple-500/50 outline-none font-mono font-bold transition-all text-center"
                                value={intervalValue}
                                onChange={(e) => setIntervalValue(e.target.value)}
                            />
                            <button
                                onClick={() => {
                                    saveSetting('UPLOAD_INTERVAL_HOURS', intervalValue, 'Hours between automated uploads');
                                }}
                                className="px-6 py-2 bg-purple-600 hover:bg-purple-500 text-white rounded-xl font-bold shadow-lg shadow-purple-500/20 transition-all active:scale-95"
                            >
                                {t.commit}
                            </button>
                        </div>
                    </div>

                    <div className="pt-10 border-t border-[var(--glass-border)]">
                        <h4 className="text-base font-bold text-[var(--text-primary)] mb-4">{t.youtubeQuotaShield}</h4>
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
    );
}
