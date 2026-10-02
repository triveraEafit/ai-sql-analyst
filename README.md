# AI SQL Analyst

Ask plain-English questions about a sample e-commerce PostgreSQL database and get back a
results table, the generated SQL, and a short explanation. A Next.js frontend calls a Spring
Boot backend, which translates the question to a single read-only `SELECT`, validates it,
executes it through a restricted read-only database role, and records every interaction.

- **Frontend**: Next.js (App Router, TypeScript) + Tailwind, in `frontend/`.
- **Backend**: Spring Boot (web, validation, JDBC) + JSqlParser, in `backend/`.
- **Database**: PostgreSQL with an init script in `db/` that creates the schema, seeds data,
  and provisions the read-only role.

The design and requirements documents live in [`docs/`](docs/).

## Quick start with Docker

Requires Docker and the Docker Compose plugin. Ports **3000** (frontend), **8080** (backend)
and **5432** (PostgreSQL) must be free.

```bash
# 1. Create your local env file. The committed demo defaults work as is for local use;
#    add an LLM key only for the real model (see "Choosing an LLM mode" below).
cp .env.example .env

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

### Choosing an LLM mode

- **No key (default): `LLM_PROVIDER=mock`.** The backend returns canned, valid answers for the
  five example questions, so the app works end to end offline with no API key.
- **Real model: `LLM_PROVIDER=http`.** Set `LLM_BASE_URL`, `LLM_MODEL` and `LLM_API_KEY` for any
  OpenAI-compatible chat-completions API. A free Groq key from
  [console.groq.com](https://console.groq.com) works with the defaults already in `.env.example`
  (`LLM_BASE_URL=https://api.groq.com/openai/v1`).

### Troubleshooting

- **`password authentication failed`** on backend startup usually means an old database volume
  that was created with different credentials. Reset it and start again:

  ```bash
  docker compose down -v
  docker compose up --build
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

The demo passwords in `.env.example` are for local use only - change them for any shared
deployment, and never commit a real `LLM_API_KEY`.

The frontend reads `NEXT_PUBLIC_API_URL` and `NEXT_PUBLIC_MAX_QUESTION_LENGTH`, which are build
arguments baked into the image at build time (defaults `http://localhost:8080` and `300`).
Changing them requires rebuilding the frontend image.