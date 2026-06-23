package com.example.callguard.data.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRtcManager — 실제 P2P 음성 통화를 담당한다.
 *
 * 로컬 오디오  : JavaAudioDeviceModule.setSamplesReadyCallback → localAudioCallback (STT)
 * 원격 오디오  : JavaAudioDeviceModule 플레이아웃 콜백 → remoteAudioCallback (STT)
 *               (이 라이브러리 버전에는 AudioTrack.addSink 미지원)
 *
 * 시그널링 흐름:
 *   caller: startLocalAudioCapture() → createOffer() → setLocal → (서버 경유) → setRemote(answer)
 *   callee: setRemote(offer) → createAnswer() → setLocal → (서버 경유)
 *   양쪽: addIceCandidate() 교환
 */
class WebRtcManager(
    private val context: Context,
    private val localAudioCallback: (ByteArray, Int, Int) -> Unit,
    private val remoteAudioCallback: (ByteArray, Int, Int) -> Unit
) {
    private val tag = "WebRtcManager"

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null

    // 루프백 전용 두 번째 PeerConnection
    private var loopbackRemotePc: PeerConnection? = null

    // 루프백 모드: 로컬 마이크 오디오를 원격 STT에도 전달
    private var isLoopbackMode = false

    private var isLocalMuted = false
    private var isRemoteMuted = false

    // ICE 후보가 peerConnection 준비 전에 도착했을 때 버퍼링
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescSet = false

    init {
        initFactory()
    }

    private fun initFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            // 로컬 마이크 PCM 프레임 → 로컬 STT
            // 루프백 모드에서는 원격 STT(피싱 감지)에도 동일 오디오 전달
            .setSamplesReadyCallback { samples ->
                if (!isLocalMuted) {
                    localAudioCallback(samples.data, samples.sampleRate, samples.channelCount)

                    if (isLoopbackMode) {
                        // 루프백: 내 목소리가 상대방 목소리 역할도 함
                        if (!isRemoteMuted) {
                            remoteAudioCallback(
                                samples.data.clone(),
                                samples.sampleRate,
                                samples.channelCount
                            )
                        }
                    }
                }
            }
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        Log.d(tag, "PeerConnectionFactory 초기화 완료")
    }

    /**
     * 로컬 마이크 AudioTrack 준비 (통화 시작 전 호출)
     */
    fun startLocalAudioCapture() {
        val f = factory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        localAudioSource = f.createAudioSource(constraints)
        localAudioTrack = f.createAudioTrack("local_audio", localAudioSource)
        Log.d(tag, "로컬 오디오 캡처 시작")
    }

    /**
     * PeerConnection 생성 + 로컬 트랙 추가
     * signalingCallback: ICE 후보나 negotiation 이벤트를 상위 레이어로 전달
     */
    fun createPeerConnection(
        onIceCandidate: (IceCandidate) -> Unit,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit
    ) {
        val f = factory ?: return

        // STUN 서버 (Google 공개 서버 사용)
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = f.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onIceCandidate(it) }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(tag, "ICE 상태: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> onDisconnected()
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is AudioTrack) {
                    // 원격 오디오 트랙 수신 확인
                    // PCM 인터셉트는 setAudioTrackSamplesReadyCallback 에서 처리
                    Log.d(tag, "원격 AudioTrack 수신 완료 (ID: ${track.id()})")
                }
            }
        }) ?: return

        // 로컬 트랙 추가
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("callguard_stream"))
        }

        Log.d(tag, "PeerConnection 생성 완료")
    }

    /**
     * Offer SDP 생성 (caller 측)
     */
    fun createOffer(onSuccess: (SessionDescription) -> Unit, onFailure: (String) -> Unit) {
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), it)
                    Log.d(tag, "Offer 생성 완료")
                    onSuccess(it)
                }
            }
            override fun onCreateFailure(error: String?) {
                onFailure(error ?: "Offer 생성 실패")
            }
        }, MediaConstraints())
    }

    /**
     * 수신한 Offer를 Remote Description으로 설정 후 Answer 생성 (callee 측)
     */
    fun setRemoteOfferAndCreateAnswer(
        sdp: String,
        onSuccess: (SessionDescription) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                isRemoteDescSet = true
                drainPendingIceCandidates()
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let {
                            peerConnection?.setLocalDescription(SdpObserverAdapter(), it)
                            Log.d(tag, "Answer 생성 완료")
                            onSuccess(it)
                        }
                    }
                    override fun onCreateFailure(error: String?) {
                        onFailure(error ?: "Answer 생성 실패")
                    }
                }, MediaConstraints())
            }
        }, offer)
    }

    /**
     * 수신한 Answer를 Remote Description으로 설정 (caller 측)
     */
    fun setRemoteAnswer(sdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                isRemoteDescSet = true
                drainPendingIceCandidates()
                Log.d(tag, "Remote Answer 설정 완료")
            }
        }, answer)
    }

    /**
     * ICE 후보 추가 (RemoteDescription 설정 전 도착하면 버퍼링)
     */
    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        if (isRemoteDescSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    private fun drainPendingIceCandidates() {
        pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    fun setLocalAudioMuted(mute: Boolean) {
        isLocalMuted = mute
        localAudioTrack?.setEnabled(!mute)
        Log.d(tag, "로컬 마이크 뮤트: $mute")
    }

    fun setRemoteAudioMuted(mute: Boolean) {
        isRemoteMuted = mute
        Log.d(tag, "원격 오디오 뮤트 (개입): $mute")
    }

    fun stopCall() {
        peerConnection?.close()
        localAudioSource?.dispose()
        loopbackRemotePc?.close()
        loopbackRemotePc = null
        peerConnection = null
        localAudioTrack = null
        localAudioSource = null
        isLocalMuted = false
        isRemoteMuted = false
        isLoopbackMode = false
        isRemoteDescSet = false
        pendingIceCandidates.clear()
        Log.d(tag, "WebRTC 리소스 정리 완료")
    }

    // ── 루프백 통화 (단일 기기 테스트용, PeerConnection 2개 사용) ─────
    /**
     * 하나의 기기에서 caller / callee PeerConnection 을 모두 생성하여
     * 내부적으로 오디오 루프를 형성한다.
     *
     * 흐름:
     *   caller PC → createOffer → setLocal
     *   callee PC → setRemote(offer) → createAnswer → setLocal
     *   caller PC → setRemote(answer)
     *   ICE 후보 상호 교환
     */
    fun startLoopbackCall(
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ) {
        isLoopbackMode = true   // 로컬 오디오 → 원격 STT도 전달
        val f = factory ?: return
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        // 1. Caller PeerConnection
        peerConnection = f.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { loopbackRemotePc?.addIceCandidate(it) }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(tag, "Loopback Caller ICE: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> onDisconnected()
                    else -> {}
                }
            }
        })

        // 로컬 트랙을 caller PC 에 추가
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("loopback_stream")) }

        // 2. Callee PeerConnection (원격 오디오 수신 역할)
        loopbackRemotePc = f.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { peerConnection?.addIceCandidate(it) }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d(tag, "Loopback: 원격 AudioTrack 수신 완료")
            }
        })

        // 3. Offer 생성 → 교환
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(offerSdp: SessionDescription?) {
                offerSdp ?: return
                peerConnection?.setLocalDescription(SdpObserverAdapter(), offerSdp)

                loopbackRemotePc?.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        loopbackRemotePc?.createAnswer(object : SdpObserverAdapter() {
                            override fun onCreateSuccess(answerSdp: SessionDescription?) {
                                answerSdp ?: return
                                loopbackRemotePc?.setLocalDescription(SdpObserverAdapter(), answerSdp)
                                peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                                    override fun onSetSuccess() {
                                        isRemoteDescSet = true
                                        Log.d(tag, "루프백 협상 완료")
                                    }
                                }, answerSdp)
                            }
                        }, MediaConstraints())
                    }
                }, offerSdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(tag, "루프백 Offer 생성 실패: $error")
            }
        }, MediaConstraints())
    }
}

open class PeerConnectionObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate?) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(channel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
    override fun onTrack(transceiver: RtpTransceiver?) {}
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) { Log.e("SdpObserver", "생성 실패: $error") }
    override fun onSetFailure(error: String?) { Log.e("SdpObserver", "설정 실패: $error") }
}
