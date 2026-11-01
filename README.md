# esopa

Clojure 1.13 (alpha) web application using the Clojure CLI, [Pedestal](http://pedestal.io) 0.8 on Jetty 12, and
[Datomic Local](https://docs.datomic.com/datomic-local.html) via the Datomic Client API. Tooling (Temurin JDK 25 and
the Clojure CLI) is managed by [mise](https://mise.jdx.dev).

So far it has registration, login and password management as server-rendered pages. The domain is described in
[docs/domain.md](docs/domain.md).

## Setup

```bash
mise install
```

## Tasks

| Command                                | What it does                                                        |
|----------------------------------------|---------------------------------------------------------------------|
| `mise run dev`                         | nREPL (with CIDER middleware) in Pedestal dev mode; then `(start)`  |
| `mise run run`                         | Start the server on http://localhost:8080 with throwaway keys       |
| `mise run test`                        | Run the fast test suite (Kaocha)                                    |
| `mise run test:watch`                  | Rerun the fast suite on every change                                |
| `mise run test:generative`             | Run the generative (property-based) tests                           |
| `mise run ci`                          | Fast suite under coverage, CRAP report, fail if any function > 15   |
| `mise run mutate src/esopa/<file>.clj` | Mutation-test one source file                                       |
| `mise run uberjar`                     | Build `target/esopa.jar`                                            |
| `mise run outdated`                    | Report updates for deps.edn, GitHub Actions and mise tools          |

How we develop (TDD, DDD, checked keys, CRAP and mutation testing) is described in [AGENTS.md](AGENTS.md).

Run the jar with `java --sun-misc-unsafe-memory-access=allow -jar target/esopa.jar`, with `SESSION_KEY` and
`TOKEN_KEY` set (see below). The flag silences a JDK 25 warning about `sun.misc.Unsafe` use in Caffeine, a Datomic
dependency.

## Configuration

| Env var               | Default                 | Notes |
|-----------------------|-------------------------|-------|
| `HOST`                | `localhost`             | Use `0.0.0.0` when deployed. |
| `PORT`                | `8080`                  | |
| `DATOMIC_STORAGE_DIR` | `data/datomic`          | |
| `DATOMIC_DB_NAME`     | `esopa`                 | |
| `BASE_URL`            | `http://localhost:8080` | Where esopa is served; used in the links in emails. |
| `SESSION_KEY`         | none, required          | 16 random bytes, base64: `openssl rand -base64 16`. Encrypts the session cookie; changing it logs everyone out. |
| `TOKEN_KEY`           | none, required          | At least 32 random bytes, base64: `openssl rand -base64 32`. Signs the links in emails; changing it cancels outstanding links. |
| `COOKIE_SECURE`       | `true`                  | `false` lets the session cookie work over plain HTTP, for local development only. |
| `TRUSTED_PROXIES`     | `0`                     | How many proxies in front of esopa append to `X-Forwarded-For`. Decides which address login throttling counts per IP. |
| `SMTP_HOST`           | none                    | The SMTP server that sends email. Without it, emails are only logged. |
| `SMTP_PORT`           | `587`                   | |
| `SMTP_FROM`           | none                    | Required with `SMTP_HOST`, like `esopa <noreply@example.com>`. |
| `SMTP_USERNAME`       | none                    | With `SMTP_PASSWORD`, logs in to the SMTP server. |
| `SMTP_PASSWORD`       | none                    | |
| `SMTP_STARTTLS`       | `true`                  | Refuse to send unless the server encrypts the connection with STARTTLS. `false` only for a local test server. |

The server refuses to start when a key is missing or the wrong size. `mise run run` and the REPL's `(start)` generate
throwaway keys, so restarting them logs you out.

Without `SMTP_HOST`, emails, including their links, are written to the log. That is only fit for development.

## Pages

| Path                | |
|---------------------|---|
| `/`                 | Home; shows who is logged in. |
| `/register`         | Register; a confirmation link is emailed. |
| `/confirm`          | Where the confirmation link leads. |
| `/confirm/resend`   | Get a new confirmation link. |
| `/login`            | Log in. `/logout` and `/logout-everywhere` take a POST. |
| `/password/forgot`  | Ask for a password reset link. |
| `/password/reset`   | Where the reset link leads. |
| `/password/change`  | Change the password while logged in. |
| `/health`           | `{"status":"ok"}` as JSON. |

The layout of the source is described in [AGENTS.md](AGENTS.md#layout).
