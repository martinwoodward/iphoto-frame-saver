# Privacy and album protocol

## Data flow

The device sends its configured public album capability to Apple's HTTPS public
album API, obtains photo metadata, and downloads image bytes from Apple's content
hosts. There is no intermediary server. The native Java implementation does not
embed or redistribute Apple's web client.

The network layer allows HTTPS only, validates the platform trust chain and
hostname, and restricts API/download/redirect destinations to Apple/iCloud domain
families. Android 6 supports TLS 1.2 through the system HTTPS implementation.
There is no trust-all certificate manager, cleartext fallback, custom CA bundle,
third-party proxy, or credential/cookie store. Old devices with obsolete trust
stores may need a vendor/system update; the app will not bypass TLS validation.

A separate read-only, on-device content provider exposes one boolean indicating
whether this app's DreamService is running. A cooperating Kiosk app can use it to
hide and restore its own navigation overlay. It exposes no album links, photo
data, clock values, or settings, and other apps cannot write its state. Nothing
from this integration is transmitted over the network.

The only persisted album metadata consists of opaque photo IDs, image URLs,
dimensions, orientation, and last successful refresh time. Public-access
authorization tokens live only during metadata requests. Owner identities and
album titles returned by the service are discarded. Exceptions shown in the UI
are fixed, generic strings rather than exception messages or server bodies.

## Modern shared albums

`photos.icloud.com/shared/album/<capability>/` is **not** assumed to be a legacy
shared-stream token.

1. Resolve the capability with the public CloudKit `records/resolve` API for
   `com.apple.photos.cloud`.
2. Read the returned zone, partition, and anonymous public-access token. An album
   requiring Apple login is not accessible to this app.
3. Query `CPLAssetAndMasterByAddedDate` in that shared zone.
4. Follow every `continuationMarker` until exhausted. Deduplicate records, join
   all `CPLAsset` records to their `CPLMaster` records across pages, and preserve
   distinct assets that reference the same master.
5. Exclude video/hidden/trashed assets. Prefer edited full JPEG renders where
   available; otherwise use the largest/full resource and retain a compatible JPEG
   fallback. Declared media types are not assumed to equal the downloaded byte
   encoding (migrated albums can label a JPEG-compatible resource as HEIC).

An incomplete or malformed page fails the refresh instead of replacing a working
album with a silently truncated subset. Requests and pagination have bounded
timeouts, response sizes, cursor checks, and a generous safety limit.

## Legacy shared albums

`www.icloud.com/sharedalbum/#<capability>` uses the public shared-stream
`webstream` API, honors Apple's shared-stream partition-routing response, and
resolves `webasseturls` in batches for **all** photo GUIDs. The largest available
image derivative is selected. This path is independent of modern CloudKit album
resolution.

## Persistence, caching, and lifecycle

Metadata is written atomically and replaced only after a complete successful
refresh. A failed refresh leaves the previous album intact. Album keys and media
filenames are SHA-256-derived, not titles or capability strings. Configuring a
different album selects its own metadata/cache namespace. Old album data remains
private until app data is cleared or the app is uninstalled.

The streaming window is the current image plus five look-ahead slots; a small
album naturally needs fewer distinct files. Failed prefetch keeps recently working
files in the remaining slots, rather than erasing the working cache or allowing
unbounded growth. In-use files are leased until decode/copy finishes, so concurrent
pruning cannot remove them. A cancelled in-flight fetch or a lease may temporarily
add a file; the next cleanup returns to the six-file window. Offline downloads use
separate staging files, not the streaming window. Partial downloads never become
playable files. No automatic full-album download occurs in streaming mode.

Signed image URLs can expire, including with manual metadata scheduling. A failed
on-demand fetch can renew metadata, throttled to once per minute, without erasing
saved data. This technical renewal is independent of automatic discovery timing.

Foreground slideshow and DreamService share one controller. Network/disk/decode
work runs off the UI thread; a generation guard discards results from stopped
surfaces. Only two decoded display bitmaps are retained during a cross-fade and
they are cleared/recycled on stop. Decoding uses a power-of-two sample bounded by
screen pixel budget before allocating, then applies all eight EXIF orientations.
Offline downloads run in a separate foreground service and write completed files
to the selected app-local directory or SAF tree. SAF writes are closed under a
partial name and renamed only after success; damaged copies are rejected on read.
The selected document provider must support file creation, deletion, and rename.

## Reporting problems safely

Report Android version, app version, generic error text, and whether the issue is
with streaming, offline storage, or the screensaver. Use a newly created
non-personal test album if a reproduction requires an album. Never post an
existing private capability link, URL token, CDN URL, title, photo, full network
capture, or preferences file in a public issue, PR, log, or screenshot.
