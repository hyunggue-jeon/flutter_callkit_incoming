package com.hiennv.flutter_callkit_incoming

import android.os.Bundle
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
        return CallkitConnection(applicationContext, data, isIncoming = false).apply {
            setActive()
            activeConnection = this
        }
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

     init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            connectionProperties = PROPERTY_SELF_MANAGED
        }
        audioModeIsVoip = true
    }
    // Incoming 전용
    override fun onShowIncomingCallUi() {
        showNotificationIfNeeded()
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        if (isIncoming && state.route == CallAudioState.ROUTE_BLUETOOTH) {
            showNotificationIfNeeded()
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