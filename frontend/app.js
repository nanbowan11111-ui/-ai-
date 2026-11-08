const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const user = () => $('user').value;
let state = {materials: [], movements: [], requests: []};
let previewToken = null;
let approvalToken = null;
let selectedRequest = null;
let importRevision = 0;
let activeChatAbort = null;
let activeChatTypewriter = null;
let chatImportRevision = 0;
let chatImportBusy = false;
let pendingChatImport = null;
const chatHistories = new Map();
let displayedChatUser = user();
const chatWelcome = $('messages').firstElementChild.cloneNode(true);

function showChatFor(nextUser) {
  if (nextUser === displayedChatUser) return;
  const messages = $('messages');
  // 保存原有节点以保留下载链接和预览结果，同时只挂载当前用户的对话。
  chatHistories.set(displayedChatUser, Array.from(messages.childNodes));
  messages.replaceChildren(...(chatHistories.get(nextUser) || [chatWelcome.cloneNode(true)]));
  displayedChatUser = nextUser;
  messages.scrollTop = messages.scrollHeight;
}

function importPreviewMarkup(result) {
  const p = result.parsed;
  const problems = p.errors.map(e => `<div class="preview-error">${e.row ? `第 ${esc(e.row)} 行：` : ''}${esc(e.reason)}</div>`).join('');
  const repeat = result.alreadyPosted ? `<div class="preview-error">入库单 ${esc(p.number)} 已入账，不能重复导入。</div>` : '';
  const rows = p.lines.map(l => `<tr><td>${esc(l.row)}</td><td class="code">${esc(l.code)}</td><td>${esc(l.name)}</td><td class="num">${esc(l.quantity)}</td><td class="preview-warning">${esc(l.warnings.join('；'))}</td></tr>`).join('');
  return `<h3 class="preview-title">${esc(p.number || '未填写单号')}</h3><div class="preview-summary">${p.lines.length} 行明细 · ${p.errors.length} 个错误</div>${repeat}${problems}<div class="table-scroll"><table><thead><tr><th>行号</th><th>物料编码</th><th>物料名称</th><th class="num">数量</th><th>提示</th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

function validateImportFile(file) {
  if (!file) return '请选择入库单 Excel 文件。';
  if (file.name.includes('物料主数据')) return '这是物料主数据，不是入库单。请选择“入库单-RK-…”文件。';
  if (!file.name.toLowerCase().endsWith('.xlsx')) return '只支持 .xlsx 入库单。';
  if (file.size > 5 * 1024 * 1024) return '文件超过 5MB，请选择原始入库单 Excel。';
  if (!file.size) return '文件为空，请选择有效的入库单 Excel。';
  return null;
}

function importStatus(text, error = false) {
  const status = $('import-status');
  status.textContent = text;
  status.classList.toggle('error', error);
}

function resetImportPreview() {
  importRevision++;
  previewToken = null;
  $('preview').hidden = true;
  $('preview').innerHTML = '';
  $('confirm-import').disabled = true;
  $('confirm-import').title = '请先预览有效的入库单';
}

async function api(path, options = {}) {
  const response = await fetch(path, options);
  let body;
  try { body = await response.json(); } catch { body = {}; }
  if (!response.ok) throw new Error(body.error || `请求失败（${response.status}）`);
  return body;
}
const jsonBody = (body) => ({method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
function icons() { if (window.lucide) window.lucide.createIcons(); }
function notice(text, error = false) {
  const node = $('notice'); node.textContent = text; node.classList.toggle('error', error); node.hidden = false;
  clearTimeout(notice.timer); notice.timer = setTimeout(() => node.hidden = true, 6000);
}
function date(value) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '—' : parsed.toLocaleString('zh-CN', {
    timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit',
    hour:'2-digit', minute:'2-digit', hour12:false
  });
}

async function refresh() {
  state = await api(`/api/state?user=${encodeURIComponent(user())}`);
  renderMaterials(); renderRequests(); renderMovements();
  $('material-count').textContent = state.materials.length;
  $('low-count').textContent = state.materials.filter(m => m.STOCK < m.SAFE_STOCK).length;
  $('pending-count').textContent = state.requests.filter(r => r.STATUS === '待审批').length;
  const initialStockStatus = $('initial-stock-status');
  const awaitingFirstReceipt = state.materials.length > 0 && state.movements.length === 0;
  initialStockStatus.hidden = !awaitingFirstReceipt;
  initialStockStatus.textContent = awaitingFirstReceipt
    ? `物料主数据已加载：${state.materials.length} 种物料。尚无入库记录，初始库存均为 0。`
    : '';
  $('new-request').hidden = user() === '王经理';
  // 视图名称由服务端角色决定，切换用户后同步更新当前页标题。
  view(document.querySelector('.tab.active')?.dataset.view || 'inventory');
}
function renderMaterials() {
  const query = $('search').value.trim().toLowerCase();
  const rows = state.materials.filter(m => `${m.CODE} ${m.NAME}`.toLowerCase().includes(query));
  $('materials').innerHTML = rows.map(m => `<tr><td class="code">${esc(m.CODE)}</td><td><strong>${esc(m.NAME)}</strong></td><td>${esc(m.SPECIFICATION)}</td><td>${esc(m.UNIT)}</td><td class="num stock">${m.STOCK}</td><td class="num">${m.SAFE_STOCK}</td><td><span class="badge ${m.STOCK < m.SAFE_STOCK ? 'low' : ''}">${m.STOCK < m.SAFE_STOCK ? '低于安全库存' : '正常'}</span></td></tr>`).join('') || '<tr><td colspan="7" class="empty">没有匹配的物料</td></tr>';
}
function renderRequests() {
  $('requests').innerHTML = state.requests.map(r => `<tr><td class="code">LY-${r.ID}</td><td>${esc(r.APPLICANT)}</td><td>${esc(r.DETAILS)}</td><td><span class="badge ${r.STATUS === '待审批' ? 'pending' : r.STATUS === '已驳回' ? 'rejected' : ''}">${esc(r.STATUS)}</span></td><td>${date(r.CREATED_AT)}</td><td><button class="link-button" data-request="${r.ID}">查看详情</button></td></tr>`).join('') || '<tr><td colspan="6" class="empty">暂无领用申请</td></tr>';
}
function renderMovements() {
  $('movements').innerHTML = state.movements.map(m => `<tr><td>${date(m.OCCURRED_AT)}</td><td>${esc(m.KIND)}</td><td class="code">${esc(m.DOCUMENT_NUMBER)}</td><td>${esc(m.MATERIAL_NAME)}</td><td class="num">${m.QUANTITY > 0 ? '+' : ''}${m.QUANTITY}</td><td class="num">${m.BALANCE}</td><td>${esc(m.OPERATOR)}</td></tr>`).join('') || '<tr><td colspan="7" class="empty">暂无出入库流水</td></tr>';
}
function view(name) {
  document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.view === name));
  document.querySelectorAll('.view').forEach(section => section.hidden = section.id !== `${name}-view`);
  const requestTitle = state.users?.find(u => u.name === user())?.role === '审批人' ? '领用审批' : '领用申请';
  document.querySelector('[data-view="requests"]').textContent = requestTitle;
  $('request-list-title').textContent = requestTitle;
  const titles = {inventory:['当前库存','物料余额与安全库存状态'], requests:[requestTitle,'申请记录与审批状态'], movements:['出入库流水','每次库存变动的记录']};
  $('page-title').textContent = titles[name][0]; $('page-subtitle').textContent = titles[name][1];
}
function addRequestRow() {
  const row = document.createElement('div'); row.className = 'request-row';
  row.innerHTML = `<select aria-label="领用物料">${state.materials.map(m => `<option value="${esc(m.CODE)}">${esc(m.NAME)} · 库存 ${m.STOCK}${esc(m.UNIT)}</option>`).join('')}</select><input type="number" min="1" step="1" placeholder="数量" aria-label="领用数量"><button class="icon-button" title="删除此项" aria-label="删除此项"><i data-lucide="trash-2"></i></button>`;
  row.querySelector('button').onclick = () => row.remove();
  $('request-items').append(row); icons();
}
async function detail(id) {
  const currentUser = user();
  const data = await api(`/api/requests/${id}?user=${encodeURIComponent(currentUser)}`);
  if (currentUser !== user()) return;
  selectedRequest = id;
  $('detail-title').textContent = `申请 LY-${id}`;
  $('detail-subtitle').textContent = `${data.APPLICANT} · ${data.STATUS}${data.REJECT_REASON ? ` · 驳回原因：${data.REJECT_REASON}` : ''}`;
  $('detail-body').innerHTML = data.items.map(i => `<div class="detail-line"><span>${esc(i.NAME)} <span class="code">${esc(i.CODE)}</span></span><span>申请 ${i.QUANTITY}${esc(i.UNIT)} · 当前库存 ${i.STOCK}${esc(i.UNIT)}</span></div>`).join('');
  const pending = data.STATUS === '待审批';
  const isApplicant = state.users?.find(u => u.name === currentUser)?.role === '申请人';
  const outcome = pending ? '审批结果' : data.STATUS;
  const stages = [
    {label:'已提交', time:date(data.CREATED_AT), state:'done'},
    {label:'待审批', time:'', state:pending ? 'current' : 'done'},
    {label:outcome, time:date(data.REVIEWED_AT), state:pending ? 'future' : data.STATUS === '已通过' ? 'done' : 'stopped'}
  ];
  $('approval-progress').innerHTML = stages.map((stage, index) => `<div class="progress-step ${stage.state}"><span class="progress-dot" aria-hidden="true">${index + 1}</span><strong>${esc(stage.label)}</strong>${stage.time !== '—' && stage.time ? `<small>${esc(stage.time)}</small>` : ''}</div>`).join('');
  $('withdraw-request').hidden = !isApplicant || !pending || data.APPLICANT !== currentUser;
  $('approve-request').hidden = $('reject-request').hidden = isApplicant || !pending;
  $('detail-actions').hidden = !pending;
  $('detail-dialog').showModal();
}
function addMessage(text, kind, link) {
  const node = document.createElement('div'); node.className = `message ${kind}`;
  updateMessage(node, text, link);
  $('messages').append(node); $('messages').scrollTop = $('messages').scrollHeight;
  return node;
}
function updateMessage(node, text, link) {
  node.textContent = text;
  if (link) { const a = document.createElement('a'); a.href = link; a.textContent = '下载台账'; node.append(document.createElement('br'), a); }
  if (node.parentElement === $('messages')) $('messages').scrollTop = $('messages').scrollHeight;
}

function invalidateChatImport(reason) {
  chatImportRevision++;
  if (pendingChatImport) {
    pendingChatImport.node.querySelectorAll('button').forEach(button => { button.disabled = true; });
    pendingChatImport.node.querySelector('.chat-import-status').textContent = reason;
    pendingChatImport = null;
  }
  chatImportBusy = false;
  $('chat-upload').disabled = false;
}

async function uploadChatReceipt(file) {
  if (chatImportBusy) return addMessage('请等待当前入库单校验完成。', 'assistant');
  const problem = validateImportFile(file);
  if (problem) return addMessage(problem, 'assistant');
  invalidateChatImport('已由新的入库单替换。');
  const revision = chatImportRevision;
  const owner = user();
  chatImportBusy = true;
  $('chat-upload').disabled = true;
  addMessage(`📎 ${file.name}`, 'user');
  const node = addMessage('正在校验入库单…', 'assistant');
  const form = new FormData();
  form.append('file', file);
  try {
    // 文件只走既有入库接口；模型不读取 Excel，预览不写库存。
    const result = await api(`/api/imports/preview?user=${encodeURIComponent(owner)}`, {method:'POST', body:form});
    if (revision !== chatImportRevision || owner !== user()) {
      updateMessage(node, '当前用户已切换，请重新上传入库单。');
      return;
    }
    const valid = !result.parsed.errors.length && !result.alreadyPosted;
    node.classList.add('import-message');
    node.innerHTML = `<strong>入库单预览 · ${esc(file.name)}</strong>${importPreviewMarkup(result)}<p class="chat-import-status" role="status">${valid ? '校验通过。确认后才会更新库存。' : '校验未通过，库存未变动。请修正单据后重新上传。'}</p>${valid ? '<div class="chat-import-actions"><button type="button" class="button primary chat-import-confirm">确认入账</button><button type="button" class="button secondary chat-import-cancel">取消</button></div>' : ''}`;
    $('messages').scrollTop = $('messages').scrollHeight;
    if (!valid) return;
    const pending = {token:result.token, owner, node};
    pendingChatImport = pending;
    node.querySelector('.chat-import-confirm').onclick = async () => {
      if (pendingChatImport !== pending) return;
      const buttons = node.querySelectorAll('button');
      buttons.forEach(button => { button.disabled = true; });
      node.querySelector('.chat-import-status').textContent = '正在确认入账…';
      try {
        const posted = await api('/api/imports/confirm', jsonBody({token:pending.token, user:pending.owner}));
        node.querySelector('.chat-import-status').textContent = `入库单 ${posted.number} 已入账，库存已更新。`;
        node.querySelector('.chat-import-actions').remove();
        if (pendingChatImport === pending) pendingChatImport = null;
        await refresh();
      } catch (error) {
        node.querySelector('.chat-import-status').textContent = `${error.message}；请检查库存或重新上传。`;
        // 令牌失效或单号已入账时不再提供无效的重复确认；其他错误允许重试。
        if (pendingChatImport === pending && /失效|已入账/.test(error.message)) {
          pendingChatImport = null;
          node.querySelector('.chat-import-actions').remove();
        } else if (pendingChatImport === pending) buttons.forEach(button => { button.disabled = false; });
        await refresh().catch(() => {});
      }
    };
    node.querySelector('.chat-import-cancel').onclick = () => invalidateChatImport('已取消入库，库存未变动。');
  } catch (error) {
    if (revision === chatImportRevision) updateMessage(node, error.message);
  } finally {
    if (revision === chatImportRevision) {
      chatImportBusy = false;
      $('chat-upload').disabled = false;
    }
  }
}
function createTypewriter(node) {
  const characters = [];
  let shown = '';
  let received = '';
  let index = 0;
  let timer = null;
  let completed = false;
  let finalText = '';
  let downloadUrl = null;
  let resolveFinished;
  const finished = new Promise(resolve => { resolveFinished = resolve; });
  node.classList.add('typing');

  function tick() {
    timer = null;
    if (index < characters.length) {
      // 网络片段先排队，每次定时器只向气泡追加一个 Unicode 字符。
      shown += characters[index++];
      updateMessage(node, shown);
      schedule();
    } else if (completed) {
      node.classList.remove('typing');
      updateMessage(node, finalText || shown, downloadUrl);
      resolveFinished();
    }
  }
  function schedule() { if (timer === null) timer = setTimeout(tick, 35); }
  function append(text) {
    if (!text || completed) return;
    received += text;
    for (const character of text) characters.push(character);
    schedule();
  }
  function finish(text, link) {
    if (completed) return finished;
    finalText = text || received;
    downloadUrl = link;
    // 无模型时只有最终事件；同样逐字显示完整回复。
    if (finalText.startsWith(received)) append(finalText.slice(received.length));
    completed = true;
    schedule();
    return finished;
  }
  function stop() {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    completed = true;
    node.classList.remove('typing');
    resolveFinished();
  }
  return {append, finish, stop};
}
async function streamChat(input, signal, onChunk) {
  const response = await fetch('/api/chat/stream', {...jsonBody(input), signal});
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `请求失败（${response.status}）`);
  }
  if (!response.body) throw new Error('浏览器不支持流式回复');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;
  try {
    while (true) {
      const {value, done} = await reader.read();
      // CRLF 可能跨网络分片；先拼接再规范化事件分隔符。
      buffer = (buffer + decoder.decode(value || new Uint8Array(), {stream:!done})).replace(/\r\n/g, '\n');
      let end;
      while ((end = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (!data) continue;
        const chunk = JSON.parse(data);
        onChunk(chunk);
        if (chunk.type === 'done' || chunk.type === 'error') finished = true;
      }
      if (done) break;
    }
    if (!finished) throw new Error('回复中断，请重试');
  } finally { reader.releaseLock(); }
}

document.querySelectorAll('.tab').forEach(b => b.onclick = () => view(b.dataset.view));
document.querySelectorAll('[data-close]').forEach(b => b.onclick = () => $(b.dataset.close).close());
$('user').onchange = () => {
  activeChatAbort?.abort(); activeChatTypewriter?.stop();
  invalidateChatImport('当前用户已切换，请重新上传入库单。');
  approvalToken = null; selectedRequest = null;
  $('chat-confirm').hidden = true;
  $('chat-input').value = '';
  $('chat-file').value = '';
  showChatFor(user());
  $('detail-dialog').close();
  refresh().catch(e => notice(e.message, true));
};
$('search').oninput = renderMaterials;
$('show-import').onclick = () => {
  $('receipt-file').value = '';
  resetImportPreview();
  importStatus('请选择“入库单-RK-…”文件，然后预览单据。');
  $('import-dialog').showModal();
};
$('receipt-file').onchange = () => {
  resetImportPreview();
  const file = $('receipt-file').files[0];
  if (!file) return importStatus('请选择“入库单-RK-…”文件，然后预览单据。');
  if (file.name.includes('物料主数据')) {
    importStatus('这是物料主数据，不是入库单。物料已在启动时导入，请选择“入库单-RK-…”文件。', true);
  } else {
    importStatus('文件已选择。点击“预览单据”，校验通过后才能确认入账。');
  }
};
$('export').onclick = () => { location.href = `/api/export?user=${encodeURIComponent(user())}`; };
$('preview-button').onclick = async () => {
  const file = $('receipt-file').files[0];
  if (!file) return importStatus('请先选择“入库单-RK-…”文件。', true);
  if (file.name.includes('物料主数据')) return importStatus('物料主数据不能作为入库单。请选择“入库单-RK-…”文件。', true);
  const form = new FormData(); form.append('file', file);
  const revision = importRevision;
  $('preview-button').disabled = true;
  try {
    const result = await api(`/api/imports/preview?user=${encodeURIComponent(user())}`, {method:'POST', body:form});
    if (revision !== importRevision) return;
    previewToken = result.token;
    const p = result.parsed;
    $('preview').innerHTML = importPreviewMarkup(result);
    $('preview').hidden = false;
    $('confirm-import').disabled = !!p.errors.length || result.alreadyPosted;
    $('confirm-import').title = $('confirm-import').disabled ? '请先修正预览中的错误或更换单据' : '确认后将更新库存';
    importStatus(result.alreadyPosted ? '这张入库单已入账，不能重复确认。' : p.errors.length ? '校验未通过，请查看下方错误并更换单据。' : '校验通过，可以确认入账。', result.alreadyPosted || p.errors.length > 0);
  } catch(e) {
    if (revision === importRevision) { resetImportPreview(); importStatus(e.message, true); }
  } finally { $('preview-button').disabled = false; }
};
$('confirm-import').onclick = async () => {
  $('confirm-import').disabled = true;
  try { const result = await api('/api/imports/confirm', jsonBody({token:previewToken, user:user()})); $('import-dialog').close(); notice(`入库单 ${result.number} 已入账`); await refresh(); }
  catch(e) { notice(e.message, true); }
};
$('new-request').onclick = () => { $('request-items').innerHTML = ''; addRequestRow(); $('request-dialog').showModal(); };
$('add-item').onclick = addRequestRow;
$('submit-request').onclick = async () => {
  const items = [...document.querySelectorAll('.request-row')].map(row => ({code:row.querySelector('select').value, quantity:Number(row.querySelector('input').value)}));
  try { const result = await api('/api/requests', jsonBody({user:user(), items})); $('request-dialog').close(); notice(`领用申请 LY-${result.id} 已提交`); await refresh(); view('requests'); }
  catch(e) { notice(e.message, true); }
};
$('requests').onclick = e => { const id = e.target.closest('[data-request]')?.dataset.request; if (id) detail(id).catch(err => notice(err.message, true)); };
$('withdraw-request').onclick = async () => {
  if (!selectedRequest) return;
  $('withdraw-request').disabled = true;
  try { await api(`/api/requests/${selectedRequest}/withdraw`, jsonBody({user:user()})); $('detail-dialog').close(); notice('申请已撤回'); await refresh(); }
  catch(e) { notice(e.message, true); }
  finally { $('withdraw-request').disabled = false; }
};
$('approve-request').onclick = async () => {
  try { await api(`/api/requests/${selectedRequest}/approve`, jsonBody({user:user()})); $('detail-dialog').close(); notice('审批通过，库存已扣减'); await refresh(); }
  catch(e) { notice(e.message, true); }
};
$('reject-request').onclick = async () => {
  const reason = prompt('请输入驳回原因'); if (reason === null) return;
  try { await api(`/api/requests/${selectedRequest}/reject`, jsonBody({user:user(), reason})); $('detail-dialog').close(); notice('申请已驳回'); await refresh(); }
  catch(e) { notice(e.message, true); }
};
$('chat-upload').onclick = () => $('chat-file').click();
$('chat-file').onchange = () => {
  const file = $('chat-file').files[0];
  $('chat-file').value = '';
  if (file) uploadChatReceipt(file);
};
const assistantPanel = document.querySelector('.assistant-panel');
const hasDraggedFiles = event => Array.from(event.dataTransfer?.types || []).includes('Files');
assistantPanel.addEventListener('dragenter', event => {
  if (!hasDraggedFiles(event)) return;
  event.preventDefault();
  assistantPanel.classList.add('dragging-file');
});
assistantPanel.addEventListener('dragover', event => {
  if (!hasDraggedFiles(event)) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = 'copy';
});
assistantPanel.addEventListener('dragleave', event => {
  if (!assistantPanel.contains(event.relatedTarget)) assistantPanel.classList.remove('dragging-file');
});
assistantPanel.addEventListener('drop', event => {
  if (!hasDraggedFiles(event)) return;
  event.preventDefault();
  assistantPanel.classList.remove('dragging-file');
  const files = event.dataTransfer.files;
  if (files.length !== 1) return addMessage('一次只能导入一张入库单，请选择一个 .xlsx 文件。', 'assistant');
  uploadChatReceipt(files[0]);
});
$('chat-form').onsubmit = async e => {
  e.preventDefault(); const input = $('chat-input'); const message = input.value.trim(); if (!message) return;
  const chatUser = user();
  const abort = new AbortController(); activeChatAbort = abort;
  input.value = ''; input.disabled = true; addMessage(message, 'user');
  approvalToken = null; $('chat-confirm').hidden = true;
  const replyNode = addMessage('正在回复…', 'assistant');
  const typewriter = createTypewriter(replyNode); activeChatTypewriter = typewriter;
  let finalReply = null;
  try {
    await streamChat({user:chatUser, message}, abort.signal, chunk => {
      if (chunk.type === 'delta') typewriter.append(chunk.text);
      else if (chunk.type === 'done') finalReply = chunk;
      else if (chunk.type === 'error') throw new Error(chunk.text || '回复失败');
    });
    if (!finalReply) throw new Error('回复中断，请重试');
    await typewriter.finish(finalReply.text, finalReply.downloadUrl);
    if (abort.signal.aborted) throw new DOMException('已切换用户', 'AbortError');
    approvalToken = finalReply.confirmationId;
    $('chat-confirm').hidden = !approvalToken;
    await refresh();
  } catch(err) { typewriter.stop(); updateMessage(replyNode, err.name === 'AbortError' ? '已切换用户，回复已停止。' : err.message); }
  finally { if (activeChatAbort === abort) activeChatAbort = null; if (activeChatTypewriter === typewriter) activeChatTypewriter = null; input.disabled = false; input.focus(); }
};
$('confirm-approval').onclick = async () => {
  const chatUser = user();
  const token = approvalToken;
  if (!token) return;
  approvalToken = null;
  $('chat-confirm').hidden = true;
  const node = addMessage('正在审批…', 'assistant');
  try {
    const reply = await api('/api/chat', jsonBody({user:chatUser, confirmationId:token}));
    await createTypewriter(node).finish(reply.text, reply.downloadUrl);
    if (chatUser === user()) await refresh();
  }
  catch(err) { updateMessage(node, err.message); }
};
$('cancel-approval').onclick = () => { approvalToken = null; $('chat-confirm').hidden = true; addMessage('已取消审批。', 'assistant'); };
refresh().catch(e => notice(e.message, true)); icons();
