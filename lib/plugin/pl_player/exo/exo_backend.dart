import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// ExoPlayer 播放媒体描述（对齐原 media_kit Media 的使用面）
class PlExoMedia {
  final String url;

  /// DASH 分离音频流（与 [url] 合并播放）
  final String? audioUrl;
  final Duration? start;
  final bool audioOnly;
  final Map<String, String>? headers;

  const PlExoMedia({
    required this.url,
    this.audioUrl,
    this.start,
    this.audioOnly = false,
    this.headers,
  });
}

/// 播放器状态快照（对齐原 media_kit PlayerState 的使用面）
class PlExoState {
  Duration position = Duration.zero;
  Duration duration = Duration.zero;
  Duration buffer = Duration.zero;
  double rate = 1.0;
  bool playing = false;
  bool completed = false;
  bool buffering = false;
  int width = 0;
  int height = 0;
}

/// 事件流（对齐原 media_kit player.stream 的使用面）
class PlExoStreams {
  final StreamController<bool> _playing = StreamController.broadcast();
  final StreamController<bool> _completed = StreamController.broadcast();
  final StreamController<Duration> _position = StreamController.broadcast();
  final StreamController<Duration> _duration = StreamController.broadcast();
  final StreamController<Duration> _buffer = StreamController.broadcast();
  final StreamController<bool> _buffering = StreamController.broadcast();
  final StreamController<(int, int)> _size = StreamController.broadcast();
  final StreamController<String> _error = StreamController.broadcast();

  Stream<bool> get playing => _playing.stream;
  Stream<bool> get completed => _completed.stream;
  Stream<Duration> get position => _position.stream;
  Stream<Duration> get duration => _duration.stream;
  Stream<Duration> get buffer => _buffer.stream;
  Stream<bool> get buffering => _buffering.stream;
  Stream<(int, int)> get size => _size.stream;
  Stream<String> get error => _error.stream;

  void dispose() {
    _playing.close();
    _completed.close();
    _position.close();
    _duration.close();
    _buffer.close();
    _buffering.close();
    _size.close();
    _error.close();
  }
}

/// mpv 旧 hwdec 设置 -> ExoPlayer 软/硬解二档映射
/// ExoPlayer 只有"硬解 Surface 直渲"，不存在 copy 模式。
bool hardDecodeFromLegacy({required bool enableHA, String? hwdec}) {
  if (!enableHA) return false;
  if (hwdec == null) return false;
  final v = hwdec.toLowerCase();
  return v != 'no' && v != 'none' && v != 'sw' && v != 'cpu';
}

/// Media3 ExoPlayer 播放内核适配层（MethodChannel "pl_exo" + 外部纹理）
class PlExoBackend {
  static const MethodChannel _channel = MethodChannel('pl_exo');

  final PlExoState state = PlExoState();
  final PlExoStreams stream = PlExoStreams();

  /// 外部纹理 id（渲染层 [PlExoVideo] 使用，对齐 media_kit VideoController.id）
  final ValueNotifier<int?> id = ValueNotifier(null);

  /// 视频尺寸（逻辑像素换算前的原始像素），变化时通知渲染层
  final ValueNotifier<(int, int)?> videoSize = ValueNotifier(null);

  PlExoMedia? _media;
  bool _disposed = false;

  PlExoBackend._() {
    _channel.setMethodCallHandler(_handleCall);
  }

  /// [hardDecode] 见 [hardDecodeFromLegacy]
  static Future<PlExoBackend> create({required bool hardDecode}) async {
    final backend = PlExoBackend._();
    final result = await _channel.invokeMapMethod<String, dynamic>('init', {
      'hardDecode': hardDecode,
    });
    backend.id.value = result?['textureId'] as int?;
    return backend;
  }

  Future<void> open(PlExoMedia media, {bool play = true}) async {
    _media = media;
    state.completed = false;
    await _channel.invokeMethod('open', {
      'videoUrl': _toUri(media.url),
      'audioUrl': media.audioUrl == null ? null : _toUri(media.audioUrl!),
      'startMs': media.start?.inMilliseconds ?? 0,
      'audioOnly': media.audioOnly,
      if (media.headers != null) 'headers': media.headers,
    });
    if (play) {
      await this.play();
    }
  }

  /// 本地绝对路径 -> file URI，其余原样返回
  static String _toUri(String url) =>
      url.startsWith('/') ? Uri.file(url).toString() : url;

  /// 重开当前媒体并保持当前位置（断流重试）
  Future<void> refresh() async {
    state.completed = false;
    await _channel.invokeMethod('refresh');
  }

  Future<void> play() async {
    state.playing = true;
    await _channel.invokeMethod('play');
  }

  Future<void> pause() async {
    state.playing = false;
    await _channel.invokeMethod('pause');
  }

  /// 播放/暂停切换（对齐 media_kit Player.playOrPause；播完后调用则从头重播）
  Future<void> playOrPause() async {
    if (state.playing) {
      await pause();
    } else {
      if (state.completed) {
        await seek(Duration.zero);
      }
      await play();
    }
  }

  Future<void> seek(Duration position) async {
    state.completed = false;
    stream._completed.add(false);
    await _channel.invokeMethod('seek', {'ms': position.inMilliseconds});
  }

  Future<void> setRate(double rate) async {
    state.rate = rate;
    await _channel.invokeMethod('setRate', {'rate': rate});
  }

  Future<void> setVolume(double volume) async {
    await _channel.invokeMethod('setVolume', {'volume': volume});
  }

  Future<void> setAudioOnly(bool audioOnly) async {
    await _channel.invokeMethod('setAudioOnly', {'audioOnly': audioOnly});
  }

  int get positionInMilliseconds => state.position.inMilliseconds;

  /// 截图：原实现依赖 mpv screenshot，ExoPlayer 内核下已置灰（返回 null）
  Future<ui.Image?> screenshot() async => null;

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    _channel.setMethodCallHandler(null);
    try {
      await _channel.invokeMethod('dispose');
    } catch (_) {}
    id.dispose();
    videoSize.dispose();
    stream.dispose();
    _media = null;
  }

  Future<dynamic> _handleCall(MethodCall call) async {
    if (call.method != 'event') return null;
    final event = Map<String, dynamic>.from(call.arguments as Map);
    switch (event['type']) {
      case 'playing':
        final playing = event['value'] == true;
        state.playing = playing;
        if (playing) state.completed = false;
        stream._playing.add(playing);
      case 'buffering':
        final buffering = event['value'] == true;
        state.buffering = buffering;
        stream._buffering.add(buffering);
      case 'completed':
        state.completed = true;
        state.playing = false;
        stream._completed.add(true);
      case 'position':
        final pos = Duration(milliseconds: (event['value'] as num?)?.toInt() ?? 0);
        final dur = Duration(milliseconds: (event['duration'] as num?)?.toInt() ?? 0);
        final buf = Duration(milliseconds: (event['buffered'] as num?)?.toInt() ?? 0);
        state.position = pos;
        if (dur > Duration.zero) state.duration = dur;
        state.buffer = buf;
        stream._position.add(pos);
        stream._buffer.add(buf);
      case 'duration':
        final dur = Duration(milliseconds: (event['value'] as num?)?.toInt() ?? 0);
        if (dur > Duration.zero) {
          state.duration = dur;
          stream._duration.add(dur);
        }
      case 'videoSize':
        final w = (event['width'] as num?)?.toInt() ?? 0;
        final h = (event['height'] as num?)?.toInt() ?? 0;
        state.width = w;
        state.height = h;
        videoSize.value = (w, h);
        stream._size.add((w, h));
      case 'error':
        final message = event['message']?.toString() ?? 'playback error';
        if (kDebugMode) {
          debugPrint('pl_exo error: $message (code=${event['code']})');
        }
        stream._error.add(message);
    }
    return null;
  }
}

/// 视频渲染层（对齐 media_kit fork 版 [SimpleVideo] 的布局语义）
class PlExoVideo extends StatefulWidget {
  final PlExoBackend controller;
  final Color fill;
  final double? aspectRatio;
  final FilterQuality filterQuality;

  const PlExoVideo({
    super.key,
    required this.controller,
    this.fill = Colors.black,
    this.aspectRatio,
    this.filterQuality = FilterQuality.low,
  });

  @override
  State<PlExoVideo> createState() => _PlExoVideoState();
}

class _PlExoVideoState extends State<PlExoVideo> {
  late double _devicePixelRatio;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([widget.controller.id, widget.controller.videoSize]),
      builder: (context, _) {
        final id = widget.controller.id.value;
        final size = widget.controller.videoSize.value;
        if (id == null || size == null || size.$1 <= 0 || size.$2 <= 0) {
          return const SizedBox();
        }
        final height = size.$2 / _devicePixelRatio;
        final width = widget.aspectRatio == null
            ? size.$1 / _devicePixelRatio
            : height * widget.aspectRatio!;
        return SizedBox(
          width: width,
          height: height,
          child: ColoredBox(
            color: widget.fill,
            child: Texture(textureId: id, filterQuality: widget.filterQuality),
          ),
        );
      },
    );
  }
}
