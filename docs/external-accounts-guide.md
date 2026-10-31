# 外部账号申请与配置步骤（期 3 前置）

> 用途：解锁 V2 期 3 的两件事——渠道真机融入（tier 真机版 / upgrade path 真机版）+ 信任标定 G3 代际。
> 分工：**Key 由你本人申请、本人填入 `.env`，任何人都不要把明文交给别人（包括我）**。
> 我只接触指纹（sha256 前 8 位）和验证结果。
> 状态：2026-10-31 核实各平台入口与端点后成文；模型名以你申请当天控制台为准。

## 0. 总览：申请什么、为什么

| 供应商 | 环境变量名（项目约定） | 用途 | 优先级 |
| --- | --- | --- | --- |
| 智谱 GLM | `ZHIPU_API_KEY` | 渠道融入 + **信任标定 G3 代际**（第三个模型代际） | ★★★ 必申请 |
| 阿里云百炼 DashScope | `DASHSCOPE_API_KEY` | 渠道融入 + tier 真机版复跑 | ★★★ 必申请 |
| LM Studio（本地） | 无 Key | 本地兜底档（README 未达成项之一） | ★ 可选 |

预计耗时：智谱 10 分钟、百炼 15 分钟（含实名）、LM Studio 30 分钟（含模型下载）。

## 1. 智谱 GLM（★★★）

1. 打开 [bigmodel.cn](https://bigmodel.cn)，注册/登录（手机号即可），完成个人认证
2. 进入个人中心 → **API Keys** → 创建密钥，复制保存（只显示一次）
   - ⚠️ 平台上**个人套餐、团队套餐、模型标准 API 的 Key 分开管理**——我们要的是**标准 API Key**（按量计费）；如果你另买了 GLM Coding Plan，那是另一把 Key 走另一端点，别混用
3. 打开 [快速开始文档](https://docs.bigmodel.cn/cn/guide/start/quick-start)，在模型清单页**抄下三样东西**（渠道四元组的另外两元）：
   - 你要用的**模型名**（建议一强一快两档，例如当前旗舰档 + Flash 快档的准确拼写）
   - **价目**：输入 / 输出各多少元 / MTok（渠道四元组必须有价目——这是唯一能根治「miku 式未知价目」的机会，申请时顺手抄，10 秒钟的事）
   - OpenAI 兼容端点：`https://open.bigmodel.cn/api/paas/v4`
4. 自助冒烟（可选，1 分钟）：
   ```bash
   curl -s https://open.bigmodel.cn/api/paas/v4/chat/completions \
     -H "Authorization: Bearer 你的key" -H "Content-Type: application/json" \
     -d '{"model":"模型名","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'
   ```
   返回 JSON 带 `choices` 即通。
5. 填入 `.env`（**你自己编辑，文件已被 gitignore**）：
   ```
   ZHIPU_API_KEY=粘贴你的key
   ```

## 2. 阿里云百炼 DashScope（★★★）

1. 注册/登录阿里云账号并完成**实名认证**（个人版即可）
2. 进入 [百炼控制台](https://bailian.console.aliyun.com)，首次会提示**开通模型服务**（有免费额度，开通不收费）
3. 控制台右上角 → **API-Key 管理** → 创建（官方指引见 [获取 API Key](https://help.aliyun.com/zh/model-studio/get-api-key)）
   - ⚠️ **Base URL、Key、模型必须同地域**：国内站用北京 `https://dashscope.aliyuncs.com/compatible-mode/v1`；若你在国际站开户则是 dashscope-intl 域名。**建议北京**
4. 抄下：**Qwen 模型名**（Qwen3 全系，选一强一快两档的准确拼写）+ **价目**（输入/输出 /MTok）
5. 自助冒烟（可选）：
   ```bash
   curl -s https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \
     -H "Authorization: Bearer 你的key" -H "Content-Type: application/json" \
     -d '{"model":"qwen-flash","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'
   ```
6. 填入 `.env`：
   ```
   DASHSCOPE_API_KEY=粘贴你的key
   ```

## 3.（可选）LM Studio 本地兜底档

无账号、无 Key、断网可用——README 未达成项里「本地兜底档位」的实体：

1. 下载安装 [LM Studio](https://lmstudio.ai)
2. 搜索并下载一个小模型（`.env.example` 里预留的是 Qwen3.5-9B，本机模型目录 `D:\AI-Models`）
3. Developer 标签页 → Start Server（默认 `http://localhost:1234/v1`，与 `.env.example` 的 `LMSTUDIO_BASE_URL` 一致，API Key 随便填 `lm-studio`）
4. 冒烟：`curl http://localhost:1234/v1/models` 返回模型清单即通

## 4. Key 就位后交回给我做什么（你不用动代码）

你完成第 1、2 步并告诉我「key 好了」，我按序执行（全程不读明文，只看验证结果）：

1. **渠道四元组入 yml**：`glm` / `dashscope` 两个渠道条目（base-url + api-key-env + 你抄回的模型名 + 价目），priority 排进现有 5 渠道之后
2. **渠道真机复测**：跑 `ops/verify_channels.py` → `data/channel-report.json` 更新（401/404/价目核对全留原文）
3. **known-defects 销账**：删「GLM / DashScope 渠道未接」挂账，留复测证据
4. **tier 矩阵真机版**：ChannelTierMatrixTest 的桩换真渠道跑一轮
5. **G3 代际**：self-healing-agent 以 GLM 为主模型跑信任标定场景集 → 三代际误拦率表成型 → 期 2 最后一格 + 期 3 主数据
6. **归因卡**：所有新数字附 run_spec（key 只记指纹）

## 5. 安全纪律（不变的老三条）

- Key 只进 `.env`（已被 gitignore 覆盖）；`.env.example` 永远保持占位符
- 仓库内一切引用走**环境变量名**（`api-key-env: ZHIPU_API_KEY`），永不落明文
- 提交前 CI 密钥扫描是最后防线——假凭据都要显式豁免，真凭据漏进去代价完全不对等
