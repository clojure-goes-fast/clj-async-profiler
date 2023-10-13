// This file was derived from flamegraph.html from the project
// https://github.com/jvm-profiling-tools/async-profiler
// Licensed under the Apache License, Version 2.0. Copyright 2020 Andrei Pangin

/// Constants
const canvas = document.getElementById('canvas');
const c = canvas.getContext('2d');
const hl = document.getElementById('hl');
const status = document.getElementById('status');
const matchContainer = document.getElementById('match');
const transformFilterTemplate = document.getElementById('transformFilterTemplate');
const transformReplaceTemplate = document.getElementById('transformReplaceTemplate');
const sidebarToggleButton = document.getElementsByClassName('sidebarToggle')[0];
const sidebarWidth = document.getElementsByClassName('configCol')[0].offsetWidth
const qString = new URLSearchParams(window.location.search)

var sidebarVisible = true;
var canvasWidth;

if (qString.get('hide-sidebar') == 'true') {
  sidebarVisible = false;
}

function updateSidebarState() {
  let style = oneByClass(document, 'configCol').style
  if (sidebarVisible) {
    style.display = 'block';
    sidebarToggleButton.innerText = ">";
    canvasWidth = window.innerWidth - sidebarWidth - 36;
  } else {
    style.display = 'none';
    sidebarToggleButton.innerText = "<";
    canvasWidth = window.innerWidth - 36;
  }
}

updateSidebarState();

var graphTitle = "<<<graphTitle>>>";
var isDiffgraph = <<<isDiffgraph>>>;
var normalizeDiff = false, b_scale_factor;
var reverseGraph = false;
var idToFrame = [<<<idToFrame>>>];
var initialStacks = [];
var stacks;

var _lastInsertedStack = null;

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
    return new RegExp(parsed[1]);
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

function _makeTransform(type, enabled, what, replacement) {
  let what2 = (typeof(what) == 'string') ? _stringToMaybeRegex(what) : what;
  let what2Str = what2.toString();
  let prefix = (what2 instanceof RegExp) ?
      _extractRegexPrefix(what2Str) : null;
  if (type == 'replace')
    return { type: type, enabled: enabled, what: what2, replacement: replacement, prefix: prefix}
  else
    return { type: type, enabled: enabled, what: what2}
}

var userTransforms = [<<<userTransforms>>>];

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
  console.time("transformStacks");
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

      xformedStr = xformedStr.substring(1,xformedStr.length-1);

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

  console.timeEnd("transformStacks");
  return result;
}

console.time("data exec time");

  <<<stacks>>>

console.timeEnd("data exec time");


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
  console.time("parseStacksToTreeSimple");
  var depth = 0;
  for (var i = 0, len = stacks.length; i < len; i++) {
    var stack = stacks[i];
    var stackframes = stack.stackStr.split(";");
    var stackLen = stackframes.length;
    depth = Math.max(depth, stackLen);
    var node = treeRoot;
    if (reverseGraph) {
      for (var j = stackLen-1; j >= 0; j--) {
        var stackframe = stackframes[j];
        node.total += stack.samples;
        node = getChildNode(node, stackframe);
      }
    } else {
      for (var j = 0; j < stackLen; j++) {
        var stackframe = stackframes[j];
        node.total += stack.samples;
        node = getChildNode(node, stackframe);
      }
    }
    node.total += stack.samples;
    node.self += stack.samples;
  }
  console.timeEnd("parseStacksToTreeSimple");
  return depth;
}

function parseStacksToTreeDiffgraph(stacks, treeRoot) {
  console.time("parseStacksToTreeDiffgraph");
  var depth = 0;

  for (var i = 0, len = stacks.length; i < len; i++) {
    var stack = stacks[i];
    var stackframes = stack.stackStr.split(";");
    var stackLen = stackframes.length;
    depth = Math.max(depth, stackLen);
    var node = treeRoot;

    var samplesA = stack.samples_a;
    var samplesB = stack.samples_b;
    if (normalizeDiff) samplesB = Math.round(samplesB * b_scale_factor);
    var delta = samplesB - samplesA;


    if (reverseGraph) {
      for (var j = stackLen-1; j >= 0; j--) {
        var stackframe = stackframes[j];
        node.total_samples_a += samplesA;
        node.total_samples_b += samplesB;
        node.total_delta += delta;
        node.delta_abs += Math.abs(delta);
        node = getChildNode(node, stackframe);
      }
    } else {
      for (var j = 0; j < stackLen; j++) {
        var stackframe = stackframes[j];
        node.total_samples_a += samplesA;
        node.total_samples_b += samplesB;
        node.total_delta += delta;
        node.delta_abs += Math.abs(delta);
        node = getChildNode(node, stackframe);
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
  console.timeEnd("parseStacksToTreeDiffgraph");
  return depth;
}

function parseStacksToTree(stacks, treeRoot) {
  if (isDiffgraph)
    return parseStacksToTreeDiffgraph(stacks, treeRoot);
  else
    return parseStacksToTreeSimple(stacks, treeRoot);
}

const palette = {
  green: "#50e150",
  aqua: "#50bebe",
  orange: "#e17d00",
  yellow: "#c8c83c",
  red: "#e15a5a",
  clojure_green: "#91dc51",
  clojure_blue: "#8fb5fe",
};

function getColor(title) {
  if (title.endsWith("_[j]")) {
    return palette.green;
  } else if (title.endsWith("_[i]")) {
    return palette.aqua;
  } else if (title.endsWith("_[k]")) {
    return palette.orange;
  } else if (title.includes("::") || title.startsWith("-[") || title.startsWith("+[")) {
    return palette.yellow;
  } else if (title.includes("/")) { // Clojure (will only work after unmunging)
    return palette.clojure_blue;
  } else if (title.includes(".")) { // Java (if it has a dot and is not Clojure)
    return palette.clojure_green;
  } else return palette.red;
}

function decToHex(n) {
  var hex = n.toString(16);
  return hex.length == 1 ? "0" + hex : hex;
}

function getDiffColor(isRed, intensity) {
  return "hsl(" + ((isRed) ? 0 : 220) + ",100%," + Math.round(90 - intensity * 30) + "%)";
  // return "hsl(" + ((isRed) ? 0 : 220) + "," + Math.round(100 * intensity) + "%, 60%)";
}

function scaleColorMap(colorMap, intensity) {
  return '#' + decToHex(intensity * colorMap.red) +
    decToHex(intensity * colorMap.green) + decToHex(intensity * colorMap.blue);
}

var stacks, tree, levels, depth;

var smallestPixelsPerSample, minPixelsPerFrame = 0.25, minSamplesToShow;

function generateLevelsSimple(levels, node, title, level, x, minSamplesToShow) {
  var left = x;

  levels[level] = levels[level] || [];
  if (node.total >= minSamplesToShow) {
    levels[level].push({left: left, width: node.total, color: getColor(title),
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
      generateLevelsSimple(levels, child, title, level+1, left, minSamplesToShow);
      left += child.total;
    }
  }
}

function generateLevelsDiffgraph(levels, node, title, level, x, minSamplesToShow) {
  var left = x;

  levels[level] = levels[level] || [];
  if (node.delta_abs >= minSamplesToShow) {
    var change = (node.total_samples_a == 0) ? 1.0 : node.total_delta / node.total_samples_a;
    var color = getDiffColor((node.total_delta > 0), Math.min(Math.abs(change), 1.0));
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
      generateLevelsDiffgraph(levels, child, title, level+1, left, minSamplesToShow);
      left += child.delta_abs;
    }
  }
}

function generateLevels(levels, node, title, level, x, minSamplesToShow) {
  if (isDiffgraph)
    generateLevelsDiffgraph(levels, node, title, level, x, minSamplesToShow);
  else
    generateLevelsSimple(levels, node, title, level, x, minSamplesToShow);
}

function refreshData() {
  if (isDiffgraph && normalizeDiff)
    b_scale_factor = totalSamplesA / totalSamplesB;

  stacks = transformStacks();

  tree = makeTreeNode();

  depth = parseStacksToTree(stacks, tree);
  smallestPixelsPerSample = canvasWidth / (tree.total || tree.delta_abs);
  minSamplesToShow = minPixelsPerFrame / smallestPixelsPerSample;

  levels = [];
  generateLevels(levels, tree, "all", 0, 0, minSamplesToShow);
  depth = levels.length;
}

refreshData();

var canvasHeight;

function initCanvas() {
  canvasHeight = (depth + 1) * 16;
  canvas.style.width = canvasWidth + 'px';
  canvas.width = canvasWidth * (devicePixelRatio || 1);
  canvas.height = canvasHeight * (devicePixelRatio || 1);
  if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
  c.font = document.body.style.font;
}

initCanvas();
isNormalizedDiv.style.display = isDiffgraph ? 'inherit' : 'none';
if (graphTitle == "")
  titleDiv.style.display = 'none';
else
  graphTitleSpan.innerText = graphTitle;


var highlightPattern = null, currentRootFrame, currentRootLevel, currentFrameUnderCursor, currentLevelUnderCursor, px;

function render(newRootFrame, newLevel) {
  console.time("render");
  // Background
  var gradient = c.createLinearGradient(0, 0, 0, canvasHeight);
  gradient.addColorStop(0.05, "#eeeeee");
  gradient.addColorStop(0.95, "#eeeeb0");
  c.fillStyle = gradient;
  c.fillRect(0, 0, canvasWidth, canvasHeight);

  currentRootFrame = newRootFrame || levels[0][0];
  currentRootLevel = newLevel || 0;
  px = canvasWidth / currentRootFrame.width;

  const marked = [];

  function mark(f) {
    return marked[f.left] >= f.width || (marked[f.left] = f.width);
  }

  function totalMarked() {
    let total = 0;
    let left = 0;
    for (let x in marked) {
      if (+x >= left) {
        total += marked[x];
        left = +x + marked[x];
      }
    }
    return total;
  }

  const x0 = currentRootFrame.left;
  const x1 = x0 + currentRootFrame.width;

  function drawFrame(f, y, alpha) {
    if (f.left < x1 && f.left + f.width > x0) {
      c.fillStyle = highlightPattern && f.title.match(highlightPattern) && mark(f) ? '#ee00ee' : f.color;
      c.fillRect((f.left - x0) * px, y, f.width * px, 15);

      if (f.width * px >= 21) {
        const chars = Math.floor(f.width * px / 7);
        const title = f.title.length <= chars ? f.title : f.title.substring(0, chars - 2) + '..';
        c.fillStyle = '#000000';
        c.fillText(title, Math.max(f.left - x0, 0) * px + 3, y + 12, f.width * px - 6);
      }

      if (alpha) {
        c.fillStyle = 'rgba(255, 255, 255, 0.5)';
        c.fillRect((f.left - x0) * px, y, f.width * px, 15);
      }
    }
  }

  for (let h = 0; h < levels.length; h++) {
    const y = reverseGraph ? h * 16 : canvasHeight - (h + 1) * 16;
    const frames = levels[h];
    for (let i = 0; i < frames.length; i++) {
      if (frames[i].width >= minSamplesToShow)
        drawFrame(frames[i], y, h < currentRootLevel);
    }
  }

  if (highlightPattern != null) {
    matchContainer.style.display = 'inherit';
    matchedLabel.textContent = pct(totalMarked(), currentRootFrame.width) + '%';
  } else
    matchContainer.style.display = 'none';
  console.timeEnd("render");
}

render();

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

canvas.onmousemove = function() {
  const h = Math.floor((reverseGraph ? event.offsetY : (canvasHeight - event.offsetY)) / 16);
  currentLevelUnderCursor = h;
  if (h >= 0 && h < levels.length) {
    const f = findFrame(levels[h], event.offsetX / px + currentRootFrame.left);
    currentFrameUnderCursor = f;
    if (f && f.width >= minSamplesToShow) {
      hl.style.left = (Math.max(f.left - currentRootFrame.left, 0) * px + canvas.offsetLeft) + 'px';
      hl.style.width = (Math.min(f.width, currentRootFrame.width) * px) + 'px';
      hl.style.top = ((reverseGraph ? h * 16 : canvasHeight - (h + 1) * 16) + canvas.offsetTop) + 'px';
      // hl.firstChild.textContent = f.title;
      hl.style.display = 'block';
      if (isDiffgraph) {
        var rel_change = (f.total_samples_a == 0) ? 1.0 : f.total_delta / f.total_samples_a;
        var total_change = f.total_delta / tree.total_samples_a;
        canvas.title = `${f.title}\n(${samples(f.total_delta, true)}, ${ratioToPct(rel_change)} self, ${ratioToPct(total_change)} total)`;
        // , self_samples_a: ${f.self_samples_a}, self_samples_b: ${f.self_samples_b},  self_delta: ${f.self_delta},  total_samples_a: ${f.total_samples_a},  total_samples_b: ${f.total_samples_b}, total_delta: ${f.total_delta})`;
      } else
        canvas.title = f.title + '\n(' + samples(f.width) + ', ' + pct(f.width, levels[0][0].width) + '%)';
      canvas.style.cursor = 'pointer';
      status.textContent = 'Function: ' + canvas.title;
      status.style['max-width'] = (canvas.offsetWidth - 150) + 'px';
      return;
    }
  }
  canvas.onmouseout();
}

canvas.onclick = function () {
  if (currentFrameUnderCursor && currentFrameUnderCursor != currentRootFrame) {
    render(currentFrameUnderCursor, currentLevelUnderCursor);
    canvas.onmousemove();
  }
}

canvas.onmouseout = function() {
  hl.style.display = 'none';
  status.textContent = '\xa0';
  canvas.title = '';
  canvas.style.cursor = '';
  currentLevelUnderCursor = -1;
  currentFrameUnderCursor = null;
}

function samples(n, add_plus) {
  return (add_plus && n > 0 ? "+" : "") + (n === 1 ? '1 sample' : n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')) + ' samples';
}

function pct(a, b) {
  return a >= b ? '100' : (100 * a / b).toFixed(2);
}

//// Configuration panel

function highlightApply() {
  const pattern = highlightInput.value;
  highlightPattern = (pattern == "") ? null : _stringToMaybeRegex(pattern);
  render(currentRootFrame, currentRootLevel);
}

function highlightClear() {
  highlightPattern = null;
  render(currentRootFrame, currentRootLevel);
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

function addNewTransform() {
  addNewTransformParameterized(newTransformType.value, "", "");
}

function deleteTransform(originator) {
  syncTransformsModelWithUI();
  userTransforms.splice(originator.internalId, 1);
  redrawTransformsSection();
}

function cloneTransform(originator) {
  syncTransformsModelWithUI();
  const idx = originator.internalId;
  userTransforms.splice(idx+1, 0, Object.assign({}, userTransforms[idx]));
  redrawTransformsSection();
}

function moveTransformUp(originator) {
  const idx = originator.internalId;
  if (idx == 0) return;
  syncTransformsModelWithUI();
  userTransformsSwap(idx-1, idx);
  redrawTransformsSection();
}

function moveTransformDown(originator) {
  const idx = originator.internalId;
  if (idx == userTransforms.length-1) return;
  syncTransformsModelWithUI();
  userTransformsSwap(idx, idx+1);
  redrawTransformsSection();
}

function refreshAfterEnabledToggle() {
  syncTransformsModelWithUI();
  redrawTransformsSection();
}

function oneByClass(container, classname) {
  return container.getElementsByClassName(classname)[0];
}

function syncTransformsModelWithUI() {
  for (var i = 0; i < transformsContainer.children.length; i++) {
    const el = transformsContainer.children[i];
    const model = userTransforms[i];
    userTransforms[i] =
      _makeTransform(model.type, oneByClass(el, 'chkEnabled').checked,
                     oneByClass(el, 'what').value,
                     model.type == 'replace' ? oneByClass(el, 'replacement').value : null);
  }
}

function redrawTransformsSection() {
  transformsContainer.innerHTML = "";
  for (var i = 0; i < userTransforms.length; i++) {
    const transform = userTransforms[i];
    var newEl = (transform.type == 'replace') ?
        transformReplaceTemplate.cloneNode(true) :
        transformFilterTemplate.cloneNode(true);
    newEl.style = '';
    newEl.internalId = i;

    const what = transform.what;
    if (typeof(what) == 'string')
      oneByClass(newEl, 'what').value = what;
    else
      oneByClass(newEl, 'what').value = what.toString().match(/^(\/.+\/)g?$/)[1];

    if (transform.type == 'replace')
      oneByClass(newEl, 'replacement').value = transform.replacement;
    else if (transform.type == 'remove')
      oneByClass(newEl, 'label').textContent = "Remove:";
    oneByClass(newEl, 'chkEnabled').checked = transform.enabled;

    oneByClass(newEl, 'chkEnabled').internalId = i;
    oneByClass(newEl, 'btnMoveUp').internalId = i;
    oneByClass(newEl, 'btnMoveDown').internalId = i;
    oneByClass(newEl, 'btnClone').internalId = i;
    oneByClass(newEl, 'btnDelete').internalId = i;
    transformsContainer.appendChild(newEl);
  }
}

redrawTransformsSection();

function scrollToTopOrBottom() {
  window.scrollTo(0, reverseGraph ? 0 : document.body.scrollHeight);
}

scrollToTopOrBottom();

function applyConfiguration() {
  console.time("apply config");
  minPixelsPerFrame = minFrameWidthInPx.value || 0.25;
  normalizeDiff = isNormalized.checked;
  let reverseChanged = (reverseGraph != isReversedInput.checked);
  reverseGraph = isReversedInput.checked;
  syncTransformsModelWithUI();
  refreshData();
  initCanvas();
  render();
  if (reverseChanged)
    scrollToTopOrBottom();
  console.timeEnd("apply config");
}

function toggleSidebarVisibility() {
  sidebarVisible = !sidebarVisible;
  updateSidebarState();
  applyConfiguration();
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
        this.hide();
        this.frame = currentFrameUnderCursor;
        this.show(e.clientX, e.clientY);
      }
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
    window.addEventListener('blur', this._onblur);
  }
}

function escapeRegex(regexString) {
  return regexString.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function menuHighlight(e, frame) {
  let rx = escapeRegex(frame.title);
  highlightInput.value = "/^" + rx + "$/";
  highlightApply();
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
  applyConfiguration();
}

function menuRemoveContaining(e, frame) {
  addNewTransformParameterized('remove', ";" + frame.title + ";", "");
  applyConfiguration();
}

function menuHideFramesAbove(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/(;" + rx + ";).*/", "$1");
  applyConfiguration();
}

function menuHideFramesBelow(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/.+(" + rx + ";)/", ";$1");
  applyConfiguration();
}

function menuCollapseRecursive(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/;(" + rx + ";)+/", ";$1");
  applyConfiguration();
}

function menuCollapseRecursiveWithGaps(e, frame) {
  let rx = escapeRegex(frame.title);
  addNewTransformParameterized('replace', "/;(" + rx + ";).*\\1/", ";$1");
  applyConfiguration();
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
