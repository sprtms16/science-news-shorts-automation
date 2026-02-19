
import {
    LayoutDashboard,
    FileText,
    RefreshCw,
    Settings,
    Youtube,
    Menu,
    X,
    Languages,
    Sun,
    Moon,
    Monitor,
    ShieldCheck,
    ChevronRight,
    Download,
    Terminal,
    Music
} from 'lucide-react';
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

function cn(...inputs: (string | undefined | null | false)[]) {
    return twMerge(clsx(inputs));
}

interface SidebarProps {
    activeTab: 'videos' | 'prompts' | 'tools' | 'settings' | 'logs' | 'youtube' | 'bgm';
    setActiveTab: (tab: 'videos' | 'prompts' | 'tools' | 'settings' | 'logs' | 'youtube' | 'bgm') => void;
    isMobileMenuOpen: boolean;
    setIsMobileMenuOpen: (isOpen: boolean) => void;
    language: 'ko' | 'en';
    setLanguage: (lang: 'ko' | 'en') => void;
    theme: 'light' | 'dark' | 'system';
    setTheme: (theme: 'light' | 'dark' | 'system') => void;
    t: any;
    installPrompt: any;
    setInstallPrompt: (prompt: any) => void;
}

export function Sidebar({
    activeTab,
    setActiveTab,
    isMobileMenuOpen,
    setIsMobileMenuOpen,
    language,
    setLanguage,
    theme,
    setTheme,
    t,
    installPrompt,
    setInstallPrompt
}: SidebarProps) {

    return (
        <>
            {/* Mobile Header */}
            <header className="md:hidden sticky top-0 z-50 glass-morphism px-4 py-3 flex justify-between items-center border-b border-[var(--glass-border)]">
                <div className="flex items-center gap-2">
                    <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-purple-500 to-indigo-600 flex items-center justify-center">
                        <Youtube size={18} className="text-white" />
                    </div>
                    <h1 className="text-lg font-bold accent-text">SciencePixels</h1>
                </div>
                <button
                    onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}
                    className="p-2 hover:bg-black/5 dark:hover:bg-white/5 rounded-lg transition-colors"
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
                "fixed left-0 top-0 h-full w-72 z-50 glass-morphism border-r border-[var(--glass-border)] flex flex-col transition-transform duration-300 ease-in-out md:translate-x-0",
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
                            <p className="text-[10px] text-[var(--text-secondary)] font-medium uppercase tracking-widest">Shorts Automation</p>
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
                        icon={<Music size={18} />}
                        label={language === 'ko' ? 'BGM 관리' : 'AI BGM Manager'}
                        active={activeTab === 'bgm'}
                        onClick={() => { setActiveTab('bgm'); setIsMobileMenuOpen(false); }}
                    />
                    <NavItem
                        icon={<RefreshCw size={18} />}
                        label={t.tools}
                        active={activeTab === 'tools'}
                        onClick={() => { setActiveTab('tools'); setIsMobileMenuOpen(false); }}
                    />
                    <NavItem
                        icon={<Youtube size={18} />}
                        label={t.youtubeVideos}
                        active={activeTab === 'youtube'}
                        onClick={() => { setActiveTab('youtube'); setIsMobileMenuOpen(false); }}
                    />
                    <NavItem
                        icon={<Settings size={18} />}
                        label={t.settings}
                        active={activeTab === 'settings'}
                        onClick={() => { setActiveTab('settings'); setIsMobileMenuOpen(false); }}
                    />
                    <NavItem
                        icon={<Terminal size={18} />}
                        label={t.logs}
                        active={activeTab === 'logs'}
                        onClick={() => { setActiveTab('logs'); setIsMobileMenuOpen(false); }}
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
                                    language === 'ko' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-black/5 dark:bg-white/5 text-gray-500 border-transparent hover:bg-black/10 dark:hover:bg-white/10"
                                )}
                            >
                                KO
                            </button>
                            <button
                                onClick={() => setLanguage('en')}
                                className={cn(
                                    "px-3 py-1.5 rounded-lg text-xs font-bold transition-all border",
                                    language === 'en' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-black/5 dark:bg-white/5 text-gray-500 border-transparent hover:bg-black/10 dark:hover:bg-white/10"
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
                                    theme === 'light' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-black/5 dark:bg-white/5 text-gray-500 border-transparent hover:bg-black/10 dark:hover:bg-white/10"
                                )}
                                title={t.light}
                            >
                                <Sun size={14} />
                            </button>
                            <button
                                onClick={() => setTheme('dark')}
                                className={cn(
                                    "p-2 rounded-lg transition-all border flex justify-center",
                                    theme === 'dark' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-black/5 dark:bg-white/5 text-gray-500 border-transparent hover:bg-black/10 dark:hover:bg-white/10"
                                )}
                                title={t.dark}
                            >
                                <Moon size={14} />
                            </button>
                            <button
                                onClick={() => setTheme('system')}
                                className={cn(
                                    "p-2 rounded-lg transition-all border flex justify-center",
                                    theme === 'system' ? "bg-purple-500/10 text-purple-400 border-purple-500/20" : "bg-black/5 dark:bg-white/5 text-gray-500 border-transparent hover:bg-black/10 dark:hover:bg-white/10"
                                )}
                                title={t.system}
                            >
                                <Monitor size={14} />
                            </button>
                        </div>
                    </div>
                </div>

                <div className="p-6 border-t border-white/5 space-y-4">
                    {installPrompt && (
                        <button
                            onClick={() => {
                                installPrompt.prompt();
                                installPrompt.userChoice.then((choiceResult: any) => {
                                    if (choiceResult.outcome === 'accepted') {
                                        console.log('User accepted the A2HS prompt');
                                    }
                                    setInstallPrompt(null);
                                });
                            }}
                            className="w-full flex items-center justify-center gap-2 px-4 py-3 bg-gradient-to-r from-purple-600 to-indigo-600 rounded-xl text-white font-bold text-sm shadow-lg shadow-purple-500/20 hover:shadow-purple-500/40 transition-all active:scale-95"
                        >
                            <Download size={18} />
                            {language === 'ko' ? '앱 설치' : 'Install App'}
                        </button>
                    )}
                    <div className="flex items-center gap-3 p-3 rounded-xl bg-black/5 dark:bg-white/5 border border-black/5 dark:border-white/5">
                        <div className="w-8 h-8 rounded-full bg-gray-700 flex items-center justify-center">
                            <ShieldCheck size={16} className="text-purple-400" />
                        </div>
                        <div className="flex-1 overflow-hidden">
                            <p className="text-xs font-semibold truncate">Admin Console</p>
                            <p className="text-[10px] text-gray-500 truncate">v2.2.0-Modern</p>
                        </div>
                    </div>
                </div>
            </aside>
        </>
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
                    : "text-[var(--text-secondary)] hover:bg-[var(--input-bg)] hover:text-[var(--text-primary)] border border-transparent"
            )}
        >
            <div className="flex items-center gap-4">
                <span className={cn("transition-colors duration-300", active ? "text-purple-400" : "text-[var(--text-secondary)] group-hover:text-[var(--text-primary)]")}>
                    {icon}
                </span>
                {label}
            </div>
            {active && <ChevronRight size={14} className="text-purple-500/50" />}
        </button>
    );
}
