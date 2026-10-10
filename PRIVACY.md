# Talon Privacy Policy

_Last updated: October 10, 2026_

Talon is a chat client for [Urbit](https://urbit.org). It connects your device directly to an Urbit ship that you control. We run only two services for it, and both are optional: a relay for push notifications, and ~nisfeb, an Armillary vendor that sells AI credit. Both are described below.

## What we collect

Talon itself collects nothing. It has no analytics, no telemetry, no crash reporting and no advertising. Your messages travel between your device and your ship.

There are two exceptions, both optional:

- If you turn on push notifications, the relay keeps what it needs to reach your phone. See "If your notifications go through our relay" below.
- If you buy AI credit from ~nisfeb, that vendor keeps the account it sells to. See "If you buy AI credit from ~nisfeb" below.

## Where your data lives

- **Your ship.** Messages, channels, and profile data live on the Urbit ship you sign in to. That ship is operated by you (or whoever hosts it for you), not by us.
- **Your device.** Talon caches messages, media, and settings locally so the app works offline and starts fast. Your ship URL and session credentials are stored in the operating system's secure storage (Keychain on iOS). Deleting the app deletes this cache.

## Network connections Talon makes

- **Your Urbit ship**: all chat traffic goes directly between your device and your ship.
- **Link previews and remote media**: when a message contains a URL or an image, Talon fetches it from that host to display it, like a web browser would.
- **Push notifications (optional)**: for notifications that arrive while Talon is closed, your phone is registered with a push relay. See "If your notifications go through our relay" below.
- **Optional AI features (off by default)**: catch-up summaries and similar features need a model, and there are three ways to give them one. The first is your own API key for an AI provider. When you use a feature, the relevant chat content is sent to the provider you configured, under that provider's privacy policy. No key, no traffic. The second is a model server of your own, which gets the same content and nothing goes anywhere else. The third is the Armillary provider below.
- **The optional Armillary provider (off until you add it)**: instead of your own key, you can buy model inference through your own Urbit ship, from a vendor ship you choose. Your ship holds the account and the balance. You pay that vendor by card through Stripe or by bitcoin through BTCPay Server, both set up by the vendor. The default vendor is ~nisfeb, which we run; any other vendor is run by whoever owns that ship. In lease mode the vendor mints a capped key at the model provider and Talon calls that provider directly. In proxy mode your requests pass through the vendor's ship on the way to the model provider. The vendor sees your ship's name, your balance and your ledger, and in proxy mode it passes your requests on without storing them. If the assistant searches the web or looks up a place and you have no Brave Search key of your own, the search goes through the vendor's ship to Brave Search under the vendor's key; the vendor passes the query on without storing it and counts the search. Orrery on your ship does the same for its own searches when it follows your Armillary account. Nothing else about you leaves the device.

## If your notifications go through our relay

A phone can only be woken for a notification by a server, so push notifications that arrive while Talon is closed go through a relay. On iPhone this is how every notification arrives; on Android, Talon can also keep its own connection instead. Talon uses the relay we run unless you point it at one you run yourself, under Settings, Notification health, Endpoint.

How it works depends on your ship:

- **Your ship sends the notifications** (when it runs a version of %trunk with push support). Your ship keeps your phone's registration and decides what notifies you. On iPhone, each notification passes through the relay's gateway to Apple's push service. The relay keeps your phone's push token, under a random handle your ship knows, and the unread count for the app icon badge. No sign-in code or session leaves your ship.
- **The relay watches your ship** (for ships without that support). When you turn notifications on, Talon sends the relay your ship's address and your sign-in code (+code), once, over TLS. The relay uses the code to sign in to your ship and keeps only the resulting session cookie, encrypted at rest; it never stores the code. It stays connected to your ship, applies the notification levels you set in Talon, and sends a notification when something should reach you. It keeps your ship's name and address, your phone's push token, and its place in your ship's activity.

Either way, a notification carries the sender and a one-line preview of the message, so your phone can show it, and it is handed on to Apple's push service (iPhone) or your UnifiedPush distributor (Android) without being stored. When the relay watches your ship, Unregister under Settings, Notification health deletes your phone's registration and the stored session.

## If you buy AI credit from ~nisfeb

For your ship, ~nisfeb keeps:

- your ship's name, which is the account;
- your balance, plan and subscription, and when your ship last spoke to it;
- a ledger of every credit, refund and charge, with the model, token counts and time of each request. After 90 days a month's rows are folded into totals, and the model and token detail is dropped;
- every checkout you open: amount, payment method, status, and the Stripe or BTCPay Server reference;
- the keys your devices use, by name (such as "Talon on iOS 26.0") and by hash, never the key itself once your ship has fetched it;
- how many web and place searches your ship made through ~nisfeb's Brave Search key each month, never the queries.

It does not keep your prompts, the model's answers or your searches: in proxy mode it passes them on without writing them down. It never sees your card number, email or billing address; Stripe or BTCPay Server collect those on their own pages, under their own privacy policies. It shares your ship's name with Stripe and BTCPay Server, to tie a payment to your account, and with OpenRouter, where it names the capped key it makes for you in lease mode. None of it is used for advertising or sold.

The account is kept until it is deleted. To delete it, with its ledger, checkouts and keys, open More on the Armillary card in Talon's AI settings and tap Delete account: your ship asks ~nisfeb, which deletes all of it and cancels any subscription. You can also message ~nisfeb from your ship and ask.

## Third parties

Apart from the relay and ~nisfeb, both described above, Talon has no backend of its own, and none of it shares data with third parties beyond what those sections list.

The servers Talon talks to are all ones you picked: your ship, the hosts of links and images in your messages, an AI provider whose key you entered or a model server of your own, the notification relay if you turned notifications on, and, if you added the Armillary provider, the vendor ship you named and the payment page it sends you to. Remove the provider, or turn notifications off, and those stop.

## Children

Talon does not target children. Talon itself collects no data; the relay and ~nisfeb hold only what is described above.

## Changes

Changes to this policy will be published at this URL with an updated date.

## Contact

Questions: open an issue at [github.com/nisfeb/talon/issues](https://github.com/nisfeb/talon/issues).
