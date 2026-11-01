# esopa

Clojure 1.13 (alpha) web service using the Clojure CLI, [Pedestal](http://pedestal.io) 0.8 on Jetty 12, and
[Datomic Local](https://docs.datomic.com/datomic-local.html) via the Datomic Client API. Tooling (Temurin JDK 25 and
the Clojure CLI) is managed by [mise](https://mise.jdx.dev).

## Setup

```bash
mise install
```

## Tasks

| Command                                | What it does                                                        |
|----------------------------------------|---------------------------------------------------------------------|
| `mise run dev`                         | nREPL (with CIDER middleware) in Pedestal dev mode; then `(start)`  |
| `mise run run`                         | Start the server on http://localhost:8080                           |
| `mise run test`                        | Run the fast test suite (Kaocha)                                    |
| `mise run test:watch`                  | Rerun the fast suite on every change                                |
| `mise run test:generative`             | Run the generative (property-based) tests                           |
| `mise run ci`                          | Fast suite under coverage, CRAP report, fail if any function > 15   |
| `mise run mutate src/esopa/<file>.clj` | Mutation-test one source file                                       |
| `mise run uberjar`                     | Build `target/esopa.jar`                                            |
| `mise run outdated`                    | Report outdated dependencies (antq)                                 |

How we develop (TDD, DDD, checked keys, CRAP and mutation testing) is described in [AGENTS.md](AGENTS.md).

Run the jar with `java --sun-misc-unsafe-memory-access=allow -jar target/esopa.jar`. The flag silences a JDK 25
warning about `sun.misc.Unsafe` use in Caffeine, a Datomic dependency.

## Configuration

| Env var               | Default        |
|-----------------------|----------------|
| `HOST`                | `localhost` (use `0.0.0.0` when deployed) |
| `PORT`                | `8080`         |
| `DATOMIC_STORAGE_DIR` | `data/datomic` |
| `DATOMIC_DB_NAME`     | `esopa`        |

## Layout

- `src/esopa/db.clj` – schema, connection, queries
- `src/esopa/server.clj` – routes, handlers, Pedestal connector
- `src/esopa/system.clj` – configuration from the environment, start/stop of the whole app
- `src/esopa/main.clj` – `-main` entry point
- `dev/user.clj` – REPL helpers `start`, `stop`, `restart`

## API

```bash
curl localhost:8080/health
curl -XPOST localhost:8080/api/notes -H 'content-type: application/json' -d '{"text":"hello"}'
curl localhost:8080/api/notes
```
