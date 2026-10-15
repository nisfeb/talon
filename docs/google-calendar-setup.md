# Connect Google Calendar to your ship

Talon displays the Calendar app running on your ship. Calendar performs the
Google authorization and synchronization. You need both an OAuth authorization
and at least one **linked calendar** before events arrive. Signing into Gmail
or signing into Talon alone does neither.

## Before starting

- Install and permit Calendar through Grubbery. Open your ship's public HTTPS
  address in your system browser and sign into the ship. Keep this address
  consistent throughout setup.
- Update Calendar to version 31 or later. Version 31 fixed a callback mismatch
  on ships behind TLS proxies: the browser displayed HTTPS while the backend
  could send an HTTP callback to Google. Save the client again after updating.
- Calendar 32 holds **one Google authorization**, shared by all its Google
  calendar links. It supports many calendars visible to that account, not
  several independently signed-in Google accounts. Connecting another account
  replaces the previous authorization.
- Use a system browser for Google sign-in. Google rejects embedded webviews;
  Talon's **Calendars → Set up Google in browser** opens that route. If the
  installed Calendar does not recognize `#google`, choose **Settings → Google
  Calendar** once it opens. The browser may need its own ship login.

The implementation and release history are in
[nisfeb/calendar](https://github.com/nisfeb/calendar), separate from Talon.
Google documents the webview restriction under
[`disallowed_useragent`](https://developers.google.com/identity/protocols/oauth2/native-app#disallowed_useragent).

## Set up the Google project once

1. Open [Google Cloud](https://console.cloud.google.com/), select or create a
   project, and keep that project selected for the remaining steps.
2. Open **APIs & Services → Library**, find **Google Calendar API**, and enable
   it. Gmail API is not required for calendar sync.
3. Open **Google Auth Platform → Branding** (older navigation calls it **OAuth
   consent screen**). Supply the app name and requested contact information.
4. Under **Audience**, choose **External** for a personal Gmail account or
   accounts from different organizations. **Internal** only works for users
   inside the project's Workspace organization. If staying in **Testing**, add
   each account that will authorize the app to **Test users**. Calendar grants
   in Testing expire after seven days. For ongoing personal use, use **Publish
   app / In production** and reconnect after the change. Production status is
   separate from verification; it neither guarantees permanent tokens nor
   bypasses Workspace administrator rules.
5. Under **Clients → Create client**, choose **Web application**. Do not choose
   Desktop, Android, a service account, or an API key. Under **Authorized
   redirect URIs**, paste exactly the URI Calendar shows:

   ```text
   https://YOUR-SHIP-HOST/apps/calendar/google/callback
   ```

   Include the path, use the public HTTPS hostname, and omit a trailing slash.
   **Authorized JavaScript origins** is a different field.
6. Save the client and securely retain its client ID and secret. If Google
   offers a JSON download, keep it private. Never put it in a Git repository,
   issue, screenshot, or support message.

Google's [web-server OAuth guide](https://developers.google.com/identity/protocols/oauth2/web-server)
describes the client and exact redirect requirements;
[Audience settings](https://support.google.com/cloud/answer/15549945) and
[personal-use verification exceptions](https://support.google.com/cloud/answer/13464323)
explain the publishing choices. Personal use may qualify for an exception,
but an unverified-app notice can still appear. Only proceed if it identifies
your own project and expected Calendar permissions.

## Connect, link, then verify

1. On the ship's **Settings → Google Calendar**, enter the matching client ID
   and secret. With the companion Calendar onboarding change, you can instead
   import the Google Web client JSON; the page checks its callback against the
   current address. On older versions, paste both fields and press **Save**.
2. Press **Connect** / **Save and connect**. Choose the intended Google account
   and review Google's consent screen. The current Calendar requests the
   broad Calendar scope for two-way sync, including changes and deletions.
3. After Google returns you to your ship, **Link** each desired calendar. A
   connected account with zero linked calendars imports nothing.
4. Press **Sync now**. Wait for each linked calendar's **last sync** to advance
   and check its error. A successful request or saved authorization is not
   proof that an event was imported.
5. Compare an existing event's title, time, recurrence and calendar with Google,
   and refresh Talon. The default ship sync interval is five minutes; Talon
   has its own refresh cycle. No test invitation or event deletion is needed.

Calendar keeps refresh tokens on the ship and continues syncing when Talon is
closed. Ship administrators and backups are part of that trust boundary.
Disconnecting forgets the tokens locally; revoke the grant in your Google
account as well if you want Google to invalidate it.

## Several Google accounts

Do not connect accounts A, B and C consecutively expecting three connections.
Calendar 32 replaces one token record; old links can then fail under the new
account. This is a backend limitation, not a requirement for three Cloud
projects or a reason to use a central service.

One supported workaround is to give the connected Google account access to
the other calendars and add them to its Google calendar list. This discloses
calendar information to that account, so obtain the owner's permission and
follow the organization's policy. Full event details are needed for useful
imports; edit permission is needed for changes to go back. Free/busy-only
sharing cannot substitute. A Workspace administrator may prohibit external
sharing or block the OAuth client itself; those are distinct restrictions.

If sharing is inappropriate, independent account support must land in Calendar
first. [The proposed design](google-calendar-oauth-design.md) describes that
change without requiring a central provider. A secret ICS URL is not an
equivalent replacement: it grants whoever holds the URL read access, is
one-way, may be disabled by an organization, and the current feed importer
does not handle repeating events like the Google integration does.

## Diagnose by the observed error

| Symptom | What to check |
| --- | --- |
| `redirect_uri_mismatch` | Same Web client, exact public HTTPS callback including path; Calendar 31+; save again after an update or hostname change. |
| `access_denied` or admin block | Correct account, External/Internal audience, Testing allowlist, Workspace app policy. |
| `disallowed_useragent` | Reopen in the system browser, not an embedded app webview. |
| Token exchange fails | Matching client ID and secret; correct client type; start a fresh flow instead of reloading a used callback. |
| Works for about seven days | Project still in Testing; publish for ongoing personal use and reconnect. Tokens can also expire or be revoked for other reasons. |
| Connected but empty | Link a calendar, check last sync/errors, API enablement and account access. |
| First account stops after connecting another | The single authorization was replaced. Reconnect the original account; do not unlink calendars merely to troubleshoot. |
| Cloudflare rejects the ship before Calendar loads | This is a separate access-layer failure. Use an allowed browser or have the site owner diagnose the rule. Changing Google credentials cannot fix it. |

Google [documents app states and Workspace policy](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview).
For event mapping and exclusions, see Calendar's
[Google guide](https://github.com/nisfeb/calendar/blob/main/docs/google.md):
attendees, invitations, conference links and Google Tasks are not imported by
the current Google integration. Do not equate successful sync with a complete
Google Calendar replica.
