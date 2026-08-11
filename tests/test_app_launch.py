"""Application launch configuration tests."""

from __future__ import annotations

from types import SimpleNamespace

import app


def test_main_allows_gradio_to_find_an_available_port(monkeypatch, tmp_path):
    launch_options = {}

    class FakeApp:
        def launch(self, **kwargs):
            launch_options.update(kwargs)

    config = SimpleNamespace(logs_dir=str(tmp_path / "logs"))
    config.ensure_dirs = lambda: (tmp_path / "logs").mkdir(parents=True)

    monkeypatch.setattr(app, "get_config", lambda: config)
    monkeypatch.setattr(app, "build_ui", FakeApp)

    app.main()

    assert launch_options["server_name"] == "127.0.0.1"
    assert launch_options["server_port"] is None
