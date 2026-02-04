export interface VideoHistory {
    id: string;
    channelId: string;
    title: string;
    summary: string;
    link: string;
    filePath: string;
    youtubeUrl: string;
    status: string;
    failureStep?: string;
    errorMessage?: string;
    description: string;
    tags: string[];
    sources: string[];
    retryCount: number;
    regenCount: number;
    createdAt: string;
    updatedAt: string;
}

export interface SystemPrompt {
    id: string;
    channelId: string;
    promptKey: string;
    content: string;
    description: string;
    updatedAt: string;
}

export interface SystemSetting {
    id: string;
    channelId: string;
    key: string;
    value: string;
    description: string;
    updatedAt: string;
}
