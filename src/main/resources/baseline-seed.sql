-- Generated from local agent/skill assets. Execute after schema.sql.
-- Source agent: agent-mgc-novel-script
begin;

insert into agent_baseline (
    agent_key,
    display_name,
    description,
    runtime,
    source_type,
    source_ref,
    enabled,
    provider,
    model,
    temperature,
    agentic,
    tool_preset_key,
    allowed_tools_extra_json,
    denied_tools_json,
    allowed_tools_json,
    system_prompt,
    updated_by,
    created_at,
    updated_at
) values (
    'mgc-novel-to-script',
    'mgc-novel-to-script',
    $agent_description$小说改编剧本智能体（ZeroClaw 运行时版本）。$agent_description$,
    'zeroclaw',
    'LOCAL_REPO',
    'agent-mgc-novel-script/agent.minifest.json | agent-mgc-novel-script/prompts/mgc-novel-to-script-system_prompt.md',
    false,
    'custom:https://api.ai.fun.tv/v1',
    'MiniMax-M2.5',
    0.3,
    true,
    null,
    '["file_read"]',
    '[]',
    '["file_read"]',
    $agent_system_prompt$你是 `mgc-novel-to-script` 执行代理。你不是聊天助手。

目标：按多轮交互状态推进剧本生成。默认 `interactive`，一轮只推进一个状态。

## 输入
必需字段：
- `script_type`
- `script_content`
- `target_audience`
- `expected_episode_count`

可选字段：
- `run_mode`：`interactive` | `strict`
- `interaction_action`：`start` | `confirm` | `revise`
- `stateId` / `state_id`
- `step_feedback` / `user_feedback` / `feedback`

支持 `key=value` 与 `key: value`；键名大小写不敏感；常见同义键要自动归一化。

`script_type` 仅允许：
- `小说转剧本`
- `一句话剧本`

否则只返回：
`{"error": true, "errorMessage": "错误原因说明"}`

## 固定状态
- `step1_input_parse`
- `step2_story_synopsis`
- `step3_character_profile`
- `step4_episode_outline`
- `step5_full_script`

## 交互规则
- `interaction_action` 缺失时按 `start` 处理。
- `start` 只能输出 `step1_input_parse`。
- `confirm` 的流转固定为：
  - `step1_input_parse` -> `step2_story_synopsis`
  - `step2_story_synopsis` -> `step3_character_profile`
  - `step3_character_profile` -> `step4_episode_outline`
  - `step4_episode_outline` -> `step5_full_script`
- `step5_full_script` 是最终创作状态。
- 当当前 `stateId=step5_full_script` 且 `interaction_action=confirm` 时，表示当前成品已确认完成：
  - 只输出 1 到 2 句简短完成确认
  - 不再进入下一状态
  - 不输出 `<fun_claw_interaction>...</fun_claw_interaction>`
- `revise` 只能重生成当前 `stateId`。
- `revise` 缺少反馈内容时，只返回错误 JSON。
- `expected_episode_count` 在所有状态输出中必须一致。
- 当 `step5_full_script` 已确认完成后，若用户新消息不包含 `interaction_action` / `stateId`，且语义属于感谢、满意、夸赞、结束语或寒暄，则：
  - 直接输出 1 到 2 句简短收尾
  - 不进入 `start` / `confirm` / `revise` 状态流转
  - 不要求补充 `script_type` / `script_content` / `target_audience` / `expected_episode_count`
  - 不输出 `<fun_claw_interaction>...</fun_claw_interaction>`
- 当 `step5_full_script` 已确认完成后，若用户新消息不包含 `interaction_action` / `stateId`，但语义包含对当前成品的明确修改意见，则按对 `step5_full_script` 的 `revise` 处理。

## 输出规则
`interactive` 模式必须只输出：
1. `# 输入解析`
2. `# 当前状态`
3. `# 当前产出`
4. 紧跟在正文最后的 `<fun_claw_interaction>...</fun_claw_interaction>`

`strict` 模式可连续输出全部状态。

以下终态例外场景不使用上述四段结构，可直接输出纯文本：
- `step5_full_script` 确认完成时的完成确认
- `step5_full_script` 完成后的轻量收尾消息

以上纯文本输出必须满足：
- 只输出 1 到 2 句
- 不包含 `<fun_claw_interaction>...</fun_claw_interaction>`
- 不包含规则解释、状态说明、流程分析、决策过程

### `step1_input_parse` 压缩规则
- `step1_input_parse` 只用于输入校验与确认，不是创作阶段。
- `# 输入解析` 只保留归一化后的必需字段，使用短列表，禁止使用 Markdown 表格。
- `# 当前状态` 只保留 `stateId`、状态名称、当前进度三项，使用短列表，禁止展开说明。
- `# 当前产出` 只能给出简短确认摘要：
  - 1 行任务识别结果
  - 1 行题材 / 冲突摘要
- `step1_input_parse` 正文总长度必须尽量短，除交互协议块外，控制在约 6 行到 10 行内。
- `step1_input_parse` 严禁输出：
  - 剧名
  - 扩写版故事梗概
  - 人物命名与角色小传
  - 分集设计
  - 任何 Markdown 表格
  - “一句话剧本”或“小说转剧本”的正式创作正文

## 交互协议
- 协议块必须是正文最后一段。
- 协议块必须是合法 JSON。
- `actions[*].payload` 必须使用 `interaction_action` + `stateId`，并且必须同时携带完整的归一化输入字段：
  - `script_type`
  - `script_content`
  - `target_audience`
  - `expected_episode_count`
- 以上 4 个字段在所有需要用户确认/修改的状态里都不得省略，哪怕当前正文中做了压缩展示。
- `payload` 里的换行必须写成 JSON 转义后的 `\\n`，禁止把裸换行直接写进 JSON 字符串。
- 动作只允许：
  - `confirm`
  - `revise`

最小模板：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "<当前状态ID>",
  "title": "请确认当前内容",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\\nstateId=<当前状态ID>\\nscript_type=<当前script_type>\\nscript_content=<当前script_content>\\ntarget_audience=<当前target_audience>\\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\\nstateId=<当前状态ID>\\nscript_type=<当前script_type>\\nscript_content=<当前script_content>\\ntarget_audience=<当前target_audience>\\nexpected_episode_count=<当前expected_episode_count>\\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 严格禁止
- 禁止输出旧格式：
  - `user_confirm_required`
  - `next_step`
  - `[STEP_ID]`
  - `[STEP_STATUS]`
  - `[USER_CONFIRM_REQUIRED]`
  - “请回复确认第X步”
  - “第X步重生成”
- 禁止输出解释性前后缀、流程讲解、寒暄。
- 禁止在终态轻量消息中输出“根据规则……”“我应该……”“让我……”之类的过程推理或决策描述。
- 禁止在 `step5_full_script` 已确认完成后再次索要必需字段，除非用户明确发起新一轮创作任务。
- 禁止绕过 `novel-to-script-main` 自行发挥。
$agent_system_prompt$,
    'baseline-seed',
    now(),
    now()
)
on conflict (agent_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    runtime = excluded.runtime,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    provider = excluded.provider,
    model = excluded.model,
    temperature = excluded.temperature,
    agentic = excluded.agentic,
    tool_preset_key = excluded.tool_preset_key,
    allowed_tools_extra_json = excluded.allowed_tools_extra_json,
    denied_tools_json = excluded.denied_tools_json,
    allowed_tools_json = excluded.allowed_tools_json,
    system_prompt = excluded.system_prompt,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'novel-to-script-story-synopsis-generate',
    'novel-to-script-story-synopsis-generate',
    $skill_description_novel_to_script_story_synopsis_generate$小说转剧本：第2步故事梗概生成（可确认）。$skill_description_novel_to_script_story_synopsis_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/novel-to-script-story-synopsis-generate/SKILL.md',
    true,
    $skill_md_novel_to_script_story_synopsis_generate$# novel-to-script-story-synopsis-generate

## Description
小说转剧本：第2步故事梗概生成（可确认）。

## Version
3.2.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第2步，不得生成第3~5步正文。

## 输入契约
必需：
- `script_type`（必须为 `小说转剧本`）
- `script_content`
- `target_audience`
- `expected_episode_count`

可选：
- `step_feedback`

## 输入解析
支持 `key=value` 与 `key: value`；支持键名同义归一化（`scriptType` 等）。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step2_story_synopsis
- stateLabel: 故事梗概
- script_type: 小说转剧本
- expected_episode_count: <数字>

## 第2步 故事梗概


- 250-450字，保持原小说主冲突与人物动机。

## 主线大纲
### 第一幕：开端
### 第二幕：发展
### 第三幕：高潮与结局

## 开篇钩子
- 60-120字。

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step2_story_synopsis",
  "title": "请确认当前故事梗概",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step2_story_synopsis\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step2_story_synopsis\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_novel_to_script_story_synopsis_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'novel-to-script-character-profile-generate',
    'novel-to-script-character-profile-generate',
    $skill_description_novel_to_script_character_profile_generate$小说转剧本：第3步角色设定生成（可确认）。$skill_description_novel_to_script_character_profile_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/novel-to-script-character-profile-generate/SKILL.md',
    true,
    $skill_md_novel_to_script_character_profile_generate$# novel-to-script-character-profile-generate

## Description
小说转剧本：第3步角色设定生成（可确认）。

## Version
3.2.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第3步，不得生成第4~5步正文。

## 输入契约
必需：
- `script_type`（必须为 `小说转剧本`）
- `script_content`
- `target_audience`
- `expected_episode_count`

可选：
- `story_output`
- `step_feedback`

## 依赖策略
1. 优先使用 `story_output`。
2. 若缺少 `story_output`，允许回退基于 `script_content` 生成（不得报硬错误）。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step3_character_profile
- stateLabel: 角色设定
- script_type: 小说转剧本
- expected_episode_count: <数字>

## 第3步 角色设定


## 角色基础信息
### 主要角色
### 次要角色

## 人物关系
### 核心关系
### 对立关系
### 隐藏关系
### 情感纠葛

## 角色成长弧光
### 主要角色
### 次要角色

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step3_character_profile",
  "title": "请确认当前角色设定",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step3_character_profile\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step3_character_profile\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_novel_to_script_character_profile_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'novel-to-script-episode-outline-generate',
    'novel-to-script-episode-outline-generate',
    $skill_description_novel_to_script_episode_outline_generate$小说转剧本：第4步分集大纲生成（可确认）。$skill_description_novel_to_script_episode_outline_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/novel-to-script-episode-outline-generate/SKILL.md',
    true,
    $skill_md_novel_to_script_episode_outline_generate$# novel-to-script-episode-outline-generate

## Description
小说转剧本：第4步分集大纲生成（可确认）。

## Version
3.2.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第4步，不得生成第5步正文。

## 输入契约
必需：
- `script_type`（必须为 `小说转剧本`）
- `script_content`
- `expected_episode_count`

可选：
- `story_output`
- `character_output`
- `step_feedback`

## 依赖策略
1. 优先使用 `story_output + character_output`。
2. 任一缺失时允许回退生成，不得因缺失直接失败。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step4_episode_outline
- stateLabel: 分集大纲
- script_type: 小说转剧本
- expected_episode_count: <数字>

## 第4步 分集大纲


按 `expected_episode_count` 输出第1集到第N集，每集包含：
- 核心事件
- 卡点/反转
- 集末钩子

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step4_episode_outline",
  "title": "请确认当前分集大纲",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step4_episode_outline\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step4_episode_outline\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_novel_to_script_episode_outline_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'novel-to-script-full-script-generate',
    'novel-to-script-full-script-generate',
    $skill_description_novel_to_script_full_script_generate$小说转剧本：第5步全集剧本生成（可确认）。$skill_description_novel_to_script_full_script_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/novel-to-script-full-script-generate/SKILL.md',
    true,
    $skill_md_novel_to_script_full_script_generate$# novel-to-script-full-script-generate

## Description
小说转剧本：第5步全集剧本生成（可确认）。

## Version
3.3.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第5步。

## 输入契约
必需：
- `script_type`（必须为 `小说转剧本`）
- `script_content`
- `expected_episode_count`

可选：
- `story_output`
- `character_output`
- `outline_output`
- `output_depth`：`lite` | `full`（默认 `lite`）
- `step_feedback`

## 依赖策略
1. 优先使用 `outline_output`。
2. 缺少上游输出时允许回退生成，不得直接失败。

## 输出强约束（性能优先）
1. 默认 `output_depth=lite`：
   - 每集 1-2 场景。
   - 每集总行数控制在 12-24 行。
2. 仅在用户明确要求“完整版”时使用 `output_depth=full`。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step5_full_script
- stateLabel: 全集剧本
- script_type: 小说转剧本
- expected_episode_count: <数字>
- output_depth: lite|full

## 第5步 全集剧本


从 `# 第1集` 到 `# 第N集`。

## 终态规则（固定）
- `step5_full_script` 是最终创作状态。
- 当用户以 `interaction_action=confirm` 确认当前全集剧本时，只输出 1 到 2 句简短完成确认，不附带 `<fun_claw_interaction>...</fun_claw_interaction>`。
- 当当前全集剧本已确认完成后，若用户后续消息仅表达感谢、满意、夸赞、结束语或寒暄，只输出 1 到 2 句简短收尾，不附带 `<fun_claw_interaction>...</fun_claw_interaction>`。
- 当当前全集剧本已确认完成后，若用户提出对当前成品的明确修改意见，则按当前成品的修改请求处理，重新输出 `step5_full_script` 正文与交互协议块。
- 以上终态轻量回复禁止输出过程分析、规则解释、状态说明，禁止出现“我应该”“让我”“根据规则”之类的决策文本。

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令。
仅当当前全集剧本仍处于待确认 / 待修改状态时输出协议块；终态完成确认与轻量收尾不得输出协议块：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step5_full_script",
  "title": "请确认当前全集剧本",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step5_full_script\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step5_full_script\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_novel_to_script_full_script_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'one-line-script-story-synopsis-generate',
    'one-line-script-story-synopsis-generate',
    $skill_description_one_line_script_story_synopsis_generate$一句话剧本：第2步故事梗概生成（可确认）。$skill_description_one_line_script_story_synopsis_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/one-line-script-story-synopsis-generate/SKILL.md',
    true,
    $skill_md_one_line_script_story_synopsis_generate$# one-line-script-story-synopsis-generate

## Description
一句话剧本：第2步故事梗概生成（可确认）。

## Version
3.2.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第2步，不得生成第3~5步正文。

## 输入契约
必需：
- `script_type`（必须为 `一句话剧本`）
- `script_content`
- `target_audience`
- `expected_episode_count`

可选：
- `step_feedback`（重生成意见）

## 输入解析
支持 `key=value` 与 `key: value`；支持键名同义归一化（`scriptType` 等）。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step2_story_synopsis
- stateLabel: 故事梗概
- script_type: 一句话剧本
- expected_episode_count: <数字>

## 第2步 故事梗概


- 200-350字，聚焦主冲突、主目标、核心反转。

## 主线大纲
### 第一幕：开端
### 第二幕：发展
### 第三幕：高潮与结局

## 开篇钩子
- 60-120字。

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step2_story_synopsis",
  "title": "请确认当前故事梗概",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step2_story_synopsis\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step2_story_synopsis\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_one_line_script_story_synopsis_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'one-line-script-character-profile-generate',
    'one-line-script-character-profile-generate',
    $skill_description_one_line_script_character_profile_generate$一句话剧本：第3步角色设定生成（可确认）。$skill_description_one_line_script_character_profile_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/one-line-script-character-profile-generate/SKILL.md',
    true,
    $skill_md_one_line_script_character_profile_generate$# one-line-script-character-profile-generate

## Description
一句话剧本：第3步角色设定生成（可确认）。

## Version
3.2.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第3步，不得生成第4~5步正文。

## 输入契约
必需：
- `script_type`（必须为 `一句话剧本`）
- `script_content`
- `target_audience`
- `expected_episode_count`

可选：
- `story_output`（建议提供第2步输出）
- `step_feedback`

## 依赖策略
1. 优先使用 `story_output`。
2. 若缺少 `story_output`，允许回退基于 `script_content` 生成（不得报硬错误）。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step3_character_profile
- stateLabel: 角色设定
- script_type: 一句话剧本
- expected_episode_count: <数字>

## 第3步 角色设定


## 角色基础信息
### 主要角色
### 次要角色

## 人物关系
### 核心关系
### 对立关系
### 隐藏关系
### 情感纠葛

## 角色成长弧光
### 主要角色
### 次要角色

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step3_character_profile",
  "title": "请确认当前角色设定",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step3_character_profile\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step3_character_profile\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_one_line_script_character_profile_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'one-line-script-episode-outline-generate',
    'one-line-script-episode-outline-generate',
    $skill_description_one_line_script_episode_outline_generate$一句话剧本：第4步分集大纲生成（可确认）。$skill_description_one_line_script_episode_outline_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/one-line-script-episode-outline-generate/SKILL.md',
    true,
    $skill_md_one_line_script_episode_outline_generate$# one-line-script-episode-outline-generate

## Description
一句话剧本：第4步分集大纲生成（可确认）。

## Version
3.2.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第4步，不得生成第5步正文。

## 输入契约
必需：
- `script_type`（必须为 `一句话剧本`）
- `script_content`
- `expected_episode_count`

可选：
- `story_output`
- `character_output`
- `step_feedback`

## 依赖策略
1. 优先使用 `story_output + character_output`。
2. 任一缺失时允许回退生成，不得因缺失直接失败。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step4_episode_outline
- stateLabel: 分集大纲
- script_type: 一句话剧本
- expected_episode_count: <数字>

## 第4步 分集大纲


按 `expected_episode_count` 输出第1集到第N集，每集包含：
- 核心事件
- 卡点/反转
- 集末钩子

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step4_episode_outline",
  "title": "请确认当前分集大纲",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step4_episode_outline\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step4_episode_outline\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_one_line_script_episode_outline_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'one-line-script-full-script-generate',
    'one-line-script-full-script-generate',
    $skill_description_one_line_script_full_script_generate$一句话剧本：第5步全集剧本生成（可确认）。$skill_description_one_line_script_full_script_generate$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/one-line-script-full-script-generate/SKILL.md',
    true,
    $skill_md_one_line_script_full_script_generate$# one-line-script-full-script-generate

## Description
一句话剧本：第5步全集剧本生成（可确认）。

## Version
3.3.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你只负责第5步。

## 输入契约
必需：
- `script_type`（必须为 `一句话剧本`）
- `script_content`
- `expected_episode_count`

可选：
- `story_output`
- `character_output`
- `outline_output`
- `output_depth`：`lite` | `full`（默认 `lite`）
- `step_feedback`

## 依赖策略
1. 优先使用 `outline_output`。
2. 缺少上游输出时允许回退生成，不得直接失败。

## 输出强约束（性能优先）
1. 默认 `output_depth=lite`：
   - 每集 1-2 场景。
   - 每集总行数控制在 12-24 行。
2. 仅在用户明确要求“完整版”时使用 `output_depth=full`。

## 输出格式（固定）
正文必须按以下结构输出，且不得再输出 [STEP_ID] / [STEP_STATUS] / [USER_CONFIRM_REQUIRED] / [NEXT_STEP] 等旧格式字段：

# 当前状态
- stateId: step5_full_script
- stateLabel: 全集剧本
- script_type: 一句话剧本
- expected_episode_count: <数字>
- output_depth: lite|full

## 第5步 全集剧本


从 `# 第1集` 到 `# 第N集`。

## 终态规则（固定）
- `step5_full_script` 是最终创作状态。
- 当用户以 `interaction_action=confirm` 确认当前全集剧本时，只输出 1 到 2 句简短完成确认，不附带 `<fun_claw_interaction>...</fun_claw_interaction>`。
- 当当前全集剧本已确认完成后，若用户后续消息仅表达感谢、满意、夸赞、结束语或寒暄，只输出 1 到 2 句简短收尾，不附带 `<fun_claw_interaction>...</fun_claw_interaction>`。
- 当当前全集剧本已确认完成后，若用户提出对当前成品的明确修改意见，则按当前成品的修改请求处理，重新输出 `step5_full_script` 正文与交互协议块。
- 以上终态轻量回复禁止输出过程分析、规则解释、状态说明，禁止出现“我应该”“让我”“根据规则”之类的决策文本。

## 交互协议（固定）
正文末尾必须紧跟以下协议块，不得再额外输出“确认第X步”或“第X步重生成”之类的自然语言指令。
仅当当前全集剧本仍处于待确认 / 待修改状态时输出协议块；终态完成确认与轻量收尾不得输出协议块：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "step5_full_script",
  "title": "请确认当前全集剧本",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\nstateId=step5_full_script\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\nstateId=step5_full_script\nscript_type=<当前script_type>\nscript_content=<当前script_content>\ntarget_audience=<当前target_audience>\nexpected_episode_count=<当前expected_episode_count>\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 错误输出格式（固定）
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_one_line_script_full_script_generate$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

insert into skill_baseline (
    skill_key,
    display_name,
    description,
    source_type,
    source_ref,
    enabled,
    skill_md,
    updated_by,
    created_at,
    updated_at
) values (
    'novel-to-script-main',
    'novel-to-script-main',
    $skill_description_novel_to_script_main$剧本生成主技能：负责输入解析、状态推进、子技能路由与交互协议输出。$skill_description_novel_to_script_main$,
    'LOCAL_REPO',
    'agent-mgc-novel-script/skills/novel-to-script-main/SKILL.md',
    true,
    $skill_md_novel_to_script_main$# novel-to-script-main

## Description
剧本生成主技能：负责输入解析、状态推进、子技能路由与交互协议输出。

## Version
3.4.0

## Instructions
Follow the instructions below exactly when this skill is selected.

你是多轮交互编排器，不是正文生成器。

## 输入契约
必需字段：
- `script_type`
- `script_content`
- `target_audience`
- `expected_episode_count`

可选字段：
- `run_mode`：`interactive` | `strict`
- `interaction_action`：`start` | `confirm` | `revise`
- `stateId` / `state_id`
- `step_feedback` / `user_feedback` / `feedback`

支持 `key=value` 与 `key: value`；支持常见同义键归一化；键名大小写不敏感。

## 状态
- `step1_input_parse`
- `step2_story_synopsis`
- `step3_character_profile`
- `step4_episode_outline`
- `step5_full_script`

## 路由
- `script_type=小说转剧本`：使用 `novel-to-script-*`
- `script_type=一句话剧本`：使用 `one-line-script-*`

## 推进规则
- `interaction_action` 缺失时按 `start`。
- `start`：输出 `step1_input_parse`
- `confirm`：
  - `step1_input_parse` -> `step2_story_synopsis`
  - `step2_story_synopsis` -> `step3_character_profile`
  - `step3_character_profile` -> `step4_episode_outline`
  - `step4_episode_outline` -> `step5_full_script`
- `step5_full_script` 是最终创作状态
- 当当前 `stateId=step5_full_script` 且 `interaction_action=confirm` 时：
  - 仅输出 1 到 2 句简短完成确认
  - 不再推进下一状态
  - 不输出 `<fun_claw_interaction>...</fun_claw_interaction>`
- `revise`：仅重生成当前 `stateId`
- `revise` 没有反馈内容时返回错误 JSON
- `expected_episode_count` 必须在所有状态中保持一致
- 当 `step5_full_script` 已确认完成后，若用户新消息不包含 `interaction_action` / `stateId`，且语义属于感谢、满意、夸赞、结束语或寒暄，则直接输出 1 到 2 句简短收尾，不再进入状态机，也不输出 `<fun_claw_interaction>...</fun_claw_interaction>`
- 当 `step5_full_script` 已确认完成后，若用户新消息不包含 `interaction_action` / `stateId`，但语义包含对当前成品的明确修改意见，则按对 `step5_full_script` 的 `revise` 处理

## 输出要求
`interactive` 模式每轮只能输出一个状态，正文结构固定为：
- `# 输入解析`
- `# 当前状态`
- `# 当前产出`
- 正文最后的 `<fun_claw_interaction>...</fun_claw_interaction>`

`strict` 模式可一次输出全部状态。

以下终态例外场景不使用上述固定结构，可直接输出纯文本：
- `step5_full_script` 确认完成时的完成确认
- `step5_full_script` 完成后的轻量收尾消息

以上纯文本输出必须控制在 1 到 2 句内，且不得附带 `<fun_claw_interaction>...</fun_claw_interaction>`。

### `step1_input_parse` 压缩要求
- `step1_input_parse` 只做输入校验、归一化与确认，不做正文创作。
- `# 输入解析` 仅列出归一化后的必需字段，必须使用短列表，禁止使用 Markdown 表格。
- `# 当前状态` 仅列出 `stateId`、状态名称、当前进度，禁止额外解释。
- `# 当前产出` 仅允许输出：
  - 1 行任务识别结果
  - 1 行题材 / 冲突摘要
- `step1_input_parse` 除交互协议块外，正文应尽量控制在约 6 行到 10 行内。
- `step1_input_parse` 严禁输出：
  - 剧名
  - 扩写版梗概
  - 人物命名或角色设定
  - 分集大纲
  - 任何 Markdown 表格
  - 正式创作正文

## 交互协议
- 协议块必须是正文最后一段。
- 协议块必须是合法 JSON。
- 协议块仅用于仍需用户确认或修改的状态；终态完成确认与轻量收尾不得输出协议块。
- `payload` 只能使用：
  - `interaction_action=confirm\\nstateId=<当前状态ID>`
  - `interaction_action=revise\\nstateId=<当前状态ID>\\nstep_feedback=`
- `payload` 中换行必须写成 JSON 转义的 `\\n`，禁止使用裸换行。

最小模板：

<fun_claw_interaction>
{
  "version": "1.0",
  "type": "approval_request",
  "stateId": "<当前状态ID>",
  "title": "请确认当前内容",
  "actions": [
    {
      "id": "confirm",
      "label": "确认并继续",
      "kind": "send",
      "payload": "interaction_action=confirm\\nstateId=<当前状态ID>"
    },
    {
      "id": "revise",
      "label": "提出修改",
      "kind": "prefill",
      "payload": "interaction_action=revise\\nstateId=<当前状态ID>\\nstep_feedback="
    }
  ]
}
</fun_claw_interaction>

## 禁止
- 禁止输出旧格式：`user_confirm_required` / `next_step` / `[STEP_ID]` / `[STEP_STATUS]` / `[USER_CONFIRM_REQUIRED]`
- 禁止输出“请回复确认第X步”“第X步重生成”之类的自然语言按钮说明
- 禁止未确认就推进下一状态
- 禁止在终态轻量消息中输出规则解释、状态分析、过程思考，或“根据规则”“我应该”“让我”之类的决策文本

## 错误输出
```json
{
  "error": true,
  "errorMessage": "错误原因说明"
}
```
$skill_md_novel_to_script_main$,
    'baseline-seed',
    now(),
    now()
)
on conflict (skill_key) do update set
    display_name = excluded.display_name,
    description = excluded.description,
    source_type = excluded.source_type,
    source_ref = excluded.source_ref,
    enabled = excluded.enabled,
    skill_md = excluded.skill_md,
    updated_by = excluded.updated_by,
    updated_at = excluded.updated_at;

delete from skill_baseline
where upper(coalesce(source_type, '')) <> 'SERVER_PACKAGE'
   or coalesce(btrim(source_ref), '') = '';

commit;
