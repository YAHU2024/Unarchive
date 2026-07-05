# 视频收藏夹知识库工具 - 实施计划

## Context

用户希望构建一个 Demo 工具，将视频平台（抖音、Bilibili）收藏夹中的视频内容进行逐字稿提取、结构分析和总结提炼，然后同步到飞书文档，打造个人知识库。

**技术选型**：Python + Gradio | 浏览器自动化(Playwright) | 平台字幕优先 + Whisper 兜底 | 国产大模型 API | 飞书文档 API

---

## 项目结构

```
d:\YaHu\Documents\Unarchive\
├── app.py                      # Gradio 主入口
├── config.py                   # 配置管理（API Keys、路径等）
├── requirements.txt            # Python 依赖
├── .env.example                # 环境变量模板
├── .gitignore
├── data/                       # 本地数据存储（gitignore）
│   ├── transcripts/            # 逐字稿 JSON 文件
│   ├── audio_cache/            # 音频缓存（临时）
│   └── knowledge_base/         # 结构化知识库输出
├── src/
│   ├── __init__.py
│   ├── platforms/              # 视频平台适配层
│   │   ├── __init__.py
│   │   ├── base.py             # 平台抽象基类
│   │   ├── bilibili.py         # Bilibili 实现
│   │   └── douyin.py           # 抖音实现
│   ├── transcript/             # 逐字稿提取
│   │   ├── __init__.py
│   │   ├── subtitle.py         # 平台字幕提取
│   │   └── whisper_asr.py      # Whisper 语音识别兜底
│   ├── analyzer/               # AI 分析模块
│   │   ├── __init__.py
│   │   └── llm_analyzer.py     # 大模型分析（DeepSeek/通义千问）
│   ├── sync/                   # 同步模块
│   │   ├── __init__.py
│   │   ├── feishu.py           # 飞书文档同步
│   │   └── base.py             # 同步抽象基类（预留 IMA 扩展）
│   └── utils/
│       ├── __init__.py
│       └── common.py           # 通用工具函数
└── prompts/                    # LLM Prompt 模板
    ├── analyze.txt             # 结构分析 prompt
    └── summarize.txt           # 总结提炼 prompt
```

---

## 核心模块设计

### Task 1: 项目基础搭建
- 初始化项目结构、依赖配置
- `requirements.txt`: playwright, openai-whisper, yt-dlp, gradio, httpx, python-dotenv
- `config.py`: 基于 python-dotenv 的配置管理
- `.env.example`: 包含 DEEPSEEK_API_KEY / QWEN_API_KEY、FEISHU_APP_ID、FEISHU_APP_SECRET 等

### Task 2: 视频平台适配层 (`src/platforms/`)

**抽象基类 `base.py`**:
```python
class PlatformBase(ABC):
    async def login(self) -> None: ...
    async def get_favorites(self) -> list[FavoriteFolder]: ...
    async def get_favorite_videos(self, folder_id) -> list[VideoInfo]: ...
    async def get_video_subtitle(self, video_id) -> SubtitleResult | None: ...
    async def get_video_audio_url(self, video_id) -> str | None: ...
```

**Bilibili 实现 `bilibili.py`**:
- 使用 Playwright 打开 Bilibili 登录页，用户扫码/手动登录，保存 Cookie
- 通过 Bilibili Web API 获取收藏夹列表和视频列表
  - 收藏夹 API: `https://api.bilibili.com/x/v3/fav/folder/list`
  - 收藏夹内容 API: `https://api.bilibili.com/x/v3/fav/resource/list`
- 字幕提取: 通过视频信息中的 subtitle 字段获取 CC 字幕 URL，下载 JSON 格式字幕
  - 视频详情 API 返回 `data.subtitle.subtitles` 包含字幕 URL
- 音频提取: 通过播放页 API 获取音频流 URL

**抖音实现 `douyin.py`**:
- 使用 Playwright 打开抖音网页版，用户扫码登录，保存 Cookie
- 在 Playwright 中直接操作页面 DOM 或拦截 API 请求获取收藏夹视频列表
- 字幕提取: 部分抖音视频自带字幕，从视频详情中提取
- 音频提取: 从视频详情中提取音频 URL

### Task 3: 逐字稿提取模块 (`src/transcript/`)

**字幕提取 `subtitle.py`**:
- 解析平台返回的字幕 JSON，提取文本和时间戳
- 统一输出格式: `[{start: float, end: float, text: str}]`

**Whisper 兜底 `whisper_asr.py`**:
- 使用 yt-dlp 下载音频（仅音频轨道，`-f bestaudio`）
- 使用 openai-whisper 本地推理（默认使用 `medium` 模型）
- 输出带时间戳的逐字稿
- 支持 GPU 加速（CUDA）和 CPU 模式

### Task 4: AI 分析模块 (`src/analyzer/`)

**LLM 分析器 `llm_analyzer.py`**:
- 支持 DeepSeek API 和通义千问 API（通过 OpenAI 兼容接口）
- 核心功能:
  1. **结构分析**: 提取关键观点、主题分类、知识标签
  2. **内容总结**: 生成摘要、核心要点列表、行动建议
  3. **知识卡片生成**: 输出结构化的知识卡片
- Prompt 模板存放在 `prompts/` 目录
- 支持长文本分段处理

### Task 5: 飞书同步模块 (`src/sync/`)

**飞书文档同步 `feishu.py`**:
- 使用飞书开放平台 API（通过 httpx 直接调用）
- 认证: tenant_access_token 方式
- 核心功能:
  1. 创建知识库文件夹结构（按平台/收藏夹/日期组织）
  2. 创建飞书文档，写入知识卡片内容
  3. 更新已有文档（增量同步，基于视频 ID 去重）
- API 端点:
  - 获取 token: `POST /open-apis/auth/v3/tenant_access_token/internal`
  - 创建文件夹: `POST /open-apis/drive/v1/files/create_folder`
  - 创建文档: `POST /open-apis/docx/v1/documents`
  - 写入内容: `POST /open-apis/docx/v1/documents/{document_id}/blocks/{block_id}/children`

### Task 6: Gradio 界面 (`app.py`)

**主界面设计**:
- **Tab 1 - 登录与配置**: 选择平台 → 打开浏览器登录 → 配置 API Keys
- **Tab 2 - 收藏夹处理**: 展示收藏夹列表 → 勾选 → 设置参数 → 开始处理
- **Tab 3 - 处理进度**: 实时进度条 + 当前视频信息 + 日志
- **Tab 4 - 知识库浏览**: 已处理的视频知识卡片，支持搜索
- **Tab 5 - 同步管理**: 飞书同步状态、一键同步

### Task 7: 数据流与处理管道

**完整处理流程**:
```
用户登录 → 获取收藏夹列表 → 选择收藏夹
  → 遍历收藏夹中的视频:
    1. 获取视频元信息（标题、链接、UP主、时长等）
    2. 尝试获取平台字幕
       → 有字幕: 解析字幕 JSON → 生成逐字稿
       → 无字幕: yt-dlp 下载音频 → Whisper 转写 → 生成逐字稿
    3. 逐字稿送入 LLM 分析:
       → 结构分析（主题、标签、关键观点）
       → 内容总结（摘要、要点、建议）
       → 生成知识卡片
    4. 保存本地（JSON + Markdown）
  → 处理完成后，一键同步到飞书文档
```

---

## 实施顺序

| 顺序 | 任务 | 依赖 | 预计复杂度 |
|------|------|------|-----------|
| 1 | 项目基础搭建（结构、配置、依赖） | 无 | 低 |
| 2 | Bilibili 平台适配（登录 + 收藏夹 + 字幕） | Task 1 | 中 |
| 3 | 逐字稿提取模块（字幕解析 + Whisper 兜底） | Task 1 | 中 |
| 4 | AI 分析模块（LLM 调用 + Prompt 设计） | Task 1 | 中 |
| 5 | 飞书同步模块 | Task 1 | 中 |
| 6 | Gradio 界面整合 | Task 2-5 | 高 |
| 7 | 抖音平台适配 | Task 2 | 高 |

**MVP 策略**: 先完成 Bilibili 单平台全流程（Task 1-6），验证完整链路后再扩展抖音。

---

## 验证方案

1. **单元测试**: 各模块独立测试（字幕解析、LLM 调用、飞书 API 等）
2. **集成测试**: Bilibili 单视频完整处理流程
3. **端到端测试**: 通过 Gradio 界面操作完整流程
4. **验证步骤**:
   - 启动应用: `python app.py`
   - 登录 Bilibili → 获取收藏夹 → 选择 1-2 个视频的收藏夹
   - 验证逐字稿提取结果
   - 验证 AI 分析输出
   - 验证飞书文档创建和内容写入

---

## 环境要求

- Python 3.10+
- FFmpeg（Whisper 和 yt-dlp 依赖）
- 可选: CUDA GPU（Whisper 加速）
- API Keys: DeepSeek 或通义千问、飞书开放平台应用凭证
