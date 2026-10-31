package com.minecraftdb.api;

/**
 * Web 查询工作台：自包含单页（无外部依赖），由 HTTP API 根路径提供。
 * SQL 编辑器 + 结果表格 + 表结构侧栏 + 快捷操作与导入导出。
 */
final class WorkbenchPage {

    private WorkbenchPage() {}

    static final String HTML = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MinecraftDB Workbench</title>
<style>
  :root { --bg:#1e1f22; --panel:#2b2d31; --line:#3f4147; --text:#e8e8e3; --dim:#9a9da3;
          --accent:#7aa2f7; --green:#9ece6a; --red:#f7768e; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text);
         font:14px/1.5 "Segoe UI", "Microsoft YaHei", sans-serif; }
  header { padding:10px 16px; background:var(--panel); border-bottom:1px solid var(--line);
           display:flex; gap:16px; align-items:center; }
  header h1 { font-size:16px; margin:0; color:var(--accent); }
  header .db { color:var(--dim); }
  main { display:grid; grid-template-columns:260px 1fr; height:calc(100vh - 49px); }
  aside { border-right:1px solid var(--line); overflow-y:auto; padding:8px; }
  aside h3 { font-size:12px; color:var(--dim); margin:10px 6px 4px; text-transform:uppercase; }
  .tbl { padding:5px 8px; border-radius:6px; cursor:pointer; }
  .tbl:hover { background:#35373c; }
  .tbl .cols { color:var(--dim); font-size:12px; margin-left:8px; }
  .content { display:flex; flex-direction:column; overflow:hidden; }
  #sql { width:100%; height:130px; background:var(--panel); color:var(--text);
         border:none; border-bottom:1px solid var(--line); padding:10px 12px;
         font:13px/1.5 Consolas, monospace; resize:vertical; outline:none; }
  .bar { padding:8px 12px; display:flex; gap:8px; align-items:center;
         border-bottom:1px solid var(--line); background:var(--panel); }
  button { background:var(--accent); color:#111; border:none; border-radius:6px;
           padding:6px 14px; cursor:pointer; font-weight:600; }
  button.sec { background:#3b3d42; color:var(--text); font-weight:400; }
  button:hover { filter:brightness(1.1); }
  #msg { color:var(--dim); font-size:13px; }
  #msg.err { color:var(--red); }
  #msg.ok { color:var(--green); }
  .results { overflow:auto; flex:1; }
  table { border-collapse:collapse; width:100%; font:13px Consolas, monospace; }
  th { position:sticky; top:0; background:var(--panel); color:var(--accent);
       text-align:left; padding:6px 10px; border-bottom:1px solid var(--line); }
  td { padding:5px 10px; border-bottom:1px solid #33353a; white-space:pre-wrap; }
  tr:hover td { background:#2f3136; }
  .hist { padding:4px 8px; color:var(--dim); font:12px Consolas, monospace;
          cursor:pointer; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .hist:hover { color:var(--text); background:#35373c; }
</style>
</head>
<body>
<header>
  <h1>&#9635; MinecraftDB Workbench</h1>
  <span class="db" id="dbinfo"></span>
</header>
<main>
  <aside>
    <h3>表</h3>
    <div id="tables"></div>
    <h3>历史</h3>
    <div id="hist"></div>
  </aside>
  <div class="content">
    <textarea id="sql" placeholder="SELECT * FROM ...">SHOW TABLES;</textarea>
    <div class="bar">
      <button onclick="run()">执行 (Ctrl+Enter)</button>
      <button class="sec" onclick="exportCsv()">导出 CSV</button>
      <button class="sec" onclick="exportSql()">导出 SQL</button>
      <button class="sec" onclick="dumpSql()">整库 dump</button>
      <span id="msg"></span>
    </div>
    <div class="results" id="results"></div>
  </div>
</main>
<script>
const $ = id => document.getElementById(id);
const hist = [];
let curTable = null;

async function api(path, opts) {
  const r = await fetch(path, opts);
  return r;
}

async function loadTables() {
  const r = await api('/api/v1/tables');
  const d = await r.json();
  $('dbinfo').textContent = '数据库: ' + d.database + ' · ' + d.count + ' 张表';
  const box = $('tables');
  box.innerHTML = '';
  for (const t of d.tables) {
    const div = document.createElement('div');
    div.className = 'tbl';
    div.textContent = t;
    div.onclick = () => { curTable = t; $('sql').value = 'SELECT * FROM ' + t + ' LIMIT 50;'; run(); };
    box.appendChild(div);
  }
}

async function run() {
  const sql = $('sql').value.trim();
  if (!sql) return;
  $('msg').textContent = '执行中…'; $('msg').className = '';
  try {
    const r = await api('/api/v1/execute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql })
    });
    const d = await r.json();
    if (!r.ok || d.success === false) {
      $('msg').textContent = d.message || d.error || '执行失败';
      $('msg').className = 'err';
      return;
    }
    renderResult(d);
    $('msg').textContent = d.message || 'OK';
    $('msg').className = 'ok';
    pushHist(sql);
    if (/^\\s*(CREATE|DROP|ALTER|TRUNCATE|INSERT|UPDATE|DELETE)/i.test(sql)) loadTables();
  } catch (e) {
    $('msg').textContent = '网络错误: ' + e.message; $('msg').className = 'err';
  }
}

function renderResult(d) {
  const box = $('results');
  if (!d.columns || !d.columns.length) { box.innerHTML = ''; return; }
  let html = '<table><thead><tr>';
  for (const c of d.columns) html += '<th>' + esc(c) + '</th>';
  html += '</tr></thead><tbody>';
  for (const row of d.rows || []) {
    html += '<tr>';
    for (const v of row) html += '<td>' + (v === null ? '<i style="color:var(--dim)">NULL</i>' : esc(String(v))) + '</td>';
    html += '</tr>';
  }
  html += '</tbody></table>';
  box.innerHTML = html;
}

function esc(s) { return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function pushHist(sql) {
  if (hist[0] === sql) return;
  hist.unshift(sql);
  if (hist.length > 20) hist.pop();
  const box = $('hist');
  box.innerHTML = '';
  for (const h of hist) {
    const div = document.createElement('div');
    div.className = 'hist';
    div.textContent = h;
    div.title = h;
    div.onclick = () => { $('sql').value = h; };
    box.appendChild(div);
  }
}

async function exportCsv() {
  if (!curTable) { needTable(); return; }
  window.open('/api/v1/table/' + encodeURIComponent(curTable) + '/export?format=csv');
}

async function exportSql() {
  if (!curTable) { needTable(); return; }
  window.open('/api/v1/table/' + encodeURIComponent(curTable) + '/export?format=sql');
}

async function dumpSql() {
  window.open('/api/v1/dump');
}

function needTable() { $('msg').textContent = '先在左侧点选一张表'; $('msg').className = 'err'; }

document.addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run(); }
});
loadTables();
</script>
</body>
</html>
""";
}
