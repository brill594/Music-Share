from __future__ import annotations

import json
from datetime import timedelta

import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


@pytest.fixture()
def test_client(tmp_path: Path) -> TestClient:
    settings = Settings(
        data_root=tmp_path / "data",
        database_path=tmp_path / "data" / "music_share.sqlite3",
        storage_root=tmp_path / "data" / "storage",
        runtime_secret_path=tmp_path / "data" / "runtime-secrets.json",
        user_password="user-password",
        admin_password="admin-password",
        cleanup_interval_seconds=3_600,
        use_x_accel_redirect=True,
        public_api_base_url="https://api.example.test",
        public_share_base_url="https://share.example.test",
    )
    app = create_app(settings)
    with TestClient(app) as client:
        yield client


def login(client: TestClient, password: str) -> str:
    response = client.post("/auth/login", json={"password": password})
    assert response.status_code == 200
    return response.json()["session_key"]


def upload_sample(client: TestClient, session_key: str, client_install_id: str = "install-0001"):
    response = client.post(
        "/upload",
        headers={
            "X-Session-Key": session_key,
            "X-Client-Install-Id": client_install_id,
        },
        data={
            "title": "Test Song",
            "artist": "Test Artist",
            "album": "Test Album",
            "duration_ms": "123000",
            "audio_mime": "audio/ogg",
            "expire_after_seconds": "120",
        },
        files={
            "file": ("track.ogg", b"test-audio", "audio/ogg"),
            "cover": ("cover.jpg", b"fake-cover", "image/jpeg"),
        },
    )
    assert response.status_code == 200
    return response.json()


def test_upload_public_queries_and_client_lifecycle(test_client: TestClient) -> None:
    session_key = login(test_client, "user-password")
    uploaded = upload_sample(test_client, session_key)
    share_code = uploaded["share_code"]
    share_uuid = uploaded["uuid"]

    track_response = test_client.get(f"/track/{share_code}")
    assert track_response.status_code == 200
    assert track_response.json()["share_code"] == share_code
    assert "uuid" not in track_response.json()

    stream_response = test_client.get(f"/stream/{share_code}")
    assert stream_response.status_code == 200
    assert stream_response.headers["x-accel-redirect"] == f"/internal-media/{share_uuid}/audio.ogg"

    list_response = test_client.get(
        "/client/shares",
        headers={
            "X-Session-Key": session_key,
            "X-Client-Install-Id": "install-0001",
        },
    )
    assert list_response.status_code == 200
    assert list_response.json()["count"] == 1

    terminate_response = test_client.post(
        f"/client/shares/{share_code}/terminate",
        headers={
            "X-Session-Key": session_key,
            "X-Client-Install-Id": "install-0001",
        },
    )
    assert terminate_response.status_code == 200
    assert terminate_response.json()["status"] == "terminated"

    gone_response = test_client.get(f"/track/{share_code}")
    assert gone_response.status_code == 410


def test_admin_can_list_and_terminate_any_share(test_client: TestClient) -> None:
    user_session = login(test_client, "user-password")
    uploaded = upload_sample(test_client, user_session)

    admin_session = login(test_client, "admin-password")
    list_response = test_client.get(
        "/admin/tracks",
        headers={"X-Session-Key": admin_session},
    )
    assert list_response.status_code == 200
    assert list_response.json()["count"] == 1
    assert list_response.json()["items"][0]["client_install_id"] == "install-0001"

    terminate_response = test_client.post(
        f"/admin/tracks/{uploaded['share_code']}/terminate",
        headers={"X-Session-Key": admin_session},
    )
    assert terminate_response.status_code == 200
    assert terminate_response.json()["status"] == "terminated"


def test_cleanup_removes_expired_share(test_client: TestClient) -> None:
    session_key = login(test_client, "user-password")
    response = test_client.post(
        "/upload",
        headers={
            "X-Session-Key": session_key,
            "X-Client-Install-Id": "install-0002",
        },
        data={
            "title": "Soon Expired",
            "artist": "Test Artist",
            "album": "Test Album",
            "duration_ms": "123000",
            "audio_mime": "audio/ogg",
            "expire_after_seconds": "1",
        },
        files={"file": ("track.ogg", b"test-audio", "audio/ogg")},
    )
    assert response.status_code == 200
    share_code = response.json()["share_code"]
    share_uuid = response.json()["uuid"]

    time.sleep(1.2)
    test_client.app.state.cleanup_service.run_once()

    assert test_client.get(f"/track/{share_code}").status_code == 404
    assert not (test_client.app.state.settings.storage_root / share_uuid).exists()


def test_admin_background_is_exposed_to_public_track_payload(test_client: TestClient) -> None:
    user_session = login(test_client, "user-password")
    uploaded = upload_sample(test_client, user_session)
    share_code = uploaded["share_code"]

    initial_track = test_client.get(f"/track/{share_code}")
    assert initial_track.status_code == 200
    assert initial_track.json()["background_url"] is None

    admin_session = login(test_client, "admin-password")
    upload_response = test_client.post(
        "/admin/background",
        headers={"X-Session-Key": admin_session},
        files={"background": ("background.jpg", b"fake-background", "image/jpeg")},
    )
    assert upload_response.status_code == 200
    assert upload_response.json()["configured"] is True
    assert upload_response.json()["background_url"] == "https://api.example.test/background"

    background_response = test_client.get("/background")
    assert background_response.status_code == 200
    assert background_response.content == b"fake-background"

    track_response = test_client.get(f"/track/{share_code}")
    assert track_response.status_code == 200
    assert track_response.json()["background_url"] == "https://api.example.test/background"


def test_admin_usage_limits_are_compatible_with_android_contract(test_client: TestClient) -> None:
    admin_session = login(test_client, "admin-password")
    update_response = test_client.post(
        "/admin/usage",
        headers={"X-Session-Key": admin_session},
        json={
            "enabled": False,
            "d1_rows_read_daily_limit": 123,
            "d1_rows_written_daily_limit": 45,
            "d1_storage_gb_limit": 6.5,
            "r2_class_a_rolling_30d_limit": 789,
            "r2_class_b_rolling_30d_limit": 987,
            "r2_storage_gb_month_limit": 4.25,
        },
    )
    assert update_response.status_code == 200
    body = update_response.json()
    assert body["enabled"] is False
    assert body["cloudflare_reference"]["d1_rows_read_daily_limit"] == 123
    assert body["d1_rows_read_daily"]["limit"] == 123
    assert body["r2_storage_rolling_30d"]["limit_gb_month"] == 4.25

    get_response = test_client.get("/admin/usage", headers={"X-Session-Key": admin_session})
    assert get_response.status_code == 200
    assert get_response.json()["enabled"] is False
    assert get_response.json()["cloudflare_reference"]["r2_class_b_rolling_30d_limit"] == 987


def test_usage_limits_block_uploads_after_configured_daily_write_limit(test_client: TestClient) -> None:
    admin_session = login(test_client, "admin-password")
    update_response = test_client.post(
        "/admin/usage",
        headers={"X-Session-Key": admin_session},
        json={
            "enabled": True,
            "d1_rows_read_daily_limit": 1_000,
            "d1_rows_written_daily_limit": 1,
            "d1_storage_gb_limit": 5.0,
            "r2_class_a_rolling_30d_limit": 1_000,
            "r2_class_b_rolling_30d_limit": 1_000,
            "r2_storage_gb_month_limit": 10.0,
        },
    )
    assert update_response.status_code == 200

    user_session = login(test_client, "user-password")
    first = upload_sample(test_client, user_session, client_install_id="install-guard-1")
    assert first["status"] == "active"

    blocked = test_client.post(
        "/upload",
        headers={
            "X-Session-Key": user_session,
            "X-Client-Install-Id": "install-guard-2",
        },
        data={
            "title": "Blocked Song",
            "artist": "Test Artist",
            "album": "Test Album",
            "duration_ms": "123000",
            "audio_mime": "audio/ogg",
            "expire_after_seconds": "120",
        },
        files={"file": ("track.ogg", b"test-audio", "audio/ogg")},
    )
    assert blocked.status_code == 429
    assert "d1_rows_written_daily" in blocked.json()["detail"]


def test_usage_limits_block_public_reads_after_configured_daily_read_limit(test_client: TestClient) -> None:
    user_session = login(test_client, "user-password")
    uploaded = upload_sample(test_client, user_session)

    admin_session = login(test_client, "admin-password")
    update_response = test_client.post(
        "/admin/usage",
        headers={"X-Session-Key": admin_session},
        json={
            "enabled": True,
            "d1_rows_read_daily_limit": 1,
            "d1_rows_written_daily_limit": 1_000,
            "d1_storage_gb_limit": 5.0,
            "r2_class_a_rolling_30d_limit": 1_000,
            "r2_class_b_rolling_30d_limit": 1_000,
            "r2_storage_gb_month_limit": 10.0,
        },
    )
    assert update_response.status_code == 200

    assert test_client.get(f"/track/{uploaded['share_code']}").status_code == 200
    blocked = test_client.get(f"/track/{uploaded['share_code']}")
    assert blocked.status_code == 429
    assert "d1_rows_read_daily" in blocked.json()["detail"]


def test_public_api_allows_configured_web_player_origin(test_client: TestClient) -> None:
    user_session = login(test_client, "user-password")
    uploaded = upload_sample(test_client, user_session)

    response = test_client.get(
        f"/track/{uploaded['share_code']}",
        headers={"Origin": "https://share.example.test"},
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "https://share.example.test"

    options_response = test_client.options(
        f"/track/{uploaded['share_code']}",
        headers={
            "Origin": "https://share.example.test",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert options_response.status_code == 200
    assert options_response.headers["access-control-allow-origin"] == "https://share.example.test"


def test_usage_storage_limit_blocks_upload_that_would_cross_cap(test_client: TestClient) -> None:
    admin_session = login(test_client, "admin-password")
    update_response = test_client.post(
        "/admin/usage",
        headers={"X-Session-Key": admin_session},
        json={
            "enabled": True,
            "d1_rows_read_daily_limit": 1_000,
            "d1_rows_written_daily_limit": 1_000,
            "d1_storage_gb_limit": 0.000000001,
            "r2_class_a_rolling_30d_limit": 1_000,
            "r2_class_b_rolling_30d_limit": 1_000,
            "r2_storage_gb_month_limit": 0.000000001,
        },
    )
    assert update_response.status_code == 200

    user_session = login(test_client, "user-password")
    blocked = test_client.post(
        "/upload",
        headers={
            "X-Session-Key": user_session,
            "X-Client-Install-Id": "install-storage-cap",
        },
        data={
            "title": "Too Large",
            "artist": "Test Artist",
            "album": "Test Album",
            "duration_ms": "123000",
            "audio_mime": "audio/ogg",
            "expire_after_seconds": "120",
        },
        files={"file": ("track.ogg", b"test-audio", "audio/ogg")},
    )
    assert blocked.status_code == 429
    assert "storage" in blocked.json()["detail"]


def test_usage_storage_limit_blocks_admin_background_upload(test_client: TestClient) -> None:
    admin_session = login(test_client, "admin-password")
    update_response = test_client.post(
        "/admin/usage",
        headers={"X-Session-Key": admin_session},
        json={
            "enabled": True,
            "d1_rows_read_daily_limit": 1_000,
            "d1_rows_written_daily_limit": 1_000,
            "d1_storage_gb_limit": 0.000000001,
            "r2_class_a_rolling_30d_limit": 1_000,
            "r2_class_b_rolling_30d_limit": 1_000,
            "r2_storage_gb_month_limit": 0.000000001,
        },
    )
    assert update_response.status_code == 200

    blocked = test_client.post(
        "/admin/background",
        headers={"X-Session-Key": admin_session},
        files={"background": ("background.jpg", b"fake-background", "image/jpeg")},
    )
    assert blocked.status_code == 429
    background = test_client.get("/admin/background", headers={"X-Session-Key": admin_session})
    assert background.status_code == 200
    assert background.json()["configured"] is False


def test_oversized_background_replacement_preserves_existing_background(test_client: TestClient) -> None:
    admin_session = login(test_client, "admin-password")
    initial = test_client.post(
        "/admin/background",
        headers={"X-Session-Key": admin_session},
        files={"background": ("background.jpg", b"old-background", "image/jpeg")},
    )
    assert initial.status_code == 200
    assert test_client.get("/background").content == b"old-background"

    too_large = b"x" * (test_client.app.state.settings.max_cover_upload_bytes + 1)
    replacement = test_client.post(
        "/admin/background",
        headers={"X-Session-Key": admin_session},
        files={"background": ("background.jpg", too_large, "image/jpeg")},
    )
    assert replacement.status_code == 413

    background = test_client.get("/background")
    assert background.status_code == 200
    assert background.content == b"old-background"
    staged_files = list((test_client.app.state.settings.storage_root / "global-background").glob("staged-*"))
    assert staged_files == []


def test_r2_class_b_limit_uses_rolling_30_day_window(test_client: TestClient) -> None:
    user_session = login(test_client, "user-password")
    uploaded = upload_sample(test_client, user_session)

    from app.models import utcnow

    previous_day = (utcnow().date() - timedelta(days=1)).isoformat()
    counters_path = test_client.app.state.settings.data_root / "usage-counters.json"
    counters_path.write_text(
        json.dumps({"days": {previous_day: {"r2_class_b_rolling_30d": 1}}}),
        encoding="utf-8",
    )

    admin_session = login(test_client, "admin-password")
    update_response = test_client.post(
        "/admin/usage",
        headers={"X-Session-Key": admin_session},
        json={
            "enabled": True,
            "d1_rows_read_daily_limit": 1_000,
            "d1_rows_written_daily_limit": 1_000,
            "d1_storage_gb_limit": 5.0,
            "r2_class_a_rolling_30d_limit": 1_000,
            "r2_class_b_rolling_30d_limit": 1,
            "r2_storage_gb_month_limit": 10.0,
        },
    )
    assert update_response.status_code == 200

    blocked = test_client.get(f"/stream/{uploaded['share_code']}")
    assert blocked.status_code == 429
    assert "r2_class_b_rolling_30d" in blocked.json()["detail"]
