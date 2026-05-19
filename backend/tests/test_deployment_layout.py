from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_root_installer_builds_current_frontend_and_fastapi_backend() -> None:
    install_script = read("install.sh")
    setup_script = read("backend/setup.sh")

    assert 'BACKEND_DIR="${ROOT_DIR}/backend"' in install_script
    assert 'FRONTEND_DIR="${ROOT_DIR}/web-player"' in install_script
    assert 'pip install -e "${BACKEND_DIR}"' in install_script
    assert "npm run build" in install_script
    assert 'local default_dir="${SCRIPT_DIR}/../web-player/dist"' in setup_script
    assert "location ~ ^/(auth|upload|client|admin|track|stream|cover|background|docs|redoc)(/|$)" in setup_script
    assert "client_max_body_size 80m" in setup_script
    assert "client_max_body_size 80m" in read("backend/nginx.example.conf")


def test_cloudflare_worker_deployment_artifacts_are_removed() -> None:
    assert not (ROOT / "backend" / "wrangler.toml").exists()
    assert not (ROOT / "backend" / "migrations").exists()
    assert not (ROOT / ".github" / "workflows" / "deploy-backend.yml").exists()
    assert not (ROOT / ".github" / "workflows" / "deploy-web-player.yml").exists()


def test_old_bare_metal_source_android_is_not_integrated() -> None:
    assert (ROOT / "android-app" / "app" / "src" / "main" / "AndroidManifest.xml").exists()
    assert not (ROOT / "Music Share" / "android-app").exists()
    assert not (ROOT / "backend" / "android-app").exists()
