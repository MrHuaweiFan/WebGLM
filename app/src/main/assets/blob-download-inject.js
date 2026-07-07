/**
 * Injected via WebViewCompat.addDocumentStartJavaScript(webView, script, allowedOrigins).
 * Use the SAME allowedOrigins set you pass to addWebMessageListener (see BlobDownloadManager.kt)
 * so `window.AndroidBlobBridge` is guaranteed to exist before this script runs.
 *
 * What this does:
 *  1. Overrides URL.createObjectURL to capture every Blob the page creates, keyed by its URL.
 *     revokeObjectURL only deletes the URL->Blob lookup entry -- it does not free a Blob object
 *     that this script still holds a reference to. That's what removes the revoke-before-fetch race.
 *  2. Intercepts <a href="blob:..." download> clicks and window.open(blobUrl) calls before the
 *     page's own handlers finish.
 *  3. Streams the Blob's bytes to native as a sequence of self-describing ArrayBuffer messages
 *     (no separate "start/end" messages to keep in order -- every chunk carries its own header).
 */
(function () {
  if (window.__blobHookInstalled) return;
  window.__blobHookInstalled = true;

  var blobRegistry = new Map(); // blob: URL string -> Blob object
  var nativeCreate = URL.createObjectURL.bind(URL);
  var nativeRevoke = URL.revokeObjectURL.bind(URL);

  URL.createObjectURL = function (obj) {
    var url = nativeCreate(obj);
    try {
      if (obj instanceof Blob) blobRegistry.set(url, obj);
    } catch (e) {}
    return url;
  };

  URL.revokeObjectURL = function (url) {
    // Real revoke still happens so the page's own behavior is unaffected.
    // Our copy in blobRegistry is a separate reference and survives this.
    try { nativeRevoke(url); } catch (e) {}
  };

  var CHUNK_BYTES = 1024 * 1024; // 1MB per bridge message; tune if needed

  function packChunk(meta, payloadBuffer) {
    var headerBytes = new TextEncoder().encode(JSON.stringify(meta));
    var out = new ArrayBuffer(4 + headerBytes.byteLength + payloadBuffer.byteLength);
    new DataView(out).setUint32(0, headerBytes.byteLength, true); // little-endian length prefix
    new Uint8Array(out, 4, headerBytes.byteLength).set(headerBytes);
    new Uint8Array(out, 4 + headerBytes.byteLength).set(new Uint8Array(payloadBuffer));
    return out;
  }

  function sendBlob(blob, filename) {
    var bridge = window.AndroidBlobBridge;
    if (!bridge) return;
    var id = 'x' + Date.now() + Math.random().toString(36).slice(2, 8);
    var mimeType = blob.type || 'application/octet-stream';
    var offset = 0;

    (function next() {
      var end = Math.min(offset + CHUNK_BYTES, blob.size);
      var isLast = end >= blob.size;
      blob.slice(offset, end).arrayBuffer().then(function (buf) {
        bridge.postMessage(packChunk(
          { id: id, last: isLast, filename: filename, mimeType: mimeType, size: blob.size },
          buf
        ));
        offset = end;
        if (!isLast) next();
      }).catch(function (err) {
        bridge.postMessage(JSON.stringify({ error: true, id: id, message: String(err) }));
      });
    })();
  }

  function isBlobUrl(u) {
    return typeof u === 'string' && u.indexOf('blob:') === 0;
  }

  function resolveAndSend(url, filename) {
    var cached = blobRegistry.get(url);
    if (cached) { sendBlob(cached, filename); return; }
    // Fallback: only reachable for a blob minted before this script installed itself
    // (e.g. created during the same tick as document-start, before override ran).
    fetch(url).then(function (r) { return r.blob(); })
      .then(function (b) { sendBlob(b, filename); })
      .catch(function (err) {
        var bridge = window.AndroidBlobBridge;
        if (bridge) bridge.postMessage(JSON.stringify({ error: true, message: 'fetch fallback failed: ' + err }));
      });
  }

  // Capture-phase click interception: runs before the page's own click handlers
  // finish and before the browser's default "download" action would fire.
  document.addEventListener('click', function (e) {
    var el = e.target;
    while (el && el !== document && !(el.tagName === 'A' && isBlobUrl(el.href))) {
      el = el.parentElement;
    }
    if (el && el.tagName === 'A' && isBlobUrl(el.href)) {
      e.preventDefault();
      e.stopPropagation();
      resolveAndSend(el.href, el.getAttribute('download') || 'download');
    }
  }, true);

  var nativeOpen = window.open;
  window.open = function (url) {
    if (isBlobUrl(url)) {
      resolveAndSend(url, 'download');
      return null;
    }
    return nativeOpen.apply(window, arguments);
  };
})();
