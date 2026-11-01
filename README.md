# esopa

Clojure 1.13 (alpha) web application using the Clojure CLI, [Pedestal](http://pedestal.io) 0.8 on Jetty 12, and
[Datomic Local](https://docs.datomic.com/datomic-local.html) via the Datomic Client API. Tooling (Temurin JDK 25 and
the Clojure CLI) is managed by [mise](https://mise.jdx.dev).

So far it has registration, login, password management and roles as server-rendered pages. The domain is described in
[docs/domain.md](docs/domain.md).

The pages are semantic HTML styled by [Pico CSS](https://picocss.com) 2, in its default theme, which follows the
browser's light or dark preference. [htmx](https://htmx.org) 4 boosts every link and form: it fetches the next page in
the background and swaps it in, so the browser doesn't reload. Without JavaScript, the pages work as plain HTML. Both
libraries are Maven dependencies packaged as [WebJars](https://www.webjars.org), served under `/webjars`, so
`mise run outdated` reports their updates too.

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
| `mise run test:browser`                | Run the browser tests: Playwright drives headless Chromium, axe-core checks accessibility |
| `mise run ci`                          | Fast suite under coverage, CRAP report, fail if any function > 15   |
| `mise run mutate src/esopa/<file>.clj` | Mutation-test one source file                                       |
| `mise run uberjar`                     | Build `target/esopa.jar`                                            |
| `mise run outdated`                    | Report updates for deps.edn, GitHub Actions and mise tools          |

How we develop (TDD, DDD, checked keys, CRAP and mutation testing) is described in [AGENTS.md](AGENTS.md).

The first `mise run test:browser` downloads Playwright's driver (about 200 MB, into `~/.m2`) and its headless Chromium
(about 280 MB, into `~/.cache/ms-playwright`). After that, the suite takes about half a minute.

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

Emails are sent in the background. Without `SMTP_HOST`, they are written to the log, links included, which is only
fit for development.

In the REPL, `(start)` also starts GreenMail, an SMTP server inside the JVM, and sends email to it unless
`SMTP_HOST` is set. `(emails)` lists what was sent, newest first, and `(last-link)` returns the link from the newest
email, ready to open in a browser.

In the REPL, which runs in Pedestal's dev mode, edits to the files in `resources/public/assets` show on the next
request. Elsewhere they're cached until a restart.

## Roles

Features are gated by permissions, and roles group permissions: admins may assign roles, and every new account is a
member. The details are in [docs/domain.md](docs/domain.md).

The first admin is made with a command, run while esopa is stopped, because Datomic Local serves one process at a
time:

```bash
java --sun-misc-unsafe-memory-access=allow -jar target/esopa.jar grant-role ada@example.com admin
```

Locally, stop `mise run run` and use `clojure -M:run grant-role ada@example.com admin`. In the REPL,
`(grant-role! "ada@example.com" "admin")` works on the running system. After that, admins change roles at
`/admin/accounts`.

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
| `/admin/accounts`   | Every account with its roles, which admins change here. |
| `/health`           | `{"status":"ok"}` as JSON. |
| `/assets/…`         | esopa's stylesheet and scripts. |
| `/webjars/…`        | htmx and Pico, at paths that include their versions. |

Errors have pages in the site's layout too: 404 for an address without a page, 403 for an expired form (a missing
or stale anti-forgery token) and 500 for a failure, which is logged. When the browser can't reach esopa, the page it's
on says so.

The layout of the source is described in [AGENTS.md](AGENTS.md#layout).
