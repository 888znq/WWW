  // bar.js - drives bar.html. Talks to native code through window.AndroidAPI
  // (see WebAppInterface.java) and receives pushes from native code through
  // the window.onXxx callbacks MainActivity calls via evaluateJavascript.
  (function () {
    'use strict';

    var addressInput = document.getElementById('address-input');
    var btnFullscreen = document.getElementById('btn-fullscreen');
    var btnDownloads = document.getElementById('btn-downloads');
    var downloadsBadge = document.getElementById('downloads-badge');
    var downloadsPanel = document.getElementById('downloads-panel');
    var dlList = document.getElementById('dl-list');
    var dlClearBtn = document.getElementById('dl-clear-btn');
    var btnSplitRow = document.getElementById('btn-split-row');
    var btnSplitCol = document.getElementById('btn-split-col');
    var btnClosePane = document.getElementById('btn-close-pane');
    var btnZoomIn = document.getElementById('btn-zoom-in');
    var btnZoomOut = document.getElementById('btn-zoom-out');

    var bookmarkBar = document.getElementById('bookmark-bar');
    var folderPanel = document.getElementById('folder-panel');
    var fpTitle = document.getElementById('fp-title');
    var fpList = document.getElementById('fp-list');
    var fpCloseBtn = document.getElementById('fp-close-btn');
    var fpAddInput = document.getElementById('fp-add-input');
    var fpAddBtn = document.getElementById('fp-add-btn');

    var currentUrl = '';

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

    btnFullscreen.addEventListener('click', function () { window.AndroidAPI.toggleFullscreen(); });

    window.onFullscreenChanged = function (isFullscreen) {
      btnFullscreen.title = isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen';
    };

    btnSplitRow.addEventListener('click', function () { window.AndroidAPI.splitPane('row'); });
    btnSplitCol.addEventListener('click', function () { window.AndroidAPI.splitPane('col'); });
    btnClosePane.addEventListener('click', function () {
      if (btnClosePane.disabled) return;
      window.AndroidAPI.closePane();
    });

    btnZoomIn.addEventListener('click', function () { window.AndroidAPI.zoomIn(); });
    btnZoomOut.addEventListener('click', function () { window.AndroidAPI.zoomOut(); });

    window.onActivePaneState = function (state) {
      state = state || {};
      currentUrl = state.url || '';
      if (document.activeElement !== addressInput) {
        addressInput.value = currentUrl;
      }
      btnClosePane.disabled = !state.hasSplit;
      if (activeFolder && document.activeElement !== fpAddInput) {
        fpAddInput.value = currentUrl;
      }
    };

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

    var FOLDER_KEYS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
    var bookmarks = [];
    var activeFolder = null;

    FOLDER_KEYS.forEach(function (key) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'folder-btn';
      btn.dataset.folder = key;
      btn.textContent = key;
      btn.title = 'Folder ' + key;
      btn.addEventListener('click', function () { toggleFolder(key); });
      bookmarkBar.appendChild(btn);
    });

    function folderButtonEl(key) {
      return bookmarkBar.querySelector('.folder-btn[data-folder="' + key + '"]');
    }

    function toggleFolder(key) {
      if (activeFolder === key) {
        closeFolderPanel();
      } else {
        openFolderPanel(key);
      }
    }

    function openFolderPanel(key) {
      var prevBtn = activeFolder ? folderButtonEl(activeFolder) : null;
      if (prevBtn) prevBtn.classList.remove('active');

      activeFolder = key;
      var btn = folderButtonEl(key);
      if (btn) btn.classList.add('active');

      fpTitle.textContent = 'Folder ' + key;
      fpAddInput.value = currentUrl || '';
      renderFolderList();
      folderPanel.classList.add('open');
      requestFolderPanelHeight();
    }

    function closeFolderPanel() {
      var btn = activeFolder ? folderButtonEl(activeFolder) : null;
      if (btn) btn.classList.remove('active');
      activeFolder = null;
      folderPanel.classList.remove('open');
      try { window.AndroidAPI.setBarExtraHeight(0); } catch (e) {}
    }

    // Sizes the bar (and so the popup) to roughly fit however many
    // bookmarks are in this folder - a couple of entries stay small,
    // a long folder caps out and scrolls instead of growing forever.
    function requestFolderPanelHeight() {
      var count = 0;
      for (var i = 0; i < bookmarks.length; i++) {
        if (bookmarks[i] && bookmarks[i].folder === activeFolder) count++;
      }
      var chrome = 74; // header + add-row
      var perItem = 28;
      var desired = chrome + Math.min(count, 6) * perItem;
      desired = Math.max(140, Math.min(desired, 260));
      try { window.AndroidAPI.setBarExtraHeight(desired - 84); } catch (e) {}
    }

    fpCloseBtn.addEventListener('click', closeFolderPanel);

    function renderFolderList() {
      fpList.innerHTML = '';
      if (!activeFolder) return;

      var entries = [];
      bookmarks.forEach(function (bm, idx) {
        if (bm && bm.folder === activeFolder) entries.push({ bm: bm, idx: idx });
      });

      if (entries.length === 0) {
        var empty = document.createElement('div');
        empty.className = 'fp-empty';
        empty.textContent = 'No bookmarks in ' + activeFolder + ' yet';
        fpList.appendChild(empty);
        return;
      }

      entries.forEach(function (entry) {
        var item = document.createElement('div');
        item.className = 'fp-item';

        var label = document.createElement('div');
        label.className = 'fp-item-label';
        label.textContent = entry.bm.label || entry.bm.url || '';
        item.appendChild(label);

        item.addEventListener('click', function () {
          if (entry.bm.url) window.AndroidAPI.navigateToBookmark(entry.bm.url);
          closeFolderPanel();
        });

        var del = document.createElement('button');
        del.className = 'fp-del';
        del.title = 'Remove bookmark';
        del.textContent = '\u2715';
        del.addEventListener('click', function (e) {
          e.stopPropagation();
          window.AndroidAPI.deleteBookmark(entry.idx);
        });
        item.appendChild(del);

        fpList.appendChild(item);
      });
    }

    function refreshFolderIndicators() {
      var seen = {};
      bookmarks.forEach(function (bm) {
        if (bm && bm.folder) seen[bm.folder] = true;
      });
      FOLDER_KEYS.forEach(function (key) {
        var btn = folderButtonEl(key);
        if (btn) btn.classList.toggle('has-bookmarks', !!seen[key]);
      });
    }

    fpAddBtn.addEventListener('click', function () {
      if (!activeFolder || !currentUrl) return;
      var label = fpAddInput.value.trim() || currentUrl;
      window.AndroidAPI.addBookmark(JSON.stringify({ label: label, url: currentUrl, folder: activeFolder }));
    });

    fpAddInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') fpAddBtn.click();
    });

    window.onBookmarksUpdated = function (json) {
      try {
        bookmarks = (typeof json === 'string') ? (JSON.parse(json) || []) : (json || []);
      } catch (e) {
        bookmarks = [];
      }
      refreshFolderIndicators();
      if (activeFolder) {
        renderFolderList();
        requestFolderPanelHeight();
      }
    };

    // ---- NEW: quick-add bookmark popup ("+" button beside the address bar) ----

    function deriveBookmarkName(url) {
      if (!url) return '';
      var host = url.replace(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//, '');
      host = host.split('/')[0].split('?')[0].split('#')[0].split(':')[0];
      host = host.replace(/^www\./i, '');
      var label = host.split('.')[0] || host;
      if (!label) return '';
      return label.charAt(0).toUpperCase() + label.slice(1);
    }

    function deriveFolderFromName(name) {
      if (!name) return '0';
      var ch = name.trim().charAt(0).toUpperCase();
      return FOLDER_KEYS.indexOf(ch) !== -1 ? ch : '0';
    }

    var btnAddBookmark = document.getElementById('btn-add-bookmark');
    var quickBmOverlay = document.getElementById('quick-bm-overlay');
    var qbmCloseBtn = document.getElementById('qbm-close-btn');
    var qbmCancelBtn = document.getElementById('qbm-cancel-btn');
    var qbmSaveBtn = document.getElementById('qbm-save-btn');
    var qbmNameInput = document.getElementById('qbm-name-input');
    var qbmUrlPreview = document.getElementById('qbm-url-preview');
    var qbmFolderValue = document.getElementById('qbm-folder-value');
    var qbmSelectedFolder = '0';

    function setQbmFolder(key) {
      qbmSelectedFolder = key;
      qbmFolderValue.textContent = key;
    }

    function openQuickBookmark() {
      if (!currentUrl) return;
      qbmUrlPreview.textContent = currentUrl;
      var name = deriveBookmarkName(currentUrl);
      qbmNameInput.value = name;
      setQbmFolder(deriveFolderFromName(name));
      quickBmOverlay.classList.add('open');
      try { window.AndroidAPI.setBarExtraHeight(200 - 84); } catch (e) {}
      qbmNameInput.focus();
      qbmNameInput.select();
    }

    function closeQuickBookmark() {
      quickBmOverlay.classList.remove('open');
      try { window.AndroidAPI.setBarExtraHeight(0); } catch (e) {}
    }

    btnAddBookmark.addEventListener('click', openQuickBookmark);
    qbmCloseBtn.addEventListener('click', closeQuickBookmark);
    qbmCancelBtn.addEventListener('click', closeQuickBookmark);

    // Live: the folder always tracks the first letter of whatever's typed,
    // so it's clear which folder it'll land in before you hit Save.
    qbmNameInput.addEventListener('input', function () {
      setQbmFolder(deriveFolderFromName(qbmNameInput.value));
    });

    qbmSaveBtn.addEventListener('click', function () {
      var label = qbmNameInput.value.trim() || deriveBookmarkName(currentUrl) || currentUrl;
      window.AndroidAPI.addBookmark(JSON.stringify({ label: label, url: currentUrl, folder: qbmSelectedFolder }));
      closeQuickBookmark();
    });

    try { window.onDownloadsUpdated(window.AndroidAPI.getDownloadsJson()); } catch (e) { renderDownloads(); }
    try { window.onBookmarksUpdated(window.AndroidAPI.getBookmarksJson()); } catch (e) { bookmarks = []; }
  })();
