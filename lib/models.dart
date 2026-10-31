enum MessageSource {
  wechat('微信'),
  qq('QQ'),
  sms('短信'),
  qqmail('QQ 邮箱'),
  netease('网易邮箱'),
  gmail('谷歌邮箱');

  const MessageSource(this.label);
  final String label;
}

class InboxMessage {
  const InboxMessage({
    required this.id,
    required this.source,
    required this.title,
    required this.body,
    required this.sender,
    required this.receivedAt,
    this.isRead = false,
    this.archived = false,
    this.target = '',
  });
  final String id, title, body, sender, target;
  final MessageSource source;
  final DateTime receivedAt;
  final bool isRead, archived;

  factory InboxMessage.fromMap(Map<dynamic, dynamic> map) => InboxMessage(
    id: map['id'] as String,
    source: MessageSource.values.byName(map['source'] as String),
    title: map['title'] as String,
    body: map['body'] as String,
    sender: map['sender'] as String,
    target: map['target'] as String? ?? '',
    receivedAt: DateTime.fromMillisecondsSinceEpoch(map['receivedAt'] as int),
    isRead: map['isRead'] == true,
    archived: map['archived'] == true,
  );

  InboxMessage copyWith({bool? isRead, bool? archived}) => InboxMessage(
    id: id,
    source: source,
    title: title,
    body: body,
    sender: sender,
    receivedAt: receivedAt,
    target: target,
    isRead: isRead ?? this.isRead,
    archived: archived ?? this.archived,
  );
}

List<InboxMessage> filterMessages(
  Iterable<InboxMessage> messages, {
  MessageSource? source,
  bool? archived = false,
  bool unreadOnly = false,
  String query = '',
}) {
  final words = query
      .trim()
      .toLowerCase()
      .split(RegExp(r'\s+'))
      .where((w) => w.isNotEmpty);
  return messages
      .where(
        (m) =>
            (archived == null || m.archived == archived) &&
            (source == null || source == m.source) &&
            (!unreadOnly || !m.isRead) &&
            words.every(
              (w) => '${m.title} ${m.body} ${m.sender} ${m.source.label}'
                  .toLowerCase()
                  .contains(w),
            ),
      )
      .toList()
    ..sort((a, b) => b.receivedAt.compareTo(a.receivedAt));
}

class MailAccount {
  MailAccount.fromMap(Map<dynamic, dynamic> map)
    : id = map['id'] as String,
      address = map['address'] as String,
      provider = MessageSource.values.byName(map['provider'] as String),
      error = map['error'] as String? ?? '',
      lastSync = (map['lastSync'] as int? ?? 0) == 0
          ? null
          : DateTime.fromMillisecondsSinceEpoch(map['lastSync'] as int);
  final String id, address, error;
  final MessageSource provider;
  final DateTime? lastSync;
}

class ConnectionStatus {
  ConnectionStatus([Map<dynamic, dynamic> map = const {}])
    : notificationAccess = map['notificationAccess'] == true,
      listenerConnected = map['listenerConnected'] == true,
      smsRead = map['smsRead'] == true,
      smsReceive = map['smsReceive'] == true,
      enabled = {
        MessageSource.wechat: map['wechat'] != false,
        MessageSource.qq: map['qq'] != false,
        MessageSource.sms: map['sms'] == true,
      },
      accounts = (map['accounts'] as List? ?? [])
          .map((a) => MailAccount.fromMap(a as Map))
          .toList();
  final bool notificationAccess, listenerConnected, smsRead, smsReceive;
  final Map<MessageSource, bool> enabled;
  final List<MailAccount> accounts;
  bool connected(MessageSource source) => switch (source) {
    MessageSource.wechat || MessageSource.qq =>
      notificationAccess && listenerConnected && enabled[source] == true,
    MessageSource.sms => smsRead && smsReceive && enabled[source] == true,
    MessageSource.gmail => false,
    _ => accounts.any(
      (a) => a.provider == source && a.lastSync != null && a.error.isEmpty,
    ),
  };
  int get connectedCount => MessageSource.values.where(connected).length;
}
