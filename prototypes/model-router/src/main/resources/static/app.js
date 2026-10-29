const $ = (id) => document.getElementById(id);

/**
 * localStorage 在沙箱 iframe（没有 allow-same-origin）、隐私模式、禁用 Cookie 的场景下
 * 访问就会抛异常。之前直接读写它，一抛就把整个状态渲染带崩了——页面只剩一串「–」。
 * 存 key 只是个便利功能，用不了就当没这功能，绝不能拖垮主流程。
 */
function lsGet(key) {
  try {
    return localStorage.getItem(key);
  } catch (e) {
    return null;
  }
}

function lsSet(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    // 存不下就算了
  }
}

async function loadStatus() {
  try {
    const res = await fetch('/ui/api/status');
    if (!res.ok) throw new Error('status ' + res.status);
    renderStatus(await res.json());
    $('service-state').textContent = '运行中';
    $('service-state').className = 'pill ok';
    $('offline-hint').classList.add('hidden');
  } catch (e) {
    console.error('状态加载失败', e);
    $('service-state').textContent = '无法连接';
    $('service-state').className = 'pill bad';
    // 说清楚为什么是一片「–」，别让人以为是界面坏了
    $('offline-hint').classList.remove('hidden');
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
  // 模型候选依赖「全部模型」+「每个渠道声明的模型」两份数据，所以放在两者之后算
  renderSection('试调候选', () => refreshChatModelOptions(false));

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
      $('f-key').value = lsGet('router-api-key') || lsGet('tts-api-key') || '';
    }
  } else {
    // 服务端没配 api-keys 时不需要鉴权，直接隐藏 key 输入框
    $('key-row').classList.add('hidden');
  }
}

/** 全量模型清单（/v1/models 同源）与「渠道id → 该渠道声明的模型」，试调候选要按渠道过滤。 */
let allModels = [];
let channelModels = {};

/**
 * LLM 渠道面板。价目未知是个一等状态，不是「便宜」——用 warn 色标出来，
 * 因为它的账单会记 costUsd=null，看板不能假装那是 0 美元。
 */
function renderChannels(channels) {
  if (!Array.isArray(channels)) {
    $('stat-channels').textContent = '–';
    channelModels = {};
    return;
  }

  // 渠道下拉：后端已经接了多个渠道，页面上就该能挑一个（对应 X-Router-Pin）
  const sel = $('c-channel');
  const prevPin = sel.value;
  sel.innerHTML = '<option value="">自动路由</option>'
    + channels.map((c) => '<option value="' + esc(c.id) + '">' + esc(c.id)
        + '（优先级 ' + c.priority + '）</option>').join('');
  if ([...sel.options].some((o) => o.value === prevPin)) sel.value = prevPin;

  channelModels = {};
  for (const c of channels) channelModels[c.id] = c.models || [];

  const tbody = $('channel-rows');
  tbody.innerHTML = '';
  if (!channels.length) {
    tbody.innerHTML = '<tr><td colspan="7" class="muted">没有已启用的渠道：'
      + '检查 .env 里的 key 是否设置（缺 key 的渠道会在装配期被禁用）</td></tr>';
    $('stat-channels').textContent = '0 / 0';
    return;
  }

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

/** 模型清单（与 /v1/models 同源）。试调候选由 refreshChatModelOptions 单独算。 */
function renderModels(models) {
  allModels = Array.isArray(models) ? models : [];
  const tbody = $('model-rows');
  tbody.innerHTML = '';
  if (!Array.isArray(models) || !models.length) {
    // 空表比空 td 更难看懂：明确写出「没数据」而不是让人以为表格坏了
    tbody.innerHTML = '<tr><td colspan="4" class="muted">暂无模型（后端未连接，或渠道全部被禁用）</td></tr>';
    return;
  }

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

  const aliasCount = allModels.filter((m) => String(m.kind || '').includes('alias')).length;
  $('models-line').textContent = '共 ' + allModels.length + ' 个模型，其中别名入口 ' + aliasCount
    + ' 个（别名本身没有渠道直供，它是降级链的入口）';
}

/**
 * 试调模型候选随所选渠道联动。之前渠道选了 miku、模型框还留着 deepseek-chat，
 * 发出去必然 404——两个控件各填各的，等于埋了个必踩的坑。规则：
 *  - 自动路由：llm + alias 都可填（别名是降级链入口）；
 *  - 锁定渠道：只能填该渠道声明的模型，当前值不匹配就换第一个；
 *  - 用户手输的不强行清空（自动路由下），但锁渠道时不匹配必换。
 */
function refreshChatModelOptions(force) {
  const pin = $('c-channel').value;
  const input = $('c-model');
  const list = $('c-model-list');

  let candidates;
  if (pin) {
    // 锁了渠道：别名没有渠道直供，不在候选里
    candidates = (channelModels[pin] || []);
  } else {
    candidates = allModels
      .filter((m) => String(m.kind || '').match(/llm|alias/))
      .map((m) => m.id);
  }

  list.innerHTML = candidates.map((id) => '<option value="' + esc(id) + '"></option>').join('');
  const valueIn = candidates.some((id) => id === input.value);
  if (!input.value || force || (pin && !valueIn)) {
    input.value = candidates.length ? candidates[0] : '';
  }
  $('chat-meta').textContent = pin
    ? '已锁定 ' + pin + '：只能填它声明的 ' + candidates.length + ' 个模型'
    : (candidates.length ? '可填 ' + candidates.length + ' 个对话模型（含别名入口）' : '');
}

// 渠道一切换，模型候选立刻跟着换：不留「选 A 渠道 + 填 B 渠道模型」的组合
$('c-channel').addEventListener('change', () => refreshChatModelOptions(true));

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
 * 401 单靠后端原文（authentication_error / No API key provided）不够可操作：
 * 把「去哪填 key」说清楚。key 框在 header 右上角，服务端没配 api-keys 时它整个隐藏。
 */
function authHint(status) {
  if (status !== 401) return '';
  const keyRowHidden = $('key-row').classList.contains('hidden');
  return keyRowHidden
    ? '\n\n提示：401 来自上游渠道（右上角 key 框被隐藏说明网关未启用鉴权，被拒的是渠道那侧的 key）。'
    : '\n\n提示：右上角「API Key」框还没填——网关启用了鉴权，/v1/* 都要带 Bearer key。';
}

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
      let msg = 'HTTP ' + res.status + ' ' + (err.type || '') + '\n' + (err.message || '请求失败')
        + authHint(res.status);
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
  return ($('f-key').value || lsGet('router-api-key') || lsGet('tts-api-key') || '').trim();
}

$('f-key').addEventListener('change', (e) => {
  lsSet('router-api-key', e.target.value.trim());
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
      showError('HTTP ' + res.status + (err.code ? ' ' + err.code : '') + '\n' + (err.message || '请求失败')
        + authHint(res.status));
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
