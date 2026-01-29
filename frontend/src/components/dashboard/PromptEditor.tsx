
import { FileText, Clock } from 'lucide-react';
import type { SystemPrompt } from '../../types';

interface PromptEditorProps {
    prompts: SystemPrompt[];
    t: any;
}

export function PromptEditor({ prompts, t }: PromptEditorProps) {
    return (
        <div className="grid gap-8 max-w-5xl">
            {prompts.map(prompt => (
                <div key={prompt.id} className="glass-morphism p-8 rounded-3xl border border-[var(--glass-border)] space-y-6 text-[var(--text-primary)]">
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
                            <span className="text-[10px] font-bold text-[var(--text-secondary)]">{new Date(prompt.updatedAt).toLocaleString()}</span>
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
    );
}
