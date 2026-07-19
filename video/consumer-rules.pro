# MediaCodec integration uses platform APIs directly.

# liblkjingle_peerconnection_so resolves these Java classes and callbacks by their exact JNI names.
# The prefixed/stripped WebRTC AAR does not ship consumer rules of its own.
-keep class livekit.org.webrtc.** { *; }
