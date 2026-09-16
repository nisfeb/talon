# Invites, QR codes and names

## Invites by QR

A public group shows a QR code in its information pane. Anyone who scans it joins. Your own profile carries an invite-me code. Someone who scans it can open a DM with you, or invite you to one of their groups, and they choose which when the code is read.

Scanning needs a camera, so it is on Android and iOS. The plus button on the chat list has a camera icon that reads either kind of code. On desktop the codes are shown, and a `talon://` link does the same job over any channel: share it and the other side's Talon opens it.

The links are `talon://group/<host>/<name>` for a group and `talon://invite/<ship>` for a ship asking to be invited. A comet in a link goes by its `--` form, not its @p.

## /invite from the composer

Type `/invite` in any composer to get a group picker, with fuzzy matching, the same way `@` completes a person and `:` completes an emoji. In a DM it invites the person you are talking to. Anywhere else it also asks for the ship to invite.

## Names for comets

A comet is never shown by its @p. It goes by a two-word name everywhere, by its twelve-word name on its profile, and by a longer one only where two comets would otherwise collide. The names are mnemonyms on the current upstream word list, pinned to the published vectors, so every Talon decodes the same comet to the same name. You can invite a comet by any of the names it goes by. A bare word never decodes to a stranger.

Planets and moons can be named too, from their Azimuth keys through the ship's own `azimuth-rpc`. That is a setting, Word names for planets and moons, and it is off by default. Comets always show word names.

There is no copyable @p for a peer comet anywhere in the app. That follows from the rule above and is a product decision, not an omission.
