package org.nanokvm.video

import android.content.Context
import android.view.Surface
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.HardwareVideoDecoderFactory
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.Loggable
import livekit.org.webrtc.Logging
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack

/** Process-scoped native WebRTC runtime. Construction never makes the console fail to open. */
class NanoKvmWebRtcRuntime private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val resources: Result<NativeResources> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createResources(applicationContext)
    }

    internal val peerFactory = NanoKvmWebRtcPeerFactory { iceServers, target, listener ->
        val native = resources.getOrElse {
            throw IOException("Native WebRTC is unavailable on this device")
        }
        val surface = (target as? SurfaceRenderTarget)?.surface
            ?: throw IOException("Native WebRTC requires an Android Surface")
        NativePeer(native, iceServers, surface, listener)
    }

    val isAvailable: Boolean
        get() = resources.isSuccess

    companion object {
        @Volatile private var instance: NanoKvmWebRtcRuntime? = null

        fun get(context: Context): NanoKvmWebRtcRuntime = instance ?: synchronized(this) {
            instance ?: NanoKvmWebRtcRuntime(context).also { instance = it }
        }

        private fun createResources(context: Context): Result<NativeResources> = runCatching {
            PeerConnectionFactory.initialize(
                WebRtcLoggingPolicy.applyTo(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false),
                )
                    .createInitializationOptions(),
            )
            val eglBase = EglBase.create()
            try {
                val decoderFactory = HardwareVideoDecoderFactory(eglBase.eglBaseContext)
                val supportsH264 = decoderFactory.supportedCodecs.any {
                    it.name.equals(H264_CODEC_NAME, ignoreCase = true)
                }
                check(supportsH264) { "No hardware H.264 decoder is available" }
                val factory = PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
                NativeResources(factory, eglBase)
            } catch (error: Throwable) {
                eglBase.release()
                throw error
            }
        }

        private const val H264_CODEC_NAME = "H264"
    }

    internal class SurfaceRenderTarget(val surface: Surface) : NanoKvmWebRtcRenderTarget

    private data class NativeResources(
        val factory: PeerConnectionFactory,
        val eglBase: EglBase,
    )

    private class NativePeer(
        resources: NativeResources,
        iceServers: List<NanoKvmWebRtcIceServer>,
        surface: Surface,
        private val listener: NanoKvmWebRtcPeerListener,
    ) : NanoKvmWebRtcPeer {
        private val lock = Any()
        private val eventExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "NanoKVM-WebRTC-events").apply { isDaemon = true }
        }
        private val renderer = EglRenderer("NanoKVM-WebRTC-renderer")
        private var peerConnection: PeerConnection? = null
        private var remoteTrack: VideoTrack? = null
        private var started = false
        private var closed = false
        private var lastWidth = 0
        private var lastHeight = 0

        private val renderSink = VideoSink { frame ->
            reportSize(frame)
            renderer.onFrame(frame)
        }

        init {
            try {
                renderer.init(resources.eglBase.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
                renderer.addRenderListener { timestampNs ->
                    postEvent { listener.onFrameRendered(timestampNs) }
                }
                renderer.setErrorCallback {
                    postFailure(IOException("WebRTC renderer ran out of graphics memory"))
                }
                renderer.createEglSurface(surface)

                val nativeIceServers = iceServers.flatMap { server ->
                    server.urls.map { url ->
                        PeerConnection.IceServer.builder(url)
                            .setUsername(server.username)
                            .setPassword(server.credential)
                            .createIceServer()
                    }
                }
                val configuration = PeerConnection.RTCConfiguration(nativeIceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }
                peerConnection = resources.factory.createPeerConnection(configuration, PeerObserver())
                    ?: throw IOException("Native WebRTC could not create a peer connection")
                peerConnection?.setAudioPlayout(false)
                peerConnection?.setAudioRecording(false)
            } catch (error: Throwable) {
                runCatching { peerConnection?.close() }
                runCatching { peerConnection?.dispose() }
                peerConnection = null
                runCatching { renderer.release() }
                eventExecutor.shutdownNow()
                throw error
            }
        }

        override fun start() {
            val peer = synchronized(lock) {
                check(!closed) { "WebRTC peer is closed" }
                check(!started) { "WebRTC peer is one-shot" }
                started = true
                checkNotNull(peerConnection)
            }
            val transceiver = peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY,
                ),
            )
            if (transceiver == null) {
                postFailure(IOException("WebRTC could not create a receive-only video transceiver"))
                return
            }
            peer.createOffer(CreateOfferObserver(peer), MediaConstraints())
        }

        override fun applyRemoteAnswer(
            description: NanoKvmWebRtcSessionDescription,
            onApplied: () -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            val peer = synchronized(lock) { if (closed) null else peerConnection }
            if (peer == null) {
                onFailure(IOException("WebRTC peer closed before its answer was applied"))
                return
            }
            peer.setRemoteDescription(
                SetDescriptionObserver(
                    onSuccess = { postEvent(onApplied) },
                    onFailure = {
                        postEvent {
                            onFailure(IOException("WebRTC rejected the remote answer"))
                        }
                    },
                ),
                SessionDescription(SessionDescription.Type.ANSWER, description.sdp),
            )
        }

        override fun addRemoteCandidate(candidate: NanoKvmWebRtcIceCandidate): Boolean {
            val peer = synchronized(lock) { if (closed) null else peerConnection } ?: return false
            return peer.addIceCandidate(
                IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate),
            )
        }

        override fun close() {
            val resources = synchronized(lock) {
                if (closed) return
                closed = true
                val values = remoteTrack to peerConnection
                remoteTrack = null
                peerConnection = null
                values
            }
            runCatching { resources.first?.removeSink(renderSink) }
            runCatching { resources.second?.close() }
            runCatching { resources.second?.dispose() }
            runCatching { renderer.release() }
            eventExecutor.shutdownNow()
        }

        private fun reportSize(frame: VideoFrame) {
            val width = frame.rotatedWidth
            val height = frame.rotatedHeight
            val changed = synchronized(lock) {
                if (closed || width <= 0 || height <= 0 ||
                    (width == lastWidth && height == lastHeight)
                ) {
                    false
                } else {
                    lastWidth = width
                    lastHeight = height
                    true
                }
            }
            if (changed) postEvent { listener.onVideoSizeChanged(width, height) }
        }

        private fun attachRemoteTrack(track: VideoTrack?) {
            if (track == null) return
            val previous = synchronized(lock) {
                if (closed || remoteTrack === track) return
                remoteTrack.also { remoteTrack = track }
            }
            runCatching { previous?.removeSink(renderSink) }
            track.addSink(renderSink)
        }

        private fun postFailure(cause: Throwable) {
            postEvent { listener.onFailure(cause) }
        }

        private fun postEvent(block: () -> Unit) {
            if (synchronized(lock) { closed }) return
            try {
                eventExecutor.execute {
                    if (!synchronized(lock) { closed }) runCatching(block)
                }
            } catch (_: Throwable) {
                // The peer is closing and no further callback is useful.
            }
        }

        private inner class CreateOfferObserver(
            private val peer: PeerConnection,
        ) : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(
                    SetDescriptionObserver(
                        onSuccess = {
                            postEvent {
                                listener.onLocalOffer(
                                    NanoKvmWebRtcSessionDescription(
                                        type = "offer",
                                        sdp = description.description,
                                    ),
                                )
                            }
                        },
                        onFailure = {
                            postFailure(IOException("WebRTC rejected the local offer"))
                        },
                    ),
                    description,
                )
            }

            override fun onCreateFailure(reason: String) {
                postFailure(IOException("WebRTC could not create an offer"))
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(reason: String) = Unit
        }

        private class SetDescriptionObserver(
            private val onSuccess: () -> Unit,
            private val onFailure: (String) -> Unit,
        ) : SdpObserver {
            override fun onSetSuccess() = onSuccess()
            override fun onSetFailure(reason: String) = onFailure(reason)
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(reason: String) = Unit
        }

        private inner class PeerObserver : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                postEvent {
                    listener.onLocalCandidate(
                        NanoKvmWebRtcIceCandidate(
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        ),
                    )
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                attachRemoteTrack(transceiver.receiver.track() as? VideoTrack)
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
                attachRemoteTrack(receiver.track() as? VideoTrack)
            }

            override fun onAddStream(stream: MediaStream) {
                attachRemoteTrack(stream.videoTracks.firstOrNull())
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED) {
                    postFailure(IOException("WebRTC peer connection failed"))
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (newState == PeerConnection.IceConnectionState.FAILED) {
                    postFailure(IOException("WebRTC ICE connection failed"))
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }
    }
}

/**
 * Prevents WebRTC's native network monitor and ICE stack from writing device and LAN metadata to
 * logcat. Error text is discarded too because native failures can contain the same identifiers;
 * the runtime already reports bounded, app-owned failure messages to its caller.
 */
internal object WebRtcLoggingPolicy {
    val minimumSeverity: Logging.Severity = Logging.Severity.LS_NONE
    val sink: Loggable = Loggable { _, _, _ -> }

    fun applyTo(
        builder: PeerConnectionFactory.InitializationOptions.Builder,
    ): PeerConnectionFactory.InitializationOptions.Builder =
        builder.setInjectableLogger(sink, minimumSeverity)
}
