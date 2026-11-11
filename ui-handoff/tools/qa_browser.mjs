// Offline UI smoke/regression checks through the installed Chrome's CDP.
import fs from "node:fs";
import path from "node:path";
import {spawn} from "node:child_process";
import {fileURLToPath, pathToFileURL} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const qa = path.join(root, ".qa");
const runId = String(Date.now());
const profile = path.join(qa, "chrome-" + runId);
const downloads = path.join(qa, "downloads-" + runId);
const customTheme = process.argv[2] ? path.resolve(process.argv[2]) : null;
const previewRoot = customTheme ? path.join(qa, "theme-previews") : path.join(root, "previews");
const chrome = process.env.SCOPE_CHROME || "C:/Program Files/Google/Chrome/Application/chrome.exe";
fs.mkdirSync(profile, {recursive: true});
fs.mkdirSync(downloads, {recursive: true});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const browser = spawn(chrome, ["--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
  "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank"], {windowsHide: true, stdio: "ignore"});
let socket;
let nextId = 1;
const pending = new Map();
const errors = [];
const results = [];
function assert(condition, message) {
  if (!condition) throw new Error(message);
}
function send(method, params = {}) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error("CDP timeout " + method)); }, 15000);
    pending.set(id, {resolve, reject, timer});
    socket.send(JSON.stringify({id, method, params}));
  });
}
async function evaluate(expression) {
  const response = await send("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true});
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description || response.exceptionDetails.text);
  return response.result.value;
}
async function selectFile(selector, files) {
  const doc = await send("DOM.getDocument");
  const node = await send("DOM.querySelector", {nodeId: doc.root.nodeId, selector});
  assert(node.nodeId, "Missing file input " + selector);
  await send("DOM.setFileInputFiles", {nodeId: node.nodeId, files});
}
async function shot(name) {
  await delay(80);
  const bounds = await evaluate("JSON.parse(JSON.stringify(document.getElementById('phone').getBoundingClientRect()))");
  const image = await send("Page.captureScreenshot", {format: "png", clip: {x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height, scale: 1}});
  fs.mkdirSync(previewRoot, {recursive: true});
  fs.writeFileSync(path.join(previewRoot, name), Buffer.from(image.data, "base64"));
}
try {
  let portFile = "";
  for (let i = 0; i < 100; i++) {
    const filename = path.join(profile, "DevToolsActivePort");
    if (fs.existsSync(filename)) { portFile = fs.readFileSync(filename, "utf8"); break; }
    await delay(100);
  }
  assert(portFile, "Chrome did not expose CDP.");
  const port = portFile.split("\n")[0];
  const tabs = await (await fetch("http://127.0.0.1:" + port + "/json/list")).json();
  const tab = tabs.find(item => item.type === "page");
  socket = new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, {once: true});
    socket.addEventListener("error", reject, {once: true});
  });
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const operation = pending.get(message.id);
      clearTimeout(operation.timer);
      pending.delete(message.id);
      if (message.error) operation.reject(new Error(message.error.message));
      else operation.resolve(message.result);
    } else if (message.method === "Runtime.exceptionThrown") errors.push(message.params.exceptionDetails);
  });
  await send("Page.enable");
  await send("Runtime.enable");
  await send("DOM.enable");
  await send("Network.enable");
  await send("Network.setBlockedURLs", {urls: ["http://*", "https://*"]});
  await send("Emulation.setDeviceMetricsOverride", {width: 1680, height: 1440, deviceScaleFactor: 1, mobile: false});
  await send("Page.navigate", {url: pathToFileURL(path.join(root, "index.html")).href});
  for (let i = 0; i < 100; i++) {
    if (await evaluate("typeof D !== 'undefined' && !!document.querySelector('#main h1')")) break;
    await delay(50);
  }
  assert(await evaluate("D.pages.length >= 28 && D.copy.length >= 200"), "Inventory not loaded.");
  results.push("PASS offline file:// load with external HTTP blocked");
  if (customTheme) {
    await evaluate("ui.tab='theme';renderMain()");
    await selectFile("#theme-files", [path.join(customTheme, "tokens.json"), path.join(customTheme, "icons.svg")]);
    await evaluate("document.getElementById('load-theme').click()");
    await delay(150);
    assert(await evaluate("document.getElementById('notice').textContent.includes('主题已载入')"), "Candidate theme load failed.");
    results.push("PASS candidate theme loaded before page/viewport regression");
  }

  const render = await evaluate("(async () => { let count=0; const bad=[]; for(const p of D.pages){ for(const v of variants[p.id]||['normal']){ ui.page=p.id;ui.variant=v;renderScreens();await new Promise(requestAnimationFrame); if(!document.querySelector('#phone .screen').children.length)bad.push(p.id+':'+v);count++; }}return {count,bad}; })()");
  assert(!render.bad.length && render.count >= 45, "Some pages or states failed to render.");
  results.push("PASS all " + render.count + " page/state combinations render");
  for (const [width, height] of [[360, 800], [390, 844], [412, 915], [844, 390]]) {
    await evaluate("ui.width=" + width + ";ui.height=" + height + ";ui.page='capture';ui.variant='live';renderScreens()");
    const violations = await evaluate("[...document.querySelectorAll('#phone button')].filter(n=>{const r=n.getBoundingClientRect();return r.width<47.5||r.height<47.5}).map(n=>n.title||n.textContent)");
    assert(!violations.length, "Touch target too small: " + violations.join(","));
  }
  results.push("PASS capture touch targets at four viewport sizes");
  await evaluate("ui.width=390;ui.height=844;ui.page='capture';ui.variant='connected-idle';ui.simulate=true;renderScreens()");
  assert(await evaluate("document.querySelector('.lens-cover') && document.querySelector('[data-action=\"device.disconnect\"]') && document.querySelector('[data-action=\"photo.capture\"]').disabled"), "Connected idle state incorrect.");
  await shot("05-connected-idle-skeleton.png");
  await evaluate("dispatch('camera.start-preview');dispatch('camera.stop-preview')");
  assert(await evaluate("ui.variant==='connected-idle'"), "Cover should preserve connected idle.");
  await evaluate("ui.variant='macro-expanded';dispatch('camera.stop-preview');dispatch('camera.start-preview')");
  assert(await evaluate("ui.variant==='macro-expanded'"), "Cover lost macro orientation.");
  results.push("PASS connected idle, disabled capture and cover open/close with macro orientation retained");
  await evaluate("ui.width=390;ui.height=844;ui.page='capture';ui.variant='macro-expanded';ui.simulate=true;renderScreens();dispatch('camera.freeze')");
  assert(await evaluate("ui.variant==='frozen' && document.querySelector('.scene').classList.contains('macro')"), "Freeze lost macro geometry.");
  await evaluate("dispatch('camera.resume')");
  assert(await evaluate("ui.variant==='macro-expanded'"), "Resume lost expanded mode.");
  await evaluate("dispatch('camera.collapse');dispatch('camera.expand')");
  assert(await evaluate("ui.variant==='macro-expanded' && document.querySelector('.zoom-act')"), "Rotation demo failed.");
  results.push("PASS simulated macro freeze/resume and rotation controls");
  await shot("01-macro-skeleton.png");
  const workbench = await send("Page.captureScreenshot", {format: "png"});
  fs.writeFileSync(path.join(previewRoot, "00-review-workbench.png"), Buffer.from(workbench.data, "base64"));

  await evaluate("ui.page='help';ui.variant='normal';ui.copyMode='current';renderScreens()");
  assert(await evaluate("document.querySelector('.reading').textContent.includes('同一侧')"), "Current help audit missing.");
  await evaluate("ui.copyMode='proposal';renderPhone()");
  assert(await evaluate("document.querySelector('.reading').textContent.includes('另存')"), "Candidate help audit missing.");
  await shot("02-help-skeleton.png");
  await evaluate("ui.page='ear-picker';ui.earContext='capture';renderScreens()");
  assert(await evaluate("document.querySelectorAll('[data-choice=ear]').length===3"), "Capture ear picker must not offer 全部.");
  await evaluate("ui.earContext='filters';renderPhone()");
  assert(await evaluate("document.querySelectorAll('[data-choice=ear]').length===4"), "Filter ear picker needs 全部.");
  await shot("03-ear-picker-skeleton.png");
  results.push("PASS help current/candidate text and contextual ear options");

  await evaluate("ui.page='markup';ui.variant='normal';ui.marks=[];ui.undone=[];renderScreens();document.getElementById('drawing-demo').dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));dispatch('markup.undo')");
  assert(await evaluate("ui.marks.length===0 && ui.undone.length===1"), "Markup undo failed.");
  await evaluate("dispatch('markup.redo')");
  assert(await evaluate("ui.marks.length===1 && ui.undone.length===0"), "Markup redo failed.");
  await shot("04-markup-skeleton.png");
  await evaluate("ui.variant='busy';renderPhone()");
  assert(await evaluate("[...document.querySelectorAll('#phone [data-action]')].every(n=>n.disabled)"), "Busy markup controls should be disabled.");
  results.push("PASS markup simulation and busy state");

  await evaluate("selectRecord('copy.connect_hint');document.getElementById('review-status').value='revise';document.getElementById('review-status').dispatchEvent(new Event('change'));document.getElementById('review-note').value='QA 审核往返';document.getElementById('review-note').dispatchEvent(new Event('input'))");
  await send("Page.reload");
  await delay(300);
  assert(await evaluate("decision('copy.connect_hint').note==='QA 审核往返'"), "Review did not persist across reload.");
  await send("Browser.setDownloadBehavior", {behavior: "allow", downloadPath: downloads});
  await evaluate("document.getElementById('export-review').click()");
  const exported = path.join(downloads, "review-decisions.json");
  for (let i = 0; i < 100 && !fs.existsSync(exported); i++) await delay(50);
  assert(fs.existsSync(exported), "Review export did not download.");
  const review = JSON.parse(fs.readFileSync(exported, "utf8"));
  assert(review.snapshot === await evaluate("D.snapshot"), "Export used a stale source snapshot.");
  assert(review.decisions.length >= 280 && review.decisions.find(r => r.id === "copy.connect_hint").status === "revise", "Review export missing decisions.");
  await evaluate("decisions={};persist();renderInspector()");
  await selectFile("#review-file", [exported]);
  await delay(150);
  assert(await evaluate("decision('copy.connect_hint').note==='QA 审核往返'"), "Review import failed.");
  results.push("PASS local review persistence and JSON export/import round trip");

  await evaluate("ui.tab='theme';renderMain()");
  const theme = customTheme || path.join(root, "starter-theme");
  await selectFile("#theme-files", [path.join(theme, "tokens.json"), path.join(theme, "icons.svg")]);
  await evaluate("document.getElementById('load-theme').click()");
  await delay(150);
  assert(await evaluate("document.getElementById('notice').textContent.includes('主题已载入')"), "Theme file import failed.");
  await evaluate("ui.tab='actions';renderMain()");
  assert(await evaluate("document.querySelectorAll('.icon-board .icon-tile').length===requiredIcons.length"), "Icon audit board incomplete.");
  assert(await evaluate("(() => {const t=structuredClone(starterTokens);t.sizes.touch=20;try{validateTokens(t);return false}catch{return true}})()"), "Invalid touch size accepted.");
  assert(await evaluate("(() => {try{installSvg('<svg><script>alert(1)</script></svg>');return false}catch{return true}})()"), "Unsafe SVG accepted.");
  results.push("PASS theme import and rejection of unsafe/invalid inputs");
  assert(!errors.length, "Browser exceptions: " + JSON.stringify(errors));
  fs.writeFileSync(path.join(qa, "browser-results.json"), JSON.stringify({results, pages: render.count, errors}, null, 2));
  console.log(results.join("\n"));
} finally {
  if (socket?.readyState === WebSocket.OPEN) {
    try { await send("Browser.close"); } catch {}
    socket.close();
  }
  browser.kill();
  await delay(250);
  const resolved = path.resolve(profile);
  if (resolved.startsWith(path.resolve(qa) + path.sep)) {
    try { fs.rmSync(resolved, {recursive: true, force: true}); } catch {}
  }
}
