# Jev 聊天助手 · WA (WAuxiliary) 版

适配 **WA（WAuxiliary_Plugin）** 微信框架的聊天辅助脚本。对方私聊发消息后，本地立刻判断意图 / 危险等级 / 情绪；可选接入**任意 OpenAI 兼容大模型**（小米 MiMo、DeepSeek、通义、智谱、OpenAI 等）给出建议和 3 条候选回复，候选自动复制到剪贴板，**手动粘贴发送，绝不自动发消息**。

> 本项目**只适配 WA（WAuxiliary）微信模块**，需要自行把脚本导入 WA 才能使用。
> QStory 版：[kongbai006/jev-qstory-mimo](https://github.com/kongbai006/jev-qstory-mimo)
> Nuke 版：[kongbai006/jev-nuke-mimo](https://github.com/kongbai006/jev-nuke-mimo)

## 使用前提

- 手机微信已安装 **WA（WAuxiliary_Plugin）** 模块（Xposed 类，需 Root / LSPatch）；
- 一个 **OpenAI 兼容** API Key（任意厂商，默认配的是小米 MiMo，可改成 DeepSeek/通义/智谱/OpenAI 等）。只用本地判断则不需要联网。

## 安装

1. 从 Release 下载 `Jev聊天助手WA_vX.X.zip`；
2. 在 WA 里导入该脚本（含 `main.java` + `info.prop`）；
3. 进入要分析的微信私聊，聊天框输入 `/jev` 打开配置页；
4. 点「开启本会话」——**只有开启的会话才会分析**，同时只开一个；
5. （可选）打开「接入大模型决策」，填 API 地址 / 密钥 / 模型。

## 配置项（聊天框输入 `/jev`）

| 配置项 | 说明 |
| --- | --- |
| 作用域 | 开启/关闭当前会话，同时只开一个 |
| 自动分析对方消息 | 总开关 |
| 接入大模型决策 | 关=只本地秒判；开=再调大模型出建议+候选 |
| 撤回第一轮本地判断 | 开=AI 返回后撤回本地那条灰字；关=保留本地判断，AI 结果作为新消息追加 |
| API 密钥 | 任意厂商 `sk-` 密钥，默认空 |
| API 地址 | OpenAI 兼容 `/v1` 地址；默认 MiMo `https://api.xiaomimimo.com/v1` |
| 模型 | 模型名；默认 `mimo-v2.6-flash`，可改成 `deepseek-chat` / `qwen-plus` 等 |
| 关系描述 | 默认「对方是我的关系亲密的对象」 |
| 上下文轮数 | 0–30，取最近 N 条历史辅助判断，0=不取 |
| 排版宽度 | 系统消息补全角空格宽度，0=自动，一般不用动 |

## 常见厂商填法

| 厂商 | API 地址 | 模型示例 |
| --- | --- | --- |
| 小米 MiMo（默认） | `https://api.xiaomimimo.com/v1` | `mimo-v2.6-flash` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 阿里通义 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |

### 选模型提示：用「非推理模型」响应最快

聊天助手这种短任务，**优先选非推理模型（flash / turbo / mini / chat 类）**，端到端通常 2–4 秒出结果。带 `reasoner` / `o1` / `r1` / 思考类的推理模型会先输出一段思考，再给答案，往往要 8–20 秒，且容易把 token 花在思考上导致 `content` 为空。

推荐试这几个（都是 OpenAI 兼容、快、便宜）：

- 小米 MiMo：`mimo-v2.6-flash`（默认，国内直连）
- DeepSeek：`deepseek-chat`（**不要用** `deepseek-reasoner`）
- 阿里通义：`qwen-turbo` / `qwen-plus`
- 智谱：`glm-4-flash`
- OpenAI：`gpt-4o-mini`
- 第三方聚合（如 right.codes / openrouter）：找名字里带 `flash` / `turbo` / `mini` / `chat` 的，避开 `reasoner` / `r1` / `o1`

## 行为说明

- 只分析单人私聊，群聊忽略；
- 情绪分 开心 / 难过 / 生气，无匹配显示「平静」；
- 候选回复**不会自动发**，会自动复制到系统剪贴板，在微信输入框长按粘贴后挑一条发；
- 大模型返回时会先撤回本地那条系统消息（微信会提示"你撤回了一条消息"，这是微信自身行为，插件无法隐藏；不想要这个提示可在配置里关掉「撤回第一轮本地判断」开关）；
- 判断结果仅供娱乐参考，本工具不绕过任何平台安全机制。
