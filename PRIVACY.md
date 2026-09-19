# Talon Privacy Policy

_Last updated: September 19, 2026_

Talon is a chat client for [Urbit](https://urbit.org). It connects your
device directly to an Urbit ship that you control. There are no Talon
servers.

## What we collect

Nothing. Talon has no analytics, no telemetry, no crash reporting, no
advertising, and no developer-operated backend. We never see your
messages, your credentials, or your usage.

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
- **The optional Armillary provider (off until you add it)**: instead of your own key, you can buy model inference through your own Urbit ship, from a vendor ship you choose. Your ship holds the account and the balance. You pay that vendor by card through Stripe or by bitcoin through BTCPay Server, both operated by the vendor and neither by us. In lease mode the vendor mints a capped key at the model provider and Talon calls that provider directly. In proxy mode your requests pass through the vendor's ship on the way to the model provider. The vendor sees your ship's name, your balance and your ledger, and in proxy mode the requests as well. Nothing else about you leaves the device.

## Third parties

We share nothing with third parties, because we hold nothing to share. Talon has no backend of its own and never has.

The servers Talon talks to are all ones you picked: your ship, the hosts of links and images in your messages, an AI provider whose key you entered, and, if you added the Armillary provider, the vendor ship you named and the payment page it sends you to. Remove the provider and that last one stops.

## Children

Talon does not target children and collects no data from anyone.

## Changes

Changes to this policy will be published at this URL with an updated
date.

## Contact

Questions: open an issue at
[github.com/nisfeb/talon/issues](https://github.com/nisfeb/talon/issues).
