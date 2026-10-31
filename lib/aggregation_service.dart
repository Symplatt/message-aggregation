import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'models.dart';

abstract class AggregationGateway {
  Stream<void> get changes;
  Future<Map<dynamic, dynamic>> load();
  Future<dynamic> invoke(String method, [Map<String, dynamic>? arguments]);
}

class AndroidGateway implements AggregationGateway {
  static const _channel = MethodChannel('message_aggregation/native');
  static const _events = EventChannel('message_aggregation/events');
  @override
  Stream<void> get changes => _events.receiveBroadcastStream().map((_) {});
  @override
  Future<Map<dynamic, dynamic>> load() async =>
      await _channel.invokeMapMethod<dynamic, dynamic>('load') ?? {};
  @override
  Future<dynamic> invoke(String method, [Map<String, dynamic>? arguments]) =>
      _channel.invokeMethod<dynamic>(method, arguments);
}

class InboxController extends ChangeNotifier {
  InboxController(this.gateway);
  final AggregationGateway gateway;
  List<InboxMessage> messages = [];
  Map<MessageSource, Uint8List> icons = {};
  ConnectionStatus status = ConnectionStatus();
  bool loading = false, syncing = false;
  String? error;
  StreamSubscription<void>? _events;
  bool _disposed = false, _reloadAgain = false;
  int get unreadCount => messages.where((m) => !m.isRead).length;

  void start() {
    _events = gateway.changes.listen(
      (_) => reload(),
      onError: (_) {
        error = '接入状态更新中断，请重新打开应用';
        _notify();
      },
    );
    unawaited(reload());
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> reload() async {
    if (loading) {
      _reloadAgain = true;
      return;
    }
    loading = true;
    _notify();
    try {
      final data = await gateway.load();
      messages = (data['messages'] as List? ?? [])
          .map((m) => InboxMessage.fromMap(m as Map))
          .toList();
      status = ConnectionStatus(data['status'] as Map? ?? {});
      icons = {
        for (final entry in (data['icons'] as Map? ?? {}).entries)
          MessageSource.values.byName(entry.key as String):
              entry.value as Uint8List,
      };
      error = null;
    } catch (e) {
      error = readableError(e);
    } finally {
      loading = false;
      _notify();
      if (_reloadAgain && !_disposed) {
        _reloadAgain = false;
        unawaited(reload());
      }
    }
  }

  Future<String> sync() async {
    if (syncing) return '正在同步';
    syncing = true;
    _notify();
    try {
      final errors = await gateway.invoke('sync') as List? ?? [];
      await reload();
      return errors.isEmpty ? '已检查已接入的消息来源' : errors.join('\n');
    } finally {
      syncing = false;
      _notify();
    }
  }

  Future<void> update(
    InboxMessage message, {
    bool? isRead,
    bool? archived,
  }) async {
    await gateway.invoke('updateMessage', {
      'id': message.id,
      'isRead': isRead,
      'archived': archived,
    });
    // Only change the UI after the durable write succeeds.
    messages = messages
        .map(
          (m) => m.id == message.id
              ? m.copyWith(isRead: isRead, archived: archived)
              : m,
        )
        .toList();
    _notify();
  }

  Future<void> setSource(MessageSource source, bool enabled) async {
    await gateway.invoke('setSource', {
      'source': source.name,
      'enabled': enabled,
    });
    await reload();
  }

  @override
  void dispose() {
    _disposed = true;
    _events?.cancel();
    super.dispose();
  }
}

String readableError(Object error) => error is PlatformException
    ? error.message ?? '操作失败，请重试'
    : error is MissingPluginException
    ? '此功能需要在 Android 设备上运行'
    : '操作未完成，请检查连接后重试';
