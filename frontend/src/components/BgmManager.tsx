import React, { useState, useEffect } from 'react';
import '../App.css';

interface BgmEntity {
    id: string;
    filename: string;
    status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
    mood?: string;
    createdAt: string;
    errorMessage?: string;
}

const BgmManager: React.FC = () => {
    const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
    const [uploading, setUploading] = useState(false);
    const [forceCategory, setForceCategory] = useState("auto");
    const [isDragging, setIsDragging] = useState(false);
    const [bgmList, setBgmList] = useState<BgmEntity[]>([]);

    useEffect(() => {
        fetchBgmList();
        const interval = setInterval(fetchBgmList, 3000); // Poll every 3 seconds
        return () => clearInterval(interval);
    }, []);

    const fetchBgmList = async () => {
        try {
            const res = await fetch('/api/science/admin/bgm/list');
            if (res.ok) {
                const data = await res.json();
                setBgmList(data);
            }
        } catch (e) {
            console.error("Failed to fetch BGM list", e);
        }
    };

    const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        if (e.target.files) {
            setSelectedFiles(Array.from(e.target.files));
        }
    };

    const handleDragOver = (e: React.DragEvent) => {
        e.preventDefault();
        setIsDragging(true);
    };

    const handleDragLeave = () => {
        setIsDragging(false);
    };

    const handleDrop = (e: React.DragEvent) => {
        e.preventDefault();
        setIsDragging(false);
        if (e.dataTransfer.files) {
            setSelectedFiles(Array.from(e.dataTransfer.files));
        }
    };

    const handleUpload = async () => {
        if (selectedFiles.length === 0) return;

        setUploading(true);

        const formData = new FormData();
        selectedFiles.forEach(file => {
            formData.append("files", file);
        });
        formData.append("forceCategory", forceCategory);

        try {
            const response = await fetch('/api/science/admin/bgm/upload', {
                method: 'POST',
                body: formData,
            });

            if (response.ok) {
                const data = await response.json();
                setSelectedFiles([]); // Clear selection after success
                fetchBgmList(); // Immediate refresh
                alert(`Upload Queued! (New: ${data.uploaded}, Duplicates: ${data.duplicates})`);
            } else {
                alert("Upload failed: " + response.statusText);
            }
        } catch (error) {
            console.error("Error uploading:", error);
            alert("Upload error. Check console.");
        } finally {
            setUploading(false);
        }
    };

    const handleRetry = async (id: string) => {
        if (!confirm("Retry AI verification for this item?")) return;
        try {
            const res = await fetch(`/api/science/admin/bgm/retry/${id}`, { method: 'POST' });
            if (res.ok) {
                alert("Retry initiated!");
                fetchBgmList();
            } else {
                alert("Retry failed");
            }
        } catch (e) {
            console.error(e);
            alert("Retry Error");
        }
    };

    const handleDelete = async (id: string) => {
        if (!confirm("Are you sure you want to delete this BGM? This cannot be undone.")) return;
        try {
            const res = await fetch(`/api/science/admin/bgm/${id}`, { method: 'DELETE' });
            if (res.ok) {
                fetchBgmList();
            } else {
                alert("Delete failed");
            }
        } catch (e) {
            console.error(e);
            alert("Delete Error");
        }
    };

    const handleEdit = async (bgm: BgmEntity) => {
        const newMood = prompt("Enter new mood (category):", bgm.mood || "");
        if (newMood === null || newMood === bgm.mood) return;

        try {
            const res = await fetch(`/api/science/admin/bgm/${bgm.id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mood: newMood })
            });
            if (res.ok) {
                fetchBgmList();
            } else {
                alert("Update failed");
            }
        } catch (e) {
            console.error(e);
            alert("Update Error");
        }
    };

    const getStatusBadge = (status: string) => {
        switch (status) {
            case 'PENDING': return <span className="status-badge pending">⏳ Pending</span>;
            case 'PROCESSING': return <span className="status-badge processing">🔄 Analyzing...</span>;
            case 'COMPLETED': return <span className="status-badge success">✅ Ready</span>;
            case 'FAILED': return <span className="status-badge error">❌ Failed</span>;
            default: return status;
        }
    };

    return (
        <div className="card">
            <h2>🎵 AI BGM Manager (Full CRUD)</h2>
            <p>Upload music. AI classifies asynchronously.</p>

            <div className="bgm-controls" style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>

                {/* Drag & Drop Zone */}
                <div
                    className={`drop-zone ${isDragging ? 'dragging' : ''}`}
                    onDragOver={handleDragOver}
                    onDragLeave={handleDragLeave}
                    onDrop={handleDrop}
                    onClick={() => document.getElementById('bgm-file-input')?.click()}
                    style={{
                        border: '3px dashed var(--input-border)',
                        borderRadius: '16px',
                        padding: '30px',
                        textAlign: 'center',
                        cursor: 'pointer',
                        transition: 'all 0.3s ease',
                        backgroundColor: isDragging ? 'rgba(168, 85, 247, 0.1)' : 'transparent',
                        borderColor: isDragging ? '#a855f7' : 'var(--input-border)'
                    }}
                >
                    <input
                        id="bgm-file-input"
                        type="file"
                        multiple
                        accept="audio/*"
                        onChange={handleFileChange}
                        style={{ display: 'none' }}
                    />
                    <div style={{ pointerEvents: 'none' }}>
                        <p style={{ fontSize: '1.2rem', fontWeight: 'bold', marginBottom: '8px' }}>
                            {selectedFiles.length > 0
                                ? `🎵 ${selectedFiles.length} files selected`
                                : "Drop files here or Click to browse"}
                        </p>
                    </div>
                </div>

                <div className="action-bar" style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <select
                        value={forceCategory}
                        onChange={(e) => setForceCategory(e.target.value)}
                        className="category-select"
                        style={{ padding: '10px', borderRadius: '8px', flex: 1 }}
                    >
                        <option value="auto">✨ Auto Detect (AI)</option>
                        <option value="futuristic">🧪 Futuristic (Science)</option>
                        <option value="suspense">👻 Suspense (Horror)</option>
                        <option value="corporate">📈 Corporate (Stocks)</option>
                        <option value="epic">📜 Epic (History)</option>
                        <option value="calm">☕ Calm (General)</option>
                    </select>

                    <button
                        onClick={handleUpload}
                        disabled={uploading || selectedFiles.length === 0}
                        className="primary-btn"
                        style={{ padding: '10px 20px', flex: 1, height: '44px' }}
                    >
                        {uploading ? "Queuing..." : "Upload & Analyze"}
                    </button>
                </div>
            </div>

            <div className="results-list" style={{ marginTop: '30px' }}>
                <h3>BGM Library Status</h3>
                <div className="table-container">
                    <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: '10px' }}>
                        <thead>
                            <tr style={{ borderBottom: '1px solid #333', textAlign: 'left' }}>
                                <th style={{ padding: '10px' }}>Filename</th>
                                <th style={{ padding: '10px' }}>Mood</th>
                                <th style={{ padding: '10px' }}>Status</th>
                                <th style={{ padding: '10px' }}>Time</th>
                                <th style={{ padding: '10px' }}>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            {bgmList.map((bgm) => (
                                <tr key={bgm.id} style={{ borderBottom: '1px solid #222' }}>
                                    <td style={{ padding: '10px' }}>{bgm.filename}</td>
                                    <td style={{ padding: '10px' }}>
                                        {bgm.mood ? <span className="category-tag">{bgm.mood.toUpperCase()}</span> : '-'}
                                    </td>
                                    <td style={{ padding: '10px' }}>{getStatusBadge(bgm.status)}</td>
                                    <td style={{ padding: '10px', fontSize: '0.8rem', color: '#888' }}>
                                        {new Date(bgm.createdAt).toLocaleTimeString()}
                                    </td>
                                    <td style={{ padding: '10px', display: 'flex', gap: '5px' }}>
                                        <button
                                            onClick={() => handleRetry(bgm.id)}
                                            style={{
                                                padding: '5px 8px',
                                                fontSize: '0.8rem',
                                                cursor: 'pointer',
                                                backgroundColor: '#333',
                                                color: '#fff',
                                                border: '1px solid #555',
                                                borderRadius: '4px'
                                            }}
                                            title="Retry AI Analysis"
                                        >
                                            🔄
                                        </button>
                                        <button
                                            onClick={() => handleEdit(bgm)}
                                            style={{
                                                padding: '5px 8px',
                                                fontSize: '0.8rem',
                                                cursor: 'pointer',
                                                backgroundColor: '#2563eb', // Blue
                                                color: '#fff',
                                                border: 'none',
                                                borderRadius: '4px'
                                            }}
                                            title="Edit Mood"
                                        >
                                            ✏️
                                        </button>
                                        <button
                                            onClick={() => handleDelete(bgm.id)}
                                            style={{
                                                padding: '5px 8px',
                                                fontSize: '0.8rem',
                                                cursor: 'pointer',
                                                backgroundColor: '#ef4444', // Red
                                                color: '#fff',
                                                border: 'none',
                                                borderRadius: '4px'
                                            }}
                                            title="Delete"
                                        >
                                            🗑️
                                        </button>
                                    </td>
                                </tr>
                            ))}
                            {bgmList.length === 0 && (
                                <tr>
                                    <td colSpan={5} style={{ textAlign: 'center', padding: '20px', color: '#666' }}>
                                        No BGM uploads yet.
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

export default BgmManager;
