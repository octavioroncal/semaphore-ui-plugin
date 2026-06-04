# Semaphore UI

IntelliJ plugin for operating Semaphore UI from the IDE.

## Current features

- Bottom tool window `Semaphore UI`
- Semaphore project list
- Template view tabs grouped by project view
- Latest task metadata per template
- Task launch from existing templates
- Stop the latest task for the selected template
- Latest task output viewer for the selected template
- `Settings` panel for URL, authentication, and proxy configuration

## Local documentation

- Downloaded OpenAPI spec: `docs/api/semaphore-api-docs.yml`
- Downloaded public HTML: `docs/api/semaphore-api-docs.html`
- Integration plan: `docs/semaphore-integration-plan.md`

## Authentication

The plugin prioritizes `API Token` with the `Authorization: Bearer <token>` header.

`Username/Password` mode is also available through `POST /auth/login` and session cookies as a fallback.

## Proxy support

- `No Proxy`
- `HTTP`
- `SOCKS5` without interactive authentication prompts

## Development

- Run the plugin: `./gradlew runIde`
- Build and validate: `./gradlew check`
