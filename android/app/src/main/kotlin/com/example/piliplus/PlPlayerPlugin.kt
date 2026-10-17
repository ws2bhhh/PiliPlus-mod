package com.example.piliplus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry

/**
 * PiliPlus 播放内核薄封装：Media3 ExoPlayer（MediaCodec -> Surface 直渲，零拷贝）。
 *
 * 范围：仅在线视频点播链路（单流 / DASH 双流合并）。直播、截图等能力不在此层实现。
 * 通道协议（MethodChannel "pl_exo"）：
 *   Dart -> Native: init/open/play/pause/seek/setRate/setVolume/setAudioOnly/refresh/dispose
 *   Native -> Dart: invokeMethod("event", {type, ...})，type ∈
 *     playing|buffering|completed|position|duration|videoSize|error
 */
class PlPlayerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var textures: TextureRegistry
    private var context: Context? = null

    private var textureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var surface: Surface? = null
    private var player: ExoPlayer? = null

    /** 最近一次 open 参数，供 refresh（断流重试）复用 */
    private var lastOpenArgs: Map<String, Any?>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val positionTicker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                sendEvent(
                    mapOf(
                        "type" to "position",
                        "value" to p.currentPosition,
                        "duration" to if (p.duration > 0) p.duration else 0L,
                        "buffered" to p.bufferedPosition,
                    ),
                )
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            sendEvent(mapOf("type" to "playing", "value" to isPlaying))
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING ->
                    sendEvent(mapOf("type" to "buffering", "value" to true))

                Player.STATE_READY -> {
                    sendEvent(mapOf("type" to "buffering", "value" to false))
                    val p = player ?: return
                    if (p.duration > 0) {
                        sendEvent(mapOf("type" to "duration", "value" to p.duration))
                    }
                }

                Player.STATE_ENDED ->
                    sendEvent(mapOf("type" to "completed", "value" to true))
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            sendEvent(
                mapOf(
                    "type" to "videoSize",
                    "width" to videoSize.width,
                    "height" to videoSize.height,
                ),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            sendEvent(
                mapOf(
                    "type" to "error",
                    "message" to (error.message ?: "playback error"),
                    "code" to error.errorCode,
                ),
            )
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        textures = binding.textureRegistry
        channel = MethodChannel(binding.binaryMessenger, "pl_exo")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        disposeAll()
        channel.setMethodCallHandler(null)
        context = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "init" -> result.success(initPlayer(call.argument("hardDecode") ?: true))

                "open" -> {
                    openMedia(call.arguments as? Map<String, Any?> ?: emptyMap())
                    result.success(null)
                }

                "play" -> {
                    player?.playWhenReady = true
                    result.success(null)
                }

                "pause" -> {
                    player?.playWhenReady = false
                    result.success(null)
                }

                "seek" -> {
                    val ms = (call.argument<Number>("ms") ?: 0L).toLong()
                    player?.seekTo(ms)
                    result.success(null)
                }

                "setRate" -> {
                    val rate = (call.argument<Number>("rate") ?: 1.0).toFloat()
                    player?.playbackParameters = PlaybackParameters(rate)
                    result.success(null)
                }

                "setVolume" -> {
                    val volume = (call.argument<Number>("volume") ?: 1.0).toFloat()
                    player?.volume = volume.coerceIn(0f, 1f)
                    result.success(null)
                }

                "setAudioOnly" -> {
                    val audioOnly = call.argument<Boolean>("audioOnly") ?: false
                    player?.let { p ->
                        p.setVideoSurface(if (audioOnly) null else surface)
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                            .build()
                    }
                    result.success(null)
                }

                "refresh" -> {
                    refreshMedia()
                    result.success(null)
                }

                "dispose" -> {
                    disposeAll()
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("pl_exo", e.message, null)
        }
    }

    private fun initPlayer(hardDecode: Boolean): Map<String, Any?> {
        disposeAll()
        val ctx = context ?: error("pl_exo: context not ready")
        val entry = textures.createSurfaceTexture()
        textureEntry = entry
        val surf = Surface(entry.surfaceTexture())
        surface = surf

        val builder = ExoPlayer.Builder(ctx)
        if (!hardDecode) {
            builder.setRenderersFactory(
                DefaultRenderersFactory(ctx).setMediaCodecSelector(SoftMediaCodecSelector),
            )
        }
        val p = builder.build()
        p.setVideoSurface(surf)
        p.addListener(listener)
        player = p

        mainHandler.removeCallbacks(positionTicker)
        mainHandler.post(positionTicker)
        return mapOf("textureId" to entry.id())
    }

    private fun openMedia(args: Map<String, Any?>) {
        val p = player ?: return
        lastOpenArgs = args
        applyMedia(p, args, (args["startMs"] as? Number)?.toLong() ?: 0L)
    }

    private fun applyMedia(p: ExoPlayer, args: Map<String, Any?>, startMs: Long) {
        val videoUrl = args["videoUrl"] as? String ?: error("pl_exo: videoUrl missing")
        val audioUrl = args["audioUrl"] as? String
        val audioOnly = args["audioOnly"] as? Boolean ?: false
        @Suppress("UNCHECKED_CAST")
        val headers = (args["headers"] as? Map<String, String>) ?: emptyMap()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
        // DefaultDataSource 按 scheme 路由：http(s) 走网络，file/content 走本地
        val dataSourceFactory = DefaultDataSource.Factory(context!!, httpFactory)
        val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

        val mediaSource: MediaSource = when {
            // 听视频：仅音频流，不渲染
            audioOnly ->
                progressiveFactory.createMediaSource(
                    MediaItem.fromUri(if (!audioUrl.isNullOrEmpty()) audioUrl else videoUrl),
                )

            // DASH 双流：video + audio 时间轴对齐合并
            !audioUrl.isNullOrEmpty() ->
                MergingMediaSource(
                    progressiveFactory.createMediaSource(MediaItem.fromUri(videoUrl)),
                    progressiveFactory.createMediaSource(MediaItem.fromUri(audioUrl)),
                )

            else ->
                progressiveFactory.createMediaSource(MediaItem.fromUri(videoUrl))
        }

        p.setMediaSource(mediaSource, startMs.coerceAtLeast(0L))
        p.prepare()
    }

    private fun refreshMedia() {
        val p = player ?: return
        val args = lastOpenArgs ?: return
        applyMedia(p, args, p.currentPosition)
        p.playWhenReady = true
    }

    private fun disposeAll() {
        mainHandler.removeCallbacks(positionTicker)
        player?.removeListener(listener)
        player?.release()
        player = null
        surface?.release()
        surface = null
        textureEntry?.release()
        textureEntry = null
        lastOpenArgs = null
    }

    private fun sendEvent(event: Map<String, Any?>) {
        mainHandler.post { channel.invokeMethod("event", event) }
    }

    /**
     * 软解选择器：仅保留软件解码器。
     * API 28 无 MediaCodecInfo.isHardwareAccelerated()，按 codec 名称启发式判断。
     */
    private object SoftMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): List<MediaCodecInfo> {
            return try {
                MediaCodecUtil.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                ).filter { isSoftware(it) }
            } catch (_: MediaCodecUtil.DecoderQueryException) {
                emptyList()
            }
        }

        private fun isSoftware(info: MediaCodecInfo): Boolean {
            val name = info.name.lowercase()
            return name.startsWith("omx.google.") ||
                name.startsWith("c2.android.") ||
                name.startsWith("c2.google.") ||
                name.startsWith("omx.ffmpeg.") ||
                name.startsWith("omx.sec.android.sw.") ||
                name.contains(".sw.")
        }
    }
}
