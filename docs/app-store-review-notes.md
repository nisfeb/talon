# App Review notes — paste into App Store Connect

The text below goes in App Store Connect → App Review Information →
Notes, alongside the demo account fields. Placeholders in ALL CAPS need
the demo ship's real values.

---

Talon is a client for Urbit (urbit.org), a personal-server platform.
There is no Talon account system: users sign in to an Urbit server
("ship") that they themselves own and operate, the way an email app
signs in to a mail server. Talon's developer operates no backend and
collects no data (see our privacy policy).

DEMO ACCOUNT
A demo ship is provided for review:
  Ship URL: DEMO_SHIP_URL
  Access code: DEMO_SHIP_CODE
Enter both on the sign-in screen. The ship is pre-populated with
direct messages and group channels demonstrating chat, threads,
reactions, and media.

ACCOUNT CREATION / DELETION (Guideline 5.1.1(v))
Talon does not create accounts and holds no server-side user data.
"Sign out" (Settings) removes all locally stored credentials and
cache. The Urbit ship belongs to the user independently of Talon,
like an IMAP mailbox belongs to the user independently of a mail
client.

USER-GENERATED CONTENT (Guideline 1.2)
All content lives on users' own private servers; there is no public
feed and no content hosted by the developer. Users can report any
message in a group channel (long-press → Report, with confirmation);
the report goes to the group's admins for review and notifies them.
Group admins can delete reported or objectionable messages in-app,
and group hosts can remove (kick) and ban members; users can block
ships from contacting them. Conversations are private,
invitation-based groups — analogous to a self-hosted IRC or Matrix
server.

ENCRYPTION
Standard HTTPS/TLS only — ITSAppUsesNonExemptEncryption is false.

OPTIONAL AI FEATURES
Off by default and inert unless the user supplies their own API key
for a third-party AI provider; no key ships with the app.
