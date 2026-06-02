import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';

import 'localsend_ffi.dart';

const _discoveryPort = 4060;
const _transferPort = 4061;

void main() {
  runApp(const LocalSendFlutterApp());
}

class LocalSendFlutterApp extends StatelessWidget {
  const LocalSendFlutterApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'LocalSend Flutter',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0F766E)),
        useMaterial3: true,
      ),
      home: const LocalSendHomePage(),
    );
  }
}

class LocalSendHomePage extends StatefulWidget {
  const LocalSendHomePage({super.key});

  @override
  State<LocalSendHomePage> createState() => _LocalSendHomePageState();
}

class _LocalSendHomePageState extends State<LocalSendHomePage> {
  final _devices = <String, DiscoveredDevice>{};
  final _logs = <String>[];

  late final TextEditingController _aliasController;
  late final TextEditingController _saveDirController;
  late final TextEditingController _targetIpController;
  late final TextEditingController _filePathController;
  late final TextEditingController _targetPortController;
  late final TextEditingController _parallelController;

  LocalSendNative? _native;
  StreamSubscription<Map<String, dynamic>>? _eventSubscription;
  Timer? _discoveryTimer;

  bool _discoveryStarted = false;
  bool _serverStarted = false;
  String? _selectedDeviceKey;
  String _status = 'Starting native core...';
  String _lastReceive = 'No incoming file yet.';
  String _lastSend = 'No outgoing transfer yet.';
  double? _sendProgress;
  double? _receiveProgress;

  @override
  void initState() {
    super.initState();
    _aliasController = TextEditingController(text: _defaultAlias());
    _saveDirController = TextEditingController(text: _defaultSaveDir());
    _targetIpController = TextEditingController();
    _filePathController = TextEditingController();
    _targetPortController = TextEditingController(text: '$_transferPort');
    _parallelController = TextEditingController(text: '8');

    try {
      _native = LocalSendNative();
      _eventSubscription = _native!.events.listen(_handleNativeEvent);
      _status = 'Native core loaded.';
      WidgetsBinding.instance.addPostFrameCallback((_) => _startServices());
    } catch (error) {
      _status = 'Native core load failed: $error';
    }
  }

  @override
  void dispose() {
    _discoveryTimer?.cancel();
    _eventSubscription?.cancel();
    _native?.dispose();
    _aliasController.dispose();
    _saveDirController.dispose();
    _targetIpController.dispose();
    _filePathController.dispose();
    _targetPortController.dispose();
    _parallelController.dispose();
    super.dispose();
  }

  Future<void> _startServices() async {
    final native = _native;
    if (native == null) {
      return;
    }

    final alias = _aliasController.text.trim().isEmpty
        ? _defaultAlias()
        : _aliasController.text.trim();
    final saveDir = _saveDirController.text.trim();
    final transferPort = _parsePort(_targetPortController.text, _transferPort);

    try {
      await Directory(saveDir).create(recursive: true);
    } catch (error) {
      _addLog('Save directory could not be created: $error');
    }

    if (!_discoveryStarted) {
      native.startDiscovery(alias: alias, port: _discoveryPort);
      _discoveryStarted = true;
    }
    if (!_serverStarted) {
      native.startFileServer(saveDir: saveDir, port: transferPort);
      _serverStarted = true;
    }

    _discoverNow();
    _discoveryTimer ??= Timer.periodic(
      const Duration(seconds: 8),
      (_) => _discoverNow(),
    );

    if (!mounted) {
      return;
    }
    setState(() {
      _status = 'Discovery :$_discoveryPort, receive :$transferPort';
    });
  }

  void _discoverNow() {
    final native = _native;
    if (native == null) {
      return;
    }
    final alias = _aliasController.text.trim().isEmpty
        ? _defaultAlias()
        : _aliasController.text.trim();
    native.discoverOnce(alias: alias, port: _discoveryPort);
    _addLog('Discovery broadcast sent.');
  }

  void _sendFile() {
    final native = _native;
    if (native == null) {
      _setStatus('Native core is not available.');
      return;
    }

    final targetIp = _targetIpController.text.trim();
    final filePath = _filePathController.text.trim();
    if (targetIp.isEmpty) {
      _setStatus('Choose a device or type a target IP.');
      return;
    }
    if (filePath.isEmpty) {
      _setStatus('Type the full file path to send.');
      return;
    }
    if (!File(filePath).existsSync()) {
      _setStatus('File does not exist: $filePath');
      return;
    }

    final port = _parsePort(_targetPortController.text, _transferPort);
    final parallelCount = _parsePort(_parallelController.text, 8);
    setState(() {
      _sendProgress = 0;
      _lastSend = 'Sending ${_fileName(filePath)} to $targetIp:$port';
      _status = _lastSend;
    });
    native.sendFile(
      targetIp: targetIp,
      filePath: filePath,
      port: port,
      parallelCount: parallelCount,
    );
  }

  void _handleNativeEvent(Map<String, dynamic> event) {
    if (!mounted) {
      return;
    }

    final type = event['type']?.toString();
    setState(() {
      switch (type) {
        case 'deviceFound':
          final device = DiscoveredDevice.fromEvent(event);
          _devices[device.key] = device;
          if (_selectedDeviceKey == null) {
            _selectDevice(device);
          }
          _addLog('Found ${device.name} at ${device.ip}.', rebuild: false);
          break;
        case 'receiveRequest':
          final fileName = event['fileName']?.toString() ?? 'unknown';
          final size = _asInt(event['fileSize']);
          final sender = event['senderIp']?.toString() ?? 'unknown';
          _receiveProgress = 0;
          _lastReceive =
              'Receiving $fileName from $sender (${_formatBytes(size)})';
          _status = _lastReceive;
          _addLog(_lastReceive, rebuild: false);
          break;
        case 'progress':
          final direction = event['direction']?.toString();
          final transferred = _asInt(event['transferred']);
          final total = _asInt(event['total']);
          final value = _progressValue(transferred, total);
          if (direction == 'send') {
            _sendProgress = value;
            _lastSend =
                'Sent ${_formatBytes(transferred)} / ${_formatBytes(total)}';
          } else {
            _receiveProgress = value;
            _lastReceive =
                'Received ${_formatBytes(transferred)} / ${_formatBytes(total)}';
          }
          break;
        case 'complete':
          final direction = event['direction']?.toString();
          final success = event['success'] == true;
          final message = event['message']?.toString() ?? '';
          if (direction == 'send') {
            _sendProgress = success ? 1 : _sendProgress;
            _lastSend = success
                ? 'Send complete: $message'
                : 'Send failed: $message';
            _status = _lastSend;
            _addLog(_lastSend, rebuild: false);
          } else {
            _receiveProgress = success ? 1 : _receiveProgress;
            _lastReceive = success
                ? 'Receive complete: $message'
                : 'Receive failed: $message';
            _status = _lastReceive;
            _addLog(_lastReceive, rebuild: false);
          }
          break;
        case 'error':
          final message =
              event['message']?.toString() ?? 'Unknown native error';
          _status = message;
          _addLog(message, rebuild: false);
          break;
        default:
          _addLog(event.toString(), rebuild: false);
      }
    });
  }

  void _selectDevice(DiscoveredDevice device) {
    _selectedDeviceKey = device.key;
    _targetIpController.text = device.ip;
  }

  void _setStatus(String message) {
    if (!mounted) {
      return;
    }
    setState(() {
      _status = message;
      _addLog(message, rebuild: false);
    });
  }

  void _addLog(String message, {bool rebuild = true}) {
    final now = DateTime.now();
    final stamp =
        '${_twoDigits(now.hour)}:${_twoDigits(now.minute)}:${_twoDigits(now.second)}';
    _logs.insert(0, '[$stamp] $message');
    if (_logs.length > 80) {
      _logs.removeRange(80, _logs.length);
    }
    if (rebuild && mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('LocalSend Flutter'),
        actions: [
          IconButton(
            tooltip: 'Start services',
            onPressed: _startServices,
            icon: const Icon(Icons.play_arrow),
          ),
          IconButton(
            tooltip: 'Discover now',
            onPressed: _discoverNow,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final content = constraints.maxWidth >= 920
                ? Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(width: 340, child: _buildDevicePanel()),
                      const SizedBox(width: 16),
                      Expanded(child: _buildTransferPanel()),
                    ],
                  )
                : Column(
                    children: [
                      _buildDevicePanel(),
                      const SizedBox(height: 16),
                      _buildTransferPanel(),
                    ],
                  );
            return SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: content,
            );
          },
        ),
      ),
    );
  }

  Widget _buildDevicePanel() {
    return _Panel(
      title: 'Devices',
      icon: Icons.devices,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _aliasController,
            decoration: const InputDecoration(
              labelText: 'This device name',
              prefixIcon: Icon(Icons.badge_outlined),
            ),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: _discoverNow,
            icon: const Icon(Icons.radar),
            label: const Text('Broadcast discovery'),
          ),
          const SizedBox(height: 12),
          if (_devices.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 28),
              child: Center(child: Text('No devices found yet.')),
            )
          else
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 360),
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: _devices.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  final device = _devices.values.elementAt(index);
                  final selected = _selectedDeviceKey == device.key;
                  return ListTile(
                    selected: selected,
                    leading: Icon(
                      selected ? Icons.check_circle : Icons.phone_android,
                    ),
                    title: Text(
                      device.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      '${device.ip}  discovery:${device.controlPort}',
                    ),
                    onTap: () {
                      setState(() => _selectDevice(device));
                    },
                  );
                },
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildTransferPanel() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _Panel(
          title: 'Service',
          icon: Icons.settings_ethernet,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(_status),
              const SizedBox(height: 12),
              TextField(
                controller: _saveDirController,
                decoration: const InputDecoration(
                  labelText: 'Receive directory',
                  prefixIcon: Icon(Icons.folder_outlined),
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _targetPortController,
                      decoration: const InputDecoration(
                        labelText: 'Transfer port',
                        prefixIcon: Icon(Icons.tag),
                      ),
                      keyboardType: TextInputType.number,
                    ),
                  ),
                  const SizedBox(width: 12),
                  FilledButton.icon(
                    onPressed: _startServices,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('Start'),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Panel(
          title: 'Send',
          icon: Icons.upload_file,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    flex: 2,
                    child: TextField(
                      controller: _targetIpController,
                      decoration: const InputDecoration(
                        labelText: 'Target IP',
                        prefixIcon: Icon(Icons.dns_outlined),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      controller: _parallelController,
                      decoration: const InputDecoration(
                        labelText: 'Parallel',
                        prefixIcon: Icon(Icons.view_column),
                      ),
                      keyboardType: TextInputType.number,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _filePathController,
                decoration: const InputDecoration(
                  labelText: 'Full file path',
                  prefixIcon: Icon(Icons.insert_drive_file_outlined),
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: _sendFile,
                icon: const Icon(Icons.send),
                label: const Text('Send file'),
              ),
              const SizedBox(height: 12),
              _ProgressLine(label: _lastSend, value: _sendProgress),
              const SizedBox(height: 8),
              _ProgressLine(label: _lastReceive, value: _receiveProgress),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Panel(
          title: 'Events',
          icon: Icons.receipt_long,
          child: _logs.isEmpty
              ? const Text('No events yet.')
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: _logs
                      .take(12)
                      .map(
                        (entry) => Padding(
                          padding: const EdgeInsets.only(bottom: 6),
                          child: Text(
                            entry,
                            style: const TextStyle(
                              fontFamily: 'monospace',
                              fontSize: 12,
                            ),
                          ),
                        ),
                      )
                      .toList(),
                ),
        ),
      ],
    );
  }
}

class _Panel extends StatelessWidget {
  const _Panel({required this.title, required this.icon, required this.child});

  final String title;
  final IconData icon;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(icon, size: 20, color: scheme.primary),
                const SizedBox(width: 8),
                Text(title, style: Theme.of(context).textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 16),
            child,
          ],
        ),
      ),
    );
  }
}

class _ProgressLine extends StatelessWidget {
  const _ProgressLine({required this.label, required this.value});

  final String label;
  final double? value;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(label, maxLines: 2, overflow: TextOverflow.ellipsis),
        const SizedBox(height: 6),
        LinearProgressIndicator(value: value),
      ],
    );
  }
}

class DiscoveredDevice {
  const DiscoveredDevice({
    required this.deviceId,
    required this.name,
    required this.ip,
    required this.controlPort,
  });

  factory DiscoveredDevice.fromEvent(Map<String, dynamic> event) {
    final ip = event['ip']?.toString() ?? '';
    final deviceId = event['deviceId']?.toString() ?? ip;
    return DiscoveredDevice(
      deviceId: deviceId,
      name: event['name']?.toString() ?? 'Unknown device',
      ip: ip,
      controlPort: _asInt(event['controlPort'], fallback: _discoveryPort),
    );
  }

  final String deviceId;
  final String name;
  final String ip;
  final int controlPort;

  String get key => deviceId.isEmpty ? ip : deviceId;
}

String _defaultAlias() {
  try {
    return 'Flutter-${Platform.localHostname}';
  } catch (_) {
    return 'Flutter-${DateTime.now().millisecondsSinceEpoch}';
  }
}

String _defaultSaveDir() {
  if (Platform.isWindows) {
    final userProfile = Platform.environment['USERPROFILE'];
    if (userProfile != null && userProfile.isNotEmpty) {
      return '$userProfile\\Downloads';
    }
  }
  if (Platform.isAndroid) {
    return '/sdcard/Android/data/com.yukon.localsend.flutter/files/Download';
  }
  return Directory.current.path;
}

int _parsePort(String value, int fallback) {
  final parsed = int.tryParse(value.trim());
  if (parsed == null || parsed < 1 || parsed > 65535) {
    return fallback;
  }
  return parsed;
}

int _asInt(Object? value, {int fallback = 0}) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString() ?? '') ?? fallback;
}

double? _progressValue(int transferred, int total) {
  if (total <= 0) {
    return null;
  }
  return (transferred / total).clamp(0, 1).toDouble();
}

String _fileName(String path) {
  final separator = Platform.isWindows ? '\\' : '/';
  return path.split(separator).last;
}

String _formatBytes(int bytes) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  final digits = unit == 0 ? 0 : 1;
  return '${value.toStringAsFixed(digits)} ${units[unit]}';
}

String _twoDigits(int value) => value.toString().padLeft(2, '0');
