# Bridge between the Android app and gallery-dl.
# gallery-dl is only used to *find* links and metadata here; nothing is
# written to disk. Every function returns a JSON string for Kotlin.

import html
import itertools
import json
import re
import threading
import urllib.error
import urllib.parse
import urllib.request

from gallery_dl import config, extractor
from gallery_dl import version as _gdl_version
from gallery_dl.extractor.message import Message

ROOT = "https://www.pornpics.com"
UA = ("Mozilla/5.0 (Linux; Android 14; SM-A155F) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36")

config.set(("extractor",), "user-agent", UA)

_IMG_RE = re.compile(r"^(?:https?:)?//[^\s\"']+\.(?:jpe?g|png|webp)(?:\?.*)?$",
                     re.I)
_feeds = {}
_lock = threading.Lock()
_ids = itertools.count(1)


# ---------------------------------------------------------------- helpers

def _abs(url):
    if not isinstance(url, str) or not url:
        return None
    if url.startswith("//"):
        return "https:" + url
    if url.startswith("/"):
        return ROOT + url
    return url


def _find_image(d):
    for key in ("t_url", "thumb", "thumbnail", "img", "image", "preview",
                "cover"):
        v = d.get(key)
        if isinstance(v, str) and _IMG_RE.match(v):
            return _abs(v)
    for v in d.values():
        if isinstance(v, str) and _IMG_RE.match(v):
            return _abs(v)
    return None


def _title(d):
    for key in ("title", "desc", "description", "name"):
        v = d.get(key)
        if isinstance(v, str) and v.strip():
            return v.strip()
    return ""


def _tags(d):
    out = []
    for key in ("tags", "categories", "models", "channel"):
        v = d.get(key)
        if isinstance(v, str):
            v = [v]
        if isinstance(v, (list, tuple)):
            for t in v:
                if isinstance(t, str):
                    t = t.strip()
                    if t and t not in out:
                        out.append(t)
    return out


def _tag_groups(d):
    groups = {}
    for key in ("tags", "categories", "models", "channel"):
        v = d.get(key)
        if isinstance(v, str):
            v = [v]
        if isinstance(v, (list, tuple)):
            vals = [t.strip() for t in v if isinstance(t, str) and t.strip()]
            if vals:
                groups[key] = vals
    return groups


def _plain(d):
    r = {}
    for k, v in d.items():
        if k.startswith("_"):
            continue
        if isinstance(v, (str, int, float, bool)) or v is None:
            r[k] = v
        elif isinstance(v, (list, tuple)):
            r[k] = [x if isinstance(x, (str, int, float, bool)) else str(x)
                    for x in v][:60]
        else:
            r[k] = str(v)
    return r


def _payload(msg):
    for part in reversed(msg):
        if isinstance(part, dict):
            return part
    return {}


def _messages(url):
    ex = extractor.find(url)
    if ex is None:
        raise ValueError("gallery-dl has no extractor for: " + url)
    return iter(ex)


def _slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


# ---------------------------------------------------------------- urls

def search_url(query):
    # gallery-dl turns "-" back into spaces for this site's search
    words = re.split(r"\s+", query.strip())
    return ROOT + "/?q=" + "-".join(urllib.parse.quote(w, safe="")
                                    for w in words if w)


def tag_url(tag):
    return ROOT + "/tags/" + _slug(tag) + "/"


def category_url(name):
    return ROOT + "/" + _slug(name) + "/"


def gdl_version():
    return _gdl_version.__version__


# ---------------------------------------------------------------- feeds

def feed_start(url):
    gen = _messages(url)
    fid = next(_ids)
    with _lock:
        _feeds[fid] = {"gen": gen, "seen": set(), "lock": threading.Lock()}
    return fid


def feed_close(fid):
    with _lock:
        _feeds.pop(fid, None)


def feed_next(fid, count):
    with _lock:
        f = _feeds.get(fid)
    if f is None:
        return json.dumps({"items": [], "done": True})
    items = []
    done = False
    with f["lock"]:
        try:
            while len(items) < count:
                msg = next(f["gen"])
                kind = msg[0]
                if kind == Message.Queue:
                    url = _abs(msg[1])
                    if not url or url in f["seen"]:
                        continue
                    f["seen"].add(url)
                    d = _payload(msg)
                    items.append({"type": "gallery", "url": url,
                                  "thumb": _find_image(d),
                                  "title": _title(d), "tags": _tags(d)})
                elif kind == Message.Url:
                    url = msg[1]
                    if not isinstance(url, str) or not url.startswith("http"):
                        continue
                    d = _payload(msg)
                    items.append({"type": "image", "url": url, "thumb": url,
                                  "title": _title(d), "tags": _tags(d)})
        except StopIteration:
            done = True
    if done:
        feed_close(fid)
    return json.dumps({"items": items, "done": done})


# ---------------------------------------------------------------- galleries

def gallery(url):
    meta = {}
    images = []
    for msg in _messages(url):
        kind = msg[0]
        if kind == Message.Directory:
            meta = _payload(msg)
        elif kind == Message.Url:
            link = msg[1]
            if not isinstance(link, str) or not link.startswith("http"):
                continue
            d = _payload(msg)
            if not meta:
                meta = d
            images.append({"url": link, "num": d.get("num")})
    return json.dumps({
        "url": url,
        "title": _title(meta),
        "tags": _tags(meta),
        "groups": _tag_groups(meta),
        "images": images,
    })


# ---------------------------------------------------------------- debug

def debug(url, limit):
    lines = ["gallery-dl " + gdl_version(), "url: " + url]
    ex = extractor.find(url)
    if ex is None:
        lines.append("no extractor matched")
        return "\n".join(lines)
    lines.append("extractor: %s/%s" % (ex.category, ex.subcategory))
    for i, msg in enumerate(ex):
        if i >= limit:
            lines.append("... (stopped after %d messages)" % limit)
            break
        kind = {2: "DIR", 3: "URL", 6: "QUEUE"}.get(msg[0], str(msg[0]))
        link = msg[1] if len(msg) > 2 else ""
        lines.append("[%s] %s" % (kind, link))
        lines.append("    " + json.dumps(_plain(_payload(msg)),
                                         ensure_ascii=False)[:600])
    return "\n".join(lines)


# ---------------------------------------------------------------- site pages
# Categories, tags and gallery listings are read straight from the site:
# the HTML has a sample image per tile, which gallery-dl's listing
# extractors drop, and category/tag/search pages continue as JSON.

_LISTINGS = ("popular", "recent", "rating", "likes", "views", "comments")
_TILE_RE = re.compile(
    r"""<a\s+class=['"]rel-link['"]\s+href=['"]([^'"]+)['"][^>]*>(.*?)</a>""",
    re.S)
_DATA_SRC_RE = re.compile(r"""data-src=['"]([^'"]+)['"]""")
_SRC_RE = re.compile(r"""\ssrc=['"]([^'"]+)['"]""")
_ALT_RE = re.compile(r"""alt=['"]([^'"]*)['"]""")
_H2_RE = re.compile(r"""<span class=['"]h2['"]>([^<]+)</span>""")
_TAG_LINK_RE = re.compile(
    r"""<a href=['"]/tags/([^/'"]+)/['"][^>]*>([^<]+)</a>""")


def _get(url, params=None, referer=None, as_json=False):
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    headers = {"User-Agent": UA, "Referer": referer or ROOT + "/"}
    if as_json:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        raise ValueError("HTTP %d for %s" % (e.code, url)) from None
    return json.loads(body) if as_json else body


def _tiles(page):
    out = []
    for href, inner in _TILE_RE.findall(page):
        img = None
        m = _DATA_SRC_RE.search(inner) or _SRC_RE.search(inner)
        if m and "1px" not in m.group(1):
            img = _abs(m.group(1))
        name = _H2_RE.search(inner) or _ALT_RE.search(inner)
        out.append({
            "url": _abs(href),
            "thumb": img,
            "title": html.unescape(name.group(1).strip()) if name else "",
        })
    return out


def categories():
    """Category tiles from the home page: name, link and a sample image."""
    out = []
    seen = set()
    for t in _tiles(_get(ROOT + "/")):
        path = t["url"][len(ROOT):] if t["url"].startswith(ROOT) else ""
        if not re.fullmatch(r"/[a-z0-9-]+/", path) or path in seen:
            continue
        seen.add(path)
        out.append({"name": t["title"] or path.strip("/"),
                    "slug": path.strip("/"), "url": t["url"],
                    "thumb": t["thumb"]})
    return json.dumps(out)


def tags_index():
    """Every tag on the site's A-Z tag page (names only, no images)."""
    out = []
    seen = set()
    for slug, name in _TAG_LINK_RE.findall(_get(ROOT + "/tags/")):
        if slug not in seen:
            seen.add(slug)
            out.append({"name": html.unescape(name.strip()), "slug": slug})
    return json.dumps(out)


def _gallery_items(tiles):
    return [{"type": "gallery", "url": t["url"], "thumb": t["thumb"],
             "title": t["title"], "tags": []}
            for t in tiles if "/galleries/" in t["url"]]


def _json_items(rows):
    items = []
    for r in rows if isinstance(rows, list) else []:
        url = _abs(r.get("g_url"))
        if not url:
            continue
        items.append({"type": "gallery", "url": url,
                      "thumb": _abs(r.get("t_url_460") or r.get("t_url")),
                      "title": (r.get("desc") or r.get("title") or "").strip(),
                      "tags": []})
    return items


def listing_page(url, offset):
    """
    One page of galleries for a category, tag, search or listing URL.
    Returns {"items", "done", "next"}; pass "next" back as the offset.
    """
    offset = int(offset)
    path = url[len(ROOT):] if url.startswith(ROOT) else url
    query = re.match(r"/\?q=([^&#]+)", path)

    if query:
        q = urllib.parse.unquote(query.group(1)).replace("-", " ")
        rows = _get(ROOT + "/search/srch.php",
                    {"q": q, "lang": "en", "limit": 20, "offset": offset},
                    referer=url, as_json=True)
        items = _json_items(rows)
        raw = len(rows) if isinstance(rows, list) else 0
    elif offset == 0:
        tiles = _tiles(_get(url))
        items = _gallery_items(tiles)
        raw = len(items)
        if path.strip("/") in _LISTINGS:
            # These pages have a single page of results.
            return json.dumps({"items": items, "done": True, "next": raw})
    else:
        rows = _get(url, {"limit": 20, "offset": offset},
                    referer=url, as_json=True)
        items = _json_items(rows)
        raw = len(rows) if isinstance(rows, list) else 0

    return json.dumps({"items": items, "done": raw < 10,
                       "next": offset + raw})


def tag_sample(url):
    """First gallery thumbnail on a tag or category page, or ""."""
    for t in _tiles(_get(url)):
        if "/galleries/" in t["url"] and t["thumb"]:
            return t["thumb"]
    return ""
