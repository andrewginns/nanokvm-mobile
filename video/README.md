# NanoKVM video library

`video` owns three server video paths and keeps their lifecycles behind
`NanoKvmVideoSession`:

- `/api/stream/h264` uses the NanoKVM 2.4.3 WebRTC signaling contract and a
  hardware H.264 decoder. It sends a receive-only video offer and trickled ICE
  candidates after the server's `ice-servers` event, applies one answer, and
  sends the protocol heartbeat every 60 seconds. A connection is considered
  streaming only after a frame has actually reached the Android EGL surface.
- `/api/stream/h264/direct` is decoded to a caller-owned `Surface` (or a bound
  `TextureView`) using `MediaCodec`. The raw WebSocket envelope is parsed before a
  key-frame-gated queue of at most three access units. A saturated queue drops the
  whole queued GOP and waits for another key frame; it never keeps a later P-frame
  after discarding one of its dependencies. Compression negotiation is refused;
  an oversized access unit cancels the source immediately and enters the normal
  fallback policy instead of leaving the peer able to send another message.
- `/api/stream/mjpeg` is parsed as Content-Length multipart data. Callers receive
  a downsampled `RGB_565` `Bitmap` by default; raw JPEG callbacks are opt-in with
  `deliverMjpegJpegBytes`. Delivery is latest-frame-only, and superseded/stale
  bitmaps are recycled before they reach the listener.
- `AUTO` preserves the lightweight direct H.264 to MJPEG behavior. Selecting
  `WEBRTC` uses independent, one-shot attempts in the order WebRTC, direct
  H.264, then MJPEG. Each failed source is completely torn down before the next
  source starts; offers, answers, candidates, media and heartbeats are never
  replayed into a later attempt.

Every network request authenticates with the NanoKVM cookie
`nano-kvm-token=<JWT>`. Use the same configured `OkHttpClient` as the protocol
layer so the selected TLS trust policy also applies to video.

Callbacks run on the executor passed to `NanoKvmVideoSession`; Android UI callers
should pass a main-thread executor. Every queued callback rechecks its session
generation when it executes. Closing the session cancels all transports, stops
the decoders, clears pending ICE candidates, and ignores late callbacks.
MediaCodec release is posted to its
generation-specific codec thread, so `stop()` is non-blocking on the main thread
and cannot release a subsequently started decoder.

The pinned `android-prefixed-stripped` WebRTC SDK intentionally omits WebRTC's
software video codecs. On a device without a hardware H.264 decoder, WebRTC is
reported unavailable and the explicit WebRTC preference advances to the fresh
direct-H.264 attempt. The app requests no microphone or camera permission and
disables WebRTC audio playout and recording.

The library manifest contributes `ACCESS_NETWORK_STATE`, which WebRTC's native
network monitor requires before peer creation. The upstream prefixed/stripped AAR
ships no consumer shrinker rules, so this module also keeps the
`livekit.org.webrtc` JNI surface intact in minified consumers. A device test creates
the real native runtime, EGL surface, peer, transceiver, and local offer; fake-peer
JVM tests alone are not accepted as coverage for this boundary.

WebRTC is initialized with a no-op injectable logger at `LS_NONE`. This prevents
the native network monitor, ICE stack, and renderer from writing interface,
address, candidate, or SDP details to logcat. Dependency updates must retain the
API 37 runtime test and a cleared-logcat review; disabling the internal tracer
alone is not sufficient to suppress native informational logging.
