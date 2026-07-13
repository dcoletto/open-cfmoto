# Reverse-Engineering Findings — CFMoto MotoPlay / EasyConn (PXC)

Everything in this document is confirmed accurate against the real bike (`com.cfmoto.cfmotointernational`
v2.2.5, versionCode 126/136 is the official app version this was verified against). Treat every value,
byte layout, and cmd ID below as verified ground truth, not a hypothesis to be re-derived.

## 0. Hardware

- Bike head unit: **"CFDL16-6GUV"**, HUID `6GUVA2C00100055`, a ~5" **800×386 landscape**, **non-touch**
  dashboard (physical ~4.25" × 2.55"). Runs a Carbit EasyConn head unit, `flavor=65540`,
  `sdkVersion 0.9.29.1`, `screenType=1`, `productType=3`, `mirrorMode=1`, `supportScreenMirroring=true`.
- **`socketTimeoutPeriodWifi = 9`** — the bike drops the media connection if it doesn't get a frame
  within ~9 seconds. First frame must arrive fast.

## 1. The QR code

The dash shows a QR that is a URL with a query string, e.g.:

```
http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37416&sn=peTz&action=9
   &ssid=CFMOTO-f46457&pwd=59a9cddc94&auth=wpa2-psk
   &mac=6C:09:4A:0F:6C:F8&name=CFMOTO-f46457
```

- `ssid` / `pwd` / `auth` / `mac` / `name` — **stable across scans** (the bike's Wi-Fi AP creds).
- `sn` — a random nonce, **changes every QR**, and is **not used** by the connection flow.
- `action` — a bitmask of supported transport modes (bit0=1 AP, bit1=2 AP+internet, bit3=8 P2P,
  bit6=64 BT). Two hardware variants seen in the wild:
  - **`action=9`** (=1+8) with `ssid=CFMOTO-xxxx` → **AP hotspot**. Phone joins the AP, gets
    `192.168.0.50`, bike is the gateway `192.168.0.1`.
  - **`action=73`** (=1+8+64) with `ssid=DIRECT-go-CFMOTO-xxxx` → **Wi-Fi Direct**. Phone joins the
    `DIRECT-` group as a normal station (via `WifiNetworkSpecifier` — works fine), gets `192.168.49.x`,
    and the bike is the **P2P group owner at `192.168.49.1`**. Wi-Fi Direct provides **no default route
    and no DNS**, so `resolveGateway` must fall back to `<subnet>.1` (implemented). Confirmed against the
    decompiled app: both transports funnel into the SAME `MDNSClient.tryConnectToServer` → PXC handshake,
    so **everything downstream (probe :10930, ports 10920/10921/10922, CmdBaseHead/ReqBase, video pull)
    is IDENTICAL** — only the transport + bike-IP resolution differ.
  - Newer QR may also carry `bm=<hex>` (Bluetooth MAC for BLE pairing) and omit `sn` — both irrelevant
    to the Wi-Fi/PXC flow.
- Parsing mirrors the official `QrResult.parseResult`. See `QrData.kt`.

## 2. Network topology (THE PHONE IS THE SERVER)

Verified against real hardware (`cfmoto-tcp-v5.log`). Sequence:

1. Phone joins the bike Wi-Fi AP (`CFMOTO-f46457`, WPA2, **no internet**). Bike = gateway
   **192.168.0.1**; phone gets **192.168.0.50/24**.
2. Phone discovers the bike via mDNS `_EasyConn._tcp.local.` advertising `192.168.0.1:10930`
   (TXT: huid, huname, channel, flavor, port, ip). In practice you can just use the gateway IP + 10930.
3. **Phone opens TCP servers on 10920, 10921, 10922** bound to its bike-network IP.
4. Phone makes ONE outbound connection to `bike:10930`, sends a probe, reads the ack, **closes it**.
5. The **bike then connects BACK** to the phone's listening ports and drives the handshake.

Port roles (fixed, decode framing by port):
- **10922** = PXC control — **CmdBaseHead** framing (16-byte header).
- **10921** = media control — **ReqBase** framing (8-byte header).
- **10920** = media data — **ReqBase** framing (8-byte header).
- **10930** = bike's probe/mDNS endpoint (phone connects out here once).

## 3. CmdBaseHead framing (control plane, port 10922 and the 10930 probe)

16-byte header, **little-endian**, then payload:

```
offset 0  : cmd     (int32)
offset 4  : totalLen(int32)  = 16 + payload.length
offset 8  : magic   (int32)  = cmd XOR totalLen   (integrity check; reject if mismatch)
offset 12 : reserved(int32)  = 0
payload[totalLen - 16]
```

Implemented in `PxcFrame.kt` (`PxcFrame.write` / `PxcFrame.read`). Verified working both directions.

### Control-plane exchange (all payloads are JSON unless noted)

1. **Probe** (phone → bike:10930): `cmd=0x70000010` (ECP_PXC_MDNS_RESPOND), payload
   `{"phoneType":"Android","packageName":"com.cfmoto.cfmotointernational"}`.
   Bike replies `cmd=0x70000011`, payload `{"status":true}` (or `{"status":false}` to reject). Close socket.
2. Bike connects to **:10922**, sends `cmd=0x10000` (CAR_CTRL channel select, empty) → phone replies
   `cmd=0x10001` (empty).
3. Bike sends `cmd=0x10010` **CLIENT_INFO** (JSON describing the bike; includes `HUID`, `HUName`,
   `channel`, `flavor`, `socketTimeoutPeriodWifi`, `supportScreenMirroring`, …). Phone replies
   `cmd=0x10011` with the phone's CLIENT_INFO JSON:
   ```json
   {"pxcVersion":"1.0.2","phoneUUID":"<uuid>","phoneBrand":"…","phoneModel":"…",
    "phoneOsVersion":"35","phoneOs":"Android","package":"com.cfmoto.cfmotointernational",
    "versionCode":126,"token":0,"pubkey":"<RSA X.509 base64>",
    "encryptedHUID":"<bike HUID signed with our RSA private key, base64>",
    "bluetoothName":"OpenCfMoto","supportH264IFrame":true,"supportFunction":0,
    "appVersionFingerPrint":"…"}
   ```
   (Matches the official app's CLIENT_INFO reply. `pubkey`/`encryptedHUID` from `RsaKeys.kt`.)
4. Bike `cmd=0x10690` `{"usbSpeed":0,"wifiSpeed":0}` → phone `cmd=0x10691` (empty).
5. Second bike connection to **:10922**: `cmd=0x20000` (CAR_DATA channel select) → phone `cmd=0x20001`.
6. **SN check**: bike `cmd=0x103e0` `{"client_set":"easy_conn","sn":"DSXXZMHNFV3DEXEBB3PZ2"}` → phone
   `cmd=0x103e1` (empty ack) then `cmd=0x201c0` (ECP_P2C_CHECK_SN_RESULT)
   `{"isOk":true,"errCode":0,"errMsg":"","id":"<sn>","client_set":"easy_conn"}` → bike `cmd=0x201c1`.
7. **Heartbeats**: bike `cmd=0x70000000` → phone `cmd=0x70000001` (both empty).

Control cmd constants live in `PxcFrame.kt`. Dispatcher: `PxcHandshake.kt`.

## 4. ReqBase framing (media plane, ports 10921 + 10920)

8-byte header, **little-endian**, then body:

```
offset 0 : cmdType  (int16)
offset 2 : cmdLen   (int16, unsigned)
offset 4 : token    (int32)
body[cmdLen]
```

Command types (media-plane commands, verified):

| cmdType | name | direction | reply |
|--------:|------|-----------|-------|
| 16 | REQ_RV_CONFIG_CAPTURE | bike→phone | 17 |
| 48 | REQ_GET_VERSION | bike→phone | 49 (two int32: version, 1) |
| 64 | REQ_HEARTBEAT | bike→phone | 65 (empty) |
| 96 | REQ_CONFIGCAPTUREREXTEND | bike→phone | 97 (JSON, we send `{"state":0}`) |
| 112 | REQ_RV_DATA_START | bike→phone | 113 (empty) — **starts the encoder** |
| 114 | REQ_RV_DATA_NEXT | bike→phone (data socket) | one H.264 frame, raw (see below) |

### REQ_RV_CONFIG_CAPTURE (16) body layout (LE)

```
deviceWidth          s16 @0     (bike wants 800)
deviceHeight         s16 @2     (bike wants 386)
wantFps              i32 @4
wantEncoder          i32 @8     (2 = H264)
supportCodec         i32 @12
minQuality           s16 @16
maxQuality           s16 @18
bitRate              i32 @20
capScreenMode        byte @24
touchMode            byte @25
orientation          byte @26
displayId            byte @27
videoType            byte @28
supportExtendProtocol byte @29
reserved             2 bytes @30
encryptedHUID        UTF-8 @32..
```

Reply **RLY_RV_CONFIG_CAPTURE (17)** body (LE):
```
encoder              i32   (echo wantEncoder; default 2=H264)
captureWidth         s16   (deviceWidth  & ~15)   → we send 800
captureHeight        s16   (deviceHeight & ~15)   → we send 384
supportExtendProtocol byte (echo)
```
(The `& ~15` rounds down to a multiple of 16, exactly as the official app does.)

### The data pull (lock-step) on port 10920

The bike sends `REQ_RV_DATA_NEXT (114)` with an **empty body**, then waits for one frame; when it gets
one, it sends the next `114`. The frame reply is written **RAW** (not wrapped in a ReqBase header):

```
[ frame size : int32 LE ][ H.264 Annex-B access unit bytes ]
```

SPS/PPS (codec-config) is prepended to the first keyframe. **⚠️ This exact wire format is inferred, not
100% confirmed.** It currently works in the mirror/UI paths, but if a new video source misbehaves, the
trailing bytes / whether an `INT_ZERO` terminator is needed may need re-verification through a live
bike test session.

Media handling: `EasyConnProber.mediaLoop` / `handleMediaReq` / `sendFrameRaw`.

## 5. The H.264 encoder (what the bike accepts)

`VideoPipeline.kt`. MediaCodec `video/avc`:
- **800×384**, **Baseline profile @ Level 3.1** (fallback to default profile if the encoder rejects it —
  embedded HU decoders often need Baseline), 2.5 Mbps, 30 fps, **I-frame interval 1s**.
- **`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100_000` (100 ms)** — CRITICAL. Surface-input MediaCodec only
  emits on *new* buffers, so a static source produces zero frames and the bike times out at 9s. This
  flag repeats the last frame during static periods. (A UI ticker masked this earlier; it must be set.)
- Output is Annex-B; SPS/PPS captured on the `BUFFER_FLAG_CODEC_CONFIG` output and prepended to the
  first keyframe so the bike's decoder can start mid-stream.
- Frames go into a small bounded queue; the data socket pulls via `pollFrame()`.

## 6. BLE wake-up (NOT needed for projection — documented for completeness)

The bike also has a BLE service used by the official app for vehicle control (lock/telemetry). It is
**not required for MotoPlay projection** (verified: full projection works with BLE untouched). Kept in
`BleWakeUp.kt` / `BleProtocol.kt` / `BleSecrets.kt` but dormant.

- GATT: service `0000B354-D6D8-C7EC-BDF0-EAB1BFC6BCBC`, write char `0000B356-…`, notify `0000B357-…`.
- Frame: `AB CD | cmd(1) | len(2,LE) | protobuf | sum8 | CF`; `sum8 = (cmd + len_lo + len_hi + Σpayload) & 0xFF`.
- Handshake: `0x5A` auth-pkg (sends a 64-byte token) → `0x5B` challenge (16-byte AES ciphertext as
  32 hex chars) → phone AES-256-ECB/PKCS7-decrypts with a per-pairing key → `0x5C` response (8-digit
  plaintext) → `0x5D` ack.
- Per-pairing secrets (this owner's bike) are hardcoded in `BleSecrets.kt`.

## 7. When you hit an unknown

Everything above is confirmed ground truth except the parts explicitly flagged as inferred (see §4).
If an inferred detail proves wrong in practice, or a new format question comes up, the resolution loop
is the same one used throughout this project: make your best-effort implementation, ship it, and have
the owner run a live bike session with verbose logging around the step in question. The on-screen log +
Share export (see `00`) is the diagnostic channel — a single well-logged bike session is enough to
confirm or correct an assumption.

If the AA data-socket frame format proves wrong, the fastest fix is a live bike test with verbose
logging around the data-socket writes on port 10920, compared against the expected framing above.
