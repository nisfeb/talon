# App Review notes, pasted into App Store Connect

The text below goes in App Store Connect, App Review Information, Notes. nomac's set_metadata review_contact.notes replaces the whole field, so always send all of it, and it holds at most 4,000 characters. The demo ship's URL and access code go in the Sign-In Information fields beside it, not here.

---

Talon is a client for Urbit (urbit.org), a personal-server platform. Users sign in to an Urbit server ("ship") they own and operate, the way a mail app signs in to a mail server; there is no Talon account system. The developer runs no chat backend and collects no data through the app, except the push relay and the optional Armillary AI credit below (see the privacy policy).

DEMO ACCOUNT
A demo ship is provided for review. Its Ship URL and access code are in the Sign-In Information fields; enter both on the sign-in screen. It holds direct messages and group channels showing chat, threads, reactions, and media.

ACCOUNT CREATION / DELETION (Guideline 5.1.1(v))
Talon creates no accounts. Sign out (Settings) removes all local credentials and cache. The ship belongs to the user independently of Talon, as a mailbox does. The one account involved is optional: adding the Armillary AI provider opens a credit account for the user's ship at a vendor ship. The user deletes it in the app (Settings > AI > the Armillary card > More > Delete account); the vendor then deletes the account, its ledger, checkouts and keys, and cancels any subscription.

USER-GENERATED CONTENT (Guideline 1.2)
All content lives on users' own private servers; there is no public feed and nothing hosted by the developer. Users can report any message in a group channel (long-press, Report, confirm); the report goes to the group's admins. Admins can delete messages, group hosts can kick and ban members, and users can block ships. Groups are private and invitation-based, like a self-hosted IRC or Matrix server.

ENCRYPTION
Standard HTTPS/TLS only. ITSAppUsesNonExemptEncryption is false.

PUSH NOTIFICATIONS
iOS needs a server to deliver notifications, so the developer runs a push relay (a user may point Talon at their own). Where the user's ship supports it, the ship sends its own notifications through the relay's APNs gateway, which keeps only the push token and badge count. Otherwise, when the user turns notifications on, Talon sends the relay the ship's address and sign-in code once; the relay exchanges the code for a session cookie, stores only that cookie (encrypted), and watches the ship. Each notification (sender and a one-line preview) is passed to APNs, not stored. Unregister (Settings, Notification health) deletes the registration and the session.

OPTIONAL AI FEATURES
Inert until the user's ship has Armillary credit. Paying turns the features on, and the top-up sheet says what they send. Each has its own switch to turn it off. Every model they call keeps nothing (zero data retention).

ARMILLARY AI CREDIT (Guidelines 3.1.1, 3.1.1(a), 3.1.3(b))
Settings > AI. Credit is sold by the Urbit vendor ship the user chooses (the default, ~nisfeb, is run by the developer) through the vendor's web checkout (Stripe or BTCPay Server), not In-App Purchase. The Top up and Subscribe buttons, the card introducing Armillary, and every line that suggests buying appear only when the App Store storefront is the United States (StoreKit storefront countryCode "USA"). Elsewhere they are hidden and the app names no other place to pay.

Outside the US the app neither sells credit nor points anywhere to buy it, but a user can still use AI access their own ship already holds: an Armillary account with any vendor ship they choose, including Armillary on their own ship running on their own provider key. None of these need involve the developer. Talon uses what the ship holds the way a mail client uses a mailbox; it unlocks no content or feature of its own.

VENDOR-CHOSEN MODELS AND WEB SEARCH
With an Armillary account, the vendor picks which model each optional feature uses; a user who wants other models picks them in Armillary on their own ship. The assistant's optional web search can go through the vendor's Brave Search key; the vendor keeps only a monthly count of searches per account, never the queries.
