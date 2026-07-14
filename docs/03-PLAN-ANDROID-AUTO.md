# Implementation Plan — Embed an Android Auto Receiver (Route A-full)

Goal: run **Google Maps / Waze (Android Auto)** on the bike dash, root-free, phone usable/lockable.
Approach: embed a full **Android Auto Projection (AAP) head-unit receiver** in OpenCfMoto, have Google's
Android Auto project to it via **non-VPN loopback self-mode**, and feed the decoded AA video into the
**existing PXC pipeline** (`VideoPipeline` → `EasyConnProber` → bike). Reference implementation to port
from: **headunit-revived (HUR)**.

## Reference project: headunit-revived (HUR)

- GitHub: `https://github.com/andreknieriem/headunit-revived` — **AGPLv3** (accepted; we ship AGPLv3).
- ~135 Kotlin files; native only for USB (`libusb`) and the AAP SSL handshake. Package
  `com.andrerinas.headunitrevived`.
- **Already cloned at `../headunit-revived/`** (a sibling of `docs/`, i.e. right next to `app/` under the
  project root), pinned to **v3.1.1** — this is the exact version everything here was tested against.
  Preserve its copyright headers in anything you port.

### HUR files that matter (studied during RE)

| Path (under `app/src/main/java/com/andrerinas/headunitrevived/`) | Role |
|---|---|
| `aap/AapVideo.kt` | Reassembles AA video messages; calls `videoDecoder.decode(buf, off, len, sw, codec)`. **This is the raw-H.264 tap point.** |
| `decoder/VideoDecoder.kt` | `MediaCodec` decoder rendering to a **settable `Surface`** (`setSurface`, `mSurface`); parses SPS for dimensions. |
| `decoder/AudioDecoder.kt` | AA audio (TTS / media) decode. |
| `aap/protocol/messages/ServiceDiscoveryResponse.kt` | Declares the head unit's capabilities to AA: video **resolution enum** (`VideoCodecResolutionType`: 800×480, 1280×720, 1080p, 1440p, 4K), FPS (30/60), **margins** (used to fit non-standard panels), codec (H264/H265). |
| `aap/AapProjectionActivity.kt` | The projection screen: sets up decoder + surface + touch input. |
| `view/*ProjectionView*.kt` (`ProjectionView`, `TextureProjectionView`, `GlProjectionView`, `ProjectionViewScaler`) | Rendering the decoded video to screen (GL/TextureView). Useful for the local control view + GL tee. |
| `connection/` (`UsbNative`, `NativeAaHandshakeManager`) + `aap/AapSslNative.kt` | Transport (USB and Wi-Fi) + AAP SSL handshake. **We only need the Wi-Fi/loopback transport; drop USB.** |
| `aap/protocol/proto/Media.java`, `Control.java` (+ `proto/*.proto`) | AAP protobuf messages. |
| Self-mode (search `selfmode`, intents to `com.google.android.projection.gearhead` / `com.google.android.gms`) | Triggers Google AA to project. **Two variants — see below.** |

### CRITICAL: self-mode variant

HUR has two self-modes:
- **"Fake VPN offline" self-mode** — uses a `VpnService` that grabs the default route. **This BREAKS
  the bike networking** (the bike's inbound connect-back is swallowed by the tun). Verified. **Do NOT use.**
- **"Wi-Fi loopback" self-mode** — pure `127.0.0.1`, **no VPN**. **This coexists with the bike perfectly**
  (verified: full PXC handshake completed with it running). **Use THIS.**

So: port the loopback self-mode trigger + a localhost AAP server. Our app's process is bound to the bike
Wi-Fi (`bindProcessToNetwork`), but the AA↔receiver link is localhost (interface-independent) and
Android Auto/GMS use cellular for map tiles — no conflict.

## Target data flow

```
Google Android Auto (Maps/Waze)
   │  projects H.264 to 127.0.0.1  (loopback self-mode; no VPN, no root)
   ▼
Embedded AAP receiver  →  AapVideo.process()  →  VideoDecoder (MediaCodec)
   │  decoder output Surface
   ├──────────────▶ (optional) local SurfaceView in OpenCfMoto  ← lets the user set a destination / touch
   └──────────────▶ VideoPipeline.inputSurface (our 800×384 H.264 ENCODER)
                          │
                          ▼
                    existing PXC data socket → bike dash
```

The **entire bike side is unchanged**. You are adding an AA video *source* that feeds
`VideoPipeline`'s encoder input surface, replacing the Presentation/MediaProjection source.

## Milestones (each independently testable; optimize for the owner's one-log-per-test loop)

**M0 (done):** HUR standalone in loopback self-mode shows AA locally; whole-screen mirror puts it on the
bike. This validated the concept.

**M1 — Receiver in-app (no bike):** Port HUR's Wi-Fi/loopback AAP receiver (transport + SSL handshake +
service discovery + video service + `AapVideo` + `VideoDecoder`) into OpenCfMoto. Trigger loopback
self-mode so Google AA projects to our in-app server. Render the decoded video to a **local
SurfaceView** to prove the receiver works inside OpenCfMoto. Drop USB and HUR's UI/launcher.
*Test: open OpenCfMoto, start AA, see Maps rendered in an OpenCfMoto view.*

**M2 — Transcode to the encoder:** Point `VideoDecoder`'s output Surface at `VideoPipeline.inputSurface`
(exposed for this). AA video (e.g. 800×480) is GPU-scaled to the encoder's 800×384 and encoded.
*Test: verify `framesSent` climbs / dump the encoded stream locally.*

**M3 — On the bike:** Make `EasyConnProber`/`VideoPipeline` use the AA source instead of
Presentation/mirror. Full path: AA → decode → encode → PXC → dash shows Maps.
*Test: one bike session; log must show handshake + `framesSent` + the dash showing Maps.*

**M4 — Phone-free + lock survival:** Move the AAP receiver + decoder + encoder + PXC into a foreground
service decoupled from any Activity; add a wake lock; keep decode→encode running when the phone is
backgrounded or locked. *Test: connect, lock phone, confirm the dash keeps navigating.*

**M5 — Control + audio + polish:** Provide a way to set a destination — either a local AA projection view
(touch → AAP input service back to AA) or rely on voice. Route AA audio (`AudioDecoder`) to the phone
speaker / BT helmet. Reconnect/auto-start UX.

## Key decisions already made

- **Route A-full** (embed receiver), not mirror or single-app capture. (Owner's explicit choice.)
- **AGPLv3** — port HUR freely; keep headers; the whole app will be AGPLv3.
- **Loopback (non-VPN) self-mode only.**
- **Transcode** AA video → 800×384 encoder (robust to AA's fixed resolution enum). Consider using AA
  **margins** (ServiceDiscoveryResponse) to make the usable area match the bike's aspect.
- **Keep the entire bike/PXC layer as-is**; only swap the video source into `VideoPipeline`.
- **Encoder must keep `KEY_REPEAT_PREVIOUS_FRAME_AFTER`** (bike's 9s timeout; static frames).

## Risks & unknowns (watch these)

1. **Data-socket frame format** (`[size LE][AnnexB AU]`) is inferred, not confirmed (see `01` §4). If the
   AA-sourced stream shows garbage on the dash while UI/mirror worked, re-verify with a live bike test,
   comparing the data-socket writes on port 10920 against the expected framing above.
2. **AA resolution vs 800×384** — AA won't project at 800×384 (fixed enum). Use transcode + margins.
3. **Self-mode reliability** — Google changes projection handshakes across Android Auto versions; HUR
   tracks these (see its CHANGELOG). Pin a known-good Android Auto version for development.
4. **Tee to two surfaces** (local control view + encoder) needs GL (decode to a `SurfaceTexture`, render
   to both). HUR's `GlProjectionView` is a starting point. If control-via-local-view is deferred, decode
   straight to the encoder surface and control AA by voice for v1.
5. **Lock survival** — rendering/Choreographer can throttle when backgrounded; drive the decode→encode
   from a foreground service with a wake lock and test on the target device.
6. **Interaction** — the bike dash is non-touch, so touch-back to AA must come from a phone-side view or
   voice. Decide in M5.

## Testing method (unchanged loop)

- Bike sessions are run by the owner; you get the exported log. Log every stage (`[AA] …`) so one session
  is diagnosable.
- If a wire format proves uncertain, resolve it via a live bike test session with verbose logging (see
  `01` §7).
- Keep each milestone shippable and independently verifiable — don't chain unverified assumptions.

## First concrete steps for you

1. Build HUR (`../headunit-revived/`, already at v3.1.1); skim `aap/`, `decoder/`, `connection/`,
   `aap/protocol/`, and locate the **loopback (non-VPN) self-mode** trigger and the localhost AAP server
   setup.
2. Decide the module boundary: a new `dev.coletz.opencfmoto.aa` package containing the ported receiver
   (transport/SSL/service-discovery/video/audio), with HUR headers preserved.
3. Implement **M1** (receiver in-app → local SurfaceView) and get one confirmation it works before
   touching the bike path.
4. Expose `VideoPipeline.inputSurface` (or a `setExternalVideoSource(Surface)`), then do **M2 → M3**.

Read `01` and `02` fully before starting; the bike protocol and the encoder constraints there are exact
and hard-won.

## Update (2026-07): status + PORTRAIT-BIKE variant (M1–M3 largely working)

The AA receiver + PXC hand-off is working: on the owner's 800×386 landscape bike, Android Auto renders
on the dash. Wi-Fi Direct bikes (`action=73`, see `01` §1) also connect (gateway = `<subnet>.1`).

**New hardware variant — a PORTRAIT bike** (HUID `CRCP24…`, `CFMOTO-EF75C7`, Wi-Fi Direct): its
`REQ_RV_CONFIG_CAPTURE` asks for **460×750 portrait**. This exposed a real design gap:

- The shared AA encoder is created at a **fixed 800×384** (landscape) *before* the bike's resolution is
  known (AA video is 800×480 landscape). We now report the encoder's *actual* resolution in
  `RLY_RV_CONFIG_CAPTURE` (interim fix in `EasyConnProber` — stops the decoder-mismatch **Connection
  reset** that killed the stream after ~5 frames). With that, a portrait bike will *display* the
  landscape AA feed, but **squished/letterboxed** — not a real fix.
- **Proper fix (your task):** make the video pipeline resolution + orientation **follow the bike**:
  1. Learn the bike's requested `deviceWidth×deviceHeight` (portrait vs landscape) from
     `REQ_RV_CONFIG_CAPTURE` — but note this arrives *after* AA projection has already started.
  2. Request the matching **orientation/resolution from Android Auto** in `ServiceDiscoveryResponse`
     (AA supports **portrait** head units; HUR has portrait-mode support). So for a portrait bike, AA
     itself renders portrait and the aspect matches.
  3. Size the H.264 **encoder to the bike's negotiated resolution** (rounded to ×16), not a fixed
     800×384. Because AA starts before the bike scan, you'll likely need to either (a) defer/recreate the
     encoder once the bike's dims are known, decoding AA into an intermediate `SurfaceTexture` and
     GL-scaling into a per-bike-sized encoder, or (b) start AA at a resolution chosen from a
     remembered/last-known bike profile.
- **`cmd 0x104a0` = `ECP_C2P_START_OTA_FTP_SERVICE`** (bike offers an OTA firmware FTP server:
  `{ctrlPort,dataPort,pwd,userName:"carbit_ota_user"}`). Optional/unrelated to video; currently ignored
  (harmless). Reply with `ECP_P2C_START_OTA_FTP_SERVICE_RESULT` only if you want OTA support.

Takeaway: **bike displays vary (landscape 800×386 AND portrait 460×750, AP AND Wi-Fi Direct).** Treat the
capture resolution/orientation as **per-bike**, driven by `REQ_RV_CONFIG_CAPTURE`, and drive the AA
projection resolution + encoder size from it.
