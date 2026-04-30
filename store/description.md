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
  sheet.
- Reply in threads.
- Quote a message inline.
- Pin posts (admin only) — a pinned banner lives at the top of every
  chat channel that has one.
- Bookmark messages for later.
- Drag-reorder groups; build folders for the conversations you check
  most.
- Optional AI features — "catch me up on N unread" summaries and
  emoji-react suggestions — using your own OpenAI / Anthropic /
  compatible API key. Off by default; key is encrypted on-device and
  only ever sent to the provider you select.

**What it isn't**
- Not a host. Bring your own ship (or get one from a friend, or one of
  the hosting services).
- Not Tlon. Talon talks to the same `tlon-apps` agents but is an
  independent client; if you're already running a Tlon-hosted ship,
  Talon will see your full chat history immediately.
- No notebooks or galleries (yet). The scope is chat — DMs and group
  channels — done well.

**Privacy**
- No analytics. No third-party SDKs. The only servers Talon talks to
  are the Urbit ship you point it at, and (if you turn on AI features)
  the AI provider whose key you've entered. Nothing gets shipped to
  the Talon developer.

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
