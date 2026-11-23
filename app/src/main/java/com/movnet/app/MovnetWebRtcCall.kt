package com.movnet.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-to-one WebRTC media engine. Signaling is exchanged through the authenticated MOVNET API.
 * STUN is configured here; production TURN credentials should be supplied by the backend.
 */
class MovnetWebRtcCall(
    context: Context,
    private val callId: Int,
    private val token: String,
    private val scope: CoroutineScope,
    private val localRenderer: SurfaceViewRenderer?,
    private val remoteRenderer: SurfaceViewRenderer?,
    private val videoEnabled: Boolean,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var localAudio: AudioTrack? = null
    private var localVideo: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var lastSignalId = 0
    private var remoteDescriptionSet = false
    private val queuedCandidates = mutableListOf<IceCandidate>()
    private var disposed = false

    fun eglContext() = egl.eglBaseContext

    fun start(isCaller: Boolean) {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
            )
            val encoder = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
            val decoder = DefaultVideoDecoderFactory(egl.eglBaseContext)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoder)
                .setVideoDecoderFactory(decoder)
                .createPeerConnectionFactory()

            localRenderer?.init(egl.eglBaseContext, null)
            localRenderer?.setMirror(true)
            remoteRenderer?.init(egl.eglBaseContext, null)
            remoteRenderer?.setMirror(false)

            val config = PeerConnection.RTCConfiguration(listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            peer = factory?.createPeerConnection(config, observer()) ?: error("Не удалось создать WebRTC-соединение")
            val f = factory ?: error("WebRTC не инициализирован")
            audioSource = f.createAudioSource(MediaConstraints())
            localAudio = f.createAudioTrack("movnet_audio_$callId", audioSource)
            peer?.addTrack(localAudio, listOf("movnet_stream_$callId"))

            if (videoEnabled) {
                val enumerator: CameraEnumerator = Camera2Enumerator(appContext)
                val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull()
                    ?: error("Камера не найдена")
                capturer = enumerator.createCapturer(cameraName, null) as? CameraVideoCapturer
                    ?: error("Не удалось открыть камеру")
                cameraSource = f.createVideoSource(false)
                val helper = SurfaceTextureHelper.create("MOVNET-Camera", egl.eglBaseContext)
                capturer?.initialize(helper, appContext, cameraSource!!.capturerObserver)
                capturer?.startCapture(720, 1280, 24)
                localVideo = f.createVideoTrack("movnet_video_$callId", cameraSource)
                localRenderer?.let { renderer -> localVideo?.addSink(renderer) }
                peer?.addTrack(localVideo, listOf("movnet_stream_$callId"))
            }

            pollSignals()
            if (isCaller) createOffer()
        } catch (e: Exception) {
            onError(e.message ?: "Не удалось запустить звонок")
        }
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.FAILED) onError("Не удалось установить связь. Возможно, нужна TURN-сеть.")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            val payload = JSONObject()
                .put("sdp", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex).toString()
            scope.launch {
                runCatching { MovnetApi.sendDirectCallSignal(callId, "ice", payload, token) }
                    .onFailure { onError(it.message ?: "Не удалось передать ICE-кандидат") }
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() as? VideoTrack ?: return
            remoteRenderer?.let { renderer -> track.addSink(renderer) }
        }
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (videoEnabled) "true" else "false"))
        }
        peer?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peer?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        sendDescription("offer", desc)
                    }
                    override fun onSetFailure(error: String?) { onError(error ?: "Ошибка локального SDP") }
                }, desc)
            }
            override fun onCreateFailure(error: String?) { onError(error ?: "Не удалось создать offer") }
        }, constraints)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (videoEnabled) "true" else "false"))
        }
        peer?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peer?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() { sendDescription("answer", desc) }
                    override fun onSetFailure(error: String?) { onError(error ?: "Ошибка локального SDP") }
                }, desc)
            }
            override fun onCreateFailure(error: String?) { onError(error ?: "Не удалось создать answer") }
        }, constraints)
    }

    private fun sendDescription(kind: String, desc: SessionDescription) {
        scope.launch {
            runCatching {
                MovnetApi.sendDirectCallSignal(
                    callId, kind, JSONObject().put("type", desc.type.canonicalForm()).put("sdp", desc.description).toString(), token
                )
            }.onFailure { onError(it.message ?: "Не удалось отправить сигнал звонка") }
        }
    }

    private fun pollSignals() {
        scope.launch(Dispatchers.IO) {
            while (!disposed) {
                try {
                    val signals = MovnetApi.loadDirectCallSignals(callId, lastSignalId, token)
                    for (i in 0 until signals.length()) {
                        val signal = signals.getJSONObject(i)
                        lastSignalId = maxOf(lastSignalId, signal.optInt("id"))
                        val kind = signal.optString("kind")
                        val payload = signal.optString("payload")
                        when (kind) {
                            "offer", "answer" -> {
                                val obj = JSONObject(payload)
                                val type = if (kind == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                                val desc = SessionDescription(type, obj.getString("sdp"))
                                peer?.setRemoteDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        remoteDescriptionSet = true
                                        queuedCandidates.toList().forEach { peer?.addIceCandidate(it) }
                                        queuedCandidates.clear()
                                        if (kind == "offer") createAnswer()
                                    }
                                    override fun onSetFailure(error: String?) { onError(error ?: "Не удалось принять SDP") }
                                }, desc)
                            }
                            "ice" -> {
                                val obj = JSONObject(payload)
                                val candidate = IceCandidate(obj.optString("sdpMid"), obj.optInt("sdpMLineIndex"), obj.optString("sdp"))
                                if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else queuedCandidates.add(candidate)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!disposed) onError(e.message ?: "Проблема с сигнализацией звонка")
                }
                kotlinx.coroutines.delay(700)
            }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) { localAudio?.setEnabled(enabled) }
    fun setCameraEnabled(enabled: Boolean) { localVideo?.setEnabled(enabled) }
    fun switchCamera() { capturer?.switchCamera(null) }

    fun dispose() {
        disposed = true
        runCatching { capturer?.stopCapture() }
        runCatching { localVideo?.removeSink(localRenderer) }
        runCatching { peer?.close(); peer?.dispose() }
        runCatching { capturer?.dispose() }
        runCatching { cameraSource?.dispose(); audioSource?.dispose() }
        runCatching { localAudio?.dispose(); localVideo?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { localRenderer?.release(); remoteRenderer?.release() }
        runCatching { egl.release() }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
