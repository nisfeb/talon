#!/usr/bin/env python3
"""Run the disappearing-messages hook on a fake ship, over eyre, as Talon does.

    TALON_SHIP=~bel TALON_SHIP_URL=http://localhost:8144 \
    TALON_SHIP_CODE=/path/to/file-holding-+code python3 -I scripts/disappearing-hook-check.py

Makes a group with a chat, posts 120 messages, lets them age, then turns
the hook on at 30 seconds: the history goes a hundred at a time, a new
post goes on its own timer, and once off nothing more is deleted. About
three minutes. Fake ships only: it refuses any URL that is not localhost.
After a change to the hook, run this, then update the hash in
DisappearingMessagesTest.
"""
import http.cookiejar, json, os, random, sys, time, urllib.request

BASE = os.environ["TALON_SHIP_URL"].rstrip("/")
SHIP = os.environ["TALON_SHIP"]
if not BASE.startswith(("http://localhost:", "http://127.0.0.1:")):
    sys.exit("fake ships only: TALON_SHIP_URL must be localhost")
CODE = open(os.environ["TALON_SHIP_CODE"]).read().strip()
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = open(os.path.join(HERE, "../composeApp/src/commonMain/composeResources/files/disappearing.hoon")).read()
NAME = "Talon disappearing messages"
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
CHAN = f"{BASE}/~/channel/hook-check-{int(time.time())}-{random.randrange(1 << 30)}"
ids = iter(range(1, 1 << 30))


def req(method, url, body=None, ctype="application/json"):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
    r = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", ctype)
    with op.open(r, timeout=60) as resp:
        return resp.read().decode()


def poke(app, mark, payload):
    req("PUT", CHAN, [{"id": next(ids), "action": "poke", "ship": SHIP.lstrip("~"), "app": app, "mark": mark, "json": payload}])


def scry(app, path):
    return json.loads(req("GET", f"{BASE}/~/scry/{app}{path}.json"))


def hook(payload):
    poke("channels-server", "hook-action-0", payload)


UW = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-~"


def delay(seconds):
    """(scot %uw (jam (mul seconds ~s1))), as Disappearing.delayNoun."""
    a = seconds << 64
    b = a.bit_length(); c = b.bit_length()
    n = ((1 << c) | (((b & ((1 << (c - 1)) - 1)) | (a << (c - 1))) << (c + 1))) << 1
    ds = []
    while n:
        ds.append(UW[n & 63]); n >>= 6
    return "0w" + ".".join(reversed(["".join(reversed(ds[i:i + 5])) for i in range(0, len(ds), 5)]))


def post(nest, n):
    for i in range(n):
        essay = {"content": [{"inline": [f"message {i}"]}], "author": SHIP, "sent": int(time.time() * 1000),
                 "kind": "/chat", "meta": None, "blob": None}
        poke("channels", "channel-action-2", {"channel": {"nest": nest, "action": {"post": {"add": essay}}}})


def live(nest):
    p = scry("channels", f"/v4/{nest}/posts/newest/300/outline")
    p = p.get("posts", p)
    return sum(1 for v in p.values() if v is not None and v.get("type") != "tombstone")


def wait(nest, want, within):
    end = time.time() + within
    while time.time() < end:
        if live(nest) == want:
            return
        time.sleep(1)
    sys.exit(f"FAIL: {live(nest)} live in {nest}, wanted {want} within {within}s")


def main():
    assert delay(30) == "0wY000.00000.000I0" and delay(7 * 86400) == "0w4D.k0000.00000.002A0"
    req("POST", f"{BASE}/~/login", f"password={CODE}".encode(), "application/x-www-form-urlencoded")
    slug = f"hc{random.randrange(1 << 20):x}"
    nest = f"chat/{SHIP}/{slug}-chat"
    req("PUT", f"{BASE}/spider/groups/group-create-thread/group-create-1/group-ui-2.json", {
        "groupId": f"{SHIP}/{slug}", "meta": {"title": slug, "description": "", "image": "", "cover": ""}, "guestList": [],
        "channels": [{"channelId": nest, "meta": {"title": "General", "description": "", "image": "", "cover": ""}}]})
    time.sleep(3)
    post(nest, 120)
    wait(nest, 120, 20)
    print("120 posted; letting them age past 30 s")
    time.sleep(32)

    def ours():
        return next((h for h in scry("channels-server", "/v0/hooks")["hooks"].values() if h["name"] == NAME), None)
    h = ours()
    if h is None:
        hook({"add": {"name": NAME, "src": SRC}})
    elif h["src"] != SRC:
        hook({"edit": {"id": h["id"], "name": None, "src": SRC, "meta": None}})
    time.sleep(2)
    h = ours()
    assert h and h["src"] == SRC and h["compiled"], "the ship did not build the hook"
    hid = h["id"]
    cfg = {"delay": delay(30)}
    order = [i for i in scry("channels-server", "/v0/hooks")["order"].get(nest, []) if i != hid] + [hid]
    hook({"config": {"id": hid, "nest": nest, "config": cfg}})
    hook({"order": {"nest": nest, "seq": order}})
    hook({"cron": {"id": hid, "origin": nest, "schedule": "~d1", "config": cfg}})
    wait(nest, 20, 10)
    print("on: 100 of the history gone at once")
    wait(nest, 0, 30)
    print("the other 20 on the next wake")

    post(nest, 3)
    time.sleep(20)
    assert live(nest) == 3, "a new post went before its time"
    wait(nest, 0, 25)
    print("new posts gone on their own timers")

    post(nest, 2)
    hooks = scry("channels-server", "/v0/hooks")
    # As Talon does it: what deletes first, then out of the order.
    hook({"config": {"id": hid, "nest": nest, "config": {}}})
    hook({"rest": {"id": hid, "origin": nest}})
    hook({"order": {"nest": nest, "seq": [i for i in hooks["order"].get(nest, []) if i != hid]}})
    time.sleep(40)
    assert live(nest) == 2, "a post went after the setting was off"
    print("off: posts stay\nPASS")


main()
