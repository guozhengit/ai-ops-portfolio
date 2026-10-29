const $ = (id) => document.getElementById(id);

async function loadStatus() {
  try {
    const res = await fetch('/ui/api/status');
    if (!res.ok) throw new Error('status ' + res.status);
    renderStatus(await res.json());
    $('service-state').textContent = '运行中';
    $('service-state').className = 'pill ok';
  } catch (e) {
    $('service-state').textContent = '无法连接';
    $('service-state').className = 'pill bad';
  }
}

function renderStatus(s) {
  $('stat-requests').textContent = fmtInt(s.usage.requests);
  $('stat-chars').textContent = fmtInt(s.usage.chars);
  $('stat-cached').textContent = fmtInt(s.usage.cached);
  $('stat-cache').textContent = fmtBytes(s.cache.bytes) + ' / ' + fmtBytes(s.cache.maxBytes);

  const tbody = $('engine-rows');
  tbody.innerHTML = '';
  for (const e of s.engines) {
    const tr = document.createElement('tr');
    const state = e.available
      ? '<span class="pill ok">可用</span>'
      : '<span class="pill warn">冷却中' + (e.cooldownUntil ? ' 至 ' + new Date(e.cooldownUntil).toLocaleTimeString() : '') + '</span>';
    tr.innerHTML = '<td><code>' + esc(e.id) + '</code></td>'
      + '<td>' + e.priority + '</td>'
      + '<td>' + e.models.map(esc).join(', ') + '</td>'
      + '<td>' + state + '</td>';
    tbody.appendChild(tr);
  }

  $('quota-line').textContent = (s.quota.enabled ? '日配额 ' + fmtInt(s.quota.dailyChars) + ' 字符'
      : '配额未启用') + ' · 响应格式 ' + s.formats.join(' / ');

  renderChannels(s.channels);

  const modelSel = $('f-model');
  const prev = modelSel.value;
  modelSel.innerHTML = s.models.map((m) => '<option value="' + esc(m.id) + '">' + esc(m.id) + '</option>').join('');
  if ([...modelSel.options].some((o) => o.value === prev)) modelSel.value = prev;

  if (s.authRequired) {
    $('key-row').classList.remove('hidden');
    // 只在输入框为空时才回填，避免周期刷新把用户正在输入的 key 冲掉
    if (!$('f-key').value) {
      $('f-key').value = localStorage.getItem('tts-api-key') || '';
    }
  } else {
    // 服务端没配 api-keys 时不需要鉴权，直接隐藏 key 输入框
    $('key-row').classList.add('hidden');
  }
}

/**
 * LLM 渠道面板。价目未知是个一等状态，不是「便宜」——用 warn 色标出来，
 * 因为它的账单会记 costUsd=null，看板不能假装那是 0 美元。
 */
function renderChannels(channels) {
  const panel = $('llm-panel');
  if (!Array.isArray(channels)) {
    // 老版本 status 没有 channels 字段：整个面板藏掉，不要渲染一张空表
    panel.classList.add('hidden');
    $('stat-channels').textContent = '–';
    return;
  }
  panel.classList.remove('hidden');

  const tbody = $('channel-rows');
  tbody.innerHTML = '';
  for (const c of channels) {
    const tr = document.createElement('tr');
    const state = c.available
      ? '<span class="pill ok">可用</span>'
      : '<span class="pill warn">冷却中' + (c.cooldownUntil ? ' 至 ' + new Date(c.cooldownUntil).toLocaleTimeString() : '') + '</span>';
    const price = c.priceKnown
      ? '有价目'
      : '<span class="pill warn">未知</span>';
    tr.innerHTML = '<td><code>' + esc(c.id) + '</code></td>'
      + '<td>' + c.priority + '</td>'
      + '<td>' + (c.models || []).map(esc).join(', ') + '</td>'
      + '<td>' + price + '</td>'
      + '<td>' + state + '</td>';
    tbody.appendChild(tr);
  }

  const alive = channels.filter((c) => c.available).length;
  $('stat-channels').textContent = alive + ' / ' + channels.length;
  const unknownPriced = channels.filter((c) => !c.priceKnown).map((c) => c.id);
  $('channel-line').textContent = '选路：优先级升序 → 同级按输出单价升序（价目未知排最后）'
    + (unknownPriced.length ? ' · 价目未知（成本记 null）：' + unknownPriced.join(', ') : '')
    + ' · 锁定渠道用 X-Router-Pin 头或 <渠道id>:<模型名> 前缀';
}

$('f-speed').addEventListener('input', (e) => {
  $('speed-value').textContent = Number(e.target.value).toFixed(2);
});

$('refresh-btn').addEventListener('click', loadStatus);

$('f-key').addEventListener('change', (e) => {
  localStorage.setItem('tts-api-key', e.target.value.trim());
});

$('speech-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const btn = $('synth-btn');
  const text = $('f-text').value.trim();
  if (!text || btn.disabled) return;
  btn.disabled = true;
  btn.textContent = '合成中…';
  $('error').classList.add('hidden');
  $('result').classList.add('hidden');

  const headers = { 'Content-Type': 'application/json' };
  const key = $('f-key').value.trim();
  if (key) {
    headers['Authorization'] = 'Bearer ' + key;
    localStorage.setItem('tts-api-key', key);
  }

  try {
    const res = await fetch('/v1/audio/speech', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        model: $('f-model').value,
        voice: $('f-voice').value.trim(),
        input: text,
        speed: parseFloat($('f-speed').value),
        response_format: $('f-format').value,
      }),
    });

    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const err = body.error || {};
      showError('HTTP ' + res.status + (err.code ? ' ' + err.code : '') + '\n' + (err.message || '请求失败'));
      return;
    }

    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const player = $('player');
    player.src = url;
    $('download').href = url;
    $('download').download = 'speech.' + $('f-format').value;
    $('result').classList.remove('hidden');
    player.play().catch(() => {});
    $('synth-meta').textContent = fmtBytes(blob.size)
      + ' · 引擎 ' + res.headers.get('X-Engine')
      + ' · 缓存 ' + res.headers.get('X-Cache')
      + ' · 今日 ' + res.headers.get('X-Usage-Chars-Today') + ' 字符';
    loadStatus();
  } catch (e) {
    showError('请求失败：' + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = '合成语音';
  }
});

function showError(msg) {
  const el = $('error');
  el.textContent = msg;
  el.classList.remove('hidden');
}

function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

function fmtInt(n) {
  return Number(n).toLocaleString('zh-CN');
}

function fmtBytes(n) {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(2) + ' MB';
}

loadStatus();
setInterval(loadStatus, 30000);
