# Semaphore UI Plugin Plan

## Downloaded references

- OpenAPI/Swagger downloaded to `docs/api/semaphore-api-docs.yml`
- Public HTML downloaded to `docs/api/semaphore-api-docs.html`
- API version observed in the specification: `2.16.14`
- API `basePath`: `/api`

## Functional scope

The plugin focuses on three operator flows:

1. View available Semaphore projects.
2. View templates grouped by project view, with the latest task executed for each template.
3. Launch tasks from an existing template and follow the output of that latest task from the IDE.

This phase does not cover full administration of inventories, repositories, environments, schedules, or advanced survey vars.

## UI design

### Bottom tool window

A bottom `Semaphore UI` panel is registered with this composition:

- Top toolbar:
  - `Refresh`
  - `Run template`
  - `Stop task`
  - `Settings`
  - connection/status label
- Left column:
  - project list (`GET /projects`)
- Upper center area:
  - view tabs from `GET /project/{project_id}/views`
  - one template table per active view tab using `GET /project/{project_id}/templates`, with:
    - latest task id
    - latest task status
    - launcher name
    - latest run timestamp
- Lower center area:
  - latest task output viewer for the selected template (`GET /project/{project_id}/tasks/{task_id}/raw_output`)

### Interactions

- Selecting a project reloads views and templates.
- Switching tabs filters templates by the selected project view.
- Selecting a template enables `Run template`.
- Selecting a template loads the output of its latest task.
- `Stop task` acts on the latest task of the selected template.
- A 5-second refresh cycle keeps the visible state updated without depending on websockets yet.

### Run dialog

`Run template` opens a dialog with overrides compatible with `POST /project/{project_id}/tasks`:

- `git_branch`
- `message`
- `arguments`
- `limit`
- `debug`
- `dry_run`
- `diff`

If a template contains `survey_vars`, the UI reports it, but the first version does not try to resolve them because the downloaded specification does not document a dedicated payload for that endpoint.

## Settings panel

A panel is reserved in `Settings > Tools > Semaphore UI` with:

- `Semaphore URL`
- proxy configuration
  - `No Proxy`
  - `HTTP`
- `SOCKS5`
  - `host`
  - `port`
- authentication mode
  - `API Token` recommended
  - `Username and Password` as session-login fallback
- `API Token`
- `Username`
- `Password`

### Persistence

- Non-sensitive data: `PersistentStateComponent`
  - URL
  - authentication mode
  - proxy mode
  - proxy host/port
  - username
- Secrets: `PasswordSafe`
  - API token
  - password

SOCKS5 is supported in this phase without interactive authentication prompts. Proxy authentication remains out of scope to avoid relying on process-wide IDE authentication hooks.

## Authentication strategy

### Preferred: Bearer token

The specification declares `securityDefinitions.bearer` using the `Authorization` header, so the client uses:

`Authorization: Bearer <token>`

Advantages:

- avoids cookie/session handling in the IDE
- fits automation and non-interactive usage
- reduces reauthentication friction

### Secondary: session login

The `POST /auth/login` flow is implemented for environments where tokens are not available. The client keeps HTTP cookies inside the plugin process.

## Endpoint map for the integration

### Connection and validation

- `GET /ping`
- `GET /info`

### Project navigation

- `GET /projects`

### Templates

- `GET /project/{project_id}/templates`

### Tasks

- `GET /project/{project_id}/tasks/last`
- `POST /project/{project_id}/tasks`
- `POST /project/{project_id}/tasks/{task_id}/stop`
- `GET /project/{project_id}/tasks/{task_id}/output`
- `GET /project/{project_id}/tasks/{task_id}/raw_output`

## Refresh strategy

First phase:

- polling every 5 seconds for tasks and output
- simpler and more robust for a first plugin iteration
- does not depend on additional behavior from `/ws`
- HTTP client implemented with `HttpURLConnection` and `URL.openConnection(Proxy)` to support per-plugin `HTTP` and `SOCKS5` proxies

Next phase:

- study `/ws` for real-time task events
- keep polling only as a fallback

## Proposed code structure

- `settings/`
  - `SemaphoreSettingsState`
  - `SemaphoreCredentialStore`
  - `SemaphoreSettingsConfigurable`
- `api/`
  - `SemaphoreApiModels`
  - `SemaphoreApiClient`
  - `SemaphoreIntegrationService`
- `toolwindow/`
  - `SemaphoreToolWindowFactory`
  - `SemaphoreToolWindowPanel`
  - `RunTemplateDialog`

## Risks and next increments

1. Resolve `survey_vars` and generate dynamic forms per template.
2. Replace polling with websocket-driven updates if `/ws` exposes useful task events.
3. Add task status filters and richer contextual actions in the task table.
4. Add a project details tab if inventory, repository, or environment views become necessary.
