from __future__ import annotations

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
