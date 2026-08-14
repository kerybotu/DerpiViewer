(function() {
  if (window.__pageTranslatorLoaded) return;
  window.__pageTranslatorLoaded = true;

  var SKIP_TAGS = { SCRIPT:1, STYLE:1, NOSCRIPT:1, TEXTAREA:1, INPUT:1, IFRAME:1, CODE:1, PRE:1 };
  var CHUNK_MAX_ITEMS = 50;
  var CHUNK_MAX_CHARS = 4000;
  var MUTATION_DEBOUNCE_MS = 800;

  var originalStore = [];
  var pendingRequests = {};
  var requestCounter = 0;
  var isActive = false;

  var observer = null;
  var pendingMutationNodes = [];
  var debounceTimer = null;

  var dynamicSelectors = [];
  window.__setDynamicSelectors = function(selectorsJson) {
    try { dynamicSelectors = JSON.parse(selectorsJson); } catch (e) {}
  };

  // -------------------- 静态 HTML 片段规则引擎 --------------------
  var compiledStaticRules = [];
  var staticRootSelector = 'body';
  var staticApplied = false;

  // 规则索引：按目标标签名分组（仅 outer 模式）
  var rulesByTag = {};
  var innerModeRules = []; // inner 模式规则单独存放

  function escapeRegexLiteral(str) {
    return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  function canonicalizeHtml(html) {
    var container = document.createElement('div');
    container.innerHTML = html;
    return container.innerHTML;
  }

  function buildTolerantLiteralPattern(text) {
    var pattern = '';
    var i = 0;
    while (i < text.length) {
      var ch = text.charAt(i);
      if (/\s/.test(ch)) {
        var j = i;
        while (j < text.length && /\s/.test(text.charAt(j))) j++;
        pattern += '\\s+';
        i = j;
      } else {
        pattern += escapeRegexLiteral(ch);
        i++;
      }
    }
    return pattern;
  }

  function compileFragmentToRegex(fragmentA) {
    var trimmed = fragmentA.trim();
    var mode = trimmed.charAt(0) === '<' ? 'outer' : 'inner';

    var canonical = canonicalizeHtml(trimmed);
    var parts = canonical.split(/(\{\{\d+\}\})/g);
    var pattern = '^\\s*';
    var groupOrder = [];

    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      var m = part.match(/^\{\{(\d+)\}\}$/);
      if (m) {
        pattern += '([\\s\\S]*?)';
        groupOrder.push(m[1]);
      } else if (part.length) {
        pattern += buildTolerantLiteralPattern(part);
      }
    }
    pattern += '\\s*$';

    return { regex: new RegExp(pattern), groupOrder: groupOrder, mode: mode };
  }

  function extractTagName(fragmentA) {
    // 从 fragmentA 的规范化 HTML 中提取第一个标签名
    try {
      var div = document.createElement('div');
      div.innerHTML = fragmentA;
      var first = div.firstElementChild;
      return first ? first.tagName.toLowerCase() : null;
    } catch (e) {
      return null;
    }
  }

  window.__setStaticRules = function(payloadJson) {
    try {
      var payload = JSON.parse(payloadJson);
      staticRootSelector = payload.rootSelector || 'body';
      var rules = payload.rules || [];

      compiledStaticRules = rules.map(function(rule) {
        var compiled = compileFragmentToRegex(rule.fragmentA);
        return {
          regex: compiled.regex,
          groupOrder: compiled.groupOrder,
          mode: compiled.mode,
          template: rule.fragmentB,
          tag: compiled.mode === 'outer' ? extractTagName(rule.fragmentA) : null
        };
      });

      // 重建索引
      rulesByTag = {};
      innerModeRules = [];
      compiledStaticRules.forEach(function(rule) {
        if (rule.mode === 'outer' && rule.tag) {
          if (!rulesByTag[rule.tag]) rulesByTag[rule.tag] = [];
          rulesByTag[rule.tag].push(rule);
        } else {
          innerModeRules.push(rule);
        }
      });
    } catch (e) {
      compiledStaticRules = [];
      rulesByTag = {};
      innerModeRules = [];
    }
  };

  // 匹配单个元素
  function matchAndReplaceElement(el) {
    var tagName = el.tagName.toLowerCase();
    var candidateRules = (rulesByTag[tagName] || []).concat(innerModeRules);

    for (var r = 0; r < candidateRules.length; r++) {
      var rule = candidateRules[r];
      var source, isOuter;
      if (rule.mode === 'outer') {
        source = el.outerHTML;
        isOuter = true;
      } else {
        source = el.innerHTML;
        isOuter = false;
      }

      var match = source.match(rule.regex);
      if (!match) continue;

      var output = rule.template;
      for (var g = 0; g < rule.groupOrder.length; g++) {
        var placeholderNum = rule.groupOrder[g];
        var token = '{{' + placeholderNum + '}}';
        var captured = match[g + 1] != null ? match[g + 1] : '';
        output = output.split(token).join(captured);
      }

      if (output !== source) {
        if (isOuter) {
          el.outerHTML = output;
        } else {
          el.innerHTML = output;
        }
      }
      return true;
    }
    return false;
  }

  // 分批处理元素，避免阻塞主线程
  var batchSize = 50; // 每批处理元素数
  var processingQueue = [];
  var isProcessing = false;

  function processBatch() {
    if (isProcessing) return;
    isProcessing = true;

    function doWork() {
      var start = Date.now();
      var end = start + 16; // 每批最多 16ms，保持 60fps
      while (processingQueue.length > 0 && Date.now() < end) {
        var item = processingQueue.shift();
        if (item && item.parentNode) { // 确保元素仍在 DOM 中
          matchAndReplaceElement(item);
        }
      }

      if (processingQueue.length > 0) {
        // 还有剩余，继续在空闲时间处理
        requestIdleCallback(doWork, { timeout: 1000 });
      } else {
        isProcessing = false;
      }
    }

    requestIdleCallback(doWork, { timeout: 1000 });
  }

  function applyStaticRules(scopeRoot) {
    var root = scopeRoot || document.querySelector(staticRootSelector) || document.body;
    if (!root) return;

    var elements = root.querySelectorAll('*');
    var arr = Array.prototype.slice.call(elements);

    // 将元素加入队列，分批处理
    processingQueue = processingQueue.concat(arr);
    if (root.nodeType === 1 && root !== document.body) {
      processingQueue.push(root);
    }
    processBatch();

    staticApplied = true;
  }

  // 增量扫描时也用同样队列
  function applyStaticRulesForNodes(nodes) {
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].nodeType === 1) {
        processingQueue.push(nodes[i]);
        // 同时加入其所有后代
        var descendants = nodes[i].querySelectorAll('*');
        for (var j = 0; j < descendants.length; j++) {
          processingQueue.push(descendants[j]);
        }
      }
    }
    processBatch();
  }

  // -------------------- 静态翻译结果缓存（localStorage） --------------------
  var CACHE_KEY_PREFIX = 'staticTransCache_';
  var cacheEnabled = true; // 默认开启，可在外部关闭

  function getCacheKey() {
    return CACHE_KEY_PREFIX + location.href;
  }

  function loadStaticCache() {
    if (!cacheEnabled) return null;
    try {
      var cached = localStorage.getItem(getCacheKey());
      return cached ? JSON.parse(cached) : null;
    } catch (e) {
      return null;
    }
  }

  function saveStaticCache(htmlMap) {
    if (!cacheEnabled) return;
    try {
      localStorage.setItem(getCacheKey(), JSON.stringify(htmlMap));
    } catch (e) {}
  }

  // 应用静态缓存（若存在）
  function applyStaticCache() {
    var cache = loadStaticCache();
    if (!cache) return;

    // cache: { path: html }
    for (var path in cache) {
      var el = document.querySelector(path);
      if (el && el.outerHTML !== cache[path]) {
        el.outerHTML = cache[path];
      }
    }
    staticApplied = true;
  }

  // 收集已翻译元素路径及其 outerHTML，用于缓存
  function collectTranslatedElements(root) {
    var map = {};
    // 只在初次全页扫描时收集，且只缓存 outer 模式替换成功的元素
    // 由于分批处理异步进行，缓存收集需在队列处理完成后进行
    // 这里简化：在 processBatch 中每次替换成功后记录
    // 可以在 matchAndReplaceElement 中保存
  }

  // 修改 matchAndReplaceElement 以支持缓存
  var currentCacheMap = null; // 用于收集替换结果

  function matchAndReplaceElement(el) {
    var tagName = el.tagName.toLowerCase();
    var candidateRules = (rulesByTag[tagName] || []).concat(innerModeRules);

    for (var r = 0; r < candidateRules.length; r++) {
      var rule = candidateRules[r];
      var source, isOuter;
      if (rule.mode === 'outer') {
        source = el.outerHTML;
        isOuter = true;
      } else {
        source = el.innerHTML;
        isOuter = false;
      }

      var match = source.match(rule.regex);
      if (!match) continue;

      var output = rule.template;
      for (var g = 0; g < rule.groupOrder.length; g++) {
        var placeholderNum = rule.groupOrder[g];
        var token = '{{' + placeholderNum + '}}';
        var captured = match[g + 1] != null ? match[g + 1] : '';
        output = output.split(token).join(captured);
      }

      if (output !== source) {
        if (isOuter) {
          // 记录原始路径和替换后 HTML，用于缓存
          if (currentCacheMap) {
            var path = getElementPath(el);
            if (path) currentCacheMap[path] = output;
          }
          el.outerHTML = output;
        } else {
          el.innerHTML = output;
        }
      }
      return true;
    }
    return false;
  }

  function getElementPath(el) {
    if (!el || el.nodeType !== 1) return null;
    var path = [];
    while (el && el.nodeType === 1) {
      var selector = el.tagName.toLowerCase();
      if (el.id) {
        selector += '#' + el.id;
        path.unshift(selector);
        break;
      } else {
        var sibling = el;
        var nth = 1;
        while (sibling = sibling.previousElementSibling) {
          if (sibling.tagName === el.tagName) nth++;
        }
        if (nth > 1) selector += ':nth-of-type(' + nth + ')';
        path.unshift(selector);
      }
      el = el.parentElement;
    }
    return path.join(' > ');
  }

  function processBatch() {
    if (isProcessing) return;
    isProcessing = true;

    function doWork() {
      var start = Date.now();
      var end = start + 16;
      while (processingQueue.length > 0 && Date.now() < end) {
        var item = processingQueue.shift();
        if (item && item.parentNode) {
          matchAndReplaceElement(item);
        }
      }

      if (processingQueue.length > 0) {
        requestIdleCallback(doWork, { timeout: 1000 });
      } else {
        isProcessing = false;
        // 队列处理完毕，保存缓存
        if (currentCacheMap && Object.keys(currentCacheMap).length > 0) {
          saveStaticCache(currentCacheMap);
          currentCacheMap = null;
        }
      }
    }

    requestIdleCallback(doWork, { timeout: 1000 });
  }

  function applyStaticRules(scopeRoot) {
    var root = scopeRoot || document.querySelector(staticRootSelector) || document.body;
    if (!root) return;

    // 如果未翻译过且缓存可用，直接应用缓存
    if (!staticApplied && cacheEnabled) {
      applyStaticCache();
      if (staticApplied) return; // 缓存已应用，直接返回
    }

    currentCacheMap = {}; // 准备收集替换结果

    var elements = root.querySelectorAll('*');
    var arr = Array.prototype.slice.call(elements);
    processingQueue = processingQueue.concat(arr);
    if (root.nodeType === 1 && root !== document.body) {
      processingQueue.push(root);
    }
    processBatch();

    staticApplied = true;
  }

  function applyStaticRulesForNodes(nodes) {
    currentCacheMap = null; // 增量扫描不缓存
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].nodeType === 1) {
        processingQueue.push(nodes[i]);
        var descendants = nodes[i].querySelectorAll('*');
        for (var j = 0; j < descendants.length; j++) {
          processingQueue.push(descendants[j]);
        }
      }
    }
    processBatch();
  }

  // -------------------- 动态内容翻译 --------------------
  function isEligibleTextNode(node) {
    var parent = node.parentElement;
    if (!parent) return false;
    if (SKIP_TAGS[parent.tagName]) return false;
    if (parent.closest && parent.closest('[data-no-translate]')) return false;
    var text = node.nodeValue;
    if (!text || !text.trim()) return false;
    if (!/[a-zA-Z]/.test(text)) return false;
    return true;
  }

  function collectTextNodes(root) {
    var nodes = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function(node) {
        return isEligibleTextNode(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    var n;
    while ((n = walker.nextNode())) nodes.push(n);
    return nodes;
  }

  function collectDynamicTextNodes() {
    var allNodes = [];
    for (var i = 0; i < dynamicSelectors.length; i++) {
      var containers;
      try { containers = document.querySelectorAll(dynamicSelectors[i]); } catch (e) { continue; }
      for (var j = 0; j < containers.length; j++) {
        allNodes = allNodes.concat(collectTextNodes(containers[j]));
      }
    }
    return allNodes;
  }

  function chunkNodes(nodes) {
    var chunks = [];
    var current = [];
    var currentChars = 0;
    for (var i = 0; i < nodes.length; i++) {
      var text = nodes[i].nodeValue;
      if (current.length >= CHUNK_MAX_ITEMS || currentChars + text.length > CHUNK_MAX_CHARS) {
        if (current.length) chunks.push(current);
        current = [];
        currentChars = 0;
      }
      current.push(nodes[i]);
      currentChars += text.length;
    }
    if (current.length) chunks.push(current);
    return chunks;
  }

  function sendChunk(nodes) {
      var requestId = 'req_' + (requestCounter++);
      pendingRequests[requestId] = nodes;
      var texts = [];
      for (var i = 0; i < nodes.length; i++) {
          // 只提交纯文本：压缩连续空白、去除首尾空白
          var clean = nodes[i].nodeValue.replace(/\s+/g, ' ').trim();
          texts.push(clean);
      }
      if (window.AndroidTranslator && window.AndroidTranslator.requestTranslate) {
          window.AndroidTranslator.requestTranslate(requestId, JSON.stringify(texts));
      }
  }

  function translateDynamicNodes(nodes) {
    var chunks = chunkNodes(nodes);
    for (var c = 0; c < chunks.length; c++) sendChunk(chunks[c]);
  }

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

  function scheduleMutationScan() {
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(processMutationQueue, MUTATION_DEBOUNCE_MS);
  }

  function processMutationQueue() {
    pendingMutationNodes = [];
    debounceTimer = null;
    var newDynamicNodes = collectDynamicTextNodes().filter(function(n) {
      return originalStore.every(function(s) { return s.node !== n; });
    });
    if (newDynamicNodes.length) translateDynamicNodes(newDynamicNodes);
  }

  function startObserver() {
    if (observer) return;
    if (!document.body) return;
    observer = new MutationObserver(function(mutations) {
      if (!isActive) return;
      var hasAdded = false;
      for (var i = 0; i < mutations.length; i++) {
        if (mutations[i].addedNodes.length) { hasAdded = true; break; }
      }
      if (hasAdded) scheduleMutationScan();
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  function stopObserver() {
    if (observer) { observer.disconnect(); observer = null; }
    pendingMutationNodes = [];
    if (debounceTimer) { clearTimeout(debounceTimer); debounceTimer = null; }
  }

  // -------------------- 静态内容增量观察器 --------------------
  var staticObserver = null;
  var staticPendingNodes = [];
  var staticDebounceTimer = null;
  var STATIC_DEBOUNCE_MS = 300;

  function scheduleStaticScan() {
    if (staticDebounceTimer) clearTimeout(staticDebounceTimer);
    staticDebounceTimer = setTimeout(function() {
      var nodes = staticPendingNodes;
      staticPendingNodes = [];
      staticDebounceTimer = null;
      applyStaticRulesForNodes(nodes);
    }, STATIC_DEBOUNCE_MS);
  }

  function startStaticObserver() {
    if (staticObserver) return;
    if (!document.body) return;
    staticObserver = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) staticPendingNodes.push(added[j]);
      }
      if (staticPendingNodes.length) scheduleStaticScan();
    });
    staticObserver.observe(document.body, { childList: true, subtree: true });
  }

  function stopStaticObserver() {
    if (staticObserver) { staticObserver.disconnect(); staticObserver = null; }
    staticPendingNodes = [];
    if (staticDebounceTimer) { clearTimeout(staticDebounceTimer); staticDebounceTimer = null; }
  }

  // -------------------- 引导函数 --------------------
  window.__bootstrapAutoStatic = function(autoEnabled) {
    if (!autoEnabled) return;

    var started = false;

    function runNow() {
      if (started) return;
      started = true;
      applyStaticRules();
      startStaticObserver();
    }

    function tryRunIfBodyReady() {
      if (!document.body) return false;
      runNow();
      return true;
    }

    function onReadyStateChange() {
      if (document.readyState === 'interactive' || document.readyState === 'complete') {
        tryRunIfBodyReady();
      }
    }

    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function() {
        tryRunIfBodyReady();
      }, { once: true });

      document.addEventListener('readystatechange', onReadyStateChange);

      var poll = setInterval(function() {
        if (tryRunIfBodyReady()) {
          clearInterval(poll);
          document.removeEventListener('readystatechange', onReadyStateChange);
        }
      }, 50);

      setTimeout(function() {
        clearInterval(poll);
        document.removeEventListener('readystatechange', onReadyStateChange);
      }, 5000);
    } else {
      tryRunIfBodyReady();
    }
  };

  window.__pageTranslator = {
    runDynamic: function() {
      isActive = true;
      if (document.body) {
        translateDynamicNodes(collectDynamicTextNodes());
        startObserver();
      }
    },
    revertDynamic: function() {
      isActive = false;
      stopObserver();
      for (var i = 0; i < originalStore.length; i++) {
        try { originalStore[i].node.nodeValue = originalStore[i].text; } catch (e) {}
      }
      originalStore = [];
      pendingRequests = {};
    },
    runStatic: function() {
      applyStaticRules();
      startStaticObserver();
    },
    stopStaticWatch: stopStaticObserver,
    isStaticApplied: function() { return staticApplied; },
    enableStaticCache: function(enable) {
      cacheEnabled = enable !== false;
    }
  };
})();