# AGENTS.md

How we build esopa. Read this before changing code; it applies to humans and coding agents alike.

## Stack

- Clojure 1.13 pre-release (`1.13.0-alpha11`) on Temurin JDK 25, built with the Clojure CLI and `deps.edn`. No Leiningen.
- Pedestal 0.8 for HTTP, using the `io.pedestal.connector` API on Jetty 12.
- Datomic Local, used through the Datomic Client API (`datomic.client.api`).
- malli for schemas, validation and generated test data.
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
| `mise run ci`                                    | What CI runs: the fast suite under Cloverage, the CRAP report and CRAP gate.  |
| `mise run crap`                                  | The CRAP report alone. Writes `.metrics/crap.edn`.                            |
| `mise run mutate src/esopa/<file>.clj`           | Mutation testing for one source file. Not part of CI.                         |
| `clojure -M:mutate src/esopa/<file>.clj --scan`  | Count a file's mutation sites without running tests.                          |
| `mise run dev`                                   | nREPL in Pedestal dev mode. Use `(start)`, `(stop)`, `(restart)` in `user`.   |
| `mise run run`, `mise run uberjar`               | Run the server; build `target/esopa.jar`.                                     |
| `mise run outdated`                              | Report outdated dependencies.                                                 |

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
- The notes API in `esopa.db` and `esopa.server` is a placeholder that shows the wiring. Replace it with the real
  domain.

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
- **Keep the fast suite fast.** Don't use sleeps, network ports or external services.
  - HTTP tests call the connector with `io.pedestal.connector.test/response-for`.
  - Database tests use in-memory Datomic (`:storage-dir :mem`) with a fresh `db-name` per test; see
    `esopa.server-test`.
- **Write generative tests** (`clojure.test.check` with malli generators) when a property states the behaviour more
  clearly than examples do. Good candidates are:
  - aggregate invariants
  - round trips (encode/decode, write/read)
  - idempotence
  - agreement with a simpler model

  Generative tests complement example tests; they never replace them. Tag them `^:generative` so the fast suite skips
  them, and keep the number of trials modest:

  ```clojure
  (defspec ^:generative created-notes-are-valid 100
    (prop/for-all [text (mg/generator note/Text)]
      (m/validate note/Note (note/create {:text text} {:id (random-uuid) :now (java.util.Date.)}))))
  ```

  Run `mise run test:generative` when you change code they cover, and before you finish. Until the first
  `^:generative` test exists, that task prints a warning and runs the whole fast suite instead.

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

## Layout

```
src/esopa/main.clj      -main entry point
src/esopa/system.clj    configuration from the environment; start and stop
src/esopa/server.clj    routes, handlers, Pedestal connector
src/esopa/db.clj        Datomic schema, connection, queries
dev/user.clj            REPL helpers
test/esopa/             tests, mirroring src
tests.edn               Kaocha suites (:unit, :generative) and Cloverage options
build.clj               uberjar and the CRAP gate
mise.toml               tool versions and tasks
```

## Before you finish

- Every direction-setting decision was discussed and agreed before you implemented it, and it is recorded.
- `mise run ci` passes.
- `mise run test:generative` passes if generative tests cover the code you changed.
- Each source file whose tests you added or changed has been mutation-tested, with no survivors left. If one is an
  equivalent mutant, say so in your summary.
- `docs/domain.md` and `README.md` reflect the change.
