# esopa domain

The living description of esopa's domain. Update it in the same change as the code.

The core domain, the reason esopa exists, isn't modelled yet. So far there are two supporting contexts: identity, for
who someone is, and access, for what they may do.

## Glossary

| Term | Meaning |
|---|---|
| **Account** | What a person registers to use esopa: an email address and a password. The identity context's aggregate. |
| **Email address** | Identifies an account. Compared case-insensitively: stored trimmed and lower-cased. One account per address. |
| **Registration** | Creating an account. Anyone can register; the account can't log in until its email address is confirmed. |
| **Confirmation** | Proving that the person registering can read email sent to the address, by using the confirmation link. |
| **Confirmation link** | Emailed on registration or on request. Works for 24 hours, and only for the address it was sent to. |
| **Login** | Starting a session with the email address and password. |
| **Session** | What keeps a person logged in on one device. Lasts 14 days from login; activity doesn't extend it. |
| **Session version** | A number on the account. Sessions issued for an older version have ended. |
| **Log out** | Ends the session on this device. |
| **Log out everywhere** | Ends every session of the account, on every device. |
| **Password reset** | Choosing a new password through a reset link, without knowing the current one. |
| **Reset link** | Emailed on request. Works for 1 hour, and only once. |
| **Breached password** | A password that appears in a known data breach, according to Have I Been Pwned's Pwned Passwords. |
| **Throttling** | Refusing further attempts for a while after too many, without locking anything. |
| **Permission** | What a feature needs, like assigning roles. Features only ever check permissions, never roles. Permissions change only when features do. |
| **Role** | A named group of permissions that describes what people do, like Admin or Member. Defined in esopa's code, and changed whenever the business needs. |
| **Admin** | The role that may assign roles. |
| **Member** | The role every account gets when it registers. It grants nothing yet; the core domain's features will add to it. |
| **Roles of an account** | The roles an account has. Its permissions are those of all its roles together. Roles apply everywhere in esopa. |

## Bounded contexts

- **Identity** (`esopa.domain.identity`, `esopa.identity.*`): accounts, passwords and sessions. A generic, supporting
  context. Other contexts should refer to an account only by `:account/id` and ask identity for anything else.
- **Access** (`esopa.domain.access`, `esopa.access.*`): permissions, roles and the roles of each account. A
  supporting context.
  - Identity announces each new account ("account registered"), and access gives it the roles new accounts get.
  - Access asks identity for the list of accounts it shows with their roles.
  - Every other context declares the permissions its features need in `esopa.domain.access.roles/permissions`, and
    asks whether an account's roles grant them.

## Account aggregate

`esopa.domain.identity.account`; schema `Account`.

| Attribute | Meaning |
|---|---|
| `:account/id` | Identity. |
| `:account/email` | The normalized email address. Unique. |
| `:account/password-hash` | argon2id hash. The password itself is never stored. |
| `:account/registered-at` | When the account was registered. |
| `:account/confirmed-at` | When the email address was confirmed; absent until then. |
| `:account/session-version` | Starts at 0. |

### Invariants and rules

- **One account per email address.** Registering an address that already has an account doesn't change that account.
- **Passwords** are 8 to 128 characters, counted as Unicode code points. There are no rules about character types.
  A password can't be the account's email address, and can't be a breached password.
- **An unconfirmed account can't log in.** Confirming twice changes nothing.
- **A password reset also confirms the email address**, since the reset link reached it.
- **Ending sessions.** Changing the password, resetting it and logging out everywhere increment the session version,
  ending every session. Changing the password keeps the current device logged in with a new session.
- **A reset link works once**, because it carries the session version, which the reset increments. Logging out
  everywhere also cancels outstanding reset links.
- **Nobody can find out whether an email address has an account:**
  - Registering a taken address gives the usual outcome and emails the owner instead of sending a confirmation link.
  - A failed login only says that the email address or password is wrong. Only with the right password does an
    unconfirmed account learn that it must confirm first.
  - Asking for a reset link or a new confirmation link gives the same outcome whether or not an account exists.
- **Limits** (`account/limits`), counted in memory:
  - 5 failed logins per email address in 15 minutes, whether or not the address has an account.
  - 50 failed logins per client IP address in 15 minutes.
  - 5 emails per address in an hour. Further emails are silently not sent.

  When a login limit is reached, logging in is refused until the window ends, even with the right password. The
  account is never locked, and a password reset still works. A successful login clears the address's failures.

### Domain events

- **Account registered**: identity announces it once a new account is stored (`:account-registered` in the identity
  use cases' deps), and access gives the account its first roles.

Other facts are attributes of the account, and Datomic's history records when each changed and whose action it was
(see "Who changed what").

## Roles of an account

`esopa.domain.access.roles`; schemas `Permission`, `Role` and `Roles`.

| Attribute | Meaning |
|---|---|
| `:access/roles` | The account's roles, on the account's entity beside identity's attributes. |

### Invariants and rules

- **An account's permissions are those of all its roles together.** No role takes a permission away, so what an
  account may do is always the union of what its roles allow.
- **Roles apply everywhere.** They aren't held within an organisation or team.
- **Only roles that exist can be given.**
- **New accounts are members.**
- **Accounts can't take away their own permission to assign roles**, so an admin can't lock everyone out by
  removing their own admin role. Another admin can.
- **Assigning roles needs the permission `:access/assign-roles`.** So does seeing every account with its roles.
- **Whether an account may act on one particular thing**, like cancelling a particular booking, is decided by the
  permission for that kind of action together with that thing's own rules, in its aggregate.
- **The first admin** is made with the `grant-role` command, run while esopa is stopped. After that, admins assign
  roles on the accounts page.

## Who changed what

Every change records the account whose action it was, as `:transaction/actor` on its Datomic transaction. Changes made
outside esopa's pages, such as with the `grant-role` command, record no account.

## Decisions

### Identity (2026-11-01)

- **Build identity into esopa** instead of using an identity provider. Rejected: an OpenID Connect provider such as
  Keycloak or Auth0. We'd revisit that if single sign-on, two-factor login or social login become requirements.
- **The term "Account"** for the people who sign up. Rejected: user, member, customer.
- **Open registration, confirming the email address before the first login.** Rejected: confirming later, no
  confirmation, invitation only.
- **Passwords of at least 8 characters, combined with the breached-password check and throttling.** Rejected: NIST's
  15-character minimum for password-only login, as too much friction for a service that isn't security-critical, and
  12 characters, which adds friction without helping against reused passwords.
- **Throttling instead of lockout**, because a lockout lets anyone lock a real person out.
- **Sessions last 14 days from login.** Activity doesn't extend them.
- **Emailed links open a page with a button**, and the button does the change, so mail scanners that open links can't
  confirm an account.

### Access (2026-11-01)

- **Features check permissions; roles group them.** Permissions are few and stable; roles are defined to suit the
  business and can change without touching any feature. Rejected: checking roles in features, which spreads every
  change to a role across the code.
- **Roles are defined in code, as data**, reviewed and tested with the permissions they name. Changing a role is a
  one-line change and a deploy. Rejected: roles stored in Datomic and edited on esopa's pages, which needs pages for
  editing roles and makes what a role allows depend on production data. We'd revisit that if people who don't change
  code need to define roles.
- **Roles apply everywhere.** Rejected: roles held within an organisation, which only matters if several businesses
  or teams share esopa.
- **Access to a particular thing is a permission plus the thing's own rules.** Rejected: per-instance grants, such as
  Shiro's `booking:cancel:123`, which store many grants and put business rules in strings.
- **Only allowing, never denying.** Rejected: roles that take permissions away, which make an account's permissions
  depend on how roles combine.
- **New accounts are members**, and **accounts can't take away their own permission to assign roles.**
- **The first admin comes from a command run while esopa is stopped**, because Datomic Local serves one process at a
  time. Rejected: an `ADMIN_EMAIL` setting applied at startup, which hands out a privilege through configuration and
  wouldn't take it away when removed.
