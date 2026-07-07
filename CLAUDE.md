# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Unarchive (解压收藏夹) — Automates turning video favorites (Bilibili, Douyin) into a personal knowledge base. Pipeline: login → get favorites → transcript extraction → AI analysis → knowledge cards → Feishu sync.

## Commands

```bash
# Install（虚拟环境可选；若依赖已装在系统 Python 中，可跳过建 venv 直接用系统 Python 运行）
python -m venv venv && venv\Scripts\activate   # 可选
pip install -r requirements.txt
playwright install chromium

# Copy and edit .env with API keys (LLM provider, Feishu credentials)
cp .env.example .env

# Run the Gradio web UI
python app.py          # → http://127.0.0.1:7860
```

Prerequisites: Python 3.10+, FFmpeg (required by Whisper), optional CUDA GPU.

No test framework, linter, or type checker is configured.

## Architecture

### Pipeline flow

```
Login → Get Favorites → Transcript Extraction → AI Analysis → Knowledge Cards → Feishu Sync
```

Transcript extraction uses a 3-tier fallback: platform CC subtitles → Bilibili AI video summary → Whisper ASR.

### Layer structure

- **`app.py`** — Gradio UI entry point. `process_videos()` (line 92) is the core async generator that orchestrates the full pipeline.
- **`config.py`** — `AppConfig` loads from `.env` via `python-dotenv`. Singleton via `@lru_cache()` on `get_config()`.
- **`src/scraper/`** — `ScraperBase` ABC defining `login()`, `get_favorites()`, `get_video_subtitle()`, etc. Implementations: `DouyinScraper`, `XhsScraper` (stub).
- **`src/platforms/`** — `BilibiliScraper` lives here (not in `scraper/`). Uses wbi signing for API calls and cookie persistence.
- **`src/scraper/__init__.py`** — Lazy-import facade via `__getattr__` to bridge both `scraper/` and `platforms/` locations.
- **`src/transcript/`** — `SubtitleParser` (static utils) + `get_transcript()` orchestrator (3-tier fallback) + `WhisperTranscriber` (lazy-loaded model, audio download with httpx → yt-dlp fallback).
- **`src/analyzer/`** — `LLMAnalyzer` uses OpenAI-compatible API (DeepSeek, Qwen, etc.). Runs `_analyze_structure()` and `_summarize()` in parallel via `asyncio.gather()`. 3 retries with exponential backoff. JSON extraction with 4-level fallback (direct → markdown code block → brace-delimited → bracket-delimited).
- **`src/sync/`** — `SyncBase` ABC. `FeishuSync` uses OAuth2 tenant access token with 5-minute early refresh, builds Feishu docx blocks from knowledge cards (batch size 50).
- **`prompts/`** — `analyze.txt` and `summarize.txt` use Python `str.format()` with `{{` escaping for JSON literals.

### Data formats

**Knowledge card** (`data/knowledge_base/{video_id}.json`): contains `video_id`, `title`, `author`, `transcript_source`, `transcript`, `summary`, `keywords`, `one_line_summary`, `topics`, `key_points`, `knowledge_tags`, `target_audience`, `action_items`, `mindmap_structure`.

### Key architectural notes

- **Douyin API interception**: Uses Playwright `page.on("response")` listeners because Douyin requires complex request signing (X-Bogus). Falls back to DOM parsing when interception fails.
- **Scraper methods not in ABC**: `download_audio_to_file()` and `get_audio_cookies()` exist on both Bilibili and Douyin scrapers but are not in `ScraperBase`. `app.py` checks for them via `hasattr()`.
- **`trust_env=False`**: Required on httpx clients to avoid system proxy interference (past bug — see `.plan.md`).
- **`src/utils/common.py`** is an empty stub.
- **Bilibili in `platforms/` vs others in `scraper/`** is a known inconsistency.
