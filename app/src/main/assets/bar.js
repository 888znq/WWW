// bar.js - drives bar.html. Talks to native code through window.AndroidAPI
// (see WebAppInterface.java) and receives pushes from native code through
// the window.onXxx callbacks MainActivity calls via evaluateJavascript.
(function () {
  'use strict';

  var addressInput = document.getElementById('address-input');
  var bookmarksInline = document.getElementById('bookmarks-inline');
  var btnFullscreen = document.getElementById('btn-fullscreen');
  var btnDownloads = document.getElementById('btn-downloads');
  var downloadsBadge = document.getElementById('downloads-badge');
  var downloadsPanel = document.getElementById('downloads-panel');
  var dlList = document.getElementById('dl-list');
  var dlClearBtn = document.getElementById('dl-clear-btn');
  var btnSplitRow = document.getElementById('btn-split-row');
  var btnSplitCol = document.getElementById('btn-split-col');
  var btnClosePane = document.getElementById('btn-close-pane');

  var bmOverlay = document.getElementById('bm-modal-overlay');
  var bmTitle = document.getElementById('bm-modal-title');
  var bmUrlInput = document.getElementById('bm-modal-url');
  var bmLabelInput = document.getElementById('bm-modal-label');
  var bmCancelBtn = document.getElementById('bm-modal-cancel');
  var bmOkBtn = document.getElementById('bm-modal-ok');

  var bookmarks = [];
  var editingIndex = -1; // -1 = adding a new bookmark
  var currentUrl = '';
  var ctxMenuEl = null;

  // -----------------------------------------------------------------
  // Address bar
  // -----------------------------------------------------------------
  addressInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') {
      var value = addressInput.value.trim();
      if (value) window.AndroidAPI.navigate(value);
      addressInput.blur();
    }
  });

  addressInput.addEventListener('focus', function () {
    addressInput.select();
  });

  // -----------------------------------------------------------------
  // Fullscreen
  // -----------------------------------------------------------------
  btnFullscreen.addEventListener('click', function () { window.AndroidAPI.toggleFullscreen(); });

  window.onFullscreenChanged = function (isFullscreen) {
    btnFullscreen.title = isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen';
  };

  // -----------------------------------------------------------------
  // Split controls
  // -----------------------------------------------------------------
  btnSplitRow.addEventListener('click', function () { window.AndroidAPI.splitPane('row'); });
  btnSplitCol.addEventListener('click', function () { window.AndroidAPI.splitPane('col'); });
  btnClosePane.addEventListener('click', function () {
    if (btnClosePane.disabled) return;
    window.AndroidAPI.closePane();
  });

  // -----------------------------------------------------------------
  // Active pane state, pushed from native on navigation / split changes
  // -----------------------------------------------------------------
  window.onActivePaneState = function (state) {
    state = state || {};
    currentUrl = state.url || '';
    if (document.activeElement !== addressInput) {
      addressInput.value = currentUrl;
    }
    btnClosePane.disabled = !state.hasSplit;
  };

  // -----------------------------------------------------------------
  // Bookmarks
  // -----------------------------------------------------------------
  function renderBookmarks() {
    bookmarksInline.innerHTML = '';

    bookmarks.forEach(function (bm, index) {
      var chip = document.createElement('button');
      chip.className = 'bm-chip';
      chip.textContent = bm.label || bm.url || 'Untitled';
      chip.addEventListener('click', function () {
        window.AndroidAPI.navigateToBookmark(bm.url);
      });
      chip.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        showBookmarkContextMenu(e.clientX, e.clientY, index);
      });
      var pressTimer = null;
      chip.addEventListener('touchstart', function (e) {
        pressTimer = setTimeout(function () {
          var touch = e.touches[0];
          showBookmarkContextMenu(touch.clientX, touch.clientY, index);
        }, 500);
      });
      chip.addEventListener('touchend', function () { clearTimeout(pressTimer); });
      chip.addEventListener('touchmove', function () { clearTimeout(pressTimer); });
      bookmarksInline.appendChild(chip);
    });

    var addChip = document.createElement('button');
    addChip.className = 'bm-chip bm-add';
    addChip.textContent = '+ Add';
    addChip.addEventListener('click', function () { openBookmarkModal(-1); });
    bookmarksInline.appendChild(addChip);
  }

  function showBookmarkContextMenu(x, y, index) {
    closeContextMenu();
    ctxMenuEl = document.createElement('div');
    ctxMenuEl.className = 'bm-ctx-menu';

    var editBtn = document.createElement('button');
    editBtn.textContent = 'Edit';
    editBtn.addEventListener('click', function () {
      closeContextMenu();
      openBookmarkModal(index);
    });

    var deleteBtn = document.createElement('button');
    deleteBtn.textContent = 'Delete';
    deleteBtn.style.color = 'var(--danger)';
    deleteBtn.addEventListener('click', function () {
      closeContextMenu();
      window.AndroidAPI.deleteBookmark(index);
    });

    ctxMenuEl.appendChild(editBtn);
    ctxMenuEl.appendChild(deleteBtn);
    document.body.appendChild(ctxMenuEl);

    var rect = ctxMenuEl.getBoundingClientRect();
    var left = Math.min(x, window.innerWidth - rect.width - 8);
    var top = Math.max(8, y - rect.height - 8);
    ctxMenuEl.style.left = left + 'px';
    ctxMenuEl.style.top = top + 'px';

    setTimeout(function () {
      document.addEventListener('click', closeContextMenuOnce);
    }, 0);
  }

  function closeContextMenuOnce() {
    closeContextMenu();
    document.removeEventListener('click', closeContextMenuOnce);
  }

  function closeContextMenu() {
    if (ctxMenuEl && ctxMenuEl.parentNode) ctxMenuEl.parentNode.removeChild(ctxMenuEl);
    ctxMenuEl = null;
  }

  function openBookmarkModal(index) {
    editingIndex = index;
    if (index >= 0 && bookmarks[index]) {
      bmTitle.textContent = 'Edit bookmark';
      bmUrlInput.value = bookmarks[index].url || '';
      bmLabelInput.value = bookmarks[index].label || '';
    } else {
      bmTitle.textContent = 'Add bookmark';
      bmUrlInput.value = currentUrl || '';
      bmLabelInput.value = '';
    }
    bmOverlay.classList.add('open');
    setTimeout(function () { bmLabelInput.focus(); }, 0);
  }

  function closeBookmarkModal() {
    bmOverlay.classList.remove('open');
    editingIndex = -1;
  }

  bmCancelBtn.addEventListener('click', closeBookmarkModal);
  bmOverlay.addEventListener('click', function (e) {
    if (e.target === bmOverlay) closeBookmarkModal();
  });

  bmOkBtn.addEventListener('click', function () {
    var url = bmUrlInput.value.trim();
    var label = bmLabelInput.value.trim();
    if (!url) return;
    var payload = JSON.stringify({ label: label || url, url: url });
    if (editingIndex >= 0) {
      window.AndroidAPI.editBookmark(editingIndex, payload);
    } else {
      window.AndroidAPI.addBookmark(payload);
    }
    closeBookmarkModal();
  });

  window.onBookmarksUpdated = function (json) {
    try {
      bookmarks = JSON.parse(json) || [];
    } catch (e) {
      bookmarks = [];
    }
    renderBookmarks();
  };

  // -----------------------------------------------------------------
  // Downloads
  // -----------------------------------------------------------------
  var downloads = [];

  btnDownloads.addEventListener('click', function () {
    downloadsPanel.classList.toggle('open');
  });

  dlClearBtn.addEventListener('click', function () {
    window.AndroidAPI.clearCompletedDownloads();
  });

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 KB';
    var units = ['B', 'KB', 'MB', 'GB'];
    var i = 0;
    var value = bytes;
    while (value >= 1024 && i < units.length - 1) {
      value /= 1024;
      i++;
    }
    return value.toFixed(i === 0 ? 0 : 1) + ' ' + units[i];
  }

  function stateLabel(dl) {
    switch (dl.state) {
      case 'progressing': return 'Downloading…';
      case 'completed': return 'Completed';
      case 'cancelled': return 'Cancelled';
      case 'interrupted': return 'Failed';
      default: return dl.state || '';
    }
  }

  function renderDownloads() {
    dlList.innerHTML = '';

    var activeCount = downloads.filter(function (d) { return d.state === 'progressing'; }).length;
    if (activeCount > 0) {
      downloadsBadge.textContent = String(activeCount);
      downloadsBadge.style.display = 'block';
    } else {
      downloadsBadge.style.display = 'none';
    }

    if (downloads.length === 0) {
      var empty = document.createElement('div');
      empty.className = 'dl-empty';
      empty.textContent = 'No downloads yet';
      dlList.appendChild(empty);
      return;
    }

    downloads.forEach(function (dl) {
      var item = document.createElement('div');
      item.className = 'dl-item';

      var name = document.createElement('div');
      name.className = 'dl-name';
      name.textContent = dl.filename || 'download';
      item.appendChild(name);

      var meta = document.createElement('div');
      meta.className = 'dl-meta';
      var pct = dl.totalBytes > 0 ? Math.round((dl.receivedBytes / dl.totalBytes) * 100) : null;
      meta.textContent = stateLabel(dl) +
        (dl.state === 'progressing'
          ? ' · ' + formatBytes(dl.receivedBytes) + (dl.totalBytes > 0 ? ' / ' + formatBytes(dl.totalBytes) : '') + (pct !== null ? ' (' + pct + '%)' : '')
          : ' · ' + formatBytes(dl.totalBytes || dl.receivedBytes));
      item.appendChild(meta);

      if (dl.state === 'progressing') {
        var track = document.createElement('div');
        track.className = 'dl-progress-track';
        var fill = document.createElement('div');
        fill.className = 'dl-progress-fill';
        fill.style.width = (pct !== null ? pct : 0) + '%';
        track.appendChild(fill);
        item.appendChild(track);
      }

      var actions = document.createElement('div');
      actions.className = 'dl-actions';

      if (dl.state === 'progressing') {
        var cancelBtn = document.createElement('button');
        cancelBtn.className = 'danger';
        cancelBtn.textContent = 'Cancel';
        cancelBtn.addEventListener('click', function () { window.AndroidAPI.cancelDownload(dl.id); });
        actions.appendChild(cancelBtn);
      } else {
        if (dl.state === 'completed') {
          var openBtn = document.createElement('button');
          openBtn.textContent = 'Open';
          openBtn.addEventListener('click', function () { window.AndroidAPI.openDownload(dl.id); });
          actions.appendChild(openBtn);

          var folderBtn = document.createElement('button');
          folderBtn.textContent = 'Show in Downloads';
          folderBtn.addEventListener('click', function () { window.AndroidAPI.showDownloadInFolder(dl.id); });
          actions.appendChild(folderBtn);
        }

        var removeBtn = document.createElement('button');
        removeBtn.className = 'danger';
        removeBtn.textContent = 'Remove';
        removeBtn.addEventListener('click', function () { window.AndroidAPI.removeDownload(dl.id); });
        actions.appendChild(removeBtn);
      }

      item.appendChild(actions);
      dlList.appendChild(item);
    });
  }

  window.onDownloadsUpdated = function (json) {
    try {
      downloads = (typeof json === 'string') ? (JSON.parse(json) || []) : (json || []);
    } catch (e) {
      downloads = [];
    }
    renderDownloads();
  };

  // -----------------------------------------------------------------
  // Initial sync - bookmarks/downloads are pulled synchronously on load,
  // active pane state arrives shortly after via onActivePaneState().
  // -----------------------------------------------------------------
  try { window.onBookmarksUpdated(window.AndroidAPI.getBookmarksJson()); } catch (e) { renderBookmarks(); }
  try { window.onDownloadsUpdated(window.AndroidAPI.getDownloadsJson()); } catch (e) { renderDownloads(); }
})();
