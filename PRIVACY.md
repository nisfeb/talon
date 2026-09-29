# Talon Privacy Policy

_Last updated: September 29, 2026_

Talon is a chat client for [Urbit](https://urbit.org). It connects your
device directly to an Urbit ship that you control. There are no Talon
servers.

## What we collect

Talon itself collects nothing. It has no analytics, no telemetry, no
crash reporting, no advertising, and no backend. We never see your
messages, your credentials, or your usage.

The one exception is optional: if you buy AI credit from ~nisfeb, the
Armillary vendor we run, that vendor keeps the account it sells to. See
"If you buy AI credit from ~nisfeb" below.

## Where your data lives

- **Your ship.** Messages, channels, and profile data live on the Urbit
  ship you sign in to. That ship is operated by you (or whoever hosts it
  for you), not by us.
- **Your device.** Talon caches messages, media, and settings locally so
  the app works offline and starts fast. Your ship URL and session
  credentials are stored in the operating system's secure storage
  (Keychain on iOS). Deleting the app deletes this cache.

## Network connections Talon makes

- **Your Urbit ship** — all chat traffic goes directly between your
  device and your ship.
- **Link previews and remote media** — when a message contains a URL or
  an image, Talon fetches it from that host to display it, like a web
  browser would.
- **Optional AI features (off by default)**: catch-up summaries and similar features need a model, and there are two ways to give them one. The first is your own API key for an AI provider. When you use a feature, the relevant chat content is sent to the provider you configured, under that provider's privacy policy. No key, no traffic.
- **The optional Armillary provider (off until you add it)**: instead of your own key, you can buy model inference through your own Urbit ship, from a vendor ship you choose. Your ship holds the account and the balance. You pay that vendor by card through Stripe or by bitcoin through BTCPay Server, both set up by the vendor. The default vendor is ~nisfeb, which we run; any other vendor is run by whoever owns that ship. In lease mode the vendor mints a capped key at the model provider and Talon calls that provider directly. In proxy mode your requests pass through the vendor's ship on the way to the model provider. The vendor sees your ship's name, your balance and your ledger, and in proxy mode it passes your requests on without storing them. Nothing else about you leaves the device.

## If you buy AI credit from ~nisfeb

For your ship, ~nisfeb keeps:

- your ship's name, which is the account;
- your balance, plan and subscription, and when your ship last spoke to it;
- a ledger of every credit, refund and charge, with the model, token
  counts and time of each request. After 90 days a month's rows are
  folded into totals, and the model and token detail is dropped;
- every checkout you open: amount, payment method, status, and the
  Stripe or BTCPay Server reference;
- the keys your devices use, by name (such as "Talon on iOS 26.0") and
  by hash, never the key itself once your ship has fetched it.

It does not keep your prompts or the model's answers: in proxy mode it
passes them on without writing them down. It never sees your card
number, email or billing address; Stripe or BTCPay Server collect those
on their own pages, under their own privacy policies. It shares your
ship's name with Stripe and BTCPay Server, to tie a payment to your
account, and with OpenRouter, where it names the capped key it makes
for you in lease mode. None of it is used for advertising or sold.

The account is kept until it is deleted. To have it deleted, with its
ledger, checkouts and keys, message ~nisfeb from your ship and ask: a
request from your ship is how we know the account is yours.

## Third parties

Talon has no backend of its own and shares nothing with third parties. The only data we hold is an Armillary account at ~nisfeb, if you open one, and what that shares is listed above.

The servers Talon talks to are all ones you picked: your ship, the hosts of links and images in your messages, an AI provider whose key you entered, and, if you added the Armillary provider, the vendor ship you named and the payment page it sends you to. Remove the provider and that last one stops.

## Children

Talon does not target children. It collects no data; ~nisfeb holds only the account described above, for those who buy from it.

## Changes

Changes to this policy will be published at this URL with an updated
date.

## Contact

Questions: open an issue at
[github.com/nisfeb/talon/issues](https://github.com/nisfeb/talon/issues).
