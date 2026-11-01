# AGENTS.md

How we build esopa. Read this before changing code; it applies to humans and coding agents alike.

## Stack

- Clojure 1.13 pre-release (`1.13.0-alpha11`) on Temurin JDK 25, built with the Clojure CLI and `deps.edn`. No Leiningen.
- Pedestal 0.8 for HTTP, using the `io.pedestal.connector` API on Jetty 12.
- Datomic Local, used through the Datomic Client API (`datomic.client.api`).
- malli for schemas, validation and generated test data.
- Hiccup 2 for server-rendered HTML; buddy for password hashing (argon2id) and signed tokens.
- htmx 4 boosts the pages and Pico CSS 2 styles them. Both are WebJars, Maven dependencies served from the class path
  under `/webjars`; `esopa.webjars/path` gives a file's URL with the version in it. Add front-end libraries the same
  way, never from a CDN.
- Jakarta Mail 2 (Eclipse Angus) for sending email over SMTP, used directly: no maintained Clojure wrapper uses it.
- mise pins the JDK and Clojure CLI and defines every task below. Run `mise install` once.

Use `clojure`, never `clj`. `clj` needs rlwrap and exits without it. Any new alias that starts a JVM with the project's
dependencies needs `--sun-misc-unsafe-memory-access=allow` in `:jvm-opts`. Without it, JDK 25 warns about Datomic's use of
`sun.misc.Unsafe`.

## Commands

| Command                                          | Use it for                                                                    |
|--------------------------------------------------|-------------------------------------------------------------------------------|
| `mise run test`                                  | The fast suite (Kaocha `:unit`). Run it constantly.                           |
| `mise run test:watch`                            | The fast suite again on every save: the red-green loop.                       |
| `mise run test:generative`                       | Generative tests (`^:generative`). Not part of CI.                            |
| `mise run test:browser`                          | Browser tests (Playwright, headless Chromium, axe-core). Not part of CI.      |
| `mise run ci`                                    | What CI runs: the fast suite under Cloverage, the CRAP report and CRAP gate.  |
| `mise run crap`                                  | The CRAP report alone. Writes `.metrics/crap.edn`.                            |
| `mise run mutate src/esopa/<file>.clj`           | Mutation testing for one source file. Not part of CI.                         |
| `clojure -M:mutate src/esopa/<file>.clj --scan`  | Count a file's mutation sites without running tests.                          |
| `mise run dev`                                   | nREPL in Pedestal dev mode. Use `(start)`, `(stop)`, `(restart)` in `user`; `(emails)` and `(last-link)` show what was sent. |
| `mise run run`, `mise run uberjar`               | Run the server; build `target/esopa.jar`.                                     |
| `mise run outdated`                              | Report updates for `deps.edn`, GitHub Actions and mise tools, with the command that applies each. |

Dependency updates are manual; nothing opens update pull requests. Run `mise run outdated` regularly and apply updates
as ordinary changes that pass `mise run ci`. GitHub Actions stay pinned to commit SHAs with a version comment; pinact
updates both together.

## Discuss trade-offs before choosing a direction

Don't settle a significant decision silently in code. Before choosing a direction, set out the trade-offs explicitly
and agree on one with the user. This applies equally to business domain modelling and to technical design.

- **Domain modelling decisions.** Examples:
  - where aggregate and bounded-context boundaries lie
  - what a term means and which invariants an aggregate protects
  - whether a concept is an entity, a value or an event
  - how a business rule handles its edge cases

  Weigh how well each option matches how the business actually works and how it is likely to change, what consistency
  the business needs, and whether domain experts would recognise the model.
- **Technical decisions.** Examples:
  - libraries
  - data shapes and Datomic schema
  - API design
  - where effects happen
  - performance and operational cost

  Weigh complexity, testability, how easily the choice can be reversed, and the cost of changing it later.

For each such decision:

1. State the problem and the constraints you know of.
2. Present at least two realistic options. For each, say what it gains and costs, both for the business and
   technically.
3. Recommend one and explain why.
4. Wait for agreement before implementing it.
5. Record the decision and the alternatives you rejected. Domain decisions go in `docs/domain.md`; technical ones go in
   the commit message that implements them.

Small choices that are easy to reverse and sit within an agreed direction don't need this. A private helper's name is
one example.

## Red-green-refactor

Every change in behaviour starts with a test.

1. **Red.** Write one small failing `clojure.test` test for the next piece of behaviour. Run it and confirm it fails
   for the reason you expect: a failed assertion, not a typo or a missing namespace.
2. **Green.** Write the simplest code that passes. Don't write code that no test asks for.
3. **Refactor.** Improve names, structure and duplication in code and tests while everything stays green.
4. **Check.** `mise run ci` passes. If you added or changed tests for a source file, mutation-test that file.

A bug fix starts with a test that reproduces the bug.

## Clojure style: data-oriented

Follow the [Clojure Style Guide](https://guide.clojure.style) and these rules:

- **Data first.** Model information as plain immutable maps, vectors and sets keyed by namespaced keywords
  (`:order/id`). Prefer data to functions, and functions to macros. Don't reach for records, protocols or multimethods
  until you need open polymorphism, and never use them to build class hierarchies.
- **Pure core, effects at the edges.** Domain code only computes. It doesn't read the clock, generate ids, or touch the
  database or network. Callers pass those in as values (`now`, `id`, a Datomic `db` value). HTTP handlers and
  persistence adapters perform the effects.
- **Describe effects as data** when that makes a decision testable without infrastructure. For example, a domain
  function can return Datomic tx-data, which the adapter then transacts.
- **Small, single-purpose functions.** Prefer threading macros to deep nesting.
- **Explicit dependencies.** Pass in everything a function needs. Don't use global singletons or dynamic vars for
  configuration. The only mutable state is the running system, held in `esopa.system` and `user`.
- **Errors.** Expected outcomes, such as invalid input or a rule that forbids an action, are data. Signal exceptional
  cases with `ex-info` and a descriptive data map.
- Public functions have docstrings. Private helpers use `defn-`.

## Domain-driven design

The core domain is why this service exists. Model it with the most care and keep it free of technical concerns.

- **Ubiquitous language.** Name namespaces, functions, keywords and Datomic attributes with the terms domain experts
  use. If a term is unclear or missing, ask rather than invent one.
- **Domain documentation.** `docs/domain.md` is the living description of the domain. It covers:
  - a glossary of the ubiquitous language
  - the bounded contexts and how they relate
  - each aggregate and its invariants
  - domain events
  
  Update it in the same change as the code. Create it along with the first real domain concept.
- **Layout.** The core domain lives in `src/esopa/domain/<context>/` and is pure Clojure plus malli. It never
  requires Pedestal, Datomic, logging or anything that does I/O. HTTP handlers and persistence adapters live outside
  `domain` and depend on it, never the other way round.
- **Aggregates and invariants.** Each aggregate has a malli schema next to the functions that own it. A function that
  changes an aggregate takes the current value and a command, enforces the invariants, and returns the new value,
  events or tx-data. Other code doesn't `assoc` into aggregate maps directly.
- **Bounded contexts** are separate namespaces with a small public API. Don't reach into another context's internals;
  translate between models at the boundary.
- **The identity context** (accounts, passwords, login sessions) is a supporting context, not the core domain, which
  is still to be modelled. Its rules are in `esopa.domain.identity.account`. `esopa.identity.service` runs its use
  cases and is the pattern for new ones: each takes a `deps` map holding the connection and every effect it needs
  (clock, email, external checks), so tests can substitute them.

## Semantic, accessible HTML

Pages are clean, semantic HTML that meets [WCAG 2.2](https://www.w3.org/TR/WCAG22/) level AA. They work without
JavaScript; htmx and our scripts only improve them.

- **Elements for their meaning.** Use the element that says what the content is:
  - `header`, `nav`, `main` and `footer` for the page's landmarks
  - one `h1` per page, and headings in order
  - lists for lists
  - `a` to go somewhere, `button` to do something
  - a `label` for every field, and `fieldset` with `legend` for a group of fields

  Don't build these from `div` and `span`.
- **HTML before ARIA.** Add ARIA only where HTML has no element or attribute for the meaning, such as `role="alert"`,
  `role="switch"`, `aria-invalid` and `aria-describedby`. Wrong ARIA is worse than none.
- **Few classes.** Pico styles semantic elements, so markup needs few classes. Add one only for what elements and
  attributes can't express. Put esopa's styles in `site.css`, built on Pico's variables so they follow its light and
  dark themes.
- **Forms.**
  - Every field has a visible label and the `autocomplete` hint that fits it.
  - Help text sits below its field, linked with `aria-describedby`.
  - A problem is described in text. It's announced with `role="alert"` and takes focus with `autofocus`. The field it's
    about gets `aria-invalid="true"` and is described by it. `field` in `esopa.identity.views` does this.
  - A form shown again keeps what was typed, except passwords.
  - While htmx submits a form, its button is marked `aria-busy` (`show-busy.js`).
- **Keyboard and focus.**
  - Everything works with the keyboard alone, in a sensible order, with a visible focus indicator. Never remove
    outlines.
  - A full page load puts screen readers at the start of the new page; a swap doesn't. So after htmx swaps in a page,
    `move-focus.js` moves focus to its `h1`, which has `tabindex="-1"` so it can take focus. An `autofocus` element,
    like a problem, takes focus instead.
  - A message shown after a redirect follows the `h1`, so reading on from the heading reaches it.
- **Perceivable.**
  - Text contrast is at least 4.5:1 (3:1 for large text and for the borders of controls), in light and dark themes.
    Pico's colours meet this; check any colour you add.
  - Never convey meaning by colour alone: an invalid field also has its problem in text.
  - Every page has `lang`, a `title` that names the page, and `alt` text on images (empty for decoration).
  - Animations respect `prefers-reduced-motion`.
- **Testing.** The fast suite asserts the markup that carries meaning: landmarks, labels, the `aria-*` links between
  fields and their texts, and what takes focus (see `accessibility-test` in `esopa.identity.web-test`). The browser
  suite checks what only a browser shows, such as focus after a swap, keyboard use and CSP errors (see "Testing"). Run
  it after any change to a page or a script.

## Checked keys (Clojure 1.13)

Clojure 1.13 adds checked map destructuring. These forms were verified against `1.13.0-alpha11`. The feature is still
alpha and its syntax has changed between alpha releases, so check these forms again after upgrading Clojure.

| Form                                               | Effect                                                                                     |
|----------------------------------------------------|--------------------------------------------------------------------------------------------|
| `{:keys! [a b]}`, `:strs!`, `:syms!`, `:order/keys!` | Bind like `:keys`, but throw `IllegalArgumentException` ("Missing required key: :b") when a key is absent. Only presence is checked: a `nil` value passes. |
| `{:req! {total :order/total}}`                     | The checked form of `{total :order/total}`.                                                |
| `{:keys [a & :b :c]}`                              | Keys after `&` are named but not bound. Under `:keys!` they are still checked.             |
| `:missing m`                                       | Collect missing required keys in `m` instead of throwing.                                  |
| `:excess x`                                        | Bind the entries whose keys the form doesn't name.                                         |
| `:select s`, `:all a`                              | Bind the named entries, or the whole map, with `:or` defaults applied.                     |
| `(req! m k)`                                       | Like `get`, but throws when `k` is absent.                                                 |
| `(selector {:keys! [a b] :select s})`              | Turn a destructuring form into a function of a map.                                        |

A checked key can't have an `:or` default.

When to use them:

- **Use `:keys!`** for keys a function can't work without when our own code is the caller. Examples are domain function
  arguments, configuration and options maps. A missing key then fails immediately with its name instead of causing a
  `NullPointerException` further down.
- **Keep `:keys` with `:or`** for genuinely optional keys.
- **Don't validate untrusted input with checked keys.** That covers request bodies, external APIs and stored data from
  older versions. Validate it with malli at the boundary and respond with a 4xx. A checked-key exception there would
  become a 500.
- **Use `:select`, `:excess` and `selector`** to project or split maps where you would otherwise write
  `select-keys`/`dissoc` by hand.

## Testing

- **clojure.test with Kaocha.** Tests mirror the source: `src/esopa/foo.clj` is tested by `test/esopa/foo_test.clj`,
  in namespace `esopa.foo-test`. The test suites are defined in `tests.edn`.
- **malli is the source of truth for data shapes.** Schemas live in `src`, next to the code that owns the data.
  Tests assert shapes with `m/validate` or `m/explain`. When specific values don't matter, generate test data with
  `malli.generator` instead of building fixtures by hand.
- **Keep the fast suite fast.** Don't use sleeps, network ports or external services. The one exception is a server
  running inside the test JVM on a random local port, like GreenMail, the SMTP server in `esopa.email-test`.
  - HTTP tests call the connector with `io.pedestal.connector.test/response-for`. `esopa.identity.web-test` drives
    pages like a browser: it keeps cookies, follows redirects and submits forms with their anti-forgery token.
  - Database tests use in-memory Datomic (`:storage-dir :mem`) with a fresh `db-name` per test; see `test-deps` in
    `esopa.identity.service-test`, which also fixes the clock and collects sent emails.
- **Browser tests** live in `browser-test/` and run with `mise run test:browser`, which installs Playwright's headless
  Chromium the first time. They're kept out of the fast suite and CI. Playwright for Java and axe-core come from the
  `:browser` alias.
  - `esopa.browser` runs esopa for each test on a free local port, with `test-deps`, so tests read links from the
    outbox. Its pages fail the test on any console error or warning, script error or failed asset request, which
    catches Content-Security-Policy violations.
  - Wait with its helpers, never with sleeps. `click!`, `submit!` and `back!` return once the next page has replaced
    `main`, whether htmx swapped it in or the browser loaded it. `eventually?` and `focused?` poll for up to two
    seconds. Playwright's `waitForFunction` doesn't work on esopa's pages: the CSP refuses the code it evaluates.
  - `esopa.identity.pages-test` shows the pattern. It covers boosted links and Back, forms with problems, busy buttons,
    a whole visit from registering to logging out, keyboard-only use and browsing without JavaScript. It also scans
    every page and state with axe-core, in light and dark themes, and checks phone width. Extend `every-page` when
    you add a page or a state.
- **Write generative tests** (`clojure.test.check` with malli generators) when a property states the behaviour more
  clearly than examples do. Good candidates are:
  - aggregate invariants
  - round trips (encode/decode, write/read)
  - idempotence
  - agreement with a simpler model

  Generative tests complement example tests; they never replace them. Tag them `^:generative` so the fast suite skips
  them, and keep the number of trials modest:

  ```clojure
  (defspec ^:generative normalizing-an-email-is-idempotent 100
    (prop/for-all [email (mg/generator account/Email)]
      (let [normalized (account/normalize-email email)]
        (= normalized (account/normalize-email normalized)))))
  ```

  Run `mise run test:generative` when you change code they cover, and before you finish.

## CRAP metric

`mise run ci` runs the fast suite under Cloverage and scores every function in `src/` with
[crap4clj](https://github.com/unclebob/crap4clj). It fails when any function scores above **15**, the `max-crap` value
in `build.clj`.

CRAP = CC² × (1 − coverage)³ + CC, where CC is cyclomatic complexity. With full coverage the score equals CC, so the
gate also caps complexity.

When the gate fails, do one of two things:

- Reduce complexity: split the function, or replace branching with data such as a lookup map.
- Add the missing tests.

Don't raise the threshold, exclude code, or write tests without assertions just to gain coverage. Changing `max-crap`
is the user's decision. `.metrics/crap.edn` is rewritten on every run and isn't committed.

## Mutation testing

[clj-mutate](https://github.com/unclebob/clj-mutate) changes the source in small ways, such as `<` → `<=`,
`and` → `or` or `first` → `second`. It runs the fast suite against each change. A mutant that survives is a change no
test notices. Mutation testing is too slow for CI; use it to check that new tests actually pin down the behaviour.

- After adding or changing tests for a source file, run `mise run mutate src/esopa/<file>.clj`. Do one file at a time.
- Cover uncovered mutation sites first. Then kill survivors by strengthening the tests, or by simplifying code whose
  mutated branch turns out to be unnecessary.
- Commit or stash first: the tool edits the source file while it runs.
- Results go to `.metrics/mutate/`. Commit them, so later runs retest only changed forms and earlier survivors.
  `--mutate-all` forces a full run.
- Exit status 3 means survivors or uncovered mutation sites remain. Don't use `--update-manifest`, which marks every
  mutant as killed, unless the user asks for it.

## Libraries before custom code

Before writing generic functionality, look for a proven solution. That includes validation, parsing, JSON, dates and
times, HTTP clients, retries, configuration, scheduling, caching and auth. Check in this order:

1. **The language and JDK.** That's `clojure.core`, including the 1.13 additions (`merge-deep`, `some-vals`,
   `clojure.walk/transform-keys`, checked keys), and the Java 25 standard library (`java.time`, `java.net.http`,
   virtual threads).
2. **What the stack already provides.**
   - Pedestal: its interceptors (`io.pedestal.http.*`, `io.pedestal.service.interceptors`).
   - Datomic: queries, rules, `pull`, history.
   - malli: coercion, transformers, generators.
3. **Established libraries.** Search [Clojars](https://clojars.org), [cljdoc](https://cljdoc.org) and
   [The Clojure Toolbox](https://www.clojure-toolbox.com). Judge a library on four things:
   - adoption
   - maintenance
   - licence and transitive dependencies
   - whether it works on JDK 25 and Clojure 1.13

   A stable Clojure library with few recent commits is normal.

Write it yourself only when nothing fits, and keep it small. When you add a dependency, pin an exact version in
`deps.edn` and explain the choice in your summary.

## Security

- Every form posts its anti-forgery token; build forms with the `form` helper in `esopa.identity.views`.
- Render HTML with Hiccup, which escapes text. Never pass user input to `hiccup2.core/raw`.
- The Content-Security-Policy only allows esopa's own scripts and styles, so put them in `resources/public/assets`,
  never inline. Images may also be `data:` URIs, which Pico uses for its icons.
- Serve files from the class path only through `resource-routes` in `esopa.server`, which refuses paths that climb
  out with `..`. Pedestal's own `resource-routes` doesn't check for them.
- Secrets come from the environment and are validated at startup (`esopa.system/Config`). Never log passwords,
  tokens or the links in emails, except through `esopa.email/log-email!`, which is for development only and is what
  esopa uses when `SMTP_HOST` isn't set. The REPL sends to GreenMail instead.
- Emails are sent in the background (`esopa.email/in-background`), so response times don't reveal whether an
  address has an account. Keep it that way for any new email.
- Don't reveal whether an email address has an account: registration, password reset and resending confirmation
  give the same outcome either way.

## Layout

```
src/esopa/main.clj                     -main entry point
src/esopa/system.clj                   configuration from the environment; start and stop
src/esopa/server.clj                   Pedestal connector: interceptors, session cookie, security headers, routes
src/esopa/db.clj                       Datomic connection and the schema of every context
src/esopa/email.clj                    sending email over SMTP, or to the log without SMTP settings
src/esopa/webjars.clj                  URLs of the files in WebJars (htmx, Pico)
src/esopa/domain/identity/account.clj  the Account aggregate: pure rules, schemas and limits
src/esopa/identity/service.clj         identity use cases, with their effects
src/esopa/identity/db.clj              account schema and queries
src/esopa/identity/web.clj             identity routes and handlers
src/esopa/identity/views.clj           identity pages (Hiccup)
src/esopa/identity/emails.clj          identity emails
src/esopa/security/                    password hashing, signed tokens, Pwned Passwords, throttling
resources/public/assets/               stylesheet and scripts (show password, busy buttons, focus after a swap)
dev/user.clj                           REPL helpers
test/esopa/                            tests, mirroring src
browser-test/esopa/                    browser tests (Playwright) and their helpers, esopa.browser
docs/domain.md                         the domain: glossary, contexts, aggregates, rules
tests.edn                              Kaocha suites (:unit, :generative, :browser) and Cloverage options
build.clj                              uberjar and the CRAP gate
mise.toml                              tool versions and tasks
```

## Before you finish

- Every direction-setting decision was discussed and agreed before you implemented it, and it is recorded.
- `mise run ci` passes.
- `mise run test:generative` passes if generative tests cover the code you changed.
- Each source file whose tests you added or changed has been mutation-tested, with no survivors left. If one is an
  equivalent mutant, say so in your summary.
- `mise run test:browser` passes if you changed a page, a script or a stylesheet.
- `docs/domain.md` and `README.md` reflect the change.
