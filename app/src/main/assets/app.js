// =========================================================
// Import Firebase Modules
// =========================================================
import { initializeApp } from "./firebase-app.js";
import { getDatabase, ref, set, onValue, get } from "./firebase-database.js";

// Initialize Firebase using the provided configuration
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
const tabsDbRef = ref(db, 'browser_data/tabs');
const passwordsDbRef = ref(db, 'browser_data/passwords');

// =========================================================
// Core Variables
// =========================================================
const MIN_PERCENT = 10, MAX_PERCENT = 90;
const rootEl = document.getElementById('split-root');
const tabPoolEl = document.getElementById('tab-pool');
let nodeIdCounter = 0;
const START_URL_FALLBACK = 'https://example.com';

let tabs = [];
let activeTabId = null;

// Bookmarks are handled by Firebase Realtime DB
let bookmarks = [];

function activeTab() { return tabs.find((t) => t.id === activeTabId) || null; }

// Data structure creation
function createLeafNode(url, tab) { return { type: 'leaf', id: 'n'+(++nodeIdCounter), url, tab, parent: null, el: null, iframe: null, zoom: 1, history: [url || START_URL_FALLBACK], historyIdx: 0 }; }
function createSplitNode(direction, first, second) {
  const node = { type: 'split', id: 's'+(++nodeIdCounter), direction, ratio: 50, first, second, parent: null };
  first.parent = node; second.parent = node; return node;
}

// --- Rendering engine ---
function renderNode(node) { return node.type === 'leaf' ? renderLeaf(node) : renderSplit(node); }

function renderLeaf(node) {
    const container = document.createElement('div');
    container.className = 'split-child';
    container.style.flex = "1";
    
    const iframe = document.createElement('iframe');
    iframe.src = node.url || START_URL_FALLBACK;
    iframe.sandbox = "allow-scripts allow-same-origin allow-forms allow-popups allow-modals allow-downloads";
    iframe.allow = "clipboard-read; clipboard-write";

    iframe.addEventListener('load', () => {
      try {
        if (iframe.contentWindow && iframe.contentWindow.location.href !== 'about:blank') {
          const newUrl = iframe.contentWindow.location.href;
          if (node.url !== newUrl) {
            node.url = newUrl;
            if (node.history[node.historyIdx] !== newUrl) {
              node.history = node.history.slice(0, node.historyIdx + 1);
              node.history.push(newUrl);
              node.historyIdx = node.history.length - 1;
            }
          }
          if (node.tab && node.tab.activeLeafNode === node) {
            document.getElementById('address-input').value = node.url;
            updateNavButtons();
          }
          scheduleTabsSave();
        }
      } catch (e) {
        // Cross-origin restricted frame fallback
      }
      
      // Auto-check password manager for domain credentials
      checkAndAutofillPassword(node);
    });
    
    const focusCatcher = document.createElement('div');
    focusCatcher.className = "focus-catcher";
    focusCatcher.addEventListener('mousedown', () => { setActiveLeaf(node); });
  
    container.appendChild(iframe);
    container.appendChild(focusCatcher);
  
    node.el = container; 
    node.iframe = iframe;
    return container;
}

function setActiveLeaf(node) {
  if (!node || node.type !== 'leaf') return;
  const tab = node.tab;
  if (tab.activeLeafNode && tab.activeLeafNode !== node && tab.activeLeafNode.el) {
    tab.activeLeafNode.el.classList.remove('pane-active');
  }
  tab.activeLeafNode = node;
  node.el.classList.add('pane-active');
  
  document.getElementById('address-input').value = node.url;
  updateSplitControlsState();
  updateNavButtons();
}

function updateNavButtons() {
  const backBtn = document.getElementById('btn-back');
  const forwardBtn = document.getElementById('btn-forward');
  const tab = activeTab();
  const node = tab && tab.activeLeafNode;
  
  let canBack = false, canForward = false;
  if (node) {
    canBack = node.historyIdx > 0;
    canForward = node.historyIdx < node.history.length - 1;
  }
  backBtn.disabled = !canBack;
  forwardBtn.disabled = !canForward;
}

function renderSplit(node) {
  const container = document.createElement('div'); container.className = 'split-container ' + node.direction;
  const firstWrap = document.createElement('div'); firstWrap.style.flex = node.ratio + ' 1 0'; firstWrap.style.display = 'flex'; firstWrap.style.position = 'relative'; firstWrap.appendChild(renderNode(node.first));
  
  const resizer = document.createElement('div'); resizer.className = 'split-resizer ' + (node.direction === 'row' ? 'vertical' : 'horizontal');
  attachResizerEvents(resizer, node);
  
  const secondWrap = document.createElement('div'); secondWrap.style.flex = (100 - node.ratio) + ' 1 0'; secondWrap.style.display = 'flex'; secondWrap.style.position = 'relative'; secondWrap.appendChild(renderNode(node.second));
  
  container.appendChild(firstWrap); container.appendChild(resizer); container.appendChild(secondWrap);
  node.el = container; node.firstWrap = firstWrap; node.secondWrap = secondWrap; node.resizerEl = resizer;
  return container;
}

function attachResizerEvents(resizerEl, splitNode) {
  let dragging = false;

  function startDrag(e) {
    dragging = true; 
    resizerEl.classList.add('active');
    document.body.classList.add('dragging'); 
    document.body.style.cursor = splitNode.direction === 'row' ? 'col-resize' : 'row-resize'; 
    document.querySelectorAll('iframe').forEach(f => f.style.pointerEvents = 'none');
    
    if (e.cancelable) e.preventDefault();
  }

  function doDrag(e) {
    if (!dragging) return;
    
    let clientX = e.type.includes('touch') ? e.touches[0].clientX : e.clientX;
    let clientY = e.type.includes('touch') ? e.touches[0].clientY : e.clientY;

    const rect = splitNode.el.getBoundingClientRect();
    let percent = splitNode.direction === 'row' 
        ? ((clientX - rect.left) / rect.width) * 100 
        : ((clientY - rect.top) / rect.height) * 100;
        
    percent = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    splitNode.ratio = percent;
    splitNode.firstWrap.style.flex = percent + ' 1 0'; 
    splitNode.secondWrap.style.flex = (100 - percent) + ' 1 0';
  }

  function stopDrag() {
    if (!dragging) return;
    dragging = false; 
    resizerEl.classList.remove('active');
    document.body.classList.remove('dragging');
    document.body.style.cursor = ''; 
    document.querySelectorAll('iframe').forEach(f => f.style.pointerEvents = '');
    scheduleTabsSave();
  }

  // Desktop Mouse Events
  resizerEl.addEventListener('mousedown', startDrag);
  window.addEventListener('mousemove', doDrag);
  window.addEventListener('mouseup', stopDrag);
  window.addEventListener('mouseleave', stopDrag);

  // Mobile Touch Events
  resizerEl.addEventListener('touchstart', startDrag, { passive: false });
  window.addEventListener('touchmove', doDrag, { passive: false });
  window.addEventListener('touchend', stopDrag);
  window.addEventListener('touchcancel', stopDrag);
}

function focusFirstLeaf(node) {
  if (!node) return;
  if (node.type === 'leaf') { setActiveLeaf(node); } else { focusFirstLeaf(node.first); }
}

function closePane(leafNode) {
  const parent = leafNode.parent; if (!parent) return;
  const sibling = (parent.first === leafNode) ? parent.second : parent.first;
  const grandparent = parent.parent; const siblingEl = sibling.el;
  
  if (leafNode.iframe) leafNode.iframe.remove();
  parent.el.remove();

  if (!grandparent) {
    leafNode.tab.rootNode = sibling; sibling.parent = null;
    leafNode.tab.containerEl.innerHTML = ''; leafNode.tab.containerEl.appendChild(siblingEl);
  } else {
    const slot = (grandparent.first === parent) ? grandparent.firstWrap : grandparent.secondWrap;
    if (grandparent.first === parent) grandparent.first = sibling; else grandparent.second = sibling;
    sibling.parent = grandparent; slot.innerHTML = ''; slot.appendChild(siblingEl);
  }
  updateSplitControlsState(); setTimeout(() => focusFirstLeaf(sibling), 0); scheduleTabsSave();
}

// --- Persistence ---
let tabsSaveTimer = null;
let isAppBooting = true;

function scheduleTabsSave() { clearTimeout(tabsSaveTimer); tabsSaveTimer = setTimeout(saveTabsToDisk, 400); }
setInterval(saveTabsToDisk, 10000);

function serializeTree(node) {
  if (!node) return null;
  if (node.type === 'leaf') return { type: 'leaf', url: node.url || START_URL_FALLBACK };
  return { type: 'split', direction: node.direction, ratio: node.ratio, first: serializeTree(node.first), second: serializeTree(node.second) };
}

function saveTabsToDisk() {
    if (isAppBooting) return;
    try { 
        const state = { tabs: tabs.map((t) => ({ id: t.id, name: t.name, tree: t.mounted ? serializeTree(t.rootNode) : t.savedTree })), activeTabId };
        localStorage.setItem('fastest-browser-tabs', JSON.stringify(state)); 
        set(tabsDbRef, state);
    } catch (e) {}
}

// --- FIREBASE SYNC FOR BOOKMARKS ---
function saveBookmarksToDatabase() {
    set(bookmarksDbRef, bookmarks);
}

onValue(bookmarksDbRef, (snapshot) => {
    const data = snapshot.val();
    bookmarks = data ? data : [];
    refreshFolderIndicators();
    if (folderPanel.classList.contains('open')) {
        renderFolderList();
    }
});

// --- PASSWORD MANAGER LOGIC ---
function checkAndAutofillPassword(node) {
  if (!node || !node.url) return;
  try {
    const currentUrl = new URL(node.url);
    const safeDomain = currentUrl.hostname.replace(/[\.\#\$\[\]]/g, '_');
    get(ref(db, `browser_data/passwords/${safeDomain}`)).then((snapshot) => {
      if (snapshot.exists()) {
        const creds = snapshot.val();
        creds.password = atob(creds.password);
        if (node.iframe && node.iframe.contentWindow) {
          node.iframe.contentWindow.postMessage({ type: 'autofill-credentials', credentials: creds }, '*');
        }
      }
    });
  } catch (err) {}
}

function buildTree(data, tab) {
  if (!data || data.type === 'leaf') return createLeafNode((data && data.url) || START_URL_FALLBACK, tab);
  const node = createSplitNode(data.direction === 'column' ? 'column' : 'row', buildTree(data.first, tab), buildTree(data.second, tab));
  node.ratio = typeof data.ratio === 'number' ? data.ratio : 50; return node;
}

function splitPaneNode(leafNode, direction) {
  const tab = leafNode.tab, parent = leafNode.parent, existingEl = leafNode.el;
  const newLeaf = createLeafNode(START_URL_FALLBACK, tab); const newLeafEl = renderLeaf(newLeaf);
  const splitNode = createSplitNode(direction, leafNode, newLeaf);
  const container = document.createElement('div'); container.className = 'split-container ' + direction;

  const firstWrap = document.createElement('div'); firstWrap.style.flex = '50 1 0'; firstWrap.style.display = 'flex'; firstWrap.style.position = 'relative'; firstWrap.appendChild(existingEl);
  const resizer = document.createElement('div'); resizer.className = 'split-resizer ' + (direction === 'row' ? 'vertical' : 'horizontal'); attachResizerEvents(resizer, splitNode);
  const secondWrap = document.createElement('div'); secondWrap.style.flex = '50 1 0'; secondWrap.style.display = 'flex'; secondWrap.style.position = 'relative'; secondWrap.appendChild(newLeafEl);

  container.appendChild(firstWrap); container.appendChild(resizer); container.appendChild(secondWrap);
  splitNode.el = container; splitNode.firstWrap = firstWrap; splitNode.secondWrap = secondWrap; splitNode.resizerEl = resizer;

  if (!parent) { tab.rootNode = splitNode; tab.containerEl.innerHTML = ''; tab.containerEl.appendChild(container); }
  else {
    const slot = (parent.first === leafNode) ? parent.firstWrap : parent.secondWrap;
    if (parent.first === leafNode) parent.first = splitNode; else parent.second = splitNode;
    splitNode.parent = parent; slot.innerHTML = ''; slot.appendChild(container);
  }
  updateSplitControlsState(); setTimeout(() => setActiveLeaf(newLeaf), 0); scheduleTabsSave();
}

function updateSplitControlsState() {
  const tab = activeTab(); document.getElementById('btn-close-pane').disabled = !tab || !tab.rootNode || tab.rootNode.type !== 'split';
}

// --- Core Session Management ---
function makeTab(opts) { return { id: opts.id, name: opts.name, savedTree: opts.tree, rootNode: null, containerEl: null, mounted: false, activeLeafNode: null }; }

function mountTab(tab) {
  if (!tab.mounted) {
    tab.rootNode = buildTree(tab.savedTree || { type: 'leaf', url: START_URL_FALLBACK }, tab);
    tab.containerEl = document.createElement('div'); tab.containerEl.className = 'tab-pane-root'; tab.containerEl.appendChild(renderNode(tab.rootNode)); tab.mounted = true;
  } else if (tab.containerEl.parentNode === tabPoolEl) tabPoolEl.removeChild(tab.containerEl);
  rootEl.innerHTML = ''; rootEl.appendChild(tab.containerEl);
}

function parkTab(tab) { if (tab && tab.containerEl && tab.containerEl.parentNode) { tab.containerEl.parentNode.removeChild(tab.containerEl); tabPoolEl.appendChild(tab.containerEl); } }

function switchTab(id) {
  const tab = tabs.find((t) => t.id === id); if (!tab) return;
  const prev = activeTab(); if (prev && prev.id !== id) parkTab(prev);
  activeTabId = id; renderAccountSwitcher(); mountTab(tab); updateSplitControlsState(); setTimeout(() => focusFirstLeaf(tab.rootNode), 0); scheduleTabsSave();
}

function addTab(name) {
  const id = 'tab_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6);
  const tab = makeTab({ id, name, tree: { type: 'leaf', url: START_URL_FALLBACK } });
  tabs.push(tab); switchTab(id); saveTabsToDisk();
}

function closeTabById(id) {
  const idx = tabs.findIndex((t) => t.id === id); if (idx === -1) return;
  const tab = tabs[idx];
  if (tab.containerEl && tab.containerEl.parentNode) tab.containerEl.parentNode.removeChild(tab.containerEl);
  tabs.splice(idx, 1);
  if (tab.id === activeTabId) { activeTabId = null; if (tabs.length > 0) switchTab(tabs[0].id); else showEmptyState(); }
  renderAccountSwitcher(); saveTabsToDisk();
}

function showEmptyState() {
  rootEl.innerHTML = '<div class="empty-state">No accounts open.<br>Use the + button to add one.</div>';
  document.getElementById('address-input').value = "";
  updateSplitControlsState();
}

// --- Account Switcher Dropdown ---
const accountDropdownBtn = document.getElementById('account-dropdown-btn');
const accountDropdownLabel = document.getElementById('account-dropdown-label');
const accountDropdownMenu = document.getElementById('account-dropdown-menu');

accountDropdownBtn.addEventListener('click', (e) => { e.stopPropagation(); accountDropdownMenu.classList.toggle('open'); accountDropdownBtn.classList.toggle('open'); });

function renderAccountSwitcher() {
  const active = activeTab(); accountDropdownLabel.textContent = active ? active.name : 'No accounts';
  accountDropdownMenu.innerHTML = '';
  if (tabs.length === 0) { accountDropdownMenu.innerHTML = '<div class="account-menu-empty">No accounts yet</div>'; return; }
  
  tabs.forEach((tab) => {
    const item = document.createElement('div'); item.className = 'account-item';
    item.addEventListener('click', () => { switchTab(tab.id); accountDropdownMenu.classList.remove('open'); accountDropdownBtn.classList.remove('open'); });
    const name = document.createElement('span'); name.className = 'account-item-name'; name.textContent = tab.name;
    const close = document.createElement('button'); close.className = 'account-item-close'; close.textContent = '✕';
    close.addEventListener('click', (e) => { e.stopPropagation(); closeTabById(tab.id); });
    item.appendChild(name); item.appendChild(close); accountDropdownMenu.appendChild(item);
  });
}

// --- Add Tab Input UI ---
const tabAddBtn = document.getElementById('tab-add-btn');
const tabNameInputContainer = document.getElementById('tab-name-input-container');
const tabNameInput = document.getElementById('tab-name-input');

tabAddBtn.addEventListener('click', () => { tabAddBtn.style.display = 'none'; tabNameInputContainer.style.display = 'flex'; tabNameInput.value = ''; tabNameInput.focus(); });
document.getElementById('tab-name-cancel').addEventListener('click', () => { tabNameInputContainer.style.display = 'none'; tabAddBtn.style.display = 'flex'; });
document.getElementById('tab-name-confirm').addEventListener('click', () => {
  const val = tabNameInput.value.trim(); if(val) addTab(val);
  tabNameInputContainer.style.display = 'none'; tabAddBtn.style.display = 'flex';
});
tabNameInput.addEventListener('keydown', (e) => {
  if(e.key === 'Enter') document.getElementById('tab-name-confirm').click();
  if(e.key === 'Escape') document.getElementById('tab-name-cancel').click();
});

// --- Initialization ---
function initApp() {
  get(tabsDbRef).then((snapshot) => {
    const remoteData = snapshot.val();
    if (remoteData && remoteData.tabs && remoteData.tabs.length > 0) {
      localStorage.setItem('fastest-browser-tabs', JSON.stringify(remoteData));
    }
  }).catch(() => {}).finally(() => {
    let rawState = localStorage.getItem('fastest-browser-tabs');
    let tabsState = rawState ? JSON.parse(rawState) : { tabs: [], activeTabId: null };
    
    tabs = (tabsState.tabs || []).map((t) => makeTab(t)); activeTabId = null;
    isAppBooting = false;
    
    if (tabs.length === 0) { 
        addTab('Main Session');
    } else {
        switchTab(tabs[0].id);
    }
  });
}

// --- Navigation & Toolbar ---
const addressInput = document.getElementById('address-input');

function navigateActivePane(url) {
    const tab = activeTab(); if (!tab || !tab.activeLeafNode) return;
    let target = url.trim();
    
    const isSearch = target.includes(' ') || (!target.includes('.') && !target.startsWith('localhost') && !target.includes('://'));
    
    if (isSearch) {
        target = 'https://www.google.com/search?q=' + encodeURIComponent(target);
    } else if (!/^https?:\/\//i.test(target)) {
        target = 'https://' + target;
    }
    
    const node = tab.activeLeafNode;
    node.url = target;
    node.iframe.src = target;
    
    node.history = node.history.slice(0, node.historyIdx + 1);
    node.history.push(target);
    node.historyIdx = node.history.length - 1;

    addressInput.value = target;
    updateNavButtons();
    scheduleTabsSave();
}

const ZOOM_MIN = 0.5, ZOOM_MAX = 2, ZOOM_STEP = 0.1;

function applyZoom(node) {
    if (!node || !node.iframe) return;
    const z = node.zoom || 1;
    const inv = 100 / z;
    node.iframe.style.width = inv + '%';
    node.iframe.style.height = inv + '%';
    node.iframe.style.left = ((100 - inv) / 2) + '%';
    node.iframe.style.top = ((100 - inv) / 2) + '%';
    node.iframe.style.transformOrigin = 'center center';
    node.iframe.style.transform = 'scale(' + z + ')';
}

function changeZoom(delta) {
    const tab = activeTab(); if (!tab || !tab.activeLeafNode) return;
    const node = tab.activeLeafNode;
    let z = (node.zoom || 1) + delta;
    z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
    node.zoom = Math.round(z * 100) / 100;
    applyZoom(node);
    scheduleTabsSave();
}

document.getElementById('btn-fullscreen').addEventListener('click', () => {
    if (!document.fullscreenElement) { document.documentElement.requestFullscreen(); document.getElementById('exit-fullscreen-btn').classList.add('visible'); }
    else if (document.exitFullscreen) { document.exitFullscreen(); document.getElementById('exit-fullscreen-btn').classList.remove('visible'); }
});
document.getElementById('btn-zoom-in').addEventListener('click', () => changeZoom(ZOOM_STEP));
document.getElementById('btn-zoom-out').addEventListener('click', () => changeZoom(-ZOOM_STEP));
document.getElementById('exit-fullscreen-btn').addEventListener('click', () => { document.exitFullscreen(); document.getElementById('exit-fullscreen-btn').classList.remove('visible');});

document.getElementById('btn-split-row').addEventListener('click', () => { const t = activeTab(); if(t && t.activeLeafNode) splitPaneNode(t.activeLeafNode, 'row'); });
document.getElementById('btn-split-col').addEventListener('click', () => { const t = activeTab(); if(t && t.activeLeafNode) splitPaneNode(t.activeLeafNode, 'column'); });
document.getElementById('btn-close-pane').addEventListener('click', () => { const t = activeTab(); if(t && t.activeLeafNode) closePane(t.activeLeafNode); });
document.getElementById('btn-go').addEventListener('click', () => { if (addressInput.value.trim()) navigateActivePane(addressInput.value); });

document.getElementById('btn-back').addEventListener('click', () => {
  const tab = activeTab(); if (!tab || !tab.activeLeafNode) return;
  const node = tab.activeLeafNode;
  if (node.historyIdx > 0) {
    node.historyIdx--;
    node.url = node.history[node.historyIdx];
    node.iframe.src = node.url;
    addressInput.value = node.url;
    updateNavButtons();
  }
});

document.getElementById('btn-forward').addEventListener('click', () => {
  const tab = activeTab(); if (!tab || !tab.activeLeafNode) return;
  const node = tab.activeLeafNode;
  if (node.historyIdx < node.history.length - 1) {
    node.historyIdx++;
    node.url = node.history[node.historyIdx];
    node.iframe.src = node.url;
    addressInput.value = node.url;
    updateNavButtons();
  }
});

addressInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') document.getElementById('btn-go').click(); });
addressInput.addEventListener('focus', () => addressInput.select());

// --- Downloads UI ---
const dlPanel = document.getElementById('downloads-panel');
const dlListEl = document.getElementById('dl-list');
const dlBadgeEl = document.getElementById('dl-badge');
const dlClearBtn = document.getElementById('dl-clear-btn');
let downloadsList = [];

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

    // EDITED DOWNLOAD BUTTON LOGIC
    const actions = document.createElement('div'); actions.className = 'dl-item-actions';
    if (d.state === 'completed') {
      const openBtn = document.createElement('button'); openBtn.className = 'dl-action-btn'; openBtn.textContent = 'Open';
      openBtn.onclick = () => {
          if (window.AndroidAPI && window.AndroidAPI.openDownloadById) window.AndroidAPI.openDownloadById(d.id);
          else if (typeof chrome !== 'undefined' && chrome.runtime) chrome.runtime.sendMessage({ type: 'OPEN_DOWNLOAD', id: d.id });
      };
      const showBtn = document.createElement('button'); showBtn.className = 'dl-action-btn'; showBtn.textContent = 'Show';
      showBtn.onclick = () => {
          if (window.AndroidAPI && window.AndroidAPI.showDownloadById) window.AndroidAPI.showDownloadById(d.id);
          else if (typeof chrome !== 'undefined' && chrome.runtime) chrome.runtime.sendMessage({ type: 'SHOW_DOWNLOAD', id: d.id });
      };
      actions.appendChild(openBtn); actions.appendChild(showBtn);
    } else if (isDownloadActive(d)) {
      const cancelBtn = document.createElement('button'); cancelBtn.className = 'dl-action-btn'; cancelBtn.textContent = 'Cancel';
      cancelBtn.onclick = () => {
          if (window.AndroidAPI && window.AndroidAPI.cancelDownload) window.AndroidAPI.cancelDownload(d.id);
          else if (typeof chrome !== 'undefined' && chrome.runtime) chrome.runtime.sendMessage({ type: 'CANCEL_DOWNLOAD', id: d.id });
      };
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

// Android WebAppInterface Hook for downloads
window.updateDownloadsList = function(data) {
    if (Array.isArray(data)) {
        downloadsList = data;
        renderDownloads();
        updateDownloadBadge();
    }
};

if (typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.onMessage) {
  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.type === 'DOWNLOAD_UPDATE' && msg.data) {
      const data = msg.data;
      const idx = downloadsList.findIndex((d) => d.id === data.id);
      if (idx === -1) downloadsList.unshift(data); else downloadsList[idx] = data;
      renderDownloads();
      updateDownloadBadge();
    }
  });

  chrome.runtime.sendMessage({ type: 'GET_ACTIVE_DOWNLOADS' }, (res) => {
    if (Array.isArray(res)) {
      downloadsList = res;
      renderDownloads();
      updateDownloadBadge();
    }
  });
}

document.getElementById('btn-downloads').addEventListener('click', (e) => {
  e.stopPropagation();
  dlPanel.classList.toggle('open');
});

dlClearBtn.addEventListener('click', () => {
  downloadsList = downloadsList.filter(isDownloadActive);
  renderDownloads();
});

renderDownloads();

// --- Bookmarks UI ---
const FOLDER_KEYS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
const bookmarkBar = document.getElementById('bookmark-bar');
const folderPanel = document.getElementById('folder-panel');
const fpTitle = document.getElementById('fp-title'), fpList = document.getElementById('fp-list');
let activeFolder = null;

FOLDER_KEYS.forEach(k => {
  const b = document.createElement('button'); b.className = 'folder-btn'; b.dataset.folder = k; b.textContent = k;
  b.onclick = (e) => { e.stopPropagation(); activeFolder === k ? closeFolderPanel() : openFolderPanel(k); }; bookmarkBar.appendChild(b);
});
const fbBtn = (k) => bookmarkBar.querySelector(`.folder-btn[data-folder="${k}"]`);

function openFolderPanel(key) {
  if(activeFolder) fbBtn(activeFolder).classList.remove('active');
  activeFolder = key; fbBtn(key).classList.add('active');
  fpTitle.textContent = key;
  renderFolderList(); folderPanel.classList.add('open'); positionFolderPanel(fbBtn(key));
}
function closeFolderPanel() { if(activeFolder) fbBtn(activeFolder).classList.remove('active'); activeFolder = null; folderPanel.classList.remove('open'); }
document.getElementById('fp-close-btn').onclick = closeFolderPanel;

function positionFolderPanel(btn) {
  if(!btn) return;
  const shellRect = document.getElementById('app-shell').getBoundingClientRect(), btnRect = btn.getBoundingClientRect(), barRect = document.getElementById('bottom-bar').getBoundingClientRect();
  const btnCenter = (btnRect.left - shellRect.left) + (btnRect.width / 2);
  const left = Math.max(8, Math.min(btnCenter - 20, shellRect.width - 220 - 8));
  folderPanel.style.left = left + 'px'; folderPanel.style.bottom = (shellRect.bottom - barRect.top + 8) + 'px';
}

function renderFolderList() {
  fpList.innerHTML = '';
  const entries = bookmarks.map((bm, idx) => ({bm, idx})).filter(e => e.bm.folder === activeFolder);
  if (!entries.length) { fpList.innerHTML = `<div class="fp-empty">No bookmarks in ${activeFolder}</div>`; return; }
  entries.forEach(e => {
    const item = document.createElement('div'); item.className = 'fp-item';
    const lbl = document.createElement('div'); lbl.className = 'fp-item-label'; lbl.textContent = e.bm.label || e.bm.url; item.appendChild(lbl);
    item.onclick = () => { navigateActivePane(e.bm.url); closeFolderPanel(); };
    
    const del = document.createElement('button'); del.className = 'fp-del'; del.textContent = '✕'; 
    del.onclick = (ev) => { 
        ev.stopPropagation(); 
        bookmarks.splice(e.idx, 1); 
        saveBookmarksToDatabase();
    };
    
    item.appendChild(del); fpList.appendChild(item);
  });
}

function refreshFolderIndicators() {
  const seen = {}; bookmarks.forEach(bm => { if(bm.folder) seen[bm.folder] = true; });
  FOLDER_KEYS.forEach(k => fbBtn(k).classList.toggle('has-bookmarks', !!seen[k]));
}

// Quick Bookmark Modal
const quickBmOverlay = document.getElementById('quick-bm-overlay'), qbmNameInput = document.getElementById('qbm-name-input');
let qbmSelectedFolder = '0';
function deriveName(url) { try { return new URL(url).hostname.replace(/^www\./i,'').split('.')[0].toUpperCase(); } catch { return url; } }
function deriveFolder(name) { const c = (name||'').trim().charAt(0).toUpperCase(); return FOLDER_KEYS.includes(c) ? c : '0'; }
function setQbmFolder(k) { qbmSelectedFolder = k; document.getElementById('qbm-folder-value').textContent = k; }

document.getElementById('btn-add-bookmark').onclick = () => {
  const tab = activeTab();
  if(!tab || !tab.activeLeafNode || !tab.activeLeafNode.url) return;
  const currentUrl = tab.activeLeafNode.url;
  document.getElementById('qbm-url-preview').textContent = currentUrl; qbmNameInput.value = deriveName(currentUrl);
  setQbmFolder(deriveFolder(qbmNameInput.value)); quickBmOverlay.classList.add('open'); qbmNameInput.focus(); qbmNameInput.select();
};
const closeQBM = () => quickBmOverlay.classList.remove('open');
document.getElementById('qbm-close-btn').onclick = closeQBM; document.getElementById('qbm-cancel-btn').onclick = closeQBM;
qbmNameInput.addEventListener('input', () => setQbmFolder(deriveFolder(qbmNameInput.value)));

document.getElementById('qbm-save-btn').onclick = () => { 
    const tab = activeTab();
    if(tab && tab.activeLeafNode) {
        bookmarks.push({ label: qbmNameInput.value.trim() || tab.activeLeafNode.url, url: tab.activeLeafNode.url, folder: qbmSelectedFolder });
        saveBookmarksToDatabase();
    }
    closeQBM(); 
};

// --- Click outside modals ---
document.addEventListener('click', (e) => {
    const accountDropdownMenu = document.getElementById('account-dropdown-menu');
    const downloadsPanel = document.getElementById('downloads-panel');
    const folderPanel = document.getElementById('folder-panel');
    
    if (accountDropdownMenu && !accountDropdownMenu.contains(e.target) && e.target.id !== 'account-dropdown-btn') accountDropdownMenu.classList.remove('open');
    if (folderPanel && !folderPanel.contains(e.target) && !e.target.classList.contains('folder-btn')) closeFolderPanel();
    if (downloadsPanel && !downloadsPanel.contains(e.target) && e.target.id !== 'btn-downloads') downloadsPanel.classList.remove('open');
});

// =========================================================
// TRINITY SYNC CLIPBOARD MONITOR & MESSAGE ROUTER
// =========================================================
let lastClipboardText = '';

function broadcastTrinityPair(pair) {
    if (!pair) return;
    const cleanPair = pair.trim().toUpperCase();
    const allPanes = document.querySelectorAll('iframe');
    allPanes.forEach(iframe => {
        try {
            iframe.contentWindow.postMessage({ trinityOS_pair: cleanPair }, '*');
        } catch (err) {}
    });
}

// Top-level system clipboard monitor for standard Extension host
setInterval(async () => {
    try {
        if (document.hasFocus()) {
            const text = await navigator.clipboard.readText();
            if (text && text !== lastClipboardText) {
                lastClipboardText = text;
                broadcastTrinityPair(text);
            }
        }
    } catch (e) {}
}, 400);

// Listen to inner frame postMessages
window.addEventListener('message', (event) => {
    if (event.data && event.data.type === 'save-credential') {
        const { domain, username, password } = event.data.credentials || {};
        if (domain && username && password) {
            const safeDomain = domain.replace(/[\.\#\$\[\]]/g, '_');
            if (confirm(`Save password for ${username} at ${domain}?`)) {
                set(ref(db, `browser_data/passwords/${safeDomain}`), {
                    domain: domain,
                    username: username,
                    password: btoa(password),
                    updatedAt: Date.now()
                });
            }
        }
    }

    if (event.data && (event.data.trinityOS_pair || event.data.pair)) {
        const pair = event.data.trinityOS_pair || event.data.pair;
        broadcastTrinityPair(pair);
    }
});

// Instant state persistence on close/refresh
window.addEventListener('beforeunload', () => {
    saveTabsToDisk();
});

// Start app
initApp();
