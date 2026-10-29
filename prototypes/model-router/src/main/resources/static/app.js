const $ = (id) => document.getElementById(id);

async function loadStatus() {
  try {
    const res = await fetch('/ui/api/status');
    if (!res.ok) throw new Error('status ' + res.status);
    renderStatus(await res.json());
    $('service-state').textContent = '运行中';
    $('service-state').className = 'pill ok';
  } catch (e) {
    console.error('状态加载失败', e);
    $('service-state').textContent = '无法连接';
    $('service-state').className = 'pill bad';
  }
}

/** 分段渲染：某一块出错只丢那一块，不能让整页白屏（看板自己先挂掉最尴尬）。 */
function renderSection(label, fn) {
  try {
    fn();
  } catch (e) {
    console.error(label + ' 渲染失败', e);
  }
}

function renderStatus(s) {
  $('stat-requests').textContent = fmtInt(s.usage.requests);
  $('stat-chars').textContent = fmtInt(s.usage.chars);
  $('stat-cache').textContent = fmtBytes(s.cache.bytes) + ' / ' + fmtBytes(s.cache.maxBytes);

  const llm = s.llmUsage;
  if (llm) {
    $('stat-llm-calls').textContent = fmtInt(llm.calls);
    $('stat-llm-cost').textContent = '$' + Number(llm.knownCostUsd).toFixed(6);
    // 价目未知的那几笔不能并进总额，也不能显示成 0：单独说清楚有几笔、是哪些渠道
    $('stat-llm-unknown').textContent = llm.unknownCostCalls
      ? llm.unknownCostCalls + ' 笔价目未知（成本记 null，不是免费）'
      : '';
    $('stat-llm-unknown').className = llm.unknownCostCalls ? 'card-note warn-text' : 'card-note';
  }

  renderSection('模型清单', () => renderModels(s.models));

  renderSection('语音引擎', () => {
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
  });

  $('quota-line').textContent = (s.quota.enabled ? '日配额 ' + fmtInt(s.quota.dailyChars) + ' 字符'
      : '配额未启用') + ' · 响应格式 ' + s.formats.join(' / ');

  renderSection('LLM 渠道', () => renderChannels(s.channels));

  // 语音下拉只该出现语音模型（kind=tts）；把对话模型喂给 /v1/audio/speech 必然失败
  const modelSel = $('f-model');
  const prev = modelSel.value;
  const speechModels = (s.models || []).filter((m) => String(m.kind || '').includes('tts'));
  modelSel.innerHTML = speechModels.map((m) => '<option value="' + esc(m.id) + '">'
      + esc(m.id) + '</option>').join('');
  if ([...modelSel.options].some((o) => o.value === prev)) modelSel.value = prev;

  if (s.authRequired) {
    $('key-row').classList.remove('hidden');
    // 只在输入框为空时才回填，避免周期刷新把用户正在输入的 key 冲掉
    if (!$('f-key').value) {
      $('f-key').value = localStorage.getItem('router-api-key')
        || localStorage.getItem('tts-api-key') || '';
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
  if (!Array.isArray(channels)) {
    $('stat-channels').textContent = '–';
    return;
  }

  // 渠道下拉：后端已经接了多个渠道，页面上就该能挑一个（对应 X-Router-Pin）
  const sel = $('c-channel');
  const prevPin = sel.value;
  sel.innerHTML = '<option value="">自动路由</option>'
    + channels.map((c) => '<option value="' + esc(c.id) + '">' + esc(c.id)
        + '（优先级 ' + c.priority + '）</option>').join('');
  if ([...sel.options].some((o) => o.value === prevPin)) sel.value = prevPin;

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
    const today = c.today;
    // 没价目的渠道，今日成本只能写「未知」——写 $0.000000 就是在账上造假
    const todayCost = !today ? '–'
      : (c.priceKnown ? '$' + Number(today.knownCostUsd).toFixed(6)
        : '<span class="pill warn">' + fmtInt(today.unknownCostCalls) + ' 笔未知</span>');
    tr.innerHTML = '<td><code>' + esc(c.id) + '</code></td>'
      + '<td>' + c.priority + '</td>'
      + '<td>' + (c.models || []).map(esc).join(', ') + '</td>'
      + '<td>' + price + '</td>'
      + '<td>' + (today ? fmtInt(today.calls) : '0') + '</td>'
      + '<td>' + todayCost + '</td>'
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

/** 模型清单（与 /v1/models 同源），并把能走对话的模型填进试调下拉。 */
function renderModels(models) {
  const tbody = $('model-rows');
  tbody.innerHTML = '';
  if (!Array.isArray(models)) return;

  for (const m of models) {
    const tr = document.createElement('tr');
    const kindText = { tts: '语音', llm: '渠道', alias: '别名' };
    const kinds = String(m.kind || '').split('+').map((k) => kindText[k] || k).join('+');
    tr.innerHTML = '<td><code>' + esc(m.id) + '</code></td>'
      + '<td>' + esc(kinds) + '</td>'
      + '<td>' + (m.owners || []).map(esc).join(' → ') + '</td>'
      + '<td>' + (m.available ? '<span class="pill ok">可用</span>'
          : '<span class="pill warn">不可用</span>') + '</td>';
    tbody.appendChild(tr);
  }

  // 只有 llm / alias 能进对话模型候选：把语音模型喂给 /v1/chat/completions 必然 503
  const chatModels = models.filter((m) => String(m.kind || '').match(/llm|alias/));
  const list = $('c-model-list');
  list.innerHTML = chatModels.map((m) => '<option value="' + esc(m.id) + '"></option>').join('');
  // 首次进入给个默认值，之后尊重用户手输的内容
  const input = $('c-model');
  if (!input.value && chatModels.length) {
    input.value = chatModels[0].id;
  }
  $('chat-meta').textContent = '可填 ' + chatModels.length + ' 个对话模型（含别名入口）';

  const aliasCount = models.filter((m) => String(m.kind || '').includes('alias')).length;
  $('models-line').textContent = '共 ' + models.length + ' 个模型，其中别名入口 ' + aliasCount
    + ' 个（别名本身没有渠道直供，它是降级链的入口）';
}

// 标签页：对话轴与语音轴分开，互不干扰（语音轴已冻结，默认不占版面）
for (const btn of document.querySelectorAll('.tab-btn')) {
  btn.addEventListener('click', () => {
    for (const other of document.querySelectorAll('.tab-btn')) {
      other.classList.toggle('active', other === btn);
    }
    for (const view of document.querySelectorAll('.tab-view')) {
      view.classList.toggle('hidden', view.id !== 'tab-' + btn.dataset.tab);
    }
  });
}

$('f-speed').addEventListener('input', (e) => {
  $('speed-value').textContent = Number(e.target.value).toFixed(2);
});

$('refresh-btn').addEventListener('click', loadStatus);

/**
 * LLM 试调：真发一个 /v1/chat/completions，把路由结果（走了哪个渠道、耗时、成本）
 * 直接摆出来。路由做得对不对，光看配置是看不出来的，得发一发。
 */
$('chat-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const btn = $('chat-btn');
  const prompt = $('c-prompt').value.trim();
  if (!prompt || btn.disabled) return;

  btn.disabled = true;
  btn.textContent = '发送中…';
  $('chat-error').classList.add('hidden');
  $('chat-result').classList.add('hidden');
  $('chat-meta').textContent = '';

  const stream = $('c-stream').checked;
  const pin = $('c-channel').value;
  const started = performance.now();
  try {
    const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + apiKey() };
    // 选了具体渠道就走 X-Router-Pin：让人能绕开自动选路去验证某个渠道本身
    if (pin) headers['X-Router-Pin'] = pin;
    const res = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        model: $('c-model').value.trim(),
        messages: [{ role: 'user', content: prompt }],
        max_tokens: parseInt($('c-max-tokens').value, 10),
        stream,
      }),
    });
    const elapsed = Math.round(performance.now() - started);
    const channel = res.headers.get('X-Router-Channel') || '未知';
    const cost = res.headers.get('X-Router-Cost-Usd');

    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const err = body.error || {};
      let msg = 'HTTP ' + res.status + ' ' + (err.type || '') + '\n' + (err.message || '请求失败');
      // 全渠道失败时后端会带回逐渠道原因，这是排障最关键的一段，不能只显示一句 502
      if (err.channels) {
        msg += '\n\n各渠道失败原因：';
        for (const [id, reason] of Object.entries(err.channels)) {
          msg += '\n  · ' + id + '：' + reason;
        }
      }
      $('chat-error').textContent = msg;
      $('chat-error').classList.remove('hidden');
      return;
    }

    let content = '';
    let usage = null;
    if (stream) {
      const text = await res.text();
      const frames = text.split('\n').filter((l) => l.startsWith('data: '));
      // 流式没有响应头的成本（要等尾帧），这里如实说明，不猜
      content = frames.map((f) => {
        try {
          return JSON.parse(f.slice(6)).choices?.[0]?.delta?.content || '';
        } catch (e) {
          return '';
        }
      }).join('');
      $('chat-route').innerHTML = '渠道 <code>' + esc(channel) + '</code>'
        + (pin ? '（已锁定）' : '（自动选路）') + ' · ' + frames.length
        + ' 帧 · ' + elapsed + 'ms · 流式成本见账本（<code>ops/usage-report.py</code>）';
    } else {
      const body = await res.json();
      content = body.choices?.[0]?.message?.content || '';
      usage = body.usage;
      $('chat-route').innerHTML = '渠道 <code>' + esc(channel) + '</code>'
        + (pin ? '（已锁定）' : '（自动选路）') + ' · ' + elapsed + 'ms'
        + (usage ? ' · ' + fmtInt(usage.prompt_tokens) + ' 入 / ' + fmtInt(usage.completion_tokens) + ' 出' : '')
        + ' · 成本 ' + (cost && cost !== 'unknown'
          ? '$' + Number(cost).toFixed(6)
          : '<span class="pill warn">价目未知</span>');
    }

    $('chat-content').textContent = content || '（无内容）';
    $('chat-result').classList.remove('hidden');
    loadStatus();
  } catch (e) {
    $('chat-error').textContent = '请求失败：' + e.message;
    $('chat-error').classList.remove('hidden');
  } finally {
    btn.disabled = false;
    btn.textContent = '发送';
  }
});

/** 语音与对话共用一把 key：存一次，两个表单都能用。 */
function apiKey() {
  return ($('f-key').value || localStorage.getItem('router-api-key')
    || localStorage.getItem('tts-api-key') || '').trim();
}

$('f-key').addEventListener('change', (e) => {
  localStorage.setItem('router-api-key', e.target.value.trim());
});

$('f-key').addEventListener('change', (e) => {
  localStorage.setItem('router-api-key', e.target.value.trim());
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
  const key = apiKey();
  if (key) {
    headers['Authorization'] = 'Bearer ' + key;
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
