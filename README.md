# AI SQL Analyst

Ask plain-English questions about a sample e-commerce PostgreSQL database and get back a
results table, the generated SQL, and a short explanation. A Next.js frontend calls a Spring
Boot backend, which translates the question to a single read-only `SELECT`, validates it,
executes it through a restricted read-only database role, and records every interaction.

- **Frontend**: Next.js (App Router, TypeScript) + Tailwind, in `frontend/`.
- **Backend**: Spring Boot (web, validation, JDBC) + JSqlParser, in `backend/`.
- **Database**: PostgreSQL with an init script in `db/` that creates the schema, seeds data,
  and provisions the read-only role.

## Quick start with Docker

Requires Docker and the Docker Compose plugin.

```bash
# 1. Create your local env file from the template and set the passwords.
cp .env.example .env
#    Edit .env and set POSTGRES_PASSWORD and DB_RO_PASSWORD (any non-empty values).
#    LLM_PROVIDER defaults to "mock" (no API key needed). To use a real provider,
#    set LLM_PROVIDER=http and fill in LLM_BASE_URL, LLM_MODEL and LLM_API_KEY.

# 2. Build and start all three services (postgres, backend, frontend).
docker compose up --build

# 3. Open the app.
#    http://localhost:3000   (frontend)
#    http://localhost:8080   (backend API)
```

To stop and reset everything, including the database volume (so the init script re-runs on the
next start):

```bash
docker compose down -v
```

### Configuration

Environment variables are read from `.env` (see `.env.example`):

| Variable            | Purpose                                                        |
|---------------------|----------------------------------------------------------------|
| `POSTGRES_PASSWORD` | PostgreSQL superuser / read-write role password.               |
| `DB_RO_PASSWORD`    | Password for the read-only role created by `db/01-init.sh`.    |
| `LLM_PROVIDER`      | `mock` (default, offline) or `http` (OpenAI-compatible API).   |
| `LLM_BASE_URL`      | Provider base URL (e.g. `https://api.groq.com/openai/v1`).     |
| `LLM_MODEL`         | Model id (used when `LLM_PROVIDER=http`).                      |
| `LLM_API_KEY`       | Provider API key (used when `LLM_PROVIDER=http`).              |

The frontend reads `NEXT_PUBLIC_API_URL` and `NEXT_PUBLIC_MAX_QUESTION_LENGTH`, which are build
arguments baked into the image at build time (defaults `http://localhost:8080` and `300`).
Changing them requires rebuilding the frontend image.