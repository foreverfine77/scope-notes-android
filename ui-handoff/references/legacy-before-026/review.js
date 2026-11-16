"use strict";

const D = window.UI_REVIEW_DATA;
const starterTokens = window.UI_STARTER_TOKENS;
const statusLabels = {pending: "待审核", approve: "通过", revise: "需要修改", drop: "取消"};
const storageKey = "scope-ui-review-" + D.snapshot;
const records = [
  ...D.pages.map(p => ({id: "page." + p.id, title: p.title, category: "页面", page: p.id,
    current: p.owner + " / " + p.sourceMethod, proposal: p.treatment, reason: "布局与阅读层级需要用户逐项确认。", priority: "中"})),
  ...D.actions.map(a => ({...a, id: "action." + a.id, category: "动作", current: "当前绘制方式：" + a.currentKind,
    proposal: a.presentation + "；" + a.placement, reason: a.behavior, priority: ["camera.freeze", "camera.resume", "device.search"].includes(a.id) ? "高" : "中"})),
  ...D.copy,
  ...D.edges.map(e => ({...e, category: "边角", current: e.detail, proposal: "", reason: e.detail}))
];
const byId = Object.fromEntries(records.map(r => [r.id, r]));
const actionById = Object.fromEntries(D.actions.map(a => [a.id, a]));
const pageById = Object.fromEntries(D.pages.map(p => [p.id, p]));
const requiredIcons = [...new Set(D.actions.map(a => a.icon).filter(Boolean))].sort();
const ui = {
  tab: "overview", page: "capture", variant: "live", selected: "edge.freeze",
  simulate: false, showSlots: true, copyMode: "current", freezePlacement: "tools",
  width: 390, height: 844, tokens: structuredClone(starterTokens),
  filter: {year: 0, month: 0, ear: "全部"}, profile: "示例使用人",
  tool: "circle", color: "#5DD9CF", marks: [], undone: [], filterText: "",
  query: "", status: "all", includeLegacy: false, includeRuntime: false, history: [], events: []
};
let decisions = {};
try {
  const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
  decisions = saved.decisions || {};
  ui.freezePlacement = saved.freezePlacement || "tools";
} catch (error) {
  notice("浏览器未能恢复本地审核记录。可以继续审核并导出 JSON。");
}

function esc(value) {
  return String(value == null ? "" : value).replace(/[&<>"']/g, c =>
    ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"})[c]);
}
function notice(message) {
  const box = document.getElementById("notice");
  box.textContent = message;
  box.hidden = !message;
}
function decision(id) {
  return decisions[id] || {id, status: "pending", proposal: byId[id]?.proposal || "", note: ""};
}
function persist() {
  try {
    localStorage.setItem(storageKey, JSON.stringify({decisions, freezePlacement: ui.freezePlacement}));
  } catch (error) {
    notice("本地保存不可用，请及时导出审核 JSON。");
  }
  updateProgress();
}
function updateProgress() {
  const count = records.filter(r => decision(r.id).status !== "pending").length;
  document.getElementById("progress").textContent = "已审 " + count + " / " + records.length + " 项";
  document.querySelectorAll("[data-status-for]").forEach(node => {
    node.textContent = statusLabels[decision(node.dataset.statusFor).status] || "待审核";
  });
}
function selectRecord(id) {
  if (!Object.hasOwn(byId, id)) return;
  ui.selected = id;
  renderInspector();
}
function renderInspector() {
  const r = byId[ui.selected];
  if (!r) return;
  const item = decision(r.id);
  const sources = (r.source || []).map(s => esc(s.path + ":" + s.line + " · " + s.method)).join("<br>");
  const translations = r.translations ? '<details><summary>英文与繁体对照</summary><pre>English\n' +
    esc(r.translations.en) + "\n\n繁體\n" + esc(r.translations["zh-TW"]) + "</pre></details>" : "";
  document.getElementById("inspector").innerHTML =
    '<span class="pill">' + esc(r.category) + '</span><h2>' + esc(r.title) + '</h2><code>' + esc(r.id) +
    '</code><p class="muted">' + esc(r.reason || r.detail || "") + '</p><label>当前</label><div class="current"><pre>' +
    esc(r.current || r.detail || "见页面骨架") + '</pre></div><label for="review-status">审核结论</label><select id="review-status">' +
    Object.entries(statusLabels).map(([value, label]) => '<option value="' + value + '"' + (item.status === value ? " selected" : "") + ">" + label + "</option>").join("") +
    '</select><label for="review-proposal">候选内容 / 你的替换方案</label><textarea id="review-proposal"></textarea>' +
    '<label for="review-note">你的意见</label><textarea id="review-note" placeholder="例如：移到工具栏首位；改成短句；这个动作保留文字"></textarea>' +
    '<p class="source">' + sources + '</p>' + translations +
    '<div class="toolbar"><button id="open-record-page">看对应页面</button><button id="next-record">下一个待审</button></div>';
  document.getElementById("review-proposal").value = item.proposal;
  document.getElementById("review-note").value = item.note;
  for (const [element, field] of [["review-status", "status"], ["review-proposal", "proposal"], ["review-note", "note"]]) {
    document.getElementById(element).addEventListener(field === "status" ? "change" : "input", event => {
      decisions[r.id] = {...decision(r.id), [field]: event.target.value};
      persist();
    });
  }
  document.getElementById("open-record-page").onclick = () => {
    ui.tab = "screens"; ui.page = r.page || "capture"; ui.variant = defaultVariant(ui.page); renderMain();
  };
  document.getElementById("next-record").onclick = () => {
    const start = records.findIndex(row => row.id === r.id);
    const next = [...records.slice(start + 1), ...records.slice(0, start)].find(row => decision(row.id).status === "pending");
    if (next) selectRecord(next.id);
    else notice("当前没有剩余待审项。");
  };
}
function T(key, values = []) {
  const r = byId["copy." + key];
  if (!r) return key;
  let text = ui.copyMode === "proposal" ? (decision(r.id).proposal || r.current) : r.current;
  text = text.replace(/%(\d+)\$[sd]/g, (_, n) => values[Number(n) - 1] ?? "示例");
  let index = 0;
  return text.replace(/%[sd]/g, () => values[index++] ?? "示例").replace(/%%/g, "%");
}
function svg(id) {
  return '<svg aria-hidden="true" viewBox="0 0 24 24"><use href="#' + esc(id) + '"></use></svg>';
}
function action(id, options = {}) {
  const a = actionById[id];
  if (!a) return "";
  const label = a.title;
  const cls = ["icon", options.selected ? "selected" : "", options.danger ? "danger" : "", options.extra || ""].join(" ");
  let artwork = svg(options.icon || a.icon);
  if (id === "camera.expand" || id === "camera.collapse") {
    const expand = id === "camera.expand";
    artwork = '<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
      (expand ? '<path d="M4 10V4h6M4 4l6 6m10 4v6h-6m6 0-6-6"/>' : '<path d="M4 4l6 6m0-6v6H4m16 10-6-6m0 6v-6h6"/>') + "</svg>";
  }
  const button = '<button class="' + cls + '" data-action="' + id + '" title="' + esc(label + " · " + id) +
    '" aria-label="' + esc(label) + '"' + (options.disabled ? " disabled" : "") + ">" + artwork + "</button>";
  return options.slot ? '<span class="icon-wrap">' + button + '<span class="slot-label">' + esc(id) + "</span></span>" : button;
}
function textAction(label, target, extra = "") {
  return '<button class="' + extra + '" data-go="' + target + '">' + esc(label) + "</button>";
}
function header(title, back = true) {
  return '<div class="p-header">' + (back ? action("nav.back") : "") + '<div class="grow"><strong>' + esc(title) + "</strong></div></div>";
}
function menu(label, target, detail = "") {
  return '<button class="menu-row" data-go="' + target + '"><span>' + esc(label) +
    '</span><span class="muted">' + esc(detail) + " ›</span></button>";
}
function screenHeader() {
  const available = !["disconnected", "connecting", "reconnecting", "error"].includes(ui.variant);
  return '<div class="p-header">' + textAction(ui.profile + " ▾", "profiles") + '<span class="grow"></span>' +
    action("settings.open") + '<span class="battery" data-review="edge.battery">' + (available ? '47%<br>约 32 分钟' : '—<br>等待设备读数') + '</span></div>';
}
function captureScreen() {
  const v = ui.variant;
  const idle = v === "connected-idle" || v === "macro-idle";
  const live = !idle && !["disconnected", "connecting", "reconnecting", "error"].includes(v);
  const frozen = v === "frozen";
  const macro = v.startsWith("macro") || (frozen && (ui.freezeBase || "").startsWith("macro"));
  const expanded = v === "macro-expanded" || (frozen && ui.freezeBase === "macro-expanded");
  const recording = v === "recording";
  const busy = v === "saving";
  const freezeId = frozen ? "camera.resume" : "camera.freeze";
  const freeze = action(freezeId, {disabled: !live || recording || busy, selected: frozen});
  const viewportFreeze = ui.freezePlacement === "current-top" ? '<div class="viewport-freeze current-top">' + freeze + "</div>" : "";
  let scene = '<span>' + (live ? (frozen ? "已定格的画面占位" : "实时画面占位") : esc(T(v === "error" ? "wifi_failed" : v === "disconnected" ? "disconnected" : "connecting"))) + "</span>";
  scene += '<span class="scene-caption">' + (live ? "640 × 480 · 示例画面" : esc(T("connect_hint"))) + "</span>";
  if (idle) scene = '<div class="lens-cover" data-review="edge.preview-session"><strong>已连接 · 尚未取景</strong><span>电量与预计时间仍可查看</span>' + action("camera.start-preview") + '<span>点开盖板，开始取景</span></div>';
  if (live) scene += action("camera.stop-preview", {extra: "cover-handle", disabled: recording || busy});
  if (macro) scene += action(expanded ? "camera.collapse" : "camera.expand", {extra: "zoom-act"});
  if (ui.freezePlacement === "viewport-left") scene += '<div class="viewport-freeze viewport-left">' + freeze + "</div>";
  if (recording) scene += '<span class="badge">● 00:12</span>';
  const tools = (ui.freezePlacement === "tools" ? action(freezeId, {slot: ui.showSlots, disabled: !live || recording || busy, selected: frozen}) : "") +
    action("camera.stabilize", {slot: ui.showSlots, disabled: !live || macro, selected: !!ui.stabilize}) +
    action("camera.mirror", {slot: ui.showSlots, disabled: !live, selected: !!ui.mirror}) +
    action("ear.mark", {slot: ui.showSlots, disabled: !live || macro,
      selected: !!ui.cameraEar && ui.cameraEar !== "未标记",
      icon: ui.cameraEar === "左耳" ? "ic-ear-left" : ui.cameraEar === "右耳" ? "ic-ear-right" : "ic-ear"});
  const link = live || idle ? "device.disconnect" : ["connecting", "reconnecting"].includes(v) ? "device.cancel" : "device.connect";
  const line = frozen ? T("frozen_status") : recording ? "● 正在录像 00:12" : busy ? T("saving") :
    live ? macro ? T("macro_hint") : T("recording_for", [ui.profile]) : "";
  return screenHeader() + '<div class="viewport">' + viewportFreeze + '<div class="scene ' + (macro ? "macro" : "circle") + '">' + scene +
    '</div></div><div class="tools">' + tools + '</div><p class="state-line">' + esc(line) +
    '</p><div class="capture-bar">' + action("gallery.open", {disabled: recording || busy}) +
    action("photo.capture", {disabled: !live || frozen || busy, extra: "shutter"}) +
    action(recording ? "video.stop" : "video.start", {disabled: !live || frozen || busy}) +
    '</div><div class="connect-bar">' + action("device.search") + action(link) + "</div>";
}
const sampleMedia = [
  {ear: "左耳", year: 2026, month: 11}, {ear: "右耳", year: 2026, month: 11},
  {ear: "未标记", year: 2026, month: 10}, {ear: "左耳", year: 2025, month: 12},
  {ear: "未标记", year: 2026, month: 11}, {ear: "右耳", year: 2026, month: 11}
];
function galleryScreen() {
  const filtered = sampleMedia.filter(m => (!ui.filter.year || m.year === ui.filter.year) &&
    (!ui.filter.month || m.month === ui.filter.month) && (ui.filter.ear === "全部" || m.ear === ui.filter.ear));
  const empty = ui.variant === "empty" || !filtered.length;
  const selected = ui.variant === "multiselect";
  const summary = [ui.filter.year || "", ui.filter.month ? ui.filter.month + "月" : "", ui.filter.ear === "全部" ? "" : ui.filter.ear].filter(Boolean).join(" · ");
  return '<div class="p-header">' + action("nav.back") + '<div class="grow">' + textAction(ui.profile + "的档案 ▾", "profiles") +
    '</div><button data-demo="select-gallery">' + (selected ? "完成" : "选择") + '</button></div><div class="p-row"><button class="grow selected">图片 ' +
    (empty ? 0 : filtered.length) + '</button><button class="grow" data-go="video">录像 2</button>' + action("gallery.filter") +
    '</div><div class="state-line">' + esc(summary) + '</div><div class="scroll">' +
    (empty ? '<p class="skeleton-note">' + esc(summary ? T("no_filter_results") : T("empty_photos")) + '</p>' :
      '<div class="p-section">示例影像</div><div class="p-grid">' + filtered.map((m, i) =>
        '<div class="p-thumb" data-go="photo"><div class="placeholder-photo">' + (selected ? "☑ " : "") + "示例 " + (i + 1) +
        "</div><small>" + esc(m.ear + " · " + m.year + "/" + m.month) + "</small></div>").join("") + "</div>") +
    '</div>' + (selected ? '<div class="selection-actions"><span class="grow">已选 2 项</span><button>全选</button>' + action("media.share") + action("media.delete", {danger: true}) + "</div>" : "");
}
function detailScreen(video) {
  const macro = ui.variant === "macro";
  return '<div class="p-header">' + action("nav.back") + '<strong class="grow">' + (video ? "录像" : ui.variant === "copy" ? "标注副本" : "照片") +
    "</strong>" + action("media.share") + '</div>' + (!video && !macro ? textAction("耳别 · 左耳", "ear-picker") : "") +
    '<div class="viewport"><div class="scene ' + (macro ? "macro" : "circle") + '"><span>' + (video ? "录像画面占位" : "照片占位") +
    "</span></div></div><p class='state-line'>2026-11-11 10:30</p>" +
    (video ? '<div class="p-row">' + action(ui.playing ? "playback.pause" : "playback.play") + '<input aria-label="播放进度" type="range" value="35"><span>00:12 / 00:34</span></div>' : '<div class="tools">' + action("photo.markup") + "</div>") +
    '<div class="tools">' + action("media.delete", {danger: true}) + "</div>";
}
function markupScreen() {
  const busy = ui.variant === "busy";
  return '<div class="tools" style="padding-top:12px">' +
    action("markup.circle", {selected: ui.tool === "circle", disabled: busy}) + action("markup.arrow", {selected: ui.tool === "arrow", disabled: busy}) +
    '<button class="icon" data-action="markup.color" title="颜色" aria-label="颜色"' + (busy ? " disabled" : "") + '><span class="swatch" style="background:' + ui.color + '"></span></button>' +
    action("markup.undo", {disabled: busy || !ui.marks.length}) + action("markup.redo", {disabled: busy || !ui.undone.length}) +
    '</div><div class="markup-stage" id="drawing-demo">' + (ui.marks.length ? '<span class="badge" style="position:absolute;top:12px;left:12px">演示笔迹 ' + ui.marks.length + "</span>" : "") +
    '</div><p class="state-line">' + esc(ui.variant === "error" ? T("markup_error") : T("markup_hint")) +
    '</p><div class="tools">' + action("markup.cancel", {disabled: busy}) + action("markup.save", {disabled: !ui.marks.length || busy}) + "</div>";
}
function sheet(title, body, footer = "") {
  return header("面板预览") + '<div class="scroll"></div><div class="sheet-panel"><h3>' + esc(title) + "</h3>" + body + footer + "</div>";
}
function filtersScreen() {
  return sheet(T("filter_title"), menu("年份", "year-picker", ui.filter.year || "全部") +
    menu("月份", "month-picker", ui.filter.month ? ui.filter.month + "月" : "全部") + menu("耳别", "ear-picker", ui.filter.ear),
    '<div class="p-row">' + textAction(T("clear_filters"), "filters-clear") + textAction(T("done"), "gallery", "primary") + "</div>");
}
function picker(title, values, field, back) {
  return sheet(title, values.map(([label, value]) => {
    const icon = field === "ear" && value !== "全部" ? svg(value === "左耳" ? "ic-ear-left" : value === "右耳" ? "ic-ear-right" : "ic-ear") : "";
    return '<button class="menu-row" data-choice="' + field + '" data-value="' + esc(value) +
      '"><span class="p-row">' + icon + esc(label) + '</span><span class="checked">' +
      (String(field === "ear" && ui.earContext !== "filters" ? ui.cameraEar || "未标记" : ui.filter[field]) === String(value) ? "✓" : "") + "</span></button>";
  }).join(""),
    textAction(T("cancel"), back));
}
function settingsScreen() {
  return header(T("settings")) + '<div class="scroll">' + menu(T("language_title"), "language") +
    menu(T("help"), "help") + menu(T("privacy"), "privacy") + menu(T("feedback"), "feedback") +
    '<p class="p-section">取景</p>' + menu(T("macro_mode"), "macro-confirm", ui.macroEnabled ? "开启" : "关闭") +
    '<p class="p-section">高级</p>' + menu(T("test_tools"), "diagnostics") +
    '<p class="skeleton-note">Scope Notes · 视觉骨架</p></div>';
}
function readingScreen(key, title) {
  return header(T(title)) + '<div class="scroll reading" data-review="copy.' + key + '">' + esc(T(key)) +
    '</div><div class="tools">' + textAction(T("close"), "settings") + "</div>";
}
function systemScreen() {
  return header(pageById[ui.page].title) + '<div class="system-surface"><strong>Android 系统绘制</strong><p>' +
    esc(pageById[ui.page].treatment) + '</p><p>此处只画交接边界。Claude 设计前置说明、等待、取消与返回后的界面。</p></div>' +
    textAction("返回 App 骨架", "capture");
}
function genericScreen() {
  switch (ui.page) {
    case "profiles":
      return sheet(T("profiles"), '<p class="skeleton-note">' + esc(T("profiles_hint")) + "</p>" +
        menu("示例使用人", "capture", "当前") + menu("示例使用人 B", "capture") +
        menu(T("profile_add_person"), "profile-edit") + menu(T("profile_edit_current"), "profile-edit"));
    case "profile-edit":
      return sheet(ui.variant === "edit" ? T("profile_edit") : T("profile_add"), '<label>昵称</label><input maxlength="24" placeholder="' +
        esc(T("nickname_hint")) + '" value="' + (ui.variant === "edit" ? "示例使用人" : "") + '"><p class="danger-banner">' +
        (ui.variant === "empty-error" ? esc(T("nickname_required")) : ui.variant === "duplicate-error" ? esc(T("nickname_duplicate")) : "最多 24 字符 · 键盘避让待验收") +
        '</p><div class="p-row">' + textAction(T("cancel"), "profiles") + textAction(T("save"), "profiles", "primary") + "</div>");
    case "language":
      return sheet(T("language_title"), ["跟随系统", "简体中文", "繁體中文", "English"].map(t => menu(t, "settings")).join(""));
    case "macro-confirm":
      return sheet(T("macro_mode"), '<p>' + esc(T("macro_explanation")) + '</p><div class="p-row">' +
        textAction(T("cancel"), "settings") + textAction(T("macro_enable"), "macro-enable", "primary") + "</div>");
    case "feedback":
      return header(T("feedback")) + '<div class="scroll"><p>当前实现打开 GitHub 个人主页。</p><p class="danger-banner">待审核是否改成项目 Issues。</p>' +
        menu(T("feedback_action"), "system-browser") + '<p class="reading">建议说明手机型号、系统版本、复现步骤。资料检查提示需要逐条审核。</p></div>';
    case "device-picker":
      return sheet(T("wifi_choose"), ui.variant === "scanning" ? '<p>' + esc(T("wifi_searching")) + "</p>" :
        ui.variant === "empty" ? '<p data-review="copy.wifi_no_devices">' + esc(T("wifi_no_devices")) + "</p>" :
          menu("示例耳镜 · 已记住", "system-wifi", "附近") + menu("示例耳镜 B", "system-wifi"),
        '<div class="p-row">' + textAction(T("wifi_rescan"), "device-picker") + textAction(T("wifi_settings"), "system-wifi") + textAction(T("cancel"), "capture") + "</div>");
    case "wifi-guide":
      return sheet(T("wireless_title"), '<p data-review="copy.wireless_prompt">' + esc(T("wireless_prompt")) +
        '</p><div class="p-row">' + textAction(T("wireless_settings"), "system-wifi") + textAction(T("continue_app"), "capture") + "</div>");
    case "wifi-permission":
      return sheet(T("wifi_discover"), '<p data-review="copy.wifi_permissions">' + esc(T("wifi_permissions")) +
        '</p><div class="p-row">' + textAction(T("allow_discovery"), "system-permission", "primary") + textAction(T("wifi_settings"), "system-wifi") + "</div>");
    case "delete-confirm":
      return sheet(T("delete_count", [2]), '<p data-review="copy.delete_warning">' + esc(T("delete_warning")) +
        '</p><div class="p-row">' + textAction(T("cancel"), "gallery") + textAction(T("delete"), "gallery", "danger") + "</div>");
    case "diagnostics":
      return header(T("test_tools")) + '<div class="scroll reading"><p>30 秒诊断 · 12 秒</p><p>' + esc(T("test_target")) + "</p><p>" +
        esc(T("test_rotate_off")) + "</p><p>" + esc(T("test_rotate_on")) + "</p><p>" + esc(T("test_hold")) +
        "</p></div>" + textAction(T("test_stop"), "diagnostic-result");
    case "diagnostic-result":
      return header(T("test_tools")) + '<div class="scroll reading">诊断结果占位\n连接、帧率、丢包、稳向来源\n技术细节属于此页</div>' +
        textAction(T("test_copy"), "settings");
    case "save-failure":
      return sheet("保存失败", '<p>' + esc(T("markup_error")) + "</p><p>原图仍在，标注草稿保留。</p>" +
        textAction("返回标注", "markup", "primary"));
    default:
      return systemScreen();
  }
}
function phoneBody() {
  const renderers = {
    capture: captureScreen, gallery: galleryScreen, photo: () => detailScreen(false),
    video: () => detailScreen(true), markup: markupScreen, filters: filtersScreen,
    settings: settingsScreen, help: () => readingScreen("help_body", "help"),
    privacy: () => readingScreen("privacy_body", "privacy"),
    "year-picker": () => picker("年份", [["全部", 0], ["2026", 2026], ["2025", 2025]], "year", "filters"),
    "month-picker": () => picker("月份", [["全部", 0], ...Array.from({length: 12}, (_, i) => [(i + 1) + "月", i + 1])], "month", "filters"),
    "ear-picker": () => picker("选择耳别", (ui.earContext === "filters" ? ["全部", "左耳", "右耳", "未标记"] : ["未标记", "左耳", "右耳"]).map(s => [s, s]), "ear", ui.earContext || "capture"),
    palette: () => sheet("标注颜色", '<div class="tools">' + ["#FF6545", "#FFC857", "#5DD9CF", "#F4F5F7"].map((color, i) =>
      '<button class="icon" data-color="' + color + '" title="' + ["朱红", "琥珀黄", "青色", "白色"][i] +
      '"><span class="swatch" style="background:' + color + '"></span></button>').join("") + "</div>")
  };
  return (renderers[ui.page] || genericScreen)();
}
function applyTokens() {
  const phone = document.getElementById("phone");
  if (!phone) return;
  const t = ui.tokens;
  Object.entries(t.colors).forEach(([key, value]) => phone.style.setProperty("--p-" + key, value));
  for (const [key, value] of Object.entries(t.radii)) phone.style.setProperty("--p-" + key + "-radius", value + "px");
  for (const [key, value] of Object.entries(t.sizes)) {
    const mapped = {touch: "touch", icon: "icon", gap: "gap", pagePadding: "pad"}[key];
    phone.style.setProperty("--p-" + mapped, value + "px");
  }
  for (const [key, value] of Object.entries(t.type)) phone.style.setProperty("--p-" + key, value + "px");
  phone.style.setProperty("--p-rotation", t.motion.rotationMs + "ms");
  phone.style.setProperty("--p-state", t.motion.stateMs + "ms");
  const e = t.effects;
  phone.style.setProperty("--p-background-fill", e.backgroundMode === "linear" ?
    "linear-gradient(" + e.gradientAngle + "deg," + t.colors.background + "," + e.gradientEnd + ")" : t.colors.background);
  const rgb = [1, 3, 5].map(i => parseInt(t.colors.surface.slice(i, i + 2), 16)).join(",");
  phone.style.setProperty("--p-control-fill", "rgba(" + rgb + "," + e.surfaceOpacity + ")");
  phone.style.setProperty("--p-border-width", e.borderWidth + "px");
  phone.style.setProperty("--p-control-shadow", "0 3px " + e.shadowBlur + "px rgba(0,0,0," + e.shadowOpacity + ")");
}
function fitScene() {
  document.querySelectorAll(".phone .viewport").forEach(viewport => {
    const scene = viewport.querySelector(".scene");
    if (!scene) return;
    const macro = scene.classList.contains("macro");
    const expanded = ui.variant === "macro-expanded" || (ui.variant === "frozen" && ui.freezeBase === "macro-expanded");
    const ratio = macro ? (expanded ? 3 / 4 : 4 / 3) : 1;
    const width = Math.max(0, Math.min(viewport.clientWidth - 12, (viewport.clientHeight - 12) * ratio));
    scene.style.width = width + "px";
    scene.style.height = width / ratio + "px";
  });
}
function renderPhone() {
  const phone = document.getElementById("phone");
  if (!phone) return;
  phone.className = "phone" + (ui.showSlots ? " show-slots" : "") + (ui.width > ui.height ? " landscape" : "");
  phone.style.width = ui.width + "px"; phone.style.height = ui.height + "px";
  phone.innerHTML = '<div class="safe"><span>10:30</span><span>' + (ui.copyMode === "proposal" ? "骨架 / 候选待审" : "骨架 / 当前文案") + '</span></div><div class="screen">' + phoneBody() +
    '</div><div class="home-safe"></div>';
  applyTokens();
  requestAnimationFrame(fitScene);
  phone.querySelectorAll("[data-action]").forEach(button => button.addEventListener("click", () => {
    selectRecord("action." + button.dataset.action);
    if (ui.simulate) dispatch(button.dataset.action);
  }));
  phone.querySelectorAll("[data-review]").forEach(node => node.addEventListener("click", () => selectRecord(node.dataset.review)));
  phone.querySelectorAll("[data-go]").forEach(button => button.addEventListener("click", () => {
    if (ui.simulate) go(button.dataset.go);
    else selectRecord("page." + (pageById[button.dataset.go] ? button.dataset.go : ui.page));
  }));
  phone.querySelectorAll("[data-choice]").forEach(button => button.addEventListener("click", () => {
    if (!ui.simulate) { selectRecord("page." + ui.page); return; }
    if (button.dataset.choice === "ear" && ui.earContext !== "filters") {
      ui.cameraEar = button.dataset.value;
      go(ui.earContext === "photo" ? "photo" : "capture");
    } else {
      ui.filter[button.dataset.choice] = button.dataset.choice === "ear" ? button.dataset.value : Number(button.dataset.value);
      go("filters");
    }
  }));
  phone.querySelectorAll("[data-color]").forEach(button => button.addEventListener("click", () => {
    if (ui.simulate) { ui.color = button.dataset.color; go("markup"); }
    else selectRecord("action.markup.color");
  }));
  phone.querySelector("[data-demo='select-gallery']")?.addEventListener("click", () => {
    if (ui.simulate) { ui.variant = ui.variant === "multiselect" ? "all" : "multiselect"; renderPhone(); }
    selectRecord("action.gallery.select");
  });
  phone.querySelector("#drawing-demo")?.addEventListener("pointerup", () => {
    if (!ui.simulate || ui.variant === "busy") return;
    ui.marks.push({tool: ui.tool, color: ui.color});
    ui.undone = [];
    renderPhone();
  });
}
function defaultVariant(page) {
  return {capture: "live", gallery: "all", photo: "normal", markup: "normal", "device-picker": "list", "profile-edit": "add"}[page] || "normal";
}
const variants = {
  capture: ["disconnected", "connecting", "connected-idle", "macro-idle", "live", "reconnecting", "frozen", "macro", "macro-expanded", "recording", "saving", "error"],
  gallery: ["all", "empty", "multiselect"], photo: ["normal", "macro", "copy"],
  markup: ["normal", "busy", "error"], "device-picker": ["list", "empty", "scanning"],
  "profile-edit": ["add", "edit", "empty-error", "duplicate-error"]
};
function go(page) {
  if (page === "ear-picker") ui.earContext = ui.page === "filters" ? "filters" : ui.page === "photo" ? "photo" : "capture";
  if (page === "filters-clear") { ui.filter = {year: 0, month: 0, ear: "全部"}; page = "filters"; }
  if (page === "macro-enable") { ui.macroEnabled = true; page = "capture"; }
  if (!pageById[page]) return;
  ui.history.push({page: ui.page, variant: ui.variant});
  ui.page = page; ui.variant = page === "capture" && ui.macroEnabled ? "macro" : defaultVariant(page);
  renderScreens();
}
function dispatch(id) {
  ui.events.push({action: id, page: ui.page, variant: ui.variant, time: new Date().toISOString()});
  if (id === "camera.start-preview") {
    ui.variant = ui.previewBase || (ui.variant === "macro-idle" ? "macro" : "live"); renderScreens(); return;
  }
  if (id === "camera.stop-preview") {
    const base = ui.variant === "frozen" ? ui.freezeBase || "live" : ui.variant;
    ui.previewBase = base; ui.variant = base.startsWith("macro") ? "macro-idle" : "connected-idle"; renderScreens(); return;
  }
  const routes = {"settings.open": "settings", "profile.open": "profiles", "device.search": "device-picker",
    "gallery.open": "gallery", "ear.mark": "ear-picker", "gallery.filter": "filters", "media.share": "system-share",
    "media.delete": "delete-confirm", "photo.markup": "markup", "markup.color": "palette",
    "markup.cancel": "photo", "markup.save": "gallery"};
  if (routes[id]) { go(routes[id]); return; }
  if (id === "camera.freeze") {
    ui.freezeBase = ui.variant; ui.variant = "frozen"; renderScreens(); return;
  }
  if (id === "camera.resume") {
    ui.variant = ui.freezeBase || "live"; renderScreens(); return;
  }
  if (id === "nav.back") {
    const previous = ui.history.pop() || {page: ui.page === "capture" ? "settings" : "capture", variant: "live"};
    ui.page = previous.page; ui.variant = previous.variant; renderScreens(); return;
  }
  const states = {"device.connect": "connecting", "device.cancel": "disconnected", "device.disconnect": "disconnected",
    "camera.expand": "macro-expanded",
    "camera.collapse": "macro", "video.start": "recording", "video.stop": "saving"};
  if (states[id]) {
    ui.variant = states[id]; renderScreens();
    if (["device.connect", "video.stop"].includes(id)) {
      setTimeout(() => { if (ui.page === "capture" && ["connecting", "saving"].includes(ui.variant)) { ui.variant = id === "device.connect" ? "connected-idle" : "live"; renderScreens(); } }, 900);
    }
    if (["camera.expand", "camera.collapse"].includes(id)) {
      const duration = matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : ui.tokens.motion.rotationMs;
      document.querySelector(".zoom-act")?.animate([{transform: "rotate(0deg)"}, {transform: "rotate(90deg)"}], {duration});
    }
    return;
  }
  if (id === "camera.stabilize") ui.stabilize = !ui.stabilize;
  if (id === "camera.mirror") ui.mirror = !ui.mirror;
  if (id === "markup.circle") ui.tool = "circle";
  if (id === "markup.arrow") ui.tool = "arrow";
  if (id === "markup.undo" && ui.marks.length) ui.undone.push(ui.marks.pop());
  if (id === "markup.redo" && ui.undone.length) ui.marks.push(ui.undone.pop());
  if (id === "playback.play") ui.playing = true;
  if (id === "playback.pause") ui.playing = false;
  renderPhone();
}
function renderScreens() {
  ui.tab = "screens";
  setActiveTab();
  const main = document.getElementById("main");
  main.innerHTML = '<h1>手机页面骨架</h1><p class="muted">灰度占位表示布局。定格的图标和位置待审核；当前 App 的图标是 Canvas 自绘，Claude 按语义 ID 重新设计。</p>' +
    '<div class="toolbar"><select id="page-select" aria-label="页面"></select><select id="variant-select" aria-label="状态"></select></div>' +
    '<div class="toolbar"><label><input type="checkbox" id="simulate"> 演示点击流</label><label><input type="checkbox" id="show-slots"> 显示槽位</label>' +
    '<select id="viewport-select" aria-label="手机尺寸"><option value="390,844">390 × 844</option><option value="360,800">360 × 800</option><option value="412,915">412 × 915</option><option value="844,390">844 × 390 横屏</option></select>' +
    '<select id="copy-mode"><option value="current">当前文案</option><option value="proposal">候选文案 · 待审核</option></select></div>' +
    '<div class="toolbar"><label>定格位置</label><select id="freeze-place"><option value="tools">建议 A · 工具栏首位</option><option value="viewport-left">建议 B · 实际画面左下</option><option value="current-top">当前 · 取景区右上</option></select>' +
    '<button id="review-page">审核这页</button></div><div class="phone-stage"><div id="phone" class="phone"></div></div>';
  const pageSelect = document.getElementById("page-select");
  pageSelect.innerHTML = D.pages.map(p => '<option value="' + p.id + '">' + esc(p.title) + "</option>").join("");
  pageSelect.value = ui.page;
  pageSelect.onchange = () => { ui.page = pageSelect.value; ui.variant = defaultVariant(ui.page); renderScreens(); selectRecord("page." + ui.page); };
  const variantSelect = document.getElementById("variant-select");
  variantSelect.innerHTML = (variants[ui.page] || ["normal"]).map(v => '<option value="' + v + '">' + esc(v) + "</option>").join("");
  variantSelect.value = ui.variant;
  variantSelect.onchange = () => { ui.variant = variantSelect.value; renderPhone(); };
  document.getElementById("simulate").checked = ui.simulate;
  document.getElementById("simulate").onchange = event => { ui.simulate = event.target.checked; };
  document.getElementById("show-slots").checked = ui.showSlots;
  document.getElementById("show-slots").onchange = event => { ui.showSlots = event.target.checked; renderPhone(); };
  document.getElementById("viewport-select").value = ui.width + "," + ui.height;
  document.getElementById("viewport-select").onchange = event => { [ui.width, ui.height] = event.target.value.split(",").map(Number); renderPhone(); };
  document.getElementById("copy-mode").value = ui.copyMode;
  document.getElementById("copy-mode").onchange = event => { ui.copyMode = event.target.value; renderPhone(); };
  document.getElementById("freeze-place").value = ui.freezePlacement;
  document.getElementById("freeze-place").onchange = event => {
    ui.freezePlacement = event.target.value;
    decisions["edge.freeze"] = {...decision("edge.freeze"), status: "revise", note: "暂选 " + event.target.selectedOptions[0].text + "，仍待最终确认。"};
    persist(); renderPhone(); selectRecord("edge.freeze");
  };
  document.getElementById("review-page").onclick = () => selectRecord("page." + ui.page);
  renderPhone();
}
function card(record) {
  const item = decision(record.id);
  return '<article class="card"><span class="pill" data-status-for="' + esc(record.id) + '">' + statusLabels[item.status] +
    '</span> <span class="pill priority">' + esc(record.priority || "中") + '</span><h3>' + esc(record.title) +
    '</h3><code>' + esc(record.id) + '</code><p>' + esc(record.reason || record.treatment || record.detail || "") + "</p>" +
    (record.current ? '<div class="copy-body">' + esc(record.current) + "</div>" : "") +
    (record.proposal ? '<p class="muted">候选 · ' + esc(record.proposal) + "</p>" : "") +
    '<button class="review-open" data-select-review="' + esc(record.id) + '">审核这一项</button></article>';
}
function recordPool() {
  if (ui.tab === "copy") return D.copy;
  if (ui.tab === "actions") return records.filter(r => r.category === "动作");
  if (ui.tab === "surfaces") return records.filter(r => r.category === "页面");
  return records.filter(r => r.category === "边角");
}
function paintList() {
  const query = ui.query.toLowerCase();
  const rows = recordPool().filter(r => (ui.includeLegacy || !r.legacy) &&
    (ui.includeRuntime || !["诊断/运行时字面量", "硬编码文案"].includes(r.category)) &&
    (ui.status === "all" || decision(r.id).status === ui.status) &&
    (!query || JSON.stringify(r).toLowerCase().includes(query)));
  document.getElementById("list-count").textContent = "当前显示 " + rows.length + " 项";
  document.getElementById("record-list").innerHTML = rows.map(card).join("");
  document.querySelectorAll("[data-select-review]").forEach(button => button.onclick = () => selectRecord(button.dataset.selectReview));
}
function renderInventory() {
  const title = {copy: "全部提示语审核", actions: "按钮、图标与文字的取舍", surfaces: "页面、弹窗与系统边界", edges: "边角与待决策"}[ui.tab];
  let intro = "逐条选择审核结论。候选是建议，不会自动覆盖 Android 文案或行为。";
  if (ui.tab === "actions") intro += " 常见高频动作可用图标；选择项、长文、错误和删除确认需要明确文字。自定义设备与定格图标应附语义验证。";
  if (ui.tab === "surfaces") intro += " App 自有 AlertDialog 也属于主题覆盖范围。Android 系统界面单独标注边界。";
  document.getElementById("main").innerHTML = '<h1>' + title + '</h1><p>' + intro + '</p>' +
    (ui.tab === "actions" ? '<div class="reference-images"><img src="references/device.png" alt="用户提供的耳镜轮廓"><img src="references/device-phone.png" alt="用户提供的耳镜与手机连接示意"></div>' +
      '<details><summary>展开 ' + requiredIcons.length + ' 个语义图标总览 · 可载入 Claude 图标后逐项审核</summary><div class="icon-board">' +
      requiredIcons.map(id => {
        const a = D.actions.find(action => action.icon === id);
        return '<button class="icon-tile" data-select-review="action.' + a.id + '">' + svg(id) + '<span>' + esc(a.title) + '</span><small>' + esc(id) + "</small></button>";
      }).join("") + "</div></details>" : "") +
    '<div class="toolbar"><input id="search" placeholder="搜索 ID、内容或来源"><select id="status-filter"><option value="all">全部结论</option>' +
    Object.entries(statusLabels).map(([key, label]) => '<option value="' + key + '">' + label + "</option>").join("") +
    '</select><label><input type="checkbox" id="legacy"> 遗留未引用</label><label><input type="checkbox" id="runtime"> 硬编码/诊断</label></div>' +
    '<p id="list-count" class="muted"></p><div id="record-list"></div>';
  document.getElementById("search").value = ui.query;
  document.getElementById("search").oninput = event => { ui.query = event.target.value; paintList(); };
  document.getElementById("status-filter").value = ui.status;
  document.getElementById("status-filter").onchange = event => { ui.status = event.target.value; paintList(); };
  document.getElementById("legacy").checked = ui.includeLegacy;
  document.getElementById("legacy").onchange = event => { ui.includeLegacy = event.target.checked; paintList(); };
  document.getElementById("runtime").checked = ui.includeRuntime;
  document.getElementById("runtime").onchange = event => { ui.includeRuntime = event.target.checked; paintList(); };
  paintList();
}
function renderOverview() {
  document.getElementById("main").innerHTML = '<h1>先定骨架，再交给 Claude 换视觉</h1><p>这是一份基于 Android 0.18 源码的离线审核包。先逐项审核位置、图标语义和文字，再让 Claude 按同一接口做不同风格，最后回归到原生 App。</p>' +
    '<div class="summary-strip"><div><strong>' + D.pages.length + '</strong>页面/界面</div><div><strong>' + D.actions.length +
    '</strong>动作</div><div><strong>' + D.copy.length + '</strong>文案/字面量</div><div><strong>' + D.edges.length + '</strong>边角项</div></div>' +
    '<div class="warning">定格的暂停/播放方案已被用户否定。本骨架以语义槽位占位，默认建议放在工具栏首位，仍待审核。喜欢的旋转反馈和实际矩形右下角锚定列为保留项。</div>' +
    '<h2>怎么使用</h2><ol><li>打开“手机页面骨架”，选择页面和状态。默认点按钮会定位审核项，勾选“演示点击流”可模拟跳转。</li><li>在右侧逐项选择通过、需要修改或取消，并填写自己的措辞与位置要求。</li><li>审核文案时切换当前/候选，特别检查帮助、隐私、连接和错误提示。</li><li>导出审核 JSON，把 ZIP 和提示语交给 Claude。</li><li>Claude 返回 tokens.json、icons.svg、manifest.json、design-decisions.json。载入主题后可立即对照同一骨架。</li></ol>' +
    '<h2>交付文件</h2><div class="table-wrap"><table><tr><th>文件</th><th>用途</th></tr>' +
    '<tr><td>index.html / inventory.json</td><td>可点击骨架、逐项审核与源码快照</td></tr><tr><td>文案审核.csv</td><td>全部资源文案、英文繁体、来源、候选与空审核栏</td></tr>' +
    '<tr><td>CLAUDE_START.txt</td><td>可复制的设计任务与交付接口</td></tr><tr><td>starter-theme/</td><td>可克隆的中性主题和图标占位模板</td></tr>' +
    '<tr><td>contracts/</td><td>布局、行为、图标 ID、Android 映射和回归清单</td></tr><tr><td>tools/check_theme.py</td><td>检查返回主题的结构、图标、对比度与安全 SVG</td></tr></table></div>' +
    '<h2>规范参考</h2>' + D.sources.map(s => '<p><a href="' + esc(s.url) + '" target="_blank" rel="noreferrer">' + esc(s.title) + "</a> · " + esc(s.use) + "</p>").join("") +
    '<p class="muted">当前 App 图标由 ScopeUi 的 Canvas 绘制。交接采用 SVG 作为可检查的设计源文件，Android 按语义 ID 映射接入。HTML 预览通过不等于原生真机验收。</p>';
}
function validateTokens(tokens) {
  if (tokens.schemaVersion !== "1.0" || typeof tokens.name !== "string") throw new Error("主题版本或名称无效。");
  if (Object.keys(tokens).sort().join() !== Object.keys(starterTokens).sort().join()) throw new Error("主题顶层字段不符。");
  for (const group of ["colors", "annotation"]) {
    if (Object.keys(tokens[group]).sort().join() !== Object.keys(starterTokens[group]).sort().join()) throw new Error(group + " 字段不符。");
    for (const value of Object.values(tokens[group])) if (!/^#[0-9a-f]{6}$/i.test(value)) throw new Error("颜色必须为 #RRGGBB。");
  }
  for (const group of ["radii", "sizes", "type", "motion"]) {
    if (Object.keys(tokens[group]).sort().join() !== Object.keys(starterTokens[group]).sort().join()) throw new Error(group + " 字段不符。");
    for (const value of Object.values(tokens[group])) if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > 250) throw new Error("尺寸数值无效。");
  }
  const ranges = {radii: {button: [0, 28], panel: [0, 32], sheet: [0, 36]},
    sizes: {touch: [48, 64], icon: [20, 28], gap: [8, 16], pagePadding: [12, 24]},
    type: {body: [14, 18], title: [18, 26], caption: [12, 15]}, motion: {rotationMs: [180, 180], stateMs: [100, 250]}};
  for (const [group, values] of Object.entries(ranges)) for (const [key, range] of Object.entries(values)) {
    if (tokens[group][key] < range[0] || tokens[group][key] > range[1]) throw new Error(group + "." + key + " 超出允许范围。");
  }
  const e = tokens.effects;
  if (Object.keys(e).sort().join() !== Object.keys(starterTokens.effects).sort().join() ||
      !["solid", "linear"].includes(e.backgroundMode) || !/^#[0-9a-f]{6}$/i.test(e.gradientEnd)) throw new Error("背景效果接口错误。");
  for (const [key, range] of Object.entries({gradientAngle: [0, 360], surfaceOpacity: [.15, 1], borderWidth: [0, 2], shadowBlur: [0, 20], shadowOpacity: [0, .25]})) {
    if (typeof e[key] !== "number" || !Number.isFinite(e[key]) || e[key] < range[0] || e[key] > range[1]) throw new Error("effects." + key + " 超出允许范围。");
  }
  if (tokens.sizes.touch < 48 || tokens.sizes.touch > 64 || tokens.motion.rotationMs !== 180) throw new Error("触摸尺寸或旋转动画违反接口。");
}
function installSvg(text) {
  const xml = new DOMParser().parseFromString(text, "image/svg+xml");
  if (xml.querySelector("parsererror")) throw new Error("SVG 解析失败。");
  const allowed = new Set(["svg", "defs", "symbol", "g", "path", "circle", "ellipse", "rect", "line", "polyline", "polygon", "title", "desc"]);
  for (const node of xml.querySelectorAll("*")) {
    if (!allowed.has(node.localName)) throw new Error("SVG 含不允许的元素 " + node.localName);
    for (const attr of node.attributes) if (/^on/i.test(attr.localName) || ["href", "style"].includes(attr.localName) || /url\(/i.test(attr.value)) throw new Error("SVG 含脚本、样式或外部引用。");
  }
  const symbols = [...xml.querySelectorAll("symbol")];
  const ids = symbols.map(n => n.id);
  if (new Set(ids).size !== ids.length || ids.sort().join() !== [...requiredIcons].sort().join()) throw new Error("SVG 图标 ID 缺失、重复或未知。");
  if (symbols.some(n => n.getAttribute("viewBox") !== "0 0 24 24")) throw new Error("图标 viewBox 必须为 0 0 24 24。");
  document.getElementById("svg-sprite").replaceChildren(document.importNode(xml.documentElement, true));
}
function renderTheme() {
  document.getElementById("main").innerHTML = '<h1>载入 Claude 返回的主题</h1><p>选择同一套主题的 tokens.json 与 icons.svg。预览器检查接口后，在相同页面骨架上替换颜色、尺寸和图标。</p>' +
    '<p class="warning">最终接回 Android 还需要 Codex 转换图标、映射原生样式，并跑手机回归。请保留用户审核导出的 JSON。</p>' +
    '<div class="card"><h3>当前主题</h3><p id="theme-name">' + esc(ui.tokens.name) + '</p><input id="theme-files" type="file" multiple accept=".json,.svg">' +
    '<div class="toolbar"><button id="load-theme" class="primary">载入所选主题</button><button id="reset-theme">恢复中性骨架</button><button id="theme-preview">看手机骨架</button></div></div>' +
    '<h2>返回前检查</h2><div class="code-block">python tools/check_theme.py 返回主题目录 --final</div><p>检查主题结构、全部语义图标、对比度和 SVG 外部引用。结果通过后还要审核所有页面与真实设备。</p>' +
    '<h2>下载模板</h2><p><a href="starter-theme/tokens.json" download>tokens.json</a> · <a href="starter-theme/icons.svg" download>icons.svg</a> · <a href="starter-theme/manifest.json" download>manifest.json</a> · <a href="starter-theme/design-decisions.json" download>design-decisions.json</a></p>';
  document.getElementById("load-theme").onclick = async () => {
    try {
      const files = [...document.getElementById("theme-files").files];
      const tokenFile = files.find(f => f.name === "tokens.json");
      const iconFile = files.find(f => f.name === "icons.svg");
      if (!tokenFile || !iconFile) throw new Error("请同时选择 tokens.json 和 icons.svg。");
      const tokens = JSON.parse(await tokenFile.text());
      validateTokens(tokens);
      installSvg(await iconFile.text());
      ui.tokens = tokens;
      document.getElementById("theme-name").textContent = tokens.name;
      notice("主题已载入。请用同一组页面、状态和尺寸逐项审核。");
    } catch (error) { notice(error.message); }
  };
  document.getElementById("reset-theme").onclick = () => { ui.tokens = structuredClone(starterTokens); makePlaceholderIcons(); renderTheme(); };
  document.getElementById("theme-preview").onclick = () => { ui.tab = "screens"; renderMain(); };
}
function setActiveTab() {
  document.querySelectorAll("#tabs button").forEach(button => button.classList.toggle("active", button.dataset.tab === ui.tab));
}
function renderMain() {
  setActiveTab();
  if (ui.tab === "screens") renderScreens();
  else if (ui.tab === "overview") renderOverview();
  else if (ui.tab === "theme") renderTheme();
  else renderInventory();
  updateProgress();
}
function makePlaceholderIcons() {
  const contents = requiredIcons.map(id => '<symbol id="' + id + '" viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="3" fill="none" stroke="currentColor" stroke-width="1.6" stroke-dasharray="3 2"/><circle cx="12" cy="12" r="2" fill="currentColor"/></symbol>').join("");
  installSvg('<svg xmlns="http://www.w3.org/2000/svg"><defs>' + contents + "</defs></svg>");
}
function download(name, value) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], {type: "application/json"}));
  const link = document.createElement("a");
  link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
document.querySelectorAll("#tabs button").forEach(button => button.onclick = () => {
  ui.tab = button.dataset.tab; ui.query = ""; renderMain();
});
document.getElementById("export-review").onclick = () => download("review-decisions.json", {
  schemaVersion: "1.0", snapshot: D.snapshot, exportedAt: new Date().toISOString(), themeName: ui.tokens.name, freezePlacement: ui.freezePlacement,
  decisions: records.map(r => decision(r.id))
});
document.getElementById("import-review").onclick = () => document.getElementById("review-file").click();
document.getElementById("review-file").onchange = async event => {
  try {
    const file = event.target.files[0];
    if (!file) return;
    const input = JSON.parse(await file.text());
    if (input.schemaVersion !== "1.0" || !Array.isArray(input.decisions)) throw new Error("审核文件接口不匹配。");
    let count = 0;
    for (const item of input.decisions) {
      if (!Object.hasOwn(byId, item.id) || !Object.hasOwn(statusLabels, item.status) || typeof item.proposal !== "string" || typeof item.note !== "string") continue;
      decisions[item.id] = {id: item.id, status: item.status, proposal: item.proposal, note: item.note}; count++;
    }
    if (["tools", "viewport-left", "current-top"].includes(input.freezePlacement)) ui.freezePlacement = input.freezePlacement;
    persist(); renderInspector(); renderMain();
    notice("导入 " + count + " 项。" + (input.snapshot !== D.snapshot ? "注意：来源快照不同，需要重新核对。" : ""));
  } catch (error) { notice(error.message); }
};
window.addEventListener("resize", fitScene);
document.getElementById("snapshot").textContent = "Android " + D.appVersion + " · " + D.snapshot;
makePlaceholderIcons();
renderMain();
renderInspector();
