import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    Eye,
    Heart,
    ExternalLink,
    RefreshCw,
    Calendar,
    Youtube
} from 'lucide-react';
import axios from 'axios';

interface YoutubeVideoStat {
    videoId: String;
    title: string;
    description: string;
    viewCount: number;
    likeCount: number;
    publishedAt: string;
    thumbnailUrl: string;
}

interface YoutubeVideoListProps {
    t: any;
    language: 'ko' | 'en';
    selectedChannel: string;
}

// Helper to get channel-specific API base path
function getApiBase(channel: string): string {
    return `/api/${channel}`;
}

const YoutubeVideoList: React.FC<YoutubeVideoListProps> = ({ t, language, selectedChannel }) => {
    const [videos, setVideos] = useState<YoutubeVideoStat[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [nextPage, setNextPage] = useState<string | null>(null);

    const observer = useRef<IntersectionObserver | null>(null);
    const lastVideoElementRef = useCallback((node: HTMLDivElement | null) => {
        if (loading || loadingMore) return;
        if (observer.current) observer.current.disconnect();

        observer.current = new IntersectionObserver(entries => {
            if (entries[0].isIntersecting && nextPage) {
                fetchVideos(nextPage);
            }
        });

        if (node) observer.current.observe(node);
    }, [loading, loadingMore, nextPage]);

    // AbortController for canceling in-flight requests
    const abortRef = useRef<AbortController | null>(null);

    const fetchVideos = async (page: string | null = '0') => {
        // Cancel previous request
        abortRef.current?.abort();
        const controller = new AbortController();
        abortRef.current = controller;
        const isInitial = page === '0';

        if (isInitial) {
            setLoading(true);
            setVideos([]);
        } else {
            setLoadingMore(true);
        }

        setError(null);
        try {
            const url = `${getApiBase(selectedChannel)}/admin/youtube/my-videos?size=24&page=${page}&channelId=${selectedChannel}`;
            const response = await axios.get(url, { signal: controller.signal });

            if (isInitial) {
                setVideos(response.data.videos);
            } else {
                setVideos(prev => [...prev, ...response.data.videos]);
            }
            // Fix: response field name is nextPageToken, not nextPage
            setNextPage(response.data.nextPageToken);
        } catch (err: any) {
            if (axios.isCancel(err)) return; // Ignore canceled requests
            console.error('Failed to fetch YouTube videos:', err);
            setError(err.message || 'Failed to load videos from YouTube.');
        } finally {
            setLoading(false);
            setLoadingMore(false);
        }
    };

    const handleFixDescription = async (videoId: string) => {
        if (!window.confirm(language === 'ko' ? '이 영상의 설명을 새로 생성하여 업데이트하시겠습니까?' : 'Do you want to regenerate and update the description for this video?')) return;

        try {
            const response = await axios.post(`${getApiBase(selectedChannel)}/admin/youtube/fix-video-description?videoId=${videoId}&channelId=${selectedChannel}`);
            if (response.data.status === 'success') {
                alert(language === 'ko' ? '설명이 성공적으로 수정되었습니다.' : 'Description fixed successfully.');
            }
        } catch (err: any) {
            console.error('Failed to fix description:', err);
            alert(language === 'ko' ? '설명 수정에 실패했습니다.' : 'Failed to fix description.');
        }
    };

    const handleSync = async () => {
        setLoading(true);
        setError(null);
        try {
            await axios.post(`${getApiBase(selectedChannel)}/admin/youtube/sync?channelId=${selectedChannel}`);
            await fetchVideos('0'); // Refresh list after sync
        } catch (err: any) {
            console.error('Sync failed:', err);
            setError('Global sync failed. Please try again later.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchVideos('0');
    }, [selectedChannel]);

    if (loading && videos.length === 0) {
        return (
            <div className="flex flex-col items-center justify-center min-h-[400px] text-gray-400">
                <RefreshCw className="w-8 h-8 animate-spin mb-4 text-blue-500" />
                <p>{t.loading}...</p>
            </div>
        );
    }

    return (
        <div className="p-6 space-y-6">
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <div className="p-2 bg-red-500/10 rounded-lg">
                        <Youtube className="w-6 h-6 text-red-500" />
                    </div>
                    <div>
                        <h2 className="text-xl font-bold text-white">{t.youtubeVideos}</h2>
                        <p className="text-sm text-gray-400">
                            {language === 'ko'
                                ? `총 ${videos.length}개의 영상 로드됨`
                                : `Loaded ${videos.length} videos`}
                        </p>
                    </div>
                </div>
                <button
                    onClick={handleSync}
                    disabled={loading}
                    className="flex items-center gap-2 px-4 py-2 bg-gray-800 hover:bg-gray-700 text-white rounded-lg transition-colors border border-gray-700 disabled:opacity-50"
                >
                    <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
                    {t.syncData}
                </button>
            </div>

            {error && (
                <div className="p-4 bg-red-500/10 border border-red-500/20 rounded-xl text-red-400 text-sm">
                    {error}
                </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
                {videos.map((video, index) => {
                    const isLast = index === videos.length - 1;
                    return (
                        <div
                            key={video.videoId.toString()}
                            ref={isLast ? lastVideoElementRef : null}
                            className="group bg-gray-900 border border-gray-800 rounded-2xl overflow-hidden hover:border-gray-600 transition-all duration-300"
                        >
                            {/* Thumbnail */}
                            <div className="relative aspect-video">
                                <img
                                    src={video.thumbnailUrl}
                                    alt={video.title}
                                    className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                                    loading="lazy"
                                />
                                <div className="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center">
                                    <a
                                        href={`https://youtube.com/watch?v=${video.videoId}`}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        className="p-3 bg-red-600 rounded-full text-white transform scale-90 group-hover:scale-100 transition-transform"
                                    >
                                        <ExternalLink className="w-6 h-6" />
                                    </a>
                                </div>
                            </div>

                            {/* Content */}
                            <div className="p-4 space-y-3">
                                <h3 className="font-semibold text-white line-clamp-2 min-h-[3rem]">
                                    {video.title}
                                </h3>

                                <p className="text-xs text-gray-500 line-clamp-3 min-h-[3rem]">
                                    {video.description}
                                </p>

                                <div className="flex items-center gap-4 text-sm text-gray-400">
                                    <div className="flex items-center gap-1.5">
                                        <Eye className="w-4 h-4 text-blue-400" />
                                        <span>{video.viewCount.toLocaleString()}</span>
                                    </div>
                                    <div className="flex items-center gap-1.5">
                                        <Heart className="w-4 h-4 text-red-400" />
                                        <span>{video.likeCount.toLocaleString()}</span>
                                    </div>
                                </div>

                                <div className="pt-3 border-t border-gray-800 flex items-center justify-between text-xs text-gray-500">
                                    <div className="flex items-center gap-1.5">
                                        <Calendar className="w-3.5 h-3.5" />
                                        <span>{new Date(video.publishedAt).toLocaleDateString()}</span>
                                    </div>
                                    <div className="flex items-center gap-3">
                                        <button
                                            onClick={() => handleFixDescription(video.videoId.toString())}
                                            className="text-purple-400 hover:text-purple-300 transition-colors"
                                            title={language === 'ko' ? '설명 복구' : 'Fix Description'}
                                        >
                                            <RefreshCw className="w-4 h-4" />
                                        </button>
                                        <a
                                            href={`https://youtube.com/watch?v=${video.videoId}`}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                            className="text-blue-400 hover:underline flex items-center gap-1"
                                        >
                                            {t.watchOnYoutube}
                                            <ExternalLink className="w-3 h-3" />
                                        </a>
                                    </div>
                                </div>
                            </div>
                        </div>
                    );
                })}
            </div>

            {(loadingMore || loading) && videos.length > 0 && (
                <div className="flex justify-center pt-8">
                    <RefreshCw className="w-6 h-6 animate-spin text-purple-400" />
                </div>
            )}

            {!nextPage && videos.length > 0 && (
                <div className="text-center py-10 text-gray-500 text-sm italic">
                    {language === 'ko' ? '모든 영상을 불러왔습니다.' : 'End of channel.'}
                </div>
            )}

            {videos.length === 0 && !loading && (
                <div className="text-center py-20 bg-gray-900/50 rounded-3xl border border-dashed border-gray-800 text-gray-500 text-sm">
                    {t.noVideos}
                </div>
            )}
        </div>
    );
};

export default YoutubeVideoList;
