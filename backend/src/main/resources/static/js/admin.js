const API_BASE = '/admin';

document.addEventListener('DOMContentLoaded', () => {
    loadVideos();
    loadPrompts();
});

function switchTab(tabId) {
    document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
    document.getElementById('tab-' + tabId).classList.add('active');

    document.querySelectorAll('.nav-links li').forEach(el => el.classList.remove('active'));
    event.currentTarget.classList.add('active'); // Needs 'event' passed or bound, but for simplicity relying on inline onclick binding scope or just refreshing logic separately.
    
    // Quick fix for navbar active state since onclick is inline
    const navItems = document.querySelectorAll('.nav-links li');
    if(tabId === 'videos') { navItems[0].classList.add('active'); navItems[1].classList.remove('active'); }
    if(tabId === 'prompts') { navItems[1].classList.add('active'); navItems[0].classList.remove('active'); }
}

/* --- Videos --- */

async function loadVideos() {
    try {
        const res = await fetch(`${API_BASE}/videos`);
        const videos = await res.json();
        renderVideos(videos);
    } catch (e) {
        console.error("Failed to load videos", e);
        alert("Failed to load videos");
    }
}

function renderVideos(videos) {
    const tbody = document.getElementById('video-list');
    tbody.innerHTML = '';
    
    if (videos.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;">No videos found.</td></tr>';
        return;
    }

    videos.forEach(v => {
        const row = document.createElement('tr');
        row.innerHTML = `
            <td><span class="badge badge-${v.status}">${v.status}</span></td>
            <td>${v.title}</td>
            <td>${new Date(v.createdAt).toLocaleString()}</td>
            <td>${v.youtubeUrl ? `<a href="https://youtu.be/${v.youtubeUrl}" target="_blank">${v.youtubeUrl}</a>` : '-'}</td>
            <td>
                <button class="btn btn-secondary btn-sm" onclick='openEditModal(${JSON.stringify(v)})'>
                    <i class="fa-solid fa-edit"></i> Edit
                </button>
            </td>
        `;
        tbody.appendChild(row);
    });
}

const editModal = document.getElementById('edit-modal');
const editId = document.getElementById('edit-id');
const editTitle = document.getElementById('edit-title');
const editStatus = document.getElementById('edit-status');
const editUrl = document.getElementById('edit-url');

function openEditModal(video) {
    editId.value = video.id;
    editTitle.value = video.title;
    editStatus.value = video.status;
    editUrl.value = video.youtubeUrl || '';
    editModal.style.display = 'block';
}

function closeModal() {
    editModal.style.display = 'none';
}

async function saveVideo(e) {
    e.preventDefault();
    const id = editId.value;
    const data = {
        status: editStatus.value,
        youtubeUrl: editUrl.value.trim() || null
    };

    try {
        const res = await fetch(`${API_BASE}/videos/${id}/status`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });

        if (res.ok) {
            closeModal();
            loadVideos();
        } else {
            alert("Failed to update video");
        }
    } catch (e) {
        alert("Error updating video");
    }
}

/* --- Prompts --- */

async function loadPrompts() {
    try {
        const res = await fetch(`${API_BASE}/prompts`);
        const prompts = await res.json();
        renderPrompts(prompts);
    } catch (e) {
        console.error("Failed to load prompts", e);
    }
}

function renderPrompts(prompts) {
    const grid = document.getElementById('prompt-list');
    grid.innerHTML = '';

    if (prompts.length === 0) {
        grid.innerHTML = '<p style="grid-column: 1/-1; text-align:center;">No custom prompts found. (Defaults usage)</p>';
        
        // Add a button to create the default one if missing (optional)
        // or just rely on backend creating it on first use.
        // For dashboard UX, let's manually show the Script Prompt card if empty so user can edit it.
        const defaultCard = document.createElement('div');
        defaultCard.className = 'prompt-card';
        defaultCard.innerHTML = `
            <h3><i class="fa-solid fa-magic"></i> script_prompt_v1</h3>
            <p>Default Script Generation Prompt</p>
            <small style="color: grey;">(Not in DB yet, click to create)</small>
        `;
        defaultCard.onclick = () => openPromptModal({ id: 'script_prompt_v1', description: 'Standard Sci-News Script', content: '' });
        grid.appendChild(defaultCard);
        return;
    }

    prompts.forEach(p => {
        const card = document.createElement('div');
        card.className = 'prompt-card';
        card.innerHTML = `
            <h3><i class="fa-solid fa-magic"></i> ${p.id}</h3>
            <p>${p.description}</p>
            <small>Updated: ${new Date(p.updatedAt).toLocaleDateString()}</small>
        `;
        card.onclick = () => openPromptModal(p);
        grid.appendChild(card);
    });
}

const promptModal = document.getElementById('prompt-modal');
const promptId = document.getElementById('prompt-id');
const promptDesc = document.getElementById('prompt-desc');
const promptContent = document.getElementById('prompt-content');

function openPromptModal(prompt) {
    promptId.value = prompt.id;
    promptDesc.value = prompt.description;
    promptContent.value = prompt.content;
    promptModal.style.display = 'block';
}

function closePromptModal() {
    promptModal.style.display = 'none';
}

async function savePrompt(e) {
    e.preventDefault();
    const data = {
        id: promptId.value,
        description: promptDesc.value,
        content: promptContent.value
    };

    try {
        const res = await fetch(`${API_BASE}/prompts`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });

        if (res.ok) {
            closePromptModal();
            loadPrompts();
        } else {
            alert("Failed to save prompt");
        }
    } catch (e) {
        alert("Error saving prompt");
    }
}

// Window click to close modals
window.onclick = function(event) {
    if (event.target == editModal) closeModal();
    if (event.target == promptModal) closePromptModal();
}
