"""Local knowledge-card persistence shared by the GUI and CLI."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from config import AppConfig, get_config


def get_knowledge_base_dir(config: AppConfig | None = None) -> Path:
    """Return the configured knowledge-card directory, creating it if needed."""
    resolved_config = config or get_config()
    path = Path(resolved_config.knowledge_base_dir)
    path.mkdir(parents=True, exist_ok=True)
    return path


def save_knowledge_card(
    video_id: str,
    data: dict[str, Any],
    config: AppConfig | None = None,
) -> Path:
    """Atomically persist a knowledge card and return its final path."""
    path = get_knowledge_base_dir(config) / f"{video_id}.json"
    temp_path = path.with_suffix(path.suffix + ".tmp")
    try:
        with open(temp_path, "w", encoding="utf-8") as file:
            json.dump(data, file, ensure_ascii=False, indent=2)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp_path, path)
    finally:
        if temp_path.exists():
            temp_path.unlink()
    return path


def load_knowledge_card(
    video_id: str,
    config: AppConfig | None = None,
) -> dict[str, Any] | None:
    """Load a knowledge card, returning ``None`` when it does not exist."""
    path = get_knowledge_base_dir(config) / f"{video_id}.json"
    if not path.exists():
        return None
    with open(path, "r", encoding="utf-8") as file:
        return json.load(file)


def list_knowledge_cards(config: AppConfig | None = None) -> list[dict[str, Any]]:
    """Return every readable card in the configured knowledge directory."""
    cards: list[dict[str, Any]] = []
    for path in get_knowledge_base_dir(config).glob("*.json"):
        try:
            with open(path, "r", encoding="utf-8") as file:
                cards.append(json.load(file))
        except (OSError, json.JSONDecodeError):
            continue
    return cards
