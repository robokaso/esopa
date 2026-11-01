# esopa domain

The living description of esopa's domain. Update it in the same change as the code.

The core domain, the reason esopa exists, isn't modelled yet. So far there is one supporting context, identity.

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

## Bounded contexts

- **Identity** (`esopa.domain.identity`, `esopa.identity.*`): accounts, passwords and sessions. A generic, supporting
  context. Other contexts should refer to an account only by `:account/id` and ask identity for anything else.

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

None are modelled as events yet. The facts are attributes of the account, and Datomic's history records when each
changed.

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
