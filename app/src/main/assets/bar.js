// =========================================================
// Fastest Browser Toolbar — Extension Feature Port for APK
// =========================================================
import { initializeApp } from "./firebase-app.js";
import { getDatabase, ref, set, onValue, get } from "./firebase-database.js";

// --- Firebase Config (from extension) ---
const firebaseConfig = {
    apiKey: "AIzaSyAYHA8Qw3Qw3Qzbyg8MtjrSKcJusNi4VaA6V4",
    authDomain: "system-data-bf026.firebaseapp.com",
    databaseURL: "https://system-data-bf026-default-rtdb.asia-southeast1.firebasedatabase.app",
    projectId: "system-data-bf026",
    storageBucket: "system-data-bf026.firebasestorage.app",
    messagingSenderId: "718302899022",
    appId: "1:718302899022:web:f1675dbcc293e138c68c1f"
};

const app = initializeApp(firebaseConfig);
const db = getDatabase(app);
const bookmarksDbRef = ref(db, 'browser_data/bookmarks');
const passwordsDbRef = ref(db, 'browser_data/passwords');
const tabsDbRef = ref(db, 'browser_data/tabs');

// --- State ---
const FOLDER_KEYS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
let bookmarks = [];
let passwordsCache = {};
let downloadsList = [];
let activeFolder = null;
let lastClipboardText = '';
let currentUrl = '';
let pendingPasswordSave = null;

// --- DOM refs ---
const addressInput = document.getElementById('address-input');
const bookmarkBar = document.getElementById('bookmark-bar');
const folderPanel = document.getElementById('folder-panel');
const fpTitle = document.getElementById('fp-title');
const fpList = document.getElementById('fp-list');
const downloadsPanel = document.getElementById('downloads-panel');
const dlListEl = document.getElementById('dl-list');
const dlBadgeEl = document.getElementById('dl-badge');
const dlClearBtn = document.getElementById('dl-clear-btn');
const quickBmOverlay = document.getElementById('quick-bm-overlay');
const qbmNameInput = document.getElementById('qbm-name-input');
const qbmUrlPreview = document.getElementById('qbm-url-preview');
const qbmFolderValue = document.getElementById('qbm-folder-value');
const pwdToast = document.getElementById('pwd-toast');
const pwdToastMsg = document.getElementById('pwd-toast-msg');

// =========================================================
// Helpers
// =========================================================
function safeCall(fn, ...args) {
    try { if (typeof fn === 'function') return fn(...args); } catch(e) {}
    return undefined;
}

function deriveName(url) {
    try { return new URL(url).hostname.replace(/^www\./i,'').split('.')[0].toUpperCase(); } catch { return url; }
}
function deriveFolder(name) {
    const c = (name||'').trim().charAt(0).toUpperCase();
    return FOLDER_KEYS.includes(c) ? c : '0';
}

// =========================================================
// Navigation (delegated to Android native layer)
// =========================================================
function navigateActivePane(url) {
    let target = (url||'').trim();
    if (!target) return;
    const isSearch = target.includes(' ') || (!target.includes('.') && !target.startsWith('localhost') && !target.includes('://'));
    if (isSearch) target = 'https://www.google.com/search?q=' + encodeURIComponent(target);
    else if (!/^https?:\/\//i.test(target)) target = 'https://' + target;
    safeCall(window.AndroidAPI && AndroidAPI.loadUrl, target);
    currentUrl = target;
    addressInput.value = target;
}

// Called from Java when active pane URL changes
window.updateAddress = function(url) {
    currentUrl = url || '';
    addressInput.value = currentUrl;
    checkAutofill(currentUrl);
};

window.setNavState = function(canBack, canForward) {
    document.getElementById('btn-back').disabled = !canBack;
    document.getElementById('btn-forward').disabled = !canForward;
};

window.setSplitState = function(canClose) {
    document.getElementById('btn-close-pane').disabled = !canClose;
};

window.onDownloadBatchUpdate = function(msg) {
    if (!msg || !msg.data) return;
    const arr = msg.data;
    arr.forEach(d => {
        const idx = downloadsList.findIndex(x => x.id === d.id);
        if (idx === -1) downloadsList.unshift(d); else downloadsList[idx] = d;
    });
    renderDownloads();
    updateDownloadBadge();
};

// --- Event listeners ---
document.getElementById('btn-go').addEventListener('click', () => navigateActivePane(addressInput.value));
addressInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') navigateActivePane(addressInput.value); });
addressInput.addEventListener('focus', () => addressInput.select());

document.getElementById('btn-back').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.goBack));
document.getElementById('btn-forward').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.goForward));
document.getElementById('btn-zoom-in').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.zoomIn));
document.getElementById('btn-zoom-out').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.zoomOut));
document.getElementById('btn-fullscreen').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.toggleFullscreen));
document.getElementById('btn-split-row').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.splitPane, 'row'));
document.getElementById('btn-split-col').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.splitPane, 'column'));
document.getElementById('btn-close-pane').addEventListener('click', () => safeCall(window.AndroidAPI && AndroidAPI.closeActivePane));

// =========================================================
// Bookmarks (Firebase synced, A-Z folders)
// =========================================================
function renderBookmarkBar() {
    bookmarkBar.innerHTML = '';
    FOLDER_KEYS.forEach(k => {
        const b = document.createElement('button');
        b.className = 'folder-btn';
        b.dataset.folder = k;
        b.textContent = k;
        const has = bookmarks.some(bm => bm.folder === k);
        if (has) b.classList.add('has-bookmarks');
        b.onclick = (e) => { e.stopPropagation(); activeFolder === k ? closeFolderPanel() : openFolderPanel(k); };
        bookmarkBar.appendChild(b);
    });
}

function openFolderPanel(key) {
    if (activeFolder) {
        const prev = bookmarkBar.querySelector(`.folder-btn[data-folder="${activeFolder}"]`);
        if (prev) prev.classList.remove('active');
    }
    activeFolder = key;
    const btn = bookmarkBar.querySelector(`.folder-btn[data-folder="${key}"]`);
    if (btn) btn.classList.add('active');
    fpTitle.textContent = 'Folder ' + key;
    renderFolderList();
    folderPanel.classList.add('open');
}

function closeFolderPanel() {
    if (activeFolder) {
        const prev = bookmarkBar.querySelector(`.folder-btn[data-folder="${activeFolder}"]`);
        if (prev) prev.classList.remove('active');
    }
    activeFolder = null;
    folderPanel.classList.remove('open');
}

function renderFolderList() {
    fpList.innerHTML = '';
    const entries = bookmarks.map((bm, idx) => ({bm, idx})).filter(e => e.bm.folder === activeFolder);
    if (!entries.length) { fpList.innerHTML = `<div class="fp-empty">No bookmarks in ${activeFolder}</div>`; return; }
    entries.forEach(e => {
        const item = document.createElement('div'); item.className = 'fp-item';
        const lbl = document.createElement('div'); lbl.className = 'fp-item-label'; lbl.textContent = e.bm.label || e.bm.url;
        item.appendChild(lbl);
        item.onclick = () => { navigateActivePane(e.bm.url); closeFolderPanel(); };
        const del = document.createElement('button'); del.className = 'fp-del'; del.textContent = '✕';
        del.onclick = (ev) => { ev.stopPropagation(); bookmarks.splice(e.idx, 1); saveBookmarks(); };
        item.appendChild(del);
        fpList.appendChild(item);
    });
}

function saveBookmarks() {
    set(bookmarksDbRef, bookmarks);
    renderBookmarkBar();
    if (folderPanel.classList.contains('open')) renderFolderList();
}

onValue(bookmarksDbRef, (snapshot) => {
    const data = snapshot.val();
    bookmarks = Array.isArray(data) ? data : [];
    renderBookmarkBar();
    if (folderPanel.classList.contains('open')) renderFolderList();
});

// Quick Bookmark Modal
let qbmSelectedFolder = '0';
function setQbmFolder(k) { qbmSelectedFolder = k; qbmFolderValue.textContent = k; }

function closeQBM() { quickBmOverlay.classList.remove('open'); }
document.getElementById('qbm-close-btn').onclick = closeQBM;
document.getElementById('qbm-cancel-btn').onclick = closeQBM;
qbmNameInput.addEventListener('input', () => setQbmFolder(deriveFolder(qbmNameInput.value)));
qbmNameInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') document.getElementById('qbm-save-btn').click(); if (e.key === 'Escape') closeQBM(); });

document.getElementById('btn-add-bookmark').onclick = () => {
    if (!currentUrl) return;
    qbmUrlPreview.textContent = currentUrl;
    qbmNameInput.value = deriveName(currentUrl);
    setQbmFolder(deriveFolder(qbmNameInput.value));
    quickBmOverlay.classList.add('open');
    qbmNameInput.focus(); qbmNameInput.select();
};

document.getElementById('qbm-save-btn').onclick = () => {
    bookmarks.push({ label: qbmNameInput.value.trim() || currentUrl, url: currentUrl, folder: qbmSelectedFolder });
    saveBookmarks();
    closeQBM();
};

// =========================================================
// Password Manager (Firebase synced)
// =========================================================
onValue(passwordsDbRef, (snapshot) => {
    passwordsCache = snapshot.val() || {};
});

function getDomainKey(url) {
    try { return new URL(url).hostname.replace(/[\.\#\$\[\]]/g, '_'); } catch { return ''; }
}

function checkAutofill(url) {
    if (!url) return;
    const domainKey = getDomainKey(url);
    if (!domainKey || !passwordsCache[domainKey]) return;
    const creds = passwordsCache[domainKey];
    const payload = JSON.stringify({
        type: 'autofill-credentials',
        credentials: {
            username: creds.username || '',
            password: creds.password ? atob(creds.password) : ''
        }
    });
    safeCall(window.AndroidAPI && AndroidAPI.broadcastToPanes, payload);
}

window.onPasswordDetected = function(domain, username, password) {
    if (!domain || !username || !password) return;
    pendingPasswordSave = { domain, username, password };
    pwdToastMsg.textContent = `Save password for ${username} at ${domain}?`;
    pwdToast.classList.add('open');
    setTimeout(() => { if (pendingPasswordSave) pwdToast.classList.remove('open'); }, 15000);
};

document.getElementById('pwd-toast-yes').onclick = () => {
    if (!pendingPasswordSave) return;
    const safeDomain = pendingPasswordSave.domain.replace(/[\.\#\$\[\]]/g, '_');
    set(ref(db, `browser_data/passwords/${safeDomain}`), {
        domain: pendingPasswordSave.domain,
        username: pendingPasswordSave.username,
        password: btoa(pendingPasswordSave.password),
        updatedAt: Date.now()
    });
    pendingPasswordSave = null;
    pwdToast.classList.remove('open');
};

document.getElementById('pwd-toast-no').onclick = () => {
    pendingPasswordSave = null;
    pwdToast.classList.remove('open');
};

// =========================================================
// Downloads Panel
// =========================================================
function isDownloadActive(d) { return d.state === 'started' || d.state === 'progressing' || d.state === 'paused'; }

function formatBytes(n) {
    if (!n || n <= 0) return '0 KB';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0, val = n;
    while (val >= 1024 && i < units.length - 1) { val /= 1024; i++; }
    return `${val.toFixed(i === 0 || val >= 10 ? 0 : 1)} ${units[i]}`;
}

function renderDownloads() {
    if (!downloadsList.length) {
        dlListEl.innerHTML = '<div class="dl-empty">No downloads yet</div>';
        dlClearBtn.style.display = 'none';
        return;
    }
    dlClearBtn.style.display = '';
    dlListEl.innerHTML = '';
    downloadsList.forEach((d) => {
        const pct = d.totalBytes > 0 ? Math.min(100, Math.round((d.receivedBytes / d.totalBytes) * 100)) : 0;
        const item = document.createElement('div'); item.className = 'dl-item';
        const top = document.createElement('div'); top.className = 'dl-item-top';
        const name = document.createElement('div'); name.className = 'dl-item-name'; name.textContent = d.filename; name.title = d.filename;
        top.appendChild(name); item.appendChild(top);

        if (isDownloadActive(d)) {
            const track = document.createElement('div'); track.className = 'dl-progress-track';
            const fill = document.createElement('div'); fill.className = 'dl-progress-fill'; fill.style.width = pct + '%';
            track.appendChild(fill); item.appendChild(track);
        }

        const meta = document.createElement('div'); meta.className = 'dl-item-meta';
        if (d.state === 'completed') meta.textContent = `${formatBytes(d.totalBytes)} — Done`;
        else if (d.state === 'cancelled') meta.textContent = 'Cancelled';
        else if (d.state === 'interrupted') meta.textContent = 'Failed';
        else if (d.state === 'paused') meta.textContent = `Paused — ${pct}%`;
        else meta.textContent = d.totalBytes > 0 ? `${pct}% of ${formatBytes(d.totalBytes)}` : formatBytes(d.receivedBytes);
        item.appendChild(meta);

        const actions = document.createElement('div'); actions.className = 'dl-item-actions';
        if (d.state === 'completed') {
            const openBtn = document.createElement('button'); openBtn.className = 'dl-action-btn'; openBtn.textContent = 'Open';
            openBtn.onclick = () => safeCall(window.AndroidAPI && AndroidAPI.openDownload, d.id);
            const showBtn = document.createElement('button'); showBtn.className = 'dl-action-btn'; showBtn.textContent = 'Show';
            showBtn.onclick = () => safeCall(window.AndroidAPI && AndroidAPI.showDownload, d.id);
            actions.appendChild(openBtn); actions.appendChild(showBtn);
        } else if (isDownloadActive(d)) {
            const cancelBtn = document.createElement('button'); cancelBtn.className = 'dl-action-btn'; cancelBtn.textContent = 'Cancel';
            cancelBtn.onclick = () => safeCall(window.AndroidAPI && AndroidAPI.cancelDownload, d.id);
            actions.appendChild(cancelBtn);
        }
        if (actions.childElementCount) item.appendChild(actions);
        dlListEl.appendChild(item);
    });
}

function updateDownloadBadge() {
    const activeCount = downloadsList.filter(isDownloadActive).length;
    if (activeCount > 0) { dlBadgeEl.textContent = activeCount; dlBadgeEl.style.display = 'flex'; }
    else { dlBadgeEl.style.display = 'none'; }
}

function pollDownloads() {
    try {
        const raw = safeCall(window.AndroidAPI && AndroidAPI.getDownloads);
        if (raw) {
            const parsed = JSON.parse(raw);
            if (Array.isArray(parsed)) { downloadsList = parsed; renderDownloads(); updateDownloadBadge(); }
        }
    } catch(e) {}
}
setInterval(pollDownloads, 800);
pollDownloads();

document.getElementById('btn-downloads').addEventListener('click', (e) => { e.stopPropagation(); downloadsPanel.classList.toggle('open'); });
dlClearBtn.addEventListener('click', () => {
    downloadsList = downloadsList.filter(isDownloadActive);
    renderDownloads();
    safeCall(window.AndroidAPI && AndroidAPI.clearCompletedDownloads);
});

// =========================================================
// TRINITY SYNC — Clipboard Monitor
// =========================================================
function broadcastTrinityPair(pair) {
    if (!pair) return;
    const clean = pair.trim().toUpperCase();
    const payload = JSON.stringify({ trinityOS_pair: clean });
    safeCall(window.AndroidAPI && AndroidAPI.broadcastToPanes, payload);
}

setInterval(async () => {
    let text = '';
    try {
        if (document.hasFocus() && navigator.clipboard && navigator.clipboard.readText) {
            text = await navigator.clipboard.readText();
        }
    } catch (e) {
        try { text = safeCall(window.AndroidAPI && AndroidAPI.getClipboard) || ''; } catch(e2) {}
    }
    if (text && text !== lastClipboardText) {
        lastClipboardText = text;
        broadcastTrinityPair(text);
    }
}, 400);

// =========================================================
// Click-outside to close panels
// =========================================================
document.addEventListener('click', (e) => {
    if (folderPanel && !folderPanel.contains(e.target) && !e.target.classList.contains('folder-btn')) closeFolderPanel();
    if (downloadsPanel && !downloadsPanel.contains(e.target) && e.target.id !== 'btn-downloads') downloadsPanel.classList.remove('open');
});

// =========================================================
// Init
// =========================================================
renderBookmarkBar();

// --- Hooks called by DownloadManagerBridge via evaluateJavascript ---
window.onDownloadUpdate = function(data) {
    const idx = downloadsList.findIndex(d => d.id === data.id);
    if (idx === -1) downloadsList.unshift(data); else downloadsList[idx] = data;
    renderDownloads();
    updateDownloadBadge();
};
window.onDownloadsList = function(list) {
    if (Array.isArray(list)) { downloadsList = list; renderDownloads(); updateDownloadBadge(); }
};
console.log('[FastestBrowserToolbar] Extension features loaded');
