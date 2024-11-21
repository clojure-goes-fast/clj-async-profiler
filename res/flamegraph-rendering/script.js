// SPDX-License-Identifier: EPL-1.0
// Copyright 2021-2024 Oleksandr Yakushev
// Contains code from https://github.com/async-profiler/async-profiler, licensed
// under Apache License, Version 2.0.

/// Constants
const c = canvas.getContext('2d');
const sidebarWidth = sidebar.offsetWidth; // Remember while sidebar is visible.
const qString = new URLSearchParams(window.location.search)
const transformFilterTemplate = document.getElementById('transformFilterTemplate');
const transformReplaceTemplate = document.getElementById('transformReplaceTemplate');
const minSamplesToShow = 0; // Don't hide any frames for now.

/// Config handling

const bakedPackedConfig = <<<config>>>;
const queryPackedConfig = qString.get('config');

async function gzipBase64(inputString) {
  let encoder = new TextEncoder();
  let data = encoder.encode(inputString);
  let cs = new CompressionStream("gzip");
  let writer = cs.writable.getWriter();
  writer.write(data);
  writer.close();
  let compressed = await new Response(cs.readable).arrayBuffer();
  let base64Encoded = btoa(String.fromCharCode(...new Uint8Array(compressed)))
      .replace(/\+/g, "-")
      .replace(/\//g, "_");
  return base64Encoded;
}

async function gunzipBase64(inputString) {
  // We use url-encoded base64, convert it to regular base64 first.
  const base64 = inputString
        .replace(/-/g, '+')
        .replace(/_/g, '/');
  const binaryString = atob(base64);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++)
    bytes[i] = binaryString.charCodeAt(i);

  const stream = new Blob([bytes]).stream()
        .pipeThrough(new DecompressionStream('gzip'));
  return await new Response(stream).text();
}

async function unpackConfig(packedConfigString) {
  try {
    let json = JSON.parse(await gunzipBase64(packedConfigString));
    console.log("[clj-async-profiler] Unpacked config", packedConfigString, json);
    return json;
  } catch (error) {
    console.error('[clj-async-profiler] Error unpacking config', packedConfigString, error);
  }
}

async function packConfig(config) {
  try {
    let result = await gzipBase64(JSON.stringify(config));
    console.log("[clj-async-profiler] Packed config", config, result);
    return result;
  } catch (error) {
    console.error('[clj-async-profiler] Error packing config', config, result);
  }
}

var sidebarVisible = false;
var canvasWidth, canvasHeight;

if (qString.get('sidebar') == 'expanded') {
  sidebarVisible = true;
}

function updateSidebarState() {
  let availableWidth = Math.min(window.innerWidth, document.documentElement.clientWidth);
  if (sidebarVisible) {
    sidebar.style.display = 'block';
    smallbar.style.display = 'none';
    canvasWidth = availableWidth - sidebarWidth;
  } else {
    sidebar.style.display = 'none';
    smallbar.style.display = 'flex';
    canvasWidth = availableWidth;
  }
  canvasWidth -= 20; // Reduce by the padding of .graphCol
}

updateSidebarState();

var graphTitle = <<<graphTitle>>> || "Flamegraph";
var isDiffgraph = <<<isDiffgraph>>>;
var b_scale_factor;
var reverseGraph = false;
var initialStacks = [];
var stacks, tree, levels, depth;
// idToFrame gets defined at the end of the file
var _lastInsertedStack = null;
var userTransforms = [];

var maxTooltipWidth = 250;
if (isDiffgraph) {
  maxTooltipWidth = 350;
} else {
  isNormalizedDiv.remove();
  tooltipSep2.remove();
  tooltipPctTotalSpan.remove();
}
tooltip.style['max-width'] = maxTooltipWidth + 'px';
graphTitleSpan.innerText = graphTitle;
graphTitleSpan.title = graphTitle;

if (window.location.protocol == 'file:') {
  saveConfigButton.remove();
}

function a(frameIds, samples) {
  var same = frameIds[0];
  var frames = (same > 0) ? _lastInsertedStack.slice(0,same) : [];

  for (var i = 1, len = frameIds.length; i < len; i++) {
    frames.push(idToFrame[frameIds[i]]);
  }

  _lastInsertedStack = frames;
  initialStacks.push({stackStr: frames.join(";"), samples: samples});
}

var totalSamplesA = 0, totalSamplesB = 0;

function d(frameIds, samples_a, samples_b) {
  var same = frameIds[0];
  var frames = (same > 0) ? _lastInsertedStack.slice(0,same) : [];

  for (var i = 1, len = frameIds.length; i < len; i++) {
    frames.push(idToFrame[frameIds[i]]);
  }

  totalSamplesA += samples_a;
  totalSamplesB += samples_b;

  _lastInsertedStack = frames;
  initialStacks.push({stackStr: frames.join(";"),
                      samples_a: samples_a, samples_b: samples_b});
}

function _extractRegexPrefix(s) {
  let parsed = s.match(/^\/\.\+(.+)\/g$/);
  if (parsed != null) {
    return new RegExp(parsed[1], 'g');
  }
}

function _stringToMaybeRegex(s) {
  if (s == null) return null;
  let parsed = s.match(/^\/(.+)\/$/);
  if (parsed != null)
    return new RegExp(parsed[1], 'g');
  else
    return s;
}

function _maybeRegexToString(o) {
  if (o == null) return null;
  if (o.source) return '/' + o.source + '/';
  else return o;
}

function _makeTransform(type, enabled, what, replacement) {
  let what2 = _stringToMaybeRegex(what);
  let what2Str = what2.toString();
  let prefix = (what2 instanceof RegExp) ?
      _extractRegexPrefix(what2Str) : null;
  if (type == 'replace')
    return { type: type, enabled: enabled, what: what2, replacement: replacement, prefix: prefix}
  else
    return { type: type, enabled: enabled, what: what2}
}

function applyConfig(config) {
  if (config.highlight) applyHighlight(config.highlight, true);
  if (config.reverse) setReverseGraph(true);
  if (config['sort-by'] == 'name') sortByNameRadio.checked = true;
  if (config.transforms) {
    userTransforms = [];
    for (let configTransform of config.transforms) {
      let t = _makeTransform(configTransform.type, configTransform.enabled != false,
                             configTransform.what, configTransform.replacement);
      userTransforms.push(t);
    }
  }
}

async function constructPackedConfig() {
  let config = {}
  if (highlightState.pattern) config.highlight = _maybeRegexToString(highlightState.pattern);
  if (reverseGraph) config.reverse = true;
  if (sortByNameRadio.checked) config['sort-by'] = 'name';

  let transformsData = [];
  for (let t of userTransforms) {
    let t2 = {type: t.type, what: _maybeRegexToString(t.what)};
    if (t.replacement) t2.replacement = t.replacement;
    if (!t.enabled) t2.enabled = false;
    transformsData.push(t2);
  }
  config.transforms = transformsData;

  return packConfig(config);
}

async function copyConfigToClipboard() {
  navigator.clipboard.writeText(await constructPackedConfig());
  showToast("Config copied to clipboard.")
}

async function pasteConfigFromClipboard() {
  let text = await navigator.clipboard.readText();
  applyConfig(await unpackConfig(text));
  redrawTransformsSection();
  fullRedraw(true);
  showToast("Config pasted from clipboard.")
}

async function saveConfigToServer() {
  let packedConfig = await constructPackedConfig();
  let req = new XMLHttpRequest();
  req.open("POST", "/save-config?packed-config=" + packedConfig);
  console.log("[clj-async-profiler] Sending save-config request to backend:", req);
  req.send();
  showToast("Config saved to server.")
}

function match(string, obj) {
  if (typeof(obj) == 'string') {
    return string.includes(obj);
  } else
    return string.match(obj);
}

function applyReplacement(string, what, replacement, prefix) {
  var s = string;
  var prevMatch = null;
  if (prefix != null) {
    while (true) {
      let match = prefix.exec(string);
      if (match == null) {
        if (prevMatch == null)
          return s;
        else {
          s = string.substring(Math.max(prevMatch.index, 0));
          return s.replace(prefix, replacement);
        }
      } else {
        prevMatch = match;
      }
    }
  }
  return s.replaceAll(what, replacement);
}

function transformStacks() {
  console.time("[clj-async-profiler] Transform stacks");
  let diff = isDiffgraph;
  var result;
  if (userTransforms.length > 0) {
    var xformedMap = {};
    for (var i = 0; i < initialStacks.length; i++) {
      var stack = initialStacks[i];
      var xformedStr = ";" + stack.stackStr + ";";
      var useIt = true;

      for (var t = 0; t < userTransforms.length; t++) {
        const transform = userTransforms[t];
        if (transform.enabled && transform.what != '') {
          if (transform.type == 'replace') {
            xformedStr = applyReplacement(xformedStr, transform.what,
                                          transform.replacement, transform.prefix);
          } else if (transform.type == 'filter') {
            if (!match(xformedStr, transform.what))
              useIt = false;
          } else if (transform.type == 'remove') {
            if (match(xformedStr, transform.what))
              useIt = false;
          }
        }
        if (!useIt) break;
      }

      let len = xformedStr.length;
      let cut_from = (xformedStr.charAt(0) == ';') ? 1 : 0;
      let cut_to = (xformedStr.charAt(len-1) == ';') ? len-1 : len;
      xformedStr = xformedStr.substring(cut_from, cut_to);

      if (useIt)
        if (diff) {
          let newVal = (xformedMap[xformedStr] || {});
          newVal.samples_a = (newVal.samples_a || 0) + stack.samples_a;
          newVal.samples_b = (newVal.samples_b || 0) + stack.samples_b;
          xformedMap[xformedStr] = newVal;
        } else
          xformedMap[xformedStr] = stack.samples + (xformedMap[xformedStr] || 0);
    }

    var xformedStacks = [];
    for (xformedStr in xformedMap) {
      if (diff) {
        let val = xformedMap[xformedStr];
        xformedStacks.push({stackStr: xformedStr, samples_a: val.samples_a, samples_b: val.samples_b})
      } else
        xformedStacks.push({stackStr: xformedStr, samples: xformedMap[xformedStr]});
    }
    result = xformedStacks;
  } else
    result = initialStacks;

  console.timeEnd("[clj-async-profiler] Transform stacks");
  return result;
}

function makeTreeNode() {
  if (isDiffgraph)
    return {self_samples_a: 0, self_samples_b: 0, self_delta: 0,
            total_samples_a: 0, total_samples_b: 0, total_delta: 0,
            delta_abs: 0, children: {}};
  else
    return {self: 0, total: 0, children: {}};
}

function getChildNode(node, childTitle) {
  var children = node.children;
  var child = children[childTitle];
  if (child == undefined) {
    child = makeTreeNode();
    children[childTitle] = child;
  }
  return child;
}

function parseStacksToTreeSimple(stacks, treeRoot) {
  for (let i = 0, len = stacks.length; i < len; i++) {
    let stack = stacks[i];
    let stackframes = stack.stackStr.split(";");
    let stackLen = stackframes.length;
    let node = treeRoot;
    let samples = stack.samples;
    if (reverseGraph) {
      for (let j = stackLen-1; j >= 0; j--) {
        node.total += samples;
        node = getChildNode(node, stackframes[j]);
      }
    } else {
      for (let j = 0; j < stackLen; j++) {
        node.total += samples;
        node = getChildNode(node, stackframes[j]);
      }
    }
    node.total += samples;
    node.self += samples;
  }
}

function parseStacksToTreeDiffgraph(stacks, treeRoot) {
  let normalizeDiff = isNormalized.checked;
  for (let i = 0, len = stacks.length; i < len; i++) {
    let stack = stacks[i];
    let stackframes = stack.stackStr.split(";");
    let stackLen = stackframes.length;
    var node = treeRoot;

    let samplesA = stack.samples_a;
    let samplesB = stack.samples_b;
    if (normalizeDiff) samplesB = Math.round(samplesB * b_scale_factor);
    let delta = samplesB - samplesA;

    if (reverseGraph) {
      for (let j = stackLen-1; j >= 0; j--) {
        node.total_samples_a += samplesA;
        node.total_samples_b += samplesB;
        node.total_delta += delta;
        node.delta_abs += Math.abs(delta);
        node = getChildNode(node, stackframes[j]);
      }
    } else {
      for (let j = 0; j < stackLen; j++) {
        node.total_samples_a += samplesA;
        node.total_samples_b += samplesB;
        node.total_delta += delta;
        node.delta_abs += Math.abs(delta);
        node = getChildNode(node, stackframes[j]);
      }
    }
    node.self_samples_a += samplesA;
    node.self_samples_b += samplesB;
    node.self_delta += delta;
    node.total_samples_a += samplesA;
    node.total_samples_b += samplesB;
    node.total_delta += delta;
    node.delta_abs += Math.abs(delta);
  }
}

function parseStacksToTree(stacks, treeRoot) {
  console.time("[clj-async-profiler] Generate tree");
  if (isDiffgraph)
    parseStacksToTreeDiffgraph(stacks, treeRoot);
  else
    parseStacksToTreeSimple(stacks, treeRoot);
  console.timeEnd("[clj-async-profiler] Generate tree");
}

const palette = {
  green     : "#50e150",
  inlined   : "#46c4bf",
  unknown   : "#f7a65b",
  cpp       : "#d9d800",
  kernel    : "#f15964",
  java      : "#91dc51",
  clojure   : "#8fb5fe",
  highlight : "#ee00ee",
  total     : "#999999",
  diff      : { blue    : [51, 119, 255],    // #3377FF
                neutral : [245, 245, 245],   // #F5F5F5
                red     : [253, 59, 73] } }; // #FD3B49

function frameColor(title) {
  if (title.endsWith("_[j]")) {
    return palette.green;
  } else if (title.endsWith("_[i]")) {
    return palette.inlined;
  } else if (title.endsWith("_[k]")) {
    return palette.kernel;
  } else if (title.includes("::") || title.startsWith("-[") || title.startsWith("+[")) {
    return palette.cpp;
  } else if (title.includes("/")) { // Clojure (will only work after unmunging)
    return palette.clojure;
  } else if (title.includes(".")) { // Java (if it has a dot and is not Clojure)
    return palette.java;
  } else if (title == "Total") {
    return palette.total;
  } else return palette.unknown;
}

function decToHex(n) {
  var hex = n.toString(16);
  return hex.length == 1 ? "0" + hex : hex;
}

function getDiffColor(value) {
  // Clamp the value between -1 and 1
  value = Math.max(-1.0, Math.min(1.0, value));

  // Find the correct color range
  let start, end;
  if (value <= 0) {
    start = palette.diff.blue;
    end = palette.diff.neutral;
    value = value + 1.0; // Normalize to 0.0-1.0
  } else {
    start = palette.diff.neutral;
    end = palette.diff.red;
  }

  // Interpolate between colors
  const r = Math.round(start[0] + (end[0] - start[0]) * value);
  const g = Math.round(start[1] + (end[1] - start[1]) * value);
  const b = Math.round(start[2] + (end[2] - start[2]) * value);

  // Convert to hex
  return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
}

function generateLevelsSimple(levels, node, title, level, x) {
  var left = x;

  levels[level] = levels[level] || [];
  levels[level].push({left: left, width: node.total, color: frameColor(title),
                      title: title});

  left += node.self;

  let children = Object.entries(node.children);
  if (sortByNameRadio.checked)
    children.sort((a, b) => a[0].localeCompare(b[0]));
  else
    children.sort((a, b) => b[1].total - a[1].total);

  for (let i in children) {
    let title = children[i][0];
    let child = children[i][1];
    generateLevelsSimple(levels, child, title, level+1, left);
    left += child.total;
  }
}

function generateLevelsDiffgraph(levels, node, title, level, x) {
  var left = x;

  levels[level] = levels[level] || [];
  var change = (node.total_samples_a == 0) ? 1.0 : node.total_delta / node.total_samples_a;
  var color = getDiffColor(change);
  levels[level].push({left: left, width: node.delta_abs,
                      self_samples_a: node.self_samples_a,
                      self_samples_b: node.self_samples_b,
                      self_delta: node.self_delta,
                      total_samples_a: node.total_samples_a,
                      total_samples_b: node.total_samples_b,
                      total_delta: node.total_delta,
                      color: color,
                      title: title});

  left += Math.abs(node.self_delta);

  let children = Object.entries(node.children);
  if (sortByNameRadio.checked)
    children.sort((a, b) => a[0].localeCompare(b[0]));
  else
    children.sort((a, b) => b[1].delta_abs - a[1].delta_abs);

  for (let i in children) {
    let title = children[i][0];
    let child = children[i][1];
    generateLevelsDiffgraph(levels, child, title, level+1, left);
    left += child.delta_abs;
  }
}

function generateLevels(node, title) {
  console.time("[clj-async-profiler] Generate flat levels");
  levels = [];
  if (isDiffgraph)
    generateLevelsDiffgraph(levels, node, title, 0, 0);
  else
    generateLevelsSimple(levels, node, title, 0, 0);
  console.timeEnd("[clj-async-profiler] Generate flat levels");
}

function recomputeDataModel() {
  console.time("[clj-async-profiler] recomputeDataModel");
  if (isDiffgraph && isNormalized.checked)
    b_scale_factor = totalSamplesA / totalSamplesB;

  stacks = transformStacks();
  tree = makeTreeNode();
  parseStacksToTree(stacks, tree);

  generateLevels(tree, "Total");
  depth = levels.length;
  if (depth > 511) depth = 511;
  console.timeEnd("[clj-async-profiler] recomputeDataModel");
}

function reinitCanvas() {
  canvasHeight = (depth + 1) * 16;
  canvas.style.width = canvasWidth + 'px';
  if (devicePixelRatio) {
    canvas.width = canvasWidth * devicePixelRatio;
    canvas.height = canvasHeight * devicePixelRatio;
    c.scale(devicePixelRatio, devicePixelRatio);
  } else {
    canvas.width = canvasWidth;
    canvas.height = canvasHeight;
  }
  canvas.onclick = canvasOnClick;
  canvas.onmousemove = canvasOnMouseMove;
  canvas.onmouseout = canvasOnMouseOut;
  c.font = document.body.style.font;
  if (window.innerWidth < canvasWidth) {
    // This is probably caused by vertical scrollbar appearing when it wasn't
    // there before. Force a recompute of canvasWidth and reinit canvas again.
    updateSidebarState(); // This recomputes canvasWidth
    reinitCanvas();
  }
}

var highlightState = {
  pattern: null, lastPatternAsText: null
}

var currentRootFrame, currentRootLevel, currentFrameUnderCursor, currentLevelUnderCursor, px;

function applyHighlight(stringOrRegex, doSetInput) {
  if (doSetInput)
    highlightInput.value = stringOrRegex;
  let newPattern = _stringToMaybeRegex(stringOrRegex);
  highlightState.pattern = newPattern;
  highlightState.lastPatternAsText = stringOrRegex;
  highlightButton1.classList.add("toggled");
  highlightButton2.classList.add("toggled");
  performSlowAction(function() { render(currentRootFrame, currentRootLevel); });
}

function clearHighlight() {
  highlightState.pattern = null;
  highlightButton1.classList.remove("toggled");
  highlightButton2.classList.remove("toggled");
  render(currentRootFrame, currentRootLevel);
}

function render(newRootFrame, newLevel) {
  var totalRenderedFrames = 0;
  console.time("[clj-async-profiler] Render");
  // Background
  c.clearRect(0, 0, canvas.width, canvas.height);

  currentRootFrame = newRootFrame || levels[0][0];
  currentRootLevel = newLevel || 0;
  px = canvasWidth / currentRootFrame.width;

  const matchedSpans = [];

  function totalMatched() {
    let total = 0;
    let left = 0;
    for (let x in matchedSpans) {
      if (+x >= left) {
        let width = matchedSpans[x];
        total += width;
        left = +x + width;
      }
    }
    return total;
  }

  const x0 = currentRootFrame.left;
  const x1 = x0 + currentRootFrame.width;

  let highlightPattern = highlightState.pattern;

  let lastL = -1;

  function drawFrame(f, y, alpha, h) {
    totalRenderedFrames += 1;
    // if (h == 78) return;
    if (f.left < x1 && f.left + f.width > x0) {
      let color = f.color;
      if (highlightPattern && f.title.match(highlightPattern)) {
        if (!(matchedSpans[f.left] >= f.width))
          matchedSpans[f.left] = f.width;
        color = palette.highlight;
      }

      if (f.width >= minSamplesToShow && y >= 0 && y <= canvasHeight) {
        c.fillStyle = color;
        let w = f.width * px;
        let l = (f.left - x0) * px;
        let newL = Math.floor(l * 8);
        // Optimization to sample the rendering of thin frames.
        if (w < 0.125 && newL <= lastL) return;

        c.fillRect(l, y, w, 15);
        lastL = newL;

        if (w >= 21) {
          const chars = Math.floor((w - 6) / 7);
          const title = f.title.length <= chars ? f.title : f.title.substring(0, chars - 1) + "…";
          c.fillStyle = '#000000';
          c.fillText(title, Math.max(l, 0) + 3, y + 12, w - 6);
        }

        if (alpha) {
          c.fillStyle = 'rgba(255, 255, 255, 0.5)';
          c.fillRect(l, y, w, 15);
        }
      }
    }
  }

  for (let h = 0; h < levels.length; h++) {
    const y = reverseGraph ? h * 16 : canvasHeight - (h + 1) * 16;
    const frames = levels[h];
    lastL = -1;
    if (totalRenderedFrames > 10000000) break;

    // Binary search left bound
    let lo = 0, hi = frames.length;
    let fromIdx = lo, toIdx = hi;
    if (currentRootLevel > 0) {
      while (lo < hi) {
        let mid = Math.floor((lo + hi) / 2);
        let frame = frames[mid];
        if (frame.left + frame.width < x0) lo = mid + 1;
        else hi = mid;
      }
      fromIdx = lo;

      // Binary search right bound
      hi = frames.length;
      while (lo < hi) {
        let mid = Math.floor((lo + hi) / 2);
        if (frames[mid].left < x1) lo = mid + 1;
        else hi = mid;
      }
      toIdx = lo;
    }

    for (let i = fromIdx; i < toIdx; i++) {
      drawFrame(frames[i], y, h < currentRootLevel, h);
    }
  }

  if (highlightPattern != null) {
    matchContainer.style.display = 'inherit';
    matchedLabel.textContent = pct(totalMatched(), currentRootFrame.width);
  } else
    matchContainer.style.display = 'none';
  console.log("[clj-async-profiler] Total frames rendered:", totalRenderedFrames);
  console.timeEnd("[clj-async-profiler] Render");
}

function round2dig(n) {
  return Math.round(n * 100) / 100;
}

function ratioToPct(n) {
  return ((n > 0) ? "+" : "") + (n * 100).toFixed(2) + "%";
}

function findFrame(frames, x) {
  let left = 0;
  let right = frames.length - 1;

  while (left <= right) {
    const mid = (left + right) >>> 1;
    const f = frames[mid];

    if (f.left > x) {
      right = mid - 1;
    } else if (f.left + f.width <= x) {
      left = mid + 1;
    } else {
      return f;
    }
  }

  if (frames[left] && (frames[left].left - x) * px < 0.5) return frames[left];
  if (frames[right] && (x - (frames[right].left + frames[right].width)) * px < 0.5) return frames[right];

  return null;
}

function samples(n, add_plus, bold) {
  let num = (add_plus && n > 0 ? "+" : "") + n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  if (bold) num = '<b>' + num + '</b>';
  return num + (n === 1 ? ' sample' : ' samples');
}

function pct(a, b) {
  let val = a >= b ? '100' : (100 * a / b).toFixed(2);
  return val + '%';
}

function bold(t) {
  return '<b>' + t + '</b>';
}

function canvasOnMouseMove(e) {
  const h = Math.floor((reverseGraph ? event.offsetY : (canvasHeight - event.offsetY)) / 16);
  if (!ctxMenu.shown && h >= 0 && h < levels.length) {
    const f = findFrame(levels[h], event.offsetX / px + currentRootFrame.left);
    var earlyReturn = h === currentLevelUnderCursor && f === currentFrameUnderCursor;
    currentLevelUnderCursor = h;
    currentFrameUnderCursor = f;
    if (f && f.width >= minSamplesToShow) {
      hl.style.left = (Math.max(f.left - currentRootFrame.left, 0) * px + canvas.offsetLeft) + 'px';
      hl.style.width = (Math.min(f.width, currentRootFrame.width) * px) + 'px';
      hl.style.top = ((reverseGraph ? h * 16 : canvasHeight - (h + 1) * 16) + canvas.offsetTop) + 'px';
      hl.style.display = 'block';

      if (earlyReturn) return;

      tooltipFrameSpan.textContent = f.title;

      tooltip.style.display = 'block';
      tooltip.style.animation = 'none';
      tooltip.offsetHeight; /* trigger reflow */
      tooltip.style.animation = null;

      var hoverTipText;
      if (isDiffgraph) {
        var rel_change = (f.total_samples_a == 0) ? 1.0 : f.total_delta / f.total_samples_a;
        var total_change = f.total_delta / tree.total_samples_a;
        tooltipSamplesSpan.innerHTML = samples(f.total_delta, true, true);
        tooltipPctSelfSpan.innerHTML = bold(ratioToPct(rel_change)) + ' self';
        tooltipPctTotalSpan.innerHTML = bold(ratioToPct(total_change)) + ' total';
        hoverTipText = `${f.title}\n(${samples(f.total_delta, true)}, ${ratioToPct(rel_change)} self, ${ratioToPct(total_change)} total)`;
        // , self_samples_a: ${f.self_samples_a}, self_samples_b: ${f.self_samples_b},  self_delta: ${f.self_delta},  total_samples_a: ${f.total_samples_a},  total_samples_b: ${f.total_samples_b}, total_delta: ${f.total_delta})`;
      } else {
        tooltipSamplesSpan.innerHTML = samples(f.width, false, true);
        tooltipPctSelfSpan.innerHTML = bold(pct(f.width, levels[0][0].width));
        hoverTipText = f.title + '\n(' + samples(f.width) + ', ' + pct(f.width, levels[0][0].width) + ')';
      }

      let cursor_x  = e.clientX + window.pageXOffset;
      let cursor_y  = e.clientY + window.pageYOffset;
      let tooltip_w = tooltip.clientWidth;
      let tooltip_h = tooltip.clientHeight;
      var tooltip_x = cursor_x;
      var tooltip_y = cursor_y + 20; // Below cursor

      if (cursor_x + tooltip_w + 20 > canvas.clientWidth) {
        tooltip_x = cursor_x - tooltip_w;
      }

      if (e.clientY + tooltip_h + 20 > window.innerHeight - statusBox.clientHeight) {
        tooltip_y = cursor_y - tooltip_h;
      }

      // console.log("e.clientY", e.clientY, "cursor_y", cursor_y, "window.innerHeight", window.innerHeight, "tooltip_h", tooltip_h, "tooltip_y", tooltip_y);

      tooltip.style.left = tooltip_x;
      tooltip.style.top = tooltip_y;

      canvas.style.cursor = 'pointer';
      hoverTip.textContent = 'Function: ' + hoverTipText;
      return;
    }
  }
  canvas.onmouseout();
}

function canvasOnClick() {
  if (currentFrameUnderCursor && currentFrameUnderCursor != currentRootFrame) {
    render(currentFrameUnderCursor, currentLevelUnderCursor);
    hl.style.display = 'none';
    canvas.onmousemove();
  }
}

function canvasOnMouseOut() {
  hl.style.display = 'none';
  tooltip.style.display = 'none';
  hoverTip.textContent = '\xa0';
  canvas.title = '';
  canvas.style.cursor = '';
  currentLevelUnderCursor = -1;
  currentFrameUnderCursor = null;
}

//// Configuration panel

function showToast(message) {
  toast.textContent = message;
  // Trigger reflow to restart animation
  toast.classList.remove('show');
  void toast.offsetWidth;
  toast.classList.add('show');
}

function performSlowAction(action) {
  spinner.style.display = 'inline-block';
  setTimeout(function() {
    action();
    spinner.style.display = 'none';
  }, 20);
}

function userTransformsSwap(idx1, idx2) {
  const swap = userTransforms[idx1];
  userTransforms[idx1] = userTransforms[idx2];
  userTransforms[idx2] = swap;
}

function addNewTransformParameterized(type, what, replacement) {
  syncTransformsModelWithUI();
  userTransforms.push(_makeTransform(type, true, what, replacement));
  redrawTransformsSection();
}

function addNewTransform(type) {
  addNewTransformParameterized(type, "", "");
}

function onTransformsChanged() {
  redrawTransformsSection();
  performSlowAction(fullRedraw);
}

function onTransformsChangedDontRecreate() {
  syncTransformsModelWithUI();
  performSlowAction(fullRedraw);
}

function deleteTransform(originator) {
  syncTransformsModelWithUI();
  userTransforms.splice(originator.internalId, 1);
  onTransformsChanged();
}

function moveTransformUp(originator) {
  const idx = originator.internalId;
  if (idx == 0) return;
  syncTransformsModelWithUI();
  userTransformsSwap(idx-1, idx);
  onTransformsChanged();
}

function oneByClass(container, classname) {
  return container.getElementsByClassName(classname)[0];
}

function syncTransformsModelWithUI() {
  userTransforms = [];
  for (var i = 0; i < transformsContainer.children.length; i++) {
    let el = transformsContainer.children[i];
    let transformType = el.transformType;
    userTransforms.push(
      _makeTransform(transformType, oneByClass(el, 'chkEnabled').checked,
                     oneByClass(el, 'what').value,
                     transformType == 'replace' ? oneByClass(el, 'replacement').value : null));
  }
}

function redrawTransformsSection() {
  transformsContainer.innerHTML = "";
  for (var i = 0; i < userTransforms.length; i++) {
    const transform = userTransforms[i];
    var newEl = (transform.type == 'replace') ?
        transformReplaceTemplate.cloneNode(true) :
        transformFilterTemplate.cloneNode(true);
    newEl.transformType = transform.type;
    newEl.style = '';
    newEl.internalId = i;
    if (i % 2 == 1)
      newEl.classList.add("xform-odd-block");

    const what = transform.what;
    const whatInput = oneByClass(newEl, 'what');
    if (typeof(what) == 'string')
      whatInput.value = what;
    else
      whatInput.value = what.toString().match(/^(\/.+\/)g?$/)[1];
    addOnEnter(whatInput, onTransformsChangedDontRecreate);

    if (transform.type == 'replace') {
      oneByClass(newEl, 'replacement').value = transform.replacement;
      addOnEnter(oneByClass(newEl, 'replacement'), onTransformsChangedDontRecreate);
    } else if (transform.type == 'remove')
      oneByClass(newEl, 'label').textContent = "Remove";
    oneByClass(newEl, 'chkEnabled').checked = transform.enabled;

    oneByClass(newEl, 'chkEnabled').internalId = i;
    oneByClass(newEl, 'btnMoveUp').internalId = i;
    oneByClass(newEl, 'btnDelete').internalId = i;
    transformsContainer.appendChild(newEl);
  }
}

function smallbarHighlightButtonOnClick() {
  if (highlightState.pattern == null) {
    let userInput = window.prompt("Search string or /regex/", highlightState.lastPatternAsText || "");
    if (userInput != null && userInput != "") {
      applyHighlight(userInput, true);
    }
  } else {
    clearHighlight();
  }
}

function highlightButtonOnClick() {
  let pattern = highlightState.pattern;
  let inputVal = highlightInput.value;
  if (pattern == null) {
    if (inputVal == "") {
      highlightInput.focus();
    } else {
      applyHighlight(inputVal);
    }
  } else {
    if (inputVal == "" || inputVal == highlightState.lastPatternAsText) {
      clearHighlight();
    } else {
      applyHighlight(inputVal);
    }
  }
}

function addOnEnter(obj, f) {
  obj.addEventListener("keypress", function(event) {
    if (event.key === "Enter") {
      event.preventDefault();
      f();
    }
  });
}

addOnEnter(highlightInput, highlightButtonOnClick);

function scrollToTopOrBottom() {
  window.scrollTo(0, reverseGraph ? 0 : document.body.scrollHeight);
}

function setReverseGraph(newReverse) {
  reverseGraph = newReverse;
  if (reverseGraph) {
    inverseButton1.classList.add("inversed");
    inverseButton2.classList.add("inversed");
  } else {
    inverseButton1.classList.remove("inversed");
    inverseButton2.classList.remove("inversed");
  }
}

function inverseOnClick() {
  setReverseGraph(!reverseGraph);
  performSlowAction(function() { fullRedraw(true); });
}

function sortRadioChange() {
  performSlowAction(fullRedraw);
}

function fullRedraw(doScroll) {
  console.time("[clj-async-profiler] Full redraw");
  syncTransformsModelWithUI();
  recomputeDataModel();
  reinitCanvas();
  render();
  if (doScroll) scrollToTopOrBottom();
  console.timeEnd("[clj-async-profiler] Full redraw");
}

function setSidebarVisibility(isVisible) {
  sidebarVisible = isVisible;
  updateSidebarState();
  reinitCanvas();
  render(currentRootFrame, currentRootLevel);
}

// Context menu implementation was taken from https://github.com/heapoverride/context-js
// and modified to suit our needs. Licensed Under MIT License, author: @UnrealSec
class ContextMenu {
  constructor(container, items) {
    this.container = container;
    this.dom = null;
    this.shown = false;
    this.root = true;
    this.items = items;
    this._onclick = e => {
      if (this.dom && e.target != this.dom &&
          e.target.parentElement != this.dom &&
          !e.target.classList.contains('item') &&
          !e.target.parentElement.classList.contains('item')) {
        this.hide();
      }
    };
    this._oncontextmenu = e => {
      if (e.target != this.dom &&
          e.target.parentElement != this.dom &&
          !e.target.classList.contains('item') &&
          !e.target.parentElement.classList.contains('item') &&
          currentFrameUnderCursor) {
        e.preventDefault();
        tooltipRightClickHint.style.display = 'none';
        this.hide();
        this.frame = currentFrameUnderCursor;
        this.show(e.clientX, e.clientY);
      }
    };
    this._oncontextmenu_keydown = e => {
      if (e.keyCode == 27) {
        this.hideAll();
        return;
      }
      if (e.keyCode != 93) return;
      e.preventDefault();

      this.hideAll();
      this.show(e.clientX, e.clientY);
    };


    this._onblur = e => { this.hide(); };
  }
  getMenuDom() {
    const menu = document.createElement('div');
    menu.classList.add('context');
    for (const item of this.items)
      menu.appendChild(this.itemToDomEl(item));
    return menu;
  }
  itemToDomEl(data) {
    const item = document.createElement('div');
    if (data === null) {
      item.classList = 'separator';
      return item;
    }
    if (data.hasOwnProperty('color') && /^#([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(data.color.toString())) {
      item.style.cssText = `color: ${data.color}`;
    }
    item.classList.add('item');

    const label = document.createElement('span');
    label.classList = 'label';
    label.innerText = data.hasOwnProperty('text') ? data['text'].toString() : '';
    item.appendChild(label);

    if (data.hasOwnProperty('disabled') && data['disabled'])
      item.classList.add('disabled');
    else
      item.classList.add('enabled');

    item.addEventListener('click', e => {
      if (item.classList.contains('disabled')) return;
      if (data.hasOwnProperty('onclick') && typeof data['onclick'] === 'function') {
        const event = {handled: false, item: item, label: label, items: this.items, data: data};
        data['onclick'](event, this.frame);
        if (!event.handled)
          this.hide();
      } else {
        this.hide();
      }
    });
    return item;
  }
  hide() {
    if (this.dom && this.shown) {
      this.shown = false;
      this.container.removeChild(this.dom);
    }
  }
  show(x, y) {
    this.dom = this.getMenuDom();
    this.dom.style.visibility = 'hidden';
    this.dom.style.left = `${x}px`;
    this.dom.style.top = `${y}px`;
    this.shown = true;
    this.container.appendChild(this.dom);

    if (this.dom.offsetWidth + x > document.body.clientWidth)
      this.dom.style.left = `${x-this.dom.offsetWidth}px`;
    if (this.dom.offsetHeight + y > document.body.clientHeight)
      this.dom.style.top = `${y-this.dom.offsetHeight}px`;
    this.dom.style.visibility = 'visible';
  }

  install() {
    this.container.addEventListener('contextmenu', this._oncontextmenu, false);
    this.container.addEventListener('keydown', this._oncontextmenu_keydown);
    this.container.addEventListener('click', this._onclick);
    document.addEventListener('keydown', (event) => {
      if (event.keyCode == 27) this.hide();
    });
    window.addEventListener('blur', this._onblur);
  }
}

function escapeRegex(regexString) {
  return regexString.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function menuHighlight(e, frame) {
  let rx = escapeRegex(frame.title);
  applyHighlight("/^" + rx + "$/", true);
}

function menuCopyAsText(e, frame) {
  navigator.clipboard.writeText(frame.title);
}

function menuCopyAsRegex(e, frame) {
  let rx = escapeRegex(frame.title);
  navigator.clipboard.writeText(rx);
}

function menuFilterContaining(e, frame) {
  addNewTransformParameterized('filter', ";" + frame.title + ";", "");
  onTransformsChanged();
}

function menuRemoveContaining(e, frame) {
  addNewTransformParameterized('remove', ";" + frame.title + ";", "");
  onTransformsChanged();
}

function menuHideFramesAbove(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/(;" + rx + ";).*/", "$1");
  onTransformsChanged();
}

function menuHideFramesBelow(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/.+(" + rx + ";)/", ";$1");
  onTransformsChanged();
}

function menuCollapseRecursive(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/;(" + rx + ";)+/", ";$1");
  onTransformsChanged();
}

function menuCollapseRecursiveWithGaps(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/;(" + rx + ";).*\\1/", ";$1");
  onTransformsChanged();
}

const ctxMenuData = [
  {text: 'Highlight', onclick: menuHighlight },
  {text: 'Copy as text', onclick: menuCopyAsText },
  {text: 'Copy as regex', onclick: menuCopyAsRegex },
  null,
  {text: 'Filter containing stacks', onclick: menuFilterContaining },
  {text: 'Remove containing stacks', onclick: menuRemoveContaining },
  {text: 'Hide frames above', onclick: menuHideFramesAbove },
  {text: 'Hide frames below', onclick: menuHideFramesBelow },
  null,
  {text: 'Collapse recursive', onclick: menuCollapseRecursive },
  {text: 'Collapse recursive (with gaps)', onclick: menuCollapseRecursiveWithGaps},
];

const ctxMenu = new ContextMenu(document.getElementById('canvasDiv'), ctxMenuData);
ctxMenu.install();

//// "Data" is inserted here

console.time("[clj-async-profiler] Data load/eval");

var idToFrame = [<<<idToFrame>>>];

<<<stacks>>>

console.timeEnd("[clj-async-profiler] Data load/eval");

performSlowAction(async function() {
  if (queryPackedConfig != null)
    applyConfig(await unpackConfig(queryPackedConfig));
  else if (bakedPackedConfig != null)
    applyConfig(await unpackConfig(bakedPackedConfig));

  redrawTransformsSection();
  fullRedraw(true);
  sidebar.style.visibility = 'visible';
  smallbar.style.visibility = 'visible';
});
