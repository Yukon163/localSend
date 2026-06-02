import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';

typedef _NativeEventCallback = Void Function(Pointer<Utf8>);
typedef _FreeStringNative = Void Function(Pointer<Utf8>);
typedef _FreeStringDart = void Function(Pointer<Utf8>);
typedef _StartDiscoveryNative =
    Void Function(
      Uint16,
      Pointer<Utf8>,
      Pointer<NativeFunction<_NativeEventCallback>>,
    );
typedef _StartDiscoveryDart =
    void Function(
      int,
      Pointer<Utf8>,
      Pointer<NativeFunction<_NativeEventCallback>>,
    );
typedef _DiscoverOnceNative = Void Function(Uint16, Pointer<Utf8>);
typedef _DiscoverOnceDart = void Function(int, Pointer<Utf8>);
typedef _StartFileServerNative =
    Void Function(
      Uint16,
      Pointer<Utf8>,
      Pointer<NativeFunction<_NativeEventCallback>>,
    );
typedef _StartFileServerDart =
    void Function(
      int,
      Pointer<Utf8>,
      Pointer<NativeFunction<_NativeEventCallback>>,
    );
typedef _SendFileNative =
    Void Function(
      Pointer<Utf8>,
      Uint16,
      Pointer<Utf8>,
      Uint64,
      Pointer<NativeFunction<_NativeEventCallback>>,
    );
typedef _SendFileDart =
    void Function(
      Pointer<Utf8>,
      int,
      Pointer<Utf8>,
      int,
      Pointer<NativeFunction<_NativeEventCallback>>,
    );

class LocalSendNative {
  LocalSendNative() : _library = _openLibrary() {
    _startDiscovery = _library
        .lookupFunction<_StartDiscoveryNative, _StartDiscoveryDart>(
          'rust_flutter_start_discovery',
        );
    _discoverOnce = _library
        .lookupFunction<_DiscoverOnceNative, _DiscoverOnceDart>(
          'rust_flutter_discover_once',
        );
    _startFileServer = _library
        .lookupFunction<_StartFileServerNative, _StartFileServerDart>(
          'rust_flutter_start_file_server',
        );
    _sendFile = _library.lookupFunction<_SendFileNative, _SendFileDart>(
      'rust_flutter_send_file',
    );
    _freeString = _library.lookupFunction<_FreeStringNative, _FreeStringDart>(
      'rust_flutter_free_string',
    );

    _eventCallback = NativeCallable<_NativeEventCallback>.listener((
      Pointer<Utf8> payload,
    ) {
      final raw = payload == nullptr ? '' : payload.toDartString();
      try {
        _events.add(jsonDecode(raw) as Map<String, dynamic>);
      } catch (_) {
        _events.add({'type': 'error', 'message': raw});
      } finally {
        if (payload != nullptr) {
          _freeString(payload);
        }
      }
    });
  }

  final DynamicLibrary _library;
  late final _StartDiscoveryDart _startDiscovery;
  late final _DiscoverOnceDart _discoverOnce;
  late final _StartFileServerDart _startFileServer;
  late final _SendFileDart _sendFile;
  late final _FreeStringDart _freeString;
  late final NativeCallable<_NativeEventCallback> _eventCallback;
  final StreamController<Map<String, dynamic>> _events =
      StreamController<Map<String, dynamic>>.broadcast();

  Stream<Map<String, dynamic>> get events => _events.stream;

  void startDiscovery({required String alias, int port = 4060}) {
    final cAlias = alias.toNativeUtf8();
    try {
      _startDiscovery(port, cAlias, _eventCallback.nativeFunction);
    } finally {
      calloc.free(cAlias);
    }
  }

  void discoverOnce({required String alias, int port = 4060}) {
    final cAlias = alias.toNativeUtf8();
    try {
      _discoverOnce(port, cAlias);
    } finally {
      calloc.free(cAlias);
    }
  }

  void startFileServer({required String saveDir, int port = 4061}) {
    final cSaveDir = saveDir.toNativeUtf8();
    try {
      _startFileServer(port, cSaveDir, _eventCallback.nativeFunction);
    } finally {
      calloc.free(cSaveDir);
    }
  }

  void sendFile({
    required String targetIp,
    required String filePath,
    int port = 4061,
    int parallelCount = 8,
  }) {
    final cTargetIp = targetIp.toNativeUtf8();
    final cFilePath = filePath.toNativeUtf8();
    try {
      _sendFile(
        cTargetIp,
        port,
        cFilePath,
        parallelCount,
        _eventCallback.nativeFunction,
      );
    } finally {
      calloc.free(cTargetIp);
      calloc.free(cFilePath);
    }
  }

  void dispose() {
    // The Rust core starts long-lived listener threads and currently has no
    // stop API, so this callback must stay valid for the process lifetime.
  }

  static DynamicLibrary _openLibrary() {
    if (Platform.isAndroid) {
      return DynamicLibrary.open('liblocalsend_core.so');
    }
    if (Platform.isWindows) {
      final candidates = <String>[
        'localsend_core.dll',
        ..._windowsCandidatePaths(),
      ];
      for (final path in candidates) {
        try {
          if (path == 'localsend_core.dll' || File(path).existsSync()) {
            return DynamicLibrary.open(path);
          }
        } catch (_) {}
      }
      throw StateError('localsend_core.dll not found');
    }
    throw UnsupportedError('Unsupported platform: ${Platform.operatingSystem}');
  }

  static List<String> _windowsCandidatePaths() {
    final candidates = <String>[];
    final exeDir = File(Platform.resolvedExecutable).parent;
    candidates.add('${exeDir.path}\\localsend_core.dll');

    var dir = Directory.current;
    for (var i = 0; i < 8; i++) {
      candidates.add(
        '${dir.path}\\locsd_lib\\target\\release\\localsend_core.dll',
      );
      final parent = dir.parent;
      if (parent.path == dir.path) break;
      dir = parent;
    }
    return candidates;
  }
}
