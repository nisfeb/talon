# App Review notes — paste into App Store Connect

The text below goes in App Store Connect → App Review Information →
Notes. The demo ship's URL and access code go in the Sign-In
Information fields beside it, not here.

---

Talon is a client for Urbit (urbit.org), a personal-server platform.
There is no Talon account system: users sign in to an Urbit server
("ship") that they themselves own and operate, the way an email app
signs in to a mail server. Talon's developer operates no backend for
the app and collects no data through it; the one exception is the
optional Armillary AI credit below (see our privacy policy).

DEMO ACCOUNT
A demo ship is provided for review. Its Ship URL and access code are
in the Sign-In Information fields; enter both on the sign-in screen.
The ship is pre-populated with direct messages and group channels
demonstrating chat, threads, reactions, and media.

ACCOUNT CREATION / DELETION (Guideline 5.1.1(v))
Talon does not create accounts. "Sign out" (Settings) removes all
locally stored credentials and cache. The Urbit ship belongs to the
user independently of Talon, like an IMAP mailbox belongs to the user
independently of a mail client. The one account involved is optional:
adding the Armillary AI provider opens a credit account for the
user's ship at a vendor ship. The user can ask the default vendor,
~nisfeb, to delete it by messaging ~nisfeb from inside Talon, as the
privacy policy describes.

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
Off by default and inert until the user adds a provider: their own
API key for a third-party AI provider (no key ships with the app), or
Armillary credit.

ARMILLARY AI CREDIT (Guidelines 3.1.1, 3.1.1(a))
Settings > AI > Providers. Credit is sold by the Urbit vendor ship the
user chooses (the default, ~nisfeb, is run by the developer) through
the vendor's web checkout (Stripe or BTCPay Server), not In-App
Purchase. The Top up and Subscribe buttons, and the card introducing
Armillary, appear only when the App Store storefront is the United
States (StoreKit storefront countryCode "USA"). On every other
storefront they are hidden and the app names no other place to pay.
