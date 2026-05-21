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
    assert "client_max_body_size 200m" in setup_script
    assert "client_max_body_size 200m" in read("backend/nginx.example.conf")


def test_cloudflare_worker_deployment_artifacts_are_available_separately() -> None:
    assert not (ROOT / "backend" / "wrangler.toml").exists()
    assert not (ROOT / "backend" / "migrations").exists()
    assert (ROOT / "worker-backend" / "wrangler.toml").exists()
    assert (ROOT / "worker-backend" / "package.json").exists()
    assert (ROOT / "worker-backend" / "src" / "index.ts").exists()
    assert (ROOT / "worker-backend" / "migrations" / "0001_init.sql").exists()


def test_cloudflare_worker_workflow_builds_frontend_and_deploys_single_worker() -> None:
    worker_workflow = read(".github/workflows/deploy-worker.yml")

    assert not (ROOT / ".github" / "workflows" / "deploy-web-player.yml").exists()
    assert "working-directory: worker-backend" in worker_workflow
    assert "working-directory: web-player" in worker_workflow
    assert "npm run build" in worker_workflow
    assert "command: deploy" in worker_workflow


def test_worker_assets_are_bound_to_web_player_dist() -> None:
    worker_config = read("worker-backend/wrangler.toml")

    assert "[assets]" in worker_config
    assert 'directory = "../web-player/dist"' in worker_config
    assert 'binding = "ASSETS"' in worker_config
    assert 'not_found_handling = "single-page-application"' in worker_config
    assert "run_worker_first = true" in worker_config
    assert not (ROOT / "web-player" / "public" / "_redirects").exists()


def test_docs_explain_worker_and_bare_metal_deployments() -> None:
    root_readme = read("README.md")
    deployment_doc = read("DEPLOYMENT.md")

    assert "worker-backend/" in root_readme
    assert "裸金属" in root_readme
    assert "Cloudflare Worker" in deployment_doc
    assert "worker-backend" in deployment_doc
    assert "backend/" in deployment_doc
    assert "从旧 Worker-only 版本迁移" in deployment_doc
    assert "backend/wrangler.toml" in deployment_doc
    assert "worker-backend/wrangler.toml" in deployment_doc
    assert "不需要迁移 D1/R2 数据" in deployment_doc


def test_old_bare_metal_source_android_is_not_integrated() -> None:
    assert (ROOT / "android-app" / "app" / "src" / "main" / "AndroidManifest.xml").exists()
    assert not (ROOT / "Music Share" / "android-app").exists()
    assert not (ROOT / "backend" / "android-app").exists()
