import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:message_aggregation/aggregation_service.dart';
import 'package:message_aggregation/main.dart';
import 'package:message_aggregation/models.dart';

Map<String, dynamic> row(
  String id,
  String source, {
  bool read = false,
  bool archived = false,
}) => {
  'id': id,
  'source': source,
  'title': id == 'a' ? '项目进度' : '下午会议',
  'body': '周五更新',
  'sender': '李明',
  'receivedAt': 1793400000000 + (id == 'a' ? 100 : 0),
  'isRead': read,
  'archived': archived,
  'target': '',
};

class FakeGateway implements AggregationGateway {
  final data = [
    row('a', 'wechat'),
    row('b', 'qq', read: true),
    row('c', 'sms', archived: true),
  ];
  bool failWrites = false;
  bool failNextWrite = false;
  Completer<void>? delayedWrite;
  @override
  Stream<void> get changes => const Stream.empty();
  @override
  Future<Map<dynamic, dynamic>> load() async => {
    'messages': data,
    'status': <String, dynamic>{},
  };
  @override
  Future<dynamic> invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    if (method == 'pinMessage') {
      final selected = data.firstWhere((m) => m['id'] == arguments!['id']);
      for (final target in data.where(
        (m) =>
            m['source'] == selected['source'] &&
            m['title'] == selected['title'],
      )) {
        target['isPinned'] = arguments!['pinned'];
      }
    }
    if (method == 'deleteMessages') {
      data.removeWhere((m) => (arguments!['ids'] as List).contains(m['id']));
    }
    if (method == 'updateMessage' || method == 'updateMessages') {
      if (failWrites || failNextWrite) {
        failNextWrite = false;
        throw PlatformException(code: 'disk', message: '无法保存');
      }
      await delayedWrite?.future;
      final ids = arguments!['ids'] as List? ?? [arguments['id']];
      for (final target in data.where((m) => ids.contains(m['id']))) {
        for (final field in ['isRead', 'archived']) {
          if (arguments[field] != null) target[field] = arguments[field];
        }
      }
    }
    return null;
  }
}

void main() {
  test('combined source, unread, archive and multi-term search filters', () {
    final messages = FakeGateway().data.map(InboxMessage.fromMap).toList();
    expect(filterMessages(messages).map((m) => m.id), ['a', 'b']);
    expect(filterMessages(messages, unreadOnly: true).map((m) => m.id), ['a']);
    expect(filterMessages(messages, archived: true).map((m) => m.id), ['c']);
    expect(
      filterMessages(
        messages,
        source: MessageSource.qq,
        query: '李明 周五',
      ).map((m) => m.id),
      ['b'],
    );
    expect(
      filterMessages(messages, source: MessageSource.qq, unreadOnly: true),
      isEmpty,
    );
    expect(filterMessages(messages, query: 'missing'), isEmpty);
  });

  test('read and archive writes survive controller reload; failed writes leave UI unchanged', () async {
    final gateway = FakeGateway();
    final controller = InboxController(gateway);
    await controller.reload();
    await controller.update(
      controller.messages.first,
      isRead: true,
      archived: true,
    );
    await controller.reload();
    expect(controller.messages.first.isRead, isTrue);
    expect(controller.messages.first.archived, isTrue);
    gateway.failWrites = true;
    await expectLater(
      controller.update(controller.messages.first, isRead: false),
      throwsA(isA<PlatformException>()),
    );
    expect(controller.messages.first.isRead, isTrue);
    controller.dispose();
  });

  testWidgets('navigate, expand search and change local read state', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(412, 915));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final gateway = FakeGateway();
    gateway.data.add({
      ...row('history', 'wechat'),
      'title': '项目进度',
      'body': '先前讨论\n${List.filled(30, '历史消息').join('\n')}',
    });
    expect(
      collapseConversations([
        InboxMessage.fromMap({...row('q1', 'qq'), 'title': '测试群'}),
        InboxMessage.fromMap({...row('q2', 'qq'), 'title': '测试群(2条新消息)'}),
      ]),
      hasLength(1),
    );
    final controller = InboxController(gateway);
    await tester.pumpWidget(AggregationApp(controller: controller));
    await tester.pumpAndSettle();
    expect(find.text('项目进度'), findsOneWidget);
    await tester.tap(find.byTooltip('搜索消息'));
    await tester.pumpAndSettle();
    // Matching history still shows one card with the latest summary.
    await tester.enterText(find.byType(TextField), '先前讨论');
    await tester.pumpAndSettle();
    expect(find.text('下午会议'), findsNothing);
    expect(find.text('项目进度'), findsOneWidget);
    await tester.tap(find.byTooltip('清除搜索'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('项目进度'));
    await tester.pumpAndSettle();
    final action = find.widgetWithText(FilledButton, '标为已读');
    final footerBefore = tester.getRect(action);
    await tester.drag(
      find.byType(SingleChildScrollView).last,
      const Offset(0, -450),
    );
    await tester.pumpAndSettle();
    expect(tester.getRect(action), footerBefore);
    expect(footerBefore.bottom, lessThanOrEqualTo(915));
    await tester.tap(find.widgetWithText(FilledButton, '标为已读'));
    await tester.pumpAndSettle();
    expect(controller.messages.first.isRead, isTrue);
    expect(
      controller.messages
          .where((m) => m.source == MessageSource.wechat)
          .every((m) => m.isRead),
      isTrue,
    );
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    await tester.tap(find.text('项目进度'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '标为未读'));
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(controller.messages.first.isRead, isFalse);

    // A failed manual save must not suppress automatic read on dismissal.
    await tester.tap(find.text('项目进度'));
    await tester.pumpAndSettle();
    gateway.failNextWrite = true;
    await tester.tap(find.widgetWithText(FilledButton, '标为已读'));
    await tester.pumpAndSettle();
    expect(controller.messages.first.isRead, isFalse);
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(controller.messages.first.isRead, isTrue);

    // Closing while a successful manual-unread save is pending must retain that choice.
    await tester.tap(find.text('项目进度'));
    await tester.pumpAndSettle();
    final pending = Completer<void>();
    gateway.delayedWrite = pending;
    await tester.tap(find.widgetWithText(FilledButton, '标为未读'));
    await tester.pump();
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    pending.complete();
    gateway.delayedWrite = null;
    await tester.pumpAndSettle();
    expect(controller.messages.first.isRead, isFalse);
    expect(tester.takeException(), isNull);
    await tester.tap(find.byTooltip('消息操作').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('置顶'));
    await tester.pumpAndSettle();
    expect(controller.conversations.first.isPinned, isTrue);
    await controller.reload();
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.push_pin_rounded), findsOneWidget);
    await tester.drag(find.text('项目进度'), const Offset(-350, 0));
    await tester.pumpAndSettle();
    expect(find.text('项目进度'), findsNothing);
    expect(
      controller.messages.where((m) => m.source == MessageSource.wechat),
      isEmpty,
    );
  });

  testWidgets(
    'empty inbox and connection states are truthful on narrow screens',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(320, 740));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final gateway = FakeGateway()..data.clear();
      await tester.pumpWidget(
        AggregationApp(controller: InboxController(gateway)),
      );
      await tester.pumpAndSettle();
      expect(find.text('尚未接入消息来源'), findsOneWidget);
      await tester.tap(find.text('接入管理'));
      await tester.pumpAndSettle();
      expect(find.text('未授权'), findsNWidgets(2));
      await tester.scrollUntilVisible(
        find.text('谷歌邮箱'),
        400,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('待接入'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
