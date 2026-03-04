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

## File-based private config

`application.yml` now imports optional local file:

- `./application-private.yml`

Recommended:

1. Copy `application-private.example.yml` to `application-private.yml`
2. Fill your real `app.llm-gateway.auth-token`
3. Keep `application-private.yml` out of git (already in `.gitignore`)

## Current Scope

- `GET /v1/health`
- `GET /v1/images`
- `GET /v1/instances`
- `POST /v1/instances`
- `DELETE /v1/instances/{instanceId}`
- `POST /v1/instances/{instanceId}/actions`
- `GET /v1/models`
- `POST /v1/chat/completions`
- `POST /v1/messages`

Compatible aliases:

- `GET /api/v1/models`
- `POST /api/v1/chat/completions`
- `POST /api/v1/messages`

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
- Set `ZEROCLAW_PRESET_IMAGE` in deployment env to point to your own registry mirror.

## LLM Data API

This service now exposes a unified LLM data plane for agents.
Agents should call `fun-ai-claw-api` endpoints instead of calling vendor APIs directly.

Configuration (`src/main/resources/application.yml`):

- `LLM_GATEWAY_BASE_URL` (default: `https://api.ai.fun.tv/v1`)
- `LLM_GATEWAY_AUTH_TOKEN` (default: empty)
- `LLM_GATEWAY_AUTH_SCHEME` (default: `Bearer`)
- `LLM_GATEWAY_TIMEOUT_SECONDS` (default: `120`)
- `LLM_GATEWAY_PREFER_INCOMING_AUTHORIZATION` (default: `true`)

Or use file config (`application-private.yml`) with the same keys under:

- `app.llm-gateway.base-url`
- `app.llm-gateway.auth-token`
- `app.llm-gateway.auth-scheme`
- `app.llm-gateway.timeout-seconds`
- `app.llm-gateway.prefer-incoming-authorization`

Behavior:

- If `LLM_GATEWAY_PREFER_INCOMING_AUTHORIZATION=true` and request includes `Authorization`, forward it upstream.
- Otherwise use configured `LLM_GATEWAY_AUTH_TOKEN`.

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
