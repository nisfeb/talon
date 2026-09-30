# Talon — Play Store listing copy

## App name

Talon — chat for Urbit

## Short description (≤80 chars)

Native Android chat client for Urbit. Direct messages, group channels, reactions.

(80 chars exact — trim if Play complains.)

## Full description (≤4000 chars)

Talon is a fast, native Android client for the Urbit network. It
connects to a ship you already run (yours, or one a friend hosts for
you) and gives you a clean, mobile-first interface for the parts of
Urbit chat that you actually use day-to-day.

**What you can do**
- Read and send messages in 1:1 DMs, group DMs (clubs), and group
  channels.
- React with the full emoji set; long-press a message for the action
  sheet. Reactions also work inside threads.
- Reply in threads.
- Quote a message inline.
- Tap an image to open the fullscreen viewer with pinch-to-zoom; save
  posted images to your Photos / Pictures folder with one tap.
- Pin posts (admin only) — a pinned banner lives at the top of every
  chat channel that has one.
- Bookmark messages for later.
- Drag-reorder groups; build folders for the conversations you check
  most.
- Optional AI features, such as "catch me up on N unread" summaries and emoji-react suggestions. Off by default. Bring your own OpenAI, Anthropic or compatible API key: it is encrypted on-device and only ever sent to the provider you select. Or add the optional Armillary provider and buy model inference through your own Urbit ship, from a vendor ship you choose, paying by card or by bitcoin. Either way there is no Talon account and no Talon bill.

**What it isn't**
- Not a host. Bring your own ship (or get one from a friend, or one of
  the hosting services).
- Not Tlon. Talon talks to the same `tlon-apps` agents but is an
  independent client; if you're already running a Tlon-hosted ship,
  Talon will see your full chat history immediately.
- Notebooks (%diary) and galleries (%heap) are read-and-write supported
  alongside chat — you can read, comment, post, and delete posts in
  any of the three channel kinds.

**Privacy**
- No analytics. No third-party SDKs. Talon has no backend of its own. The servers it talks to are the Urbit ship you point it at, the AI provider whose key you entered if you turned AI features on, and, if you added the Armillary provider, the vendor ship you chose and the payment page it sends you to. Nothing gets shipped to the Talon developer.

**Open source**
<https://github.com/nisfeb/talon>. Mutation-tested at ~97% on the
parsers / wire helpers; ~558 unit tests across the JVM-only test suite.

---

## What's new

Release notes are kept per-tag in the GitHub Releases page
(<https://github.com/nisfeb/talon/releases>) so they stay in lockstep
with what was actually shipped. When promoting a build to Play,
copy the bullet points from that release into the Play Console's
"What's new" field (≤500 chars).

---

## Categorization

- **Category:** Communication.
- **Tags / target audience:** "Urbit", "decentralized social",
  "self-hosted chat".
- **Content rating** — mature (user-generated content). On the IARC
  questionnaire: answer YES to "users can interact / chat" and "share
  user-provided content". Everything else: no.
