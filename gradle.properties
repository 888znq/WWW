const MIN_PERCENT = 10;
const MAX_PERCENT = 90;
const ZOOM_MIN = 50;
const ZOOM_MAX = 200;
const ZOOM_STEP = 10;
const rootEl = document.getElementById('split-root');

let nodeIdCounter = 0;
let rootNode = null;
let activeLeafNode = null;
let isFullscreen = false;

// API bridge to Java WebAppInterface. Only fullscreen actually needs native
// code (hiding the Android system bars); navigate/zoom/split/close are all
// handled locally below since the panes are just <iframe>s in this page.
const api = window.AndroidAPI || {
    toggleFullscreen: () => console.log('Fullscreen')
};

function createLeafNode(url) {
  return { type: 'leaf', id: 'n' + (++nodeIdCounter), url, zoom: 100, parent: null, el: null, webview: null };
}

function createSplitNode(direction, first, second) {
  const node = { type: 'split', id: 's' + (++nodeIdCounter), direction, ratio: 50, first, second, parent: null, el: null, firstWrap: null, secondWrap: null, resizerEl: null };
  first.parent = node; second.parent = node;
  return node;
}

function renderNode(node) {
  return node.type === 'leaf' ? renderLeaf(node) : renderSplit(node);
}

function renderLeaf(node) {
  const iframe = document.createElement('iframe');
  iframe.setAttribute('src', node.url);
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-popups allow-forms');
  iframe.style.zoom = (node.zoom || 100) / 100;
  iframe.addEventListener('focus', () => setActiveLeaf(node));
  node.el = iframe;
  node.webview = iframe;
  return iframe;
}

function setActiveLeaf(node) {
  if (!node || node.type !== 'leaf' || !node.webview) return;
  if (activeLeafNode && activeLeafNode !== node && activeLeafNode.webview) {
    activeLeafNode.webview.classList.remove('pane-active');
  }
  activeLeafNode = node;
  node.webview.classList.add('pane-active');
  addressInput.value = node.url || '';
}

function firstLeafOf(node) {
  return node.type === 'leaf' ? node : firstLeafOf(node.first);
}

function attachResizerDrag(resizerEl, node) {
  let dragging = false;

  const move = (clientX, clientY) => {
    const rect = node.el.getBoundingClientRect();
    let percent = node.direction === 'row'
      ? ((clientX - rect.left) / rect.width) * 100
      : ((clientY - rect.top) / rect.height) * 100;
    percent = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    node.ratio = percent;
    node.firstWrap.style.flex = percent + ' 1 0';
    node.secondWrap.style.flex = (100 - percent) + ' 1 0';
  };

  resizerEl.addEventListener('mousedown', (e) => { dragging = true; resizerEl.classList.add('active'); e.preventDefault(); });
  window.addEventListener('mousemove', (e) => { if (dragging) move(e.clientX, e.clientY); });
  window.addEventListener('mouseup', () => { dragging = false; resizerEl.classList.remove('active'); });

  // Touch support, since this is what actually gets used on Android.
  resizerEl.addEventListener('touchstart', () => { dragging = true; resizerEl.classList.add('active'); }, { passive: true });
  window.addEventListener('touchmove', (e) => {
    if (!dragging || !e.touches.length) return;
    move(e.touches[0].clientX, e.touches[0].clientY);
  }, { passive: true });
  window.addEventListener('touchend', () => { dragging = false; resizerEl.classList.remove('active'); });
}

function renderSplit(node) {
  const container = document.createElement('div');
  container.className = 'split-container ' + node.direction;

  const firstWrap = document.createElement('div');
  firstWrap.className = 'split-child';
  firstWrap.style.flex = node.ratio + ' 1 0';
  firstWrap.appendChild(renderNode(node.first));

  const resizer = document.createElement('div');
  resizer.className = 'split-resizer ' + (node.direction === 'row' ? 'vertical' : 'horizontal');

  const secondWrap = document.createElement('div');
  secondWrap.className = 'split-child';
  secondWrap.style.flex = (100 - node.ratio) + ' 1 0';
  secondWrap.appendChild(renderNode(node.second));

  container.appendChild(firstWrap);
  container.appendChild(resizer);
  container.appendChild(secondWrap);
  node.el = container; node.firstWrap = firstWrap; node.secondWrap = secondWrap; node.resizerEl = resizer;
  attachResizerDrag(resizer, node);
  return container;
}

function updateCloseButtonState() {
  document.getElementById('btn-close-pane').disabled = !rootNode || rootNode.type === 'leaf';
}

function splitActivePane(direction) {
  if (!activeLeafNode) return;
  const leaf = activeLeafNode;
  const parent = leaf.parent;
  const existingEl = leaf.el;

  const newLeaf = createLeafNode('about:blank');
  const splitNode = createSplitNode(direction, leaf, newLeaf);

  const container = document.createElement('div');
  container.className = 'split-container ' + direction;

  const firstWrap = document.createElement('div');
  firstWrap.className = 'split-child';
  firstWrap.style.flex = '50 1 0';
  firstWrap.appendChild(existingEl);

  const resizer = document.createElement('div');
  resizer.className = 'split-resizer ' + (direction === 'row' ? 'vertical' : 'horizontal');

  const secondWrap = document.createElement('div');
  secondWrap.className = 'split-child';
  secondWrap.style.flex = '50 1 0';
  secondWrap.appendChild(renderNode(newLeaf));

  container.appendChild(firstWrap);
  container.appendChild(resizer);
  container.appendChild(secondWrap);

  splitNode.el = container;
  splitNode.firstWrap = firstWrap;
  splitNode.secondWrap = secondWrap;
  splitNode.resizerEl = resizer;
  attachResizerDrag(resizer, splitNode);

  if (!parent) {
    rootNode = splitNode;
    rootEl.innerHTML = '';
    rootEl.appendChild(container);
  } else {
    splitNode.parent = parent;
    if (parent.first === leaf) {
      parent.first = splitNode;
      parent.firstWrap.innerHTML = '';
      parent.firstWrap.appendChild(container);
    } else {
      parent.second = splitNode;
      parent.secondWrap.innerHTML = '';
      parent.secondWrap.appendChild(container);
    }
  }

  setActiveLeaf(newLeaf);
  updateCloseButtonState();
}

function closeActivePane() {
  const leaf = activeLeafNode;
  if (!leaf || !leaf.parent) return; // last remaining pane - nothing to close into

  const parent = leaf.parent;
  const sibling = (parent.first === leaf) ? parent.second : parent.first;
  const grandparent = parent.parent;
  const siblingEl = sibling.el;

  if (leaf.el && leaf.el.parentNode) leaf.el.parentNode.removeChild(leaf.el);
  if (parent.el && parent.el.parentNode) parent.el.parentNode.removeChild(parent.el);

  sibling.parent = grandparent;

  if (!grandparent) {
    rootNode = sibling;
    rootEl.innerHTML = '';
    rootEl.appendChild(siblingEl);
  } else {
    if (grandparent.first === parent) {
      grandparent.first = sibling;
      grandparent.firstWrap.innerHTML = '';
      grandparent.firstWrap.appendChild(siblingEl);
    } else {
      grandparent.second = sibling;
      grandparent.secondWrap.innerHTML = '';
      grandparent.secondWrap.appendChild(siblingEl);
    }
  }

  setActiveLeaf(firstLeafOf(sibling));
  updateCloseButtonState();
}

function applyZoom(node) {
  if (node && node.el) node.el.style.zoom = (node.zoom || 100) / 100;
}

function zoomActive(delta) {
  if (!activeLeafNode) return;
  activeLeafNode.zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, (activeLeafNode.zoom || 100) + delta));
  applyZoom(activeLeafNode);
}

function normalizeUrl(input) {
  const trimmed = input.trim();
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)) return trimmed;
  if (/^(localhost|(\d{1,3}\.){3}\d{1,3})(:\d+)?(\/.*)?$/.test(trimmed)) return 'http://' + trimmed;
  if (!trimmed.includes(' ') && trimmed.includes('.')) return 'https://' + trimmed;
  return 'https://www.google.com/search?q=' + encodeURIComponent(trimmed);
}

function navigateActive(rawUrl) {
  if (!activeLeafNode || !rawUrl.trim()) return;
  const url = normalizeUrl(rawUrl);
  activeLeafNode.url = url;
  if (activeLeafNode.el) activeLeafNode.el.setAttribute('src', url);
  addressInput.value = url;
}

// Event Listeners for Bottom Bar Controls
document.getElementById('btn-zoom-in').addEventListener('click', () => zoomActive(ZOOM_STEP));
document.getElementById('btn-zoom-out').addEventListener('click', () => zoomActive(-ZOOM_STEP));
document.getElementById('btn-fullscreen').addEventListener('click', () => {
  isFullscreen = !isFullscreen;
  const btn = document.getElementById('btn-fullscreen');
  btn.title = isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen';
  api.toggleFullscreen();
});
document.getElementById('btn-split-row').addEventListener('click', () => splitActivePane('row'));
document.getElementById('btn-split-col').addEventListener('click', () => splitActivePane('column'));
document.getElementById('btn-close-pane').addEventListener('click', closeActivePane);

const addressInput = document.getElementById('address-input');
addressInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    navigateActive(addressInput.value);
    addressInput.blur();
  }
});

// Initialization
function initRoot(url) {
  rootNode = createLeafNode(url);
  rootEl.appendChild(renderNode(rootNode));
  setActiveLeaf(rootNode);
  updateCloseButtonState();
}
initRoot("https://google.com");
