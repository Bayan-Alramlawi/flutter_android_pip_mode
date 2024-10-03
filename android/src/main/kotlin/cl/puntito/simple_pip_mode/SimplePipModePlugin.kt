package cl.puntito.simple_pip_mode

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.annotation.NonNull
import cl.puntito.simple_pip_mode.Constants.EXTRA_ACTION_TYPE
import cl.puntito.simple_pip_mode.Constants.SIMPLE_PIP_ACTION
import cl.puntito.simple_pip_mode.actions.PipAction
import cl.puntito.simple_pip_mode.actions.PipActionsLayout
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler

/** SimplePipModePlugin */
class SimplePipModePlugin: FlutterPlugin, MethodCallHandler, ActivityAware {

  private val CHANNEL = "puntito.simple_pip_mode"
  private lateinit var channel: MethodChannel
  private lateinit var context: Context
  private var activity: Activity? = null
  private var actions: MutableList<RemoteAction> = mutableListOf()
  private var actionsLayout: PipActionsLayout = PipActionsLayout.NONE

  private var callbackHelper = PipCallbackHelper()
  private var params: PictureInPictureParams.Builder? = null
  private lateinit var broadcastReceiver: BroadcastReceiver

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL)
    callbackHelper.setChannel(channel)
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
    broadcastReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (SIMPLE_PIP_ACTION != intent.action) {
          return
        }
        intent.getStringExtra(EXTRA_ACTION_TYPE)?.let {
          val action = PipAction.valueOf(it)
          action.afterAction()?.let {
            toggleAction(action)
          }
          callbackHelper.onPipAction(action)
        }
      }
    }.also { broadcastReceiver = it }
    context.registerReceiver(broadcastReceiver, IntentFilter(SIMPLE_PIP_ACTION))
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    context.unregisterReceiver(broadcastReceiver)
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    if (activity == null) {
      result.error("ActivityNotInitialized", "Activity has not been initialized", null)
      return
    }

    when (call.method) {
      "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
      "isPipAvailable" -> result.success(isPipAvailable())
      "isPipActivated" -> result.success(isPipActivated())
      "enterPipMode" -> enterPipMode(call, result)
      "setPipLayout" -> setPipLayout(call, result)
      "setIsPlaying" -> setIsPlaying(call, result)
      "setAutoPipMode" -> setAutoPipMode(call, result)
      else -> result.notImplemented()
    }
  }

  private fun isPipAvailable(): Boolean {
    return activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) ?: false
  }

  private fun isPipActivated(): Boolean {
    return activity?.isInPictureInPictureMode ?: false
  }

  private fun enterPipMode(call: MethodCall, result: MethodChannel.Result) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val aspectRatio = call.argument<List<Int>>("aspectRatio")
      val autoEnter = call.argument<Boolean>("autoEnter") ?: false
      val seamlessResize = call.argument<Boolean>("seamlessResize") ?: false
      var params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(aspectRatio!![0], aspectRatio[1]))
        .setActions(actions)
        .setAutoEnterEnabled(autoEnter)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        params = params.setAutoEnterEnabled(autoEnter)
          .setSeamlessResizeEnabled(seamlessResize)
      }

      this.params = params
      result.success(activity?.enterPictureInPictureMode(params.build()) ?: false)
    } else {
      result.error("NotSupported", "PIP mode is only supported on Android O or newer", null)
    }
  }

  private fun setPipLayout(call: MethodCall, result: MethodChannel.Result) {
    val success = call.argument<String>("layout")?.let {
      try {
        actionsLayout = PipActionsLayout.valueOf(it.uppercase())
        actions = actionsLayout.remoteActions(context)
        true
      } catch (e: Exception) {
        false
      }
    } ?: false
    result.success(success)
  }

  private fun setIsPlaying(call: MethodCall, result: MethodChannel.Result) {
    call.argument<Boolean>("isPlaying")?.let { isPlaying ->
      if (actionsLayout.actions.contains(PipAction.PLAY) ||
        actionsLayout.actions.contains(PipAction.PAUSE)) {
        var i = actionsLayout.actions.indexOf(PipAction.PLAY)
        if (i == -1) {
          i = actionsLayout.actions.indexOf(PipAction.PAUSE)
        }
        if (i >= 0) {
          actionsLayout.actions[i] = if (isPlaying) PipAction.PAUSE else PipAction.PLAY
          renderPipActions()
          result.success(true)
        }
      } else {
        result.success(false)
      }
    } ?: result.success(false)
  }

  private fun setAutoPipMode(call: MethodCall, result: MethodChannel.Result) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val aspectRatio = call.argument<List<Int>>("aspectRatio")
      val seamlessResize = call.argument<Boolean>("seamlessResize") ?: false
      val autoEnter = call.argument<Boolean>("autoEnter") ?: false
      val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(aspectRatio!![0], aspectRatio[1]))
        .setAutoEnterEnabled(autoEnter)
        .setSeamlessResizeEnabled(seamlessResize)
        .setActions(actions)

      this.params = params

      activity?.setPictureInPictureParams(params.build())
      result.success(true)
    } else {
      result.error("NotImplemented", "System Version less than Android S found", "Expected Android S or newer.")
    }
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivity() {
    activity = null
  }

  private fun toggleAction(action: PipAction) {
    actionsLayout.toggleToAfterAction(action)
    renderPipActions()
  }

  private fun renderPipActions() {
    actions = PipActionsLayout.remoteActions(context, actionsLayout.actions)
    params?.let {
      it.setActions(actions).build()
      activity?.setPictureInPictureParams(it.build())
    }
  }
}
