---
name: faster-whisper-only-dev
overview: 从 main 新建 dev 分支，移除 Whisper 双引擎兜底逻辑、仅保留 faster-whisper；前端模型下拉框扩展为 faster-whisper 完整模型列表；清理 requirements.txt 中的 openai-whisper/torch 依赖；并通过 tiny 模型加载实测验证 faster-whisper 端到端可用。
todos:
  - id: create-dev-branch
    content: 从 main 创建并切换到 dev 分支
    status: completed
  - id: refactor-whisper-backend
    content: 改造 whisper_asr.py 移除 openai-whisper 回退与 torch 依赖
    status: completed
    dependencies:
      - create-dev-branch
  - id: update-frontend-model
    content: 扩展 app.py 模型下拉框为 faster-whisper 完整列表并更新标签
    status: completed
    dependencies:
      - refactor-whisper-backend
  - id: cleanup-requirements
    content: 清理 requirements.txt 移除 openai-whisper 与 torch
    status: completed
    dependencies:
      - create-dev-branch
  - id: verify-faster-whisper
    content: 在 venv 加载 tiny 模型实测端到端转写可用
    status: completed
    dependencies:
      - refactor-whisper-backend
  - id: restart-and-smoke
    content: 重启 app.py 确认启动与前端下拉框无报错
    status: completed
    dependencies:
      - update-frontend-model
      - cleanup-requirements
---

## 用户需求

用户要求项目仅使用 faster-whisper 作为语音转写引擎，移除 openai-whisper 的双引擎兜底逻辑，并新建 `dev` 分支进行这些改动；同时确认 faster-whisper 实际可用，并同步更新 Web 前端「Whisper 模型」下拉框的代码逻辑。

## 产品概述

一个基于 Gradio 的音视频收藏夹处理工具，语音转写模块当前支持 faster-whisper 优先、openai-whisper 回退的双引擎。本次改造将其收敛为单一 faster-whisper 引擎，并在前端暴露完整的 faster-whisper 模型列表供用户选择。

## 核心特性

- 新建 `dev` 分支承载本次改动（基于当前 main）。
- 后端移除 openai-whisper 回退与 `torch` 依赖，仅保留 faster-whisper 加载/推理路径，加载失败给出明确报错。
- 设备检测不再依赖 `torch`，改用 ctranslate2 探测 CUDA 或默认走 CPU（本机无 GPU，实际走 CPU）。
- 前端「Whisper 模型」下拉框扩展为 faster-whisper 支持的完整模型列表（含 large-v3、distil-large-v3、distil-medium.en 等），标签明确标注为 faster-whisper。
- 清理 requirements.txt，移除 openai-whisper 与 torch 相关依赖，仅保留 faster-whisper。
- 实测验证：在 venv 中加载 tiny 模型完成一次端到端转写，确认可用。

## 技术栈

- 语言/运行时：Python 3.11（现有 venv）
- 转写引擎：faster-whisper（CTranslate2 后端，不依赖 torch）
- Web：Gradio（现有 `app.py`，仅调整已有下拉框，无架构变更）
- 依赖管理：requirements.txt（裁剪 openai-whisper / torch）

## 实现方案

### 总体策略

将 `WhisperTranscriber` 的双引擎 `_load_model` 简化为单一 faster-whisper 加载路径，删除 `_engine` 分流字段、`_run_openai_whisper` 方法及其调用分支；`_resolve_device` 与 `cleanup` 去 torch 化；前端 `WHISPER_MODEL_CHOICES` 扩展为 faster-whisper 完整模型集并修正下拉框标签。新建 `dev` 分支隔离改动。

### 关键技术决策

1. **删除 openai-whisper 回退**：直接 `from faster_whisper import WhisperModel` 加载，失败即抛出 `RuntimeError`（含“未安装 faster-whisper”提示），不再静默回退，避免用户无感知地用错引擎。
2. **去 torch 的设备检测**：原 `_resolve_device` 用 `import torch` 判定 CUDA。改用 `import ctranslate2; ctranslate2.get_cuda_device_count() > 0` 判定，异常时回退 CPU。`compute_type` 维持 `auto` 时 CUDA 走 float16、CPU 走 int8 的逻辑不变。
3. **cleanup 去 torch**：移除 `torch.cuda.empty_cache()` 调用；模型置 `None` 让引用释放即可（CTranslate2 无独立缓存需清）。
4. **前端模型列表**：扩展为 faster-whisper 官方模型名集合（中文语音场景默认仍用 multilingual 模型，en 系列作为可选项保留），并加 `info` 注明“仅支持 faster-whisper”。

### 性能与可靠性

- faster-whisper 推理路径不变（vad_filter、beam_size=5、language="zh"），性能无回归。
- 加载失败改为快速失败（fail-fast），避免后台长时间无提示。
- 验证用 tiny 模型（约 75MB）联网下载实测，覆盖“加载→转写→出 SubtitleSegment”全链路。

## 实现注意

- 保持 `WhisperTranscriber` 的公开构造签名 `model_name/device/compute_type` 不变，避免影响 `app.py`、`cli.py` 的调用方。
- 模块与类 docstring 中“OpenAI Whisper / openai-whisper 兜底”等表述需同步改为 faster-whisper 描述。
- 不改动 `config.py` 中 `whisper_*` 字段（描述已注明 faster-whisper），避免无关改动。
- 分支改动不触及已运行的后台 app.py 进程；验证时另起独立 python 进程或重启服务，避免影响现有 7860 端口服务状态。

## 架构设计

本次为局部收敛改造，沿用现有分层（Gradio 前端 → `process_videos`/`WhisperTranscriber` → faster-whisper）。无新增模块、无新架构模式。改动范围严格限定在三处文件，保持低风险与向后兼容。

## 目录结构与改动文件

```
project-root/
├── src/
│   └── transcript/
│       └── whisper_asr.py   # [MODIFY] 移除 openai-whisper 回退：删除 _engine 字段与分流、_run_openai_whisper 方法；
│                             #           _load_model 仅加载 faster-whisper 并在失败时明确报错；_run_whisper 直接调用
│                             #           _run_faster_whisper；_resolve_device 用 ctranslate2 探测 CUDA（去 torch）；
│                             #           cleanup 移除 torch.cuda.empty_cache；更新模块/类 docstring。
├── app.py                   # [MODIFY] 扩展 WHISPER_MODEL_CHOICES 为 faster-whisper 完整模型列表（含 large-v3、
│                             #           distil-large-v3、distil-medium.en 等）；将下拉框 label 改为
│                             #           "Whisper 模型 (faster-whisper)" 并补充 info 说明。
└── requirements.txt         # [MODIFY] 删除 openai-whisper 与 torch（及 torch 相关）依赖行，保留 faster-whisper。
```

## 关键代码结构

```python
# app.py 中改为（示例，faster-whisper 官方模型名集合）
WHISPER_MODEL_CHOICES = [
    "tiny", "base", "small", "medium",
    "large-v1", "large-v2", "large-v3",
    "tiny.en", "base.en", "small.en", "medium.en",
    "distil-small.en", "distil-medium.en",
    "distil-large-v2", "distil-large-v3",
]
```