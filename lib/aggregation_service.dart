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
  List<InboxMessage> _messages = [];
  List<InboxMessage>? _conversations;
  List<InboxMessage> get messages => _messages;
  set messages(List<InboxMessage> value) {
    _messages = value;
    _conversations = null;
  }
  Map<MessageSource, Uint8List> icons = {};
  ConnectionStatus status = ConnectionStatus();
  bool loading = false, syncing = false;
  String? error;
  StreamSubscription<void>? _events;
  bool _disposed = false, _reloadAgain = false;
  bool _foreground = true;
  int get unreadCount => messages.where((m) => !m.isRead).length;
  List<InboxMessage> get conversations =>
      _conversations ??= collapseConversations(messages);

  void setForeground(bool foreground) {
    if (_foreground == foreground) return;
    _foreground = foreground;
    // Collection continues natively; load the latest state once the UI returns.
    if (foreground) unawaited(reload());
  }

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
    if (_disposed || !_foreground) return;
    if (loading) {
      _reloadAgain = true;
      return;
    }
    _reloadAgain = false;
    loading = true;
    _notify();
    try {
      final data = await gateway.load();
      if (_disposed || !_foreground) return;
      messages = (data['messages'] as List? ?? [])
          .map((m) => InboxMessage.fromMap(m as Map))
          .toList();
      status = ConnectionStatus(data['status'] as Map? ?? {});
      final nextIcons = <MessageSource, Uint8List>{};
      for (final entry in (data['icons'] as Map? ?? {}).entries) {
        final source = MessageSource.values.byName(entry.key as String);
        final bytes = entry.value as Uint8List;
        // MemoryImage keys use buffer identity; reuse unchanged bytes so refreshes
        // do not leave another decoded copy in Flutter's image cache each time.
        nextIcons[source] = listEquals(icons[source], bytes)
            ? icons[source]!
            : bytes;
      }
      icons = nextIcons;
      error = null;
    } catch (e) {
      error = readableError(e);
    } finally {
      loading = false;
      _notify();
      if (_reloadAgain && !_disposed && _foreground) {
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
    final ids = message.entries.map((m) => m.id).toSet();
    await gateway.invoke(ids.length == 1 ? 'updateMessage' : 'updateMessages', {
      'id': message.id,
      if (ids.length > 1) 'ids': ids.toList(),
      'isRead': isRead,
      'archived': archived,
    });
    // Only change the UI after the durable write succeeds.
    messages = messages
        .map(
          (m) => ids.contains(m.id)
              ? m.copyWith(isRead: isRead, archived: archived)
              : m,
        )
        .toList();
    _notify();
  }

  Future<void> markAllRead(MessageSource? source) async {
    await gateway.invoke('markAllRead', {'source': source?.name});
    await reload();
  }

  Future<void> setPinned(InboxMessage message) async {
    final pinned = !message.isPinned;
    await gateway.invoke('pinMessage', {'id': message.id, 'pinned': pinned});
    messages = messages
        .map(
          (m) => m.conversationKey == message.conversationKey
              ? m.copyWith(isPinned: pinned)
              : m,
        )
        .toList();
    _notify();
  }

  Future<void> delete(InboxMessage message) async {
    final ids = message.entries.map((m) => m.id).toSet();
    await gateway.invoke('deleteMessages', {'ids': ids.toList()});
    messages = messages.where((m) => !ids.contains(m.id)).toList();
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
