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

  var currentUrl = '';

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
  // Initial sync - downloads are pulled synchronously on load, active
  // pane state arrives shortly after via onActivePaneState().
  // -----------------------------------------------------------------
  try { window.onDownloadsUpdated(window.AndroidAPI.getDownloadsJson()); } catch (e) { renderDownloads(); }
})();
