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
    if (method == 'updateMessage') {
      if (failWrites) throw PlatformException(code: 'disk', message: '无法保存');
      final target = data.firstWhere((m) => m['id'] == arguments!['id']);
      for (final field in ['isRead', 'archived']) {
        if (arguments![field] != null) target[field] = arguments[field];
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
    final controller = InboxController(FakeGateway());
    await tester.pumpWidget(AggregationApp(controller: controller));
    await tester.pumpAndSettle();
    expect(find.text('项目进度'), findsOneWidget);
    await tester.tap(find.byTooltip('搜索消息'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '项目');
    await tester.pumpAndSettle();
    expect(find.text('下午会议'), findsNothing);
    await tester.tap(find.byTooltip('清除搜索'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('项目进度'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '标为已读'));
    await tester.pumpAndSettle();
    expect(controller.messages.first.isRead, isTrue);
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    await tester.tap(find.text('项目进度'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '标为未读'));
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(10, 10));
    await tester.pumpAndSettle();
    expect(controller.messages.first.isRead, isFalse);
    expect(tester.takeException(), isNull);
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
