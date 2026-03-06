# fun-ai-claw-api

Control API service for managing claw instances.

## Tech Stack

- Java 17+
- Spring Boot 4.0.3
- Spring WebMVC + Validation + Actuator + JDBC
- PostgreSQL

## Run

```bash
mvn spring-boot:run
```

Default port: `8080`

Default database config is in `src/main/resources/application.yml` and points to:

- `jdbc:postgresql://172.21.138.98:5432/fun_ai_claw`
- username: `funai_agent`

Schema is auto-initialized at startup via `src/main/resources/schema.sql`.

## Current Scope

- `GET /v1/health`
- `GET /v1/images`
- `GET /v1/instances`
- `POST /v1/instances`
- `DELETE /v1/instances/{instanceId}`
- `POST /v1/instances/{instanceId}/actions`
- `GET /v1/instances/{instanceId}/pairing-code`
- `GET /v1/instances/{instanceId}/agents`
- `GET /v1/instances/{instanceId}/main-agent-guidance`
- `PUT /v1/instances/{instanceId}/main-agent-guidance`
- `DELETE /v1/instances/{instanceId}/main-agent-guidance`

## Runtime Image Presets

Default preset image is configured in `src/main/resources/application.yml`:

```yaml
app:
  images:
    allow-custom-image: false
    presets:
      - id: zeroclaw-default
        name: ZeroClaw Default
        image: ${ZEROCLAW_PRESET_IMAGE:zeroclaw:latest}
        recommended: true
```

- `GET /v1/images` returns preset list for frontend image selector.
- If `allow-custom-image: false`, `POST /v1/instances` only accepts images from the preset list.
- `POST /v1/instances` returns `409 Conflict` if instance name already exists (case-insensitive).
- `DELETE /v1/instances/{instanceId}` removes the instance and its action history.
- API calls plane service for real execution. Configure:
  - `PLANE_BASE_URL` (default: `http://127.0.0.1:8090/internal/v1`)
  - `PLANE_REQUESTED_BY` (default: `fun-ai-claw-api`)
- Instance main-agent guidance is stored in DB table `instance_agent_guidance`.
- Runtime sync resolution priority on START/RESTART/ROLLBACK:
  1. Instance override (`enabled=true`)
  2. Global fallback file (`app.agent-guidance.default-main-agents-md-path`)
  3. Global inline fallback (`app.agent-guidance.default-main-agents-md`)
- If no effective prompt is resolved and `overwrite-on-start=true`, API sends an empty payload so runtime can clear stale workspace `AGENTS.md`.
- Management API returns `workspacePath` so frontend can show the actual runtime file path.
- Set `ZEROCLAW_PRESET_IMAGE` in deployment env to point to your own registry mirror.

## Update Script

Use `update-claw-api.sh` for one-command update on server:

```bash
chmod +x /opt/fun-ai-claw-api/update-claw-api.sh
/opt/fun-ai-claw-api/update-claw-api.sh
```

Optional environment variables:

- `APP_DIR` (default: `/opt/fun-ai-claw-api`)
- `SERVICE_NAME` (default: `fun-ai-claw-api`)
- `GIT_REMOTE` (default: `origin`)
- `GIT_BRANCH` (default: `main`)
- `HEALTH_URL` (default: `http://127.0.0.1:8080/v1/health`)
- `MVN_CMD` (default: `mvn`, Maven >= `3.6.3`)
