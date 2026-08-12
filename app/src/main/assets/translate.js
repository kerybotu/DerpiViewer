(function() {
  if (window.__pageTranslatorLoaded) return;
  window.__pageTranslatorLoaded = true;

  var SKIP_TAGS = { SCRIPT:1, STYLE:1, NOSCRIPT:1, TEXTAREA:1, INPUT:1, IFRAME:1, CODE:1, PRE:1 };
  var CHUNK_MAX_ITEMS = 25;
  var CHUNK_MAX_CHARS = 1800;

  var originalStore = [];   // { node, text }
  var pendingRequests = {}; // requestId -> node 数组（顺序与发出去的文本一一对应）
  var requestCounter = 0;
  var isRunning = false;

  function collectTextNodes(root) {
    var nodes = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function(node) {
        var parent = node.parentElement;
        if (!parent) return NodeFilter.FILTER_REJECT;
        if (SKIP_TAGS[parent.tagName]) return NodeFilter.FILTER_REJECT;
        if (parent.closest && parent.closest('[data-no-translate]')) return NodeFilter.FILTER_REJECT;
        var text = node.nodeValue;
        if (!text || !text.trim()) return NodeFilter.FILTER_REJECT;
        if (!/[a-zA-Z]/.test(text)) return NodeFilter.FILTER_REJECT; // 跳过纯数字/符号/已是中文的内容
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var n;
    while ((n = walker.nextNode())) nodes.push(n);
    return nodes;
  }

  function chunkNodes(nodes) {
    var chunks = [];
    var current = [];
    var currentChars = 0;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      var text = node.nodeValue;
      if (current.length >= CHUNK_MAX_ITEMS || currentChars + text.length > CHUNK_MAX_CHARS) {
        if (current.length) chunks.push(current);
        current = [];
        currentChars = 0;
      }
      current.push(node);
      currentChars += text.length;
    }
    if (current.length) chunks.push(current);
    return chunks;
  }

  function sendChunk(nodes) {
    var requestId = 'req_' + (requestCounter++);
    pendingRequests[requestId] = nodes;
    var texts = [];
    for (var i = 0; i < nodes.length; i++) texts.push(nodes[i].nodeValue);
    if (window.AndroidTranslator && window.AndroidTranslator.requestTranslate) {
      window.AndroidTranslator.requestTranslate(requestId, JSON.stringify(texts));
    }
  }

  // Kotlin 翻译完成后会回调这里
  window.__onTranslateResult = function(requestId, translatedJson) {
    var nodes = pendingRequests[requestId];
    if (!nodes) return;
    delete pendingRequests[requestId];
    var translated;
    try { translated = JSON.parse(translatedJson); } catch (e) { return; }
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      var newText = translated[i];
      if (typeof newText === 'string' && newText.length) {
        originalStore.push({ node: node, text: node.nodeValue });
        node.nodeValue = newText;
      }
    }
  };

  window.__pageTranslator = {
    run: function() {
      if (isRunning) return;
      isRunning = true;
      originalStore = [];
      pendingRequests = {};
      var nodes = collectTextNodes(document.body);
      var chunks = chunkNodes(nodes);
      for (var i = 0; i < chunks.length; i++) sendChunk(chunks[i]);
    },
    revert: function() {
      for (var i = 0; i < originalStore.length; i++) {
        try { originalStore[i].node.nodeValue = originalStore[i].text; } catch (e) {}
      }
      originalStore = [];
      pendingRequests = {};
      isRunning = false;
    }
  };
})();