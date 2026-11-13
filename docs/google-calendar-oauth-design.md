# Google onboarding without a required central provider

This is a proposed follow-up architecture, not an implemented service. The
present Talon change opens setup in the system browser. The companion
Calendar change improves the existing bring-your-own-client workflow.

## Improve the direct path first

The ship is already a confidential OAuth client: it stores a user's client
secret and refresh token, talks to Google, and syncs events. Importing the
client JSON, validating the callback, guiding Cloud setup, identifying the
connected account, and reporting actionable sync errors do not require a
central service. A wizard cannot eliminate Google's requirement to register
an application and obtain user consent.

The next backend change should support independent accounts:

- Store account records keyed by an opaque local account ID, with provider,
  verified Google subject, display email, client identity, scopes, tokens,
  expiry, authorization generation and health. Treat email as a display
  label, not an immutable key. Verify any ID token's signature, issuer,
  audience and nonce before using identity claims; requesting identity scopes
  is a separate explicit consent choice.
- Bind each calendar link to an account ID and Google calendar ID. Scope sync
  cursors, event maps, queues and token-refresh locks to that pair. Reconnect
  A must never replace B, and one account's failure must not stop the others.
- Bind each pending authorization to an owner session, single-use random
  state, expiry, chosen account slot, client and fixed redirect URI. Use PKCE
  where supported. Consume state on success or denial; concurrent flows must
  not overwrite one shared `state` field. Check the generation before an
  in-flight refresh or sync may write back after disconnect/reconnect.
- Migrate the existing single token record into a legacy account slot and
  attach existing links without discarding events or forcing reauthorization.
  Resolve the account's identity before permitting a switch; ask explicitly
  when its identity differs. Show a per-account reconnect/disconnect action.
- Separate OAuth-connected, calendar-linked, first-sync-pending, healthy and
  failed states. Preserve prior event data on permission errors. Surface
  inaccessible calendar links even if they disappear from calendarList.
- Offer read-only sync with `calendar.events.readonly` and
  `calendar.calendarlist.readonly`; request `calendar.events` only for
  two-way editing after the backend enforces the chosen mode. Fetch all
  calendarList pages. Recheck the exact endpoints and Google verification
  requirements before reducing the existing broad scope.

Google's [scope reference](https://developers.google.com/workspace/calendar/api/auth)
supports separating event access from calendar-list access. These proposed
scopes do not themselves restrict tokens to only the calendars selected in
Calendar's UI; selection is enforced by Calendar.

Required tests include three accounts concurrently, identical calendar IDs
under different accounts, denied/cancelled/replayed callbacks, two tabs
authorizing concurrently, revoked/expired credentials, failed refreshes,
disconnect during a refresh, missing permissions, pagination, migration and
rollback. A real-account pilot is needed before claiming end-to-end support.

## Optional provider: a token broker, not a calendar proxy

A verified provider can own one Google OAuth project and a fixed HTTPS
callback, sparing each user the Cloud Console. It cannot remove consent,
Workspace administrator controls, or the account model above. A recommended
implementation would broker authorization and refresh only; calendar event
API calls would still travel directly between the ship and Google.

1. The user chooses a broker or self-hosted endpoint in Calendar. The ship
   establishes a narrowly scoped broker identity using a dedicated key, with
   an owner-approved enrollment ceremony. Never request the ship's `+code`,
   Eyre session cookie, or general-purpose signing capability. Bind identity
   and the encrypted response key to the same verified enrollment; a random
   caller must not be able to substitute its key for another ship's.
2. The ship starts an authenticated transaction. The broker records a short
   expiry, one-use random state, PKCE challenge where supported, account slot,
   approved scopes and the enrolled ship key. The system browser opens
   Google's consent flow. The broker's registered callback is fixed; arbitrary
   `return_to` URLs and arbitrary token/API endpoints are rejected.
3. The broker validates state and the authorization response and exchanges the
   code with Google. It encrypts the resulting token bundle to the enrolled
   ship key, holds it briefly for authenticated polling, and deletes the
   transaction after acknowledgment or expiry. No tokens go in URLs, browser
   storage, analytics, email, or request logs. If a browser returns to the
   ship, it carries only a one-use completion reference bound to that ship.
4. The ship stores the refresh token. When an access token expires, it sends
   the refresh token to the broker over authenticated TLS; the broker supplies
   its server-held client secret to Google's token endpoint and returns the
   new access token encrypted to the enrolled ship. The broker handles the
   refresh token transiently and does not need a persistent token vault.
   If Google introduces or requires a different supported refresh mechanism,
   validate it before changing this boundary.
5. The ship calls Google's Calendar API directly. Broker outages pause new
   token issuance; they do not erase local events. Retry transient failures
   with bounded backoff and expose status instead of silently dropping edits.
   Export/recovery must keep the broker identity and client association clear:
   a refresh token cannot simply move to an unrelated OAuth client.

This reduces routine data exposure but is **not zero knowledge**. The broker
sees tokens during exchange/refresh and could use or retain them to access
whatever Google authorized. Encrypting a response to the ship does not undo
that access. Google still sees the account, permissions and API traffic. The
broker also learns connection metadata. A service that proxies calendar API
traffic would additionally see event contents and is unnecessary here.

The provider therefore needs strict endpoint allowlists, tenant isolation,
per-ship authentication, rate limits, single-flight refresh handling, no token
logging, short transaction retention, protected client credentials, redacted
operational metrics, a published incident/revocation process, independent
review, reproducible builds and a self-hosting option. Google tokens should
be encrypted at rest on the ship; merely writing them into a ship tree does
not establish encrypted backups. Google describes credential handling and
refresh-token invalidation in its
[best practices](https://developers.google.com/identity/protocols/oauth2/resources/best-practices).

Do not distribute the broker's confidential client secret to all ships or
embed it in Talon. Do not promise Google verification removes a company's
admin restrictions. A broker reduces setup steps by introducing a trusted
operator; direct mode preserves the choice to avoid that operator. Provider
launch would need Google's verification/policy review, security review, and
explicit consent separate from this draft PR.
