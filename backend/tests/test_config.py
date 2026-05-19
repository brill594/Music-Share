import json

from app.config import load_settings


def test_generated_runtime_passwords_survive_restart(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("MUSIC_SHARE_DATA_ROOT", str(tmp_path / "data"))
    monkeypatch.delenv("MUSIC_SHARE_USER_PASSWORD", raising=False)
    monkeypatch.delenv("MUSIC_SHARE_ADMIN_PASSWORD", raising=False)

    first = load_settings()
    second = load_settings()

    assert second.user_password == first.user_password
    assert second.admin_password == first.admin_password


def test_env_passwords_are_not_written_to_runtime_secret_file(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("MUSIC_SHARE_DATA_ROOT", str(tmp_path / "data"))
    monkeypatch.setenv("MUSIC_SHARE_USER_PASSWORD", "user-from-env")
    monkeypatch.setenv("MUSIC_SHARE_ADMIN_PASSWORD", "admin-from-env")

    settings = load_settings()

    assert settings.user_password == "user-from-env"
    assert settings.admin_password == "admin-from-env"
    assert not settings.runtime_secret_path.exists()


def test_mixed_env_password_writes_only_generated_secret(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("MUSIC_SHARE_DATA_ROOT", str(tmp_path / "data"))
    monkeypatch.setenv("MUSIC_SHARE_USER_PASSWORD", "user-from-env")
    monkeypatch.delenv("MUSIC_SHARE_ADMIN_PASSWORD", raising=False)

    settings = load_settings()
    payload = json.loads(settings.runtime_secret_path.read_text(encoding="utf-8"))

    assert "user_password" not in payload
    assert payload["admin_password"] == settings.admin_password
