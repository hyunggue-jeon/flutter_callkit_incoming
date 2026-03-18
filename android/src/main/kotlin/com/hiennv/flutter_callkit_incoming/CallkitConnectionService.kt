package com.hiennv.flutter_callkit_incoming

import android.os.Bundle
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.util.Log
import androidx.annotation.RequiresApi
import android.telecom.TelecomManager

@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnectionService : ConnectionService() {

     companion object {
        var activeConnection: CallkitConnection? = null

        fun disconnectCurrentConnection() {
            activeConnection?.let {
                it.setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
                it.destroy()
            }
            activeConnection = null
        }
        fun setConnectionActive() {
            activeConnection?.let {
                if (it.state != Connection.STATE_ACTIVE) {
                    it.setActive()
                }
            }
        }
        fun setAudioRoute(route: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeConnection?.setAudioRoute(route)
            }
            Log.d("CallkitConnectionService","setAudioRoute route: ${route}")
        } 
        
        fun getCurrentAudioState(): Map<String, Any>? {
            val state = activeConnection?.callAudioState ?: return null
            Log.d("CallkitConnectionService", "getCurrentAudioState: route=${state.route} ")
            return mapOf(
                "route" to state.route,
                "supportedRoutes" to state.supportedRouteMask,
                "isMuted" to state.isMuted
            )
        }

        fun holdCall() {
            activeConnection?.let {
                it.setOnHold()
                it.sendHeldBroadcast()
            }
        }

        fun unholdCall() {
            activeConnection?.let {
                it.setActive()
                it.sendUnheldBroadcast()
            }
        }
    }
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
         // 이전 Connection이 남아있으면 정리
        disconnectCurrentConnection()

        val data = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        
        return CallkitConnection(applicationContext, data, isIncoming = true).apply {
            setRinging()  // STATE_RINGING 설정
            activeConnection = this
        }
    }
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        disconnectCurrentConnection()
        val data = request?.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)
        ?: request?.extras
        
        return CallkitConnection(applicationContext, data, isIncoming = false).apply {
            setActive()
            activeConnection = this
        }
    }
    // API 28+: native 전화 수신/발신 시 포커스 잃음 (권한 불필요)
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onConnectionServiceFocusLost() {
        Log.d("CallkitCSFocus", "onConnectionServiceFocusLost() called | activeConnection=${activeConnection} | state=${activeConnection?.state}")
        activeConnection?.let {
            if (it.state != Connection.STATE_HOLDING) {
                Log.d("CallkitCSFocus", "FocusLost → setOnHold + sendHeldBroadcast")
                it.setOnHold()
                it.sendHeldBroadcast()
            } else {
                Log.d("CallkitCSFocus", "FocusLost → already HOLDING, skip")
            }
            // hold 설정 직후 onCallAudioStateChanged 버스트가 오므로
            // 1초 후에 unhold 감지 활성화 (발신 종료 fallback용)
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("CallkitCSFocus", "FocusLost → enableUnholdDetection after delay")
                it.enableUnholdDetection()
            }, 1000)
        } ?: Log.w("CallkitCSFocus", "FocusLost → activeConnection is NULL")
        connectionServiceFocusReleased()
    }

    // API 28+: native 전화 종료 시 포커스 돌아옴 (수신 종료 시 동작)
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onConnectionServiceFocusGained() {
        Log.d("CallkitCSFocus", "onConnectionServiceFocusGained() called | activeConnection=${activeConnection} | state=${activeConnection?.state}")
        activeConnection?.let {
            it.disableUnholdDetection() // 이미 FocusGained로 처리하므로 fallback 비활성화
            if (it.state == Connection.STATE_HOLDING) {
                Log.d("CallkitCSFocus", "FocusGained → setActive + sendUnheldBroadcast")
                it.setActive()
                it.sendUnheldBroadcast()
            } else {
                Log.d("CallkitCSFocus", "FocusGained → state is NOT HOLDING (state=${it.state}), skip")
            }
        } ?: Log.w("CallkitCSFocus", "FocusGained → activeConnection is NULL")
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e("CallkitConnectionService", "IncomingConnection FAILED")
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e("CallkitConnectionService", "OutgoingConnection FAILED")
    }

}

@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnection(private val context: Context, private val callData: Bundle?, private val isIncoming: Boolean) : Connection() {

    private var notificationShown = false
    // FocusGained가 오지 않는 발신 종료 케이스의 fallback 감지 플래그
    @Volatile private var pendingUnholdDetection = false

    fun enableUnholdDetection() {
        Log.d("CallkitCSFocus", "enableUnholdDetection() | state=${state}")
        if (state == Connection.STATE_HOLDING) {
            pendingUnholdDetection = true
        }
    }

    fun disableUnholdDetection() {
        pendingUnholdDetection = false
    }

     init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            connectionProperties = PROPERTY_SELF_MANAGED
        }
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
    }
    // Incoming 전용
    override fun onShowIncomingCallUi() {
        showNotificationIfNeeded()
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {

        if (isIncoming && state.route == CallAudioState.ROUTE_BLUETOOTH) {
            showNotificationIfNeeded()
        }

        Log.d("CallkitConnectionService","onCallAudioStateChanged route: ${state.route} | connectionState=${this.state} | pendingUnhold=$pendingUnholdDetection")

        // FocusGained가 오지 않는 발신 종료 케이스 fallback
        if (pendingUnholdDetection && this.state == Connection.STATE_HOLDING) {
            Log.d("CallkitCSFocus", "onCallAudioStateChanged fallback → unhold (native outgoing call ended)")
            pendingUnholdDetection = false
            setActive()
            sendUnheldBroadcast()
            return
        }

         callData?.let { data ->
            val ctx = context
             val enriched = Bundle(data).apply {
                putInt("audioRoute", state.route)
                putInt("audioSupportedRoutes", state.supportedRouteMask)
                putBoolean("audioIsMuted", state.isMuted)
            }
            ctx.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentAudioState(ctx, enriched)
            )
        }

    }

    override fun onAnswer() {
        setActive()  // 시스템이 BT 라우팅 유지

        // 링톤 정지
        FlutterCallkitIncomingPlugin.getInstance()
            ?.getCallkitSoundPlayerManager()?.stop()
        
        // 노티 전환 + Flutter 이벤트
        callData?.let { data ->
            val ctx = context
            // BroadcastReceiver 경유해서 기존 accept 흐름 타게
            ctx.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentAccept(ctx, data)
            )
        }
    }

    override fun onReject() {
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()

        CallkitConnectionService.activeConnection = null

        FlutterCallkitIncomingPlugin.getInstance()
            ?.getCallkitSoundPlayerManager()?.stop()

        callData?.let { data ->
            val ctx = context
            ctx.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentDecline(ctx, data)
            )
        }
    }

    // 시스템(Telecom)이 hold를 요청할 때 호출되는 콜백
    override fun onHold() {
        Log.d("CallkitCSFocus", "onHold() called (system callback)")
        setOnHold()
        sendHeldBroadcast()
    }

    // 시스템(Telecom)이 unhold를 요청할 때 호출되는 콜백
    override fun onUnhold() {
        Log.d("CallkitCSFocus", "onUnhold() called (system callback)")
        setActive()
        sendUnheldBroadcast()
    }

    fun sendHeldBroadcast() {
        Log.d("CallkitCSFocus", "sendHeldBroadcast() | callData=${callData != null}")
        callData?.let { data ->
            context.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentHeldByCell(context, data)
            )
            Log.d("CallkitCSFocus", "sendHeldBroadcast() → broadcast sent")
        } ?: Log.w("CallkitCSFocus", "sendHeldBroadcast() → callData is NULL, broadcast NOT sent")
    }

    fun sendUnheldBroadcast() {
        Log.d("CallkitCSFocus", "sendUnheldBroadcast() | callData=${callData != null}")
        callData?.let { data ->
            context.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentUnHeldByCell(context, data)
            )
            Log.d("CallkitCSFocus", "sendUnheldBroadcast() → broadcast sent")
        } ?: Log.w("CallkitCSFocus", "sendUnheldBroadcast() → callData is NULL, broadcast NOT sent")
    }

    override fun onDisconnect() {
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()

        CallkitConnectionService.activeConnection = null

        callData?.let { data ->
            context.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentEnded(context, data)
            )
        }
    }

    private fun showNotificationIfNeeded() {
        if (notificationShown) return
        notificationShown = true
        callData?.let {
            FlutterCallkitIncomingPlugin.getInstance()
                ?.getCallkitNotificationManager()
                ?.showIncomingNotification(it)
        }
    }
}