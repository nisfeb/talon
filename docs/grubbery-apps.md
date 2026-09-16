# Mail, calendar and Lattice on your ship

Talon's chat runs on `%groups`, the same agents Tlon uses. Mail, the calendar and Lattice are different: they are Grubbery apps. Grubbery is a framework on the ship, published by `~ricsul-bilwyt` as one desk that carries Auspex (mail), the calendar and Lattice as stock apps. Talon talks to them as plain JSON over HTTP, under the same session cookie it already holds. Nothing leaves the ship except to the peers you mail or share with.

## The Apps page

Settings has an Apps page that shows, for each thing Talon needs, whether the ship has it and whether it is answering: Groups for chat, and Grubbery for mail, the calendar and Lattice. A ship without one shows an install button. The install is a normal `|install` from the publisher, sent as a poke to the ship's own `%hood`, so it is the same as typing it in the dojo. A Grubbery that predates mail or the calendar says so; it updates itself from its publisher.

After Grubbery installs, its apps still have to be permitted. Grubbery apps ask for what they may do, and the ship's owner approves it on the permits page at `<ship>/apps/grubbery/permits`. The Apps page links there. Until an app is permitted it answers, but it cannot read or write.

## Mail

Mail is Auspex, reached at `/apps/auspex` on the ship. Talon lays it out as a mail client: an inbox and the other mailboxes, labels, lists, filters, drafts and rules. A thread is drawn as a tree, and a forged copy of a message cannot decide what a node says: the verified message wins. Attachments arrive inline, links and pictures render in the body, and tags are offered as you type them.

Every row names everyone on the thread and the subject, not only the last person to write. Rows are kept in the device's database and thread bodies in its cache, so a large inbox opens from what it had and only fetches what changed. New mail arrives with a notification like a message does.

From a thread you can file it to Lattice, send an event it carries to your calendar, or reply. From anyone's profile you can write them mail. The assistant can read, search and send mail as well, when you give it a key.

Availability is probed, not assumed. No Grubbery, a Grubbery that predates mail, and mail that is present are three different screens, and none of them is an error.

## Calendar

The calendar is the Grubbery calendar app, and Talon keeps up with its versions (8 and 11 at the time of writing). It shows a month, a week by the hour, and a day. In the month view the selected day's events are listed beside the grid. The view you leave it in is the one it opens to. On a phone the header stays one line.

You can keep several calendars, and each has a colour and a name. Events have places, people, links and numbers that act when you tap them, and edits show in place. Tasks with due dates live on the calendar too, and the Today widget on the home screen lists both events and tasks inside its window. A calendar can be shared with another ship from the calendars dialog.

Synced calendars pull from ICS, CalDAV or a Google calendar every few minutes. Each shows when it last pulled and any error, with a Sync now button. A change made elsewhere shows after the next pull, and one shared onward from a Google or followed calendar takes two. A refusal from the ship is shown rather than swallowed, and you choose which calendar new events go to.

The last answer from the ship is kept on the device, so a cold start paints the month it had while the fresh one loads. An event someone posted in chat, or mailed you, can be added to your own calendar in one tap, and the assistant can post events into chats as cards.

## Signatures with your ship's key

Your profile shows your ship's public keys and lets you copy them. From the same place you can sign a message or a file, and check a signature someone sent you. The signing is done by the ship through Lattice, which needs Lattice 31 or later. Talon never holds a key.

What gets signed is a salted hash of the content, so signing a sentence and signing a file are the same operation, and a Lattice signature can never be passed off as an Ames packet or a mail. The result is a text block:

```
-----BEGIN LATTICE SIGNATURE-----
ship: ~sampel-palnet
life: 3
alg: ed25519
salt: lattice
digest: ...
sig: ...
-----END LATTICE SIGNATURE-----
```

Paste the block wherever the thing itself went. To check one, paste it into the dialog and give it the same text or file. The ship recomputes the digest and refuses a record whose digest does not match. A block on its own, with nothing to compare, checks only that the signature covers the digest as given, and the dialog says so.

The key is whatever the ship's own is. For a planet, star or galaxy that is its Azimuth key. For a Groundwire comet it is the key attested on Bitcoin, since such a comet has no Azimuth point.
