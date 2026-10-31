import 'package:flutter/material.dart';

import '../aggregation_service.dart';
import '../models.dart';
import '../widgets/source_icon.dart';
import 'connections_screen.dart';

class InboxScreen extends StatefulWidget {
  const InboxScreen({
    super.key,
    required this.controller,
    required this.tab,
    required this.onConnections,
  });
  final InboxController controller;
  final int tab;
  final VoidCallback onConnections;
  @override
  State<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends State<InboxScreen> {
  MessageSource? source;
  bool searchExpanded = false;
  String query = '';
  final search = TextEditingController();
  @override
  void dispose() {
    search.dispose();
    super.dispose();
  }

  void toast(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> perform(Future<void> Function() action) async {
    try {
      await action();
    } catch (e) {
      toast(readableError(e));
    }
  }

  Future<void> sync() async {
    try {
      toast(await widget.controller.sync());
    } catch (e) {
      toast(readableError(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    if (widget.tab == 1) return ConnectionsScreen(controller: controller);
    final visible = filterMessages(
      controller.messages,
      source: source,
      archived: null,
      query: query,
    );
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 18, 12, 8),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(9),
                decoration: BoxDecoration(
                  color: const Color(0xFF194F46),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(
                  Icons.all_inbox_rounded,
                  size: 20,
                  color: Color(0xFFE2F3B0),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  '讯息聚合',
                  style: const TextStyle(
                    fontSize: 21,
                    fontWeight: FontWeight.w700,
                    letterSpacing: .3,
                  ),
                ),
              ),
              IconButton(
                tooltip: searchExpanded ? '收起搜索' : '搜索消息',
                onPressed: () => setState(() {
                  searchExpanded = !searchExpanded;
                  if (!searchExpanded) {
                    search.clear();
                    query = '';
                    FocusManager.instance.primaryFocus?.unfocus();
                  }
                }),
                icon: Icon(
                  searchExpanded
                      ? Icons.search_off_rounded
                      : Icons.search_rounded,
                ),
              ),
              IconButton(
                tooltip: '刷新消息',
                onPressed: controller.syncing ? null : sync,
                icon: controller.syncing
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.sync_rounded),
              ),
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: sync,
            child: CustomScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              slivers: [
                if (searchExpanded)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          TextField(
                            controller: search,
                            autofocus: true,
                            onChanged: (value) => setState(() => query = value),
                            decoration: InputDecoration(
                              hintText: '搜索消息、联系人或主题',
                              prefixIcon: const Icon(Icons.search_rounded),
                              suffixIcon: query.isEmpty
                                  ? null
                                  : IconButton(
                                      tooltip: '清除搜索',
                                      onPressed: () => setState(() {
                                        search.clear();
                                        query = '';
                                      }),
                                      icon: const Icon(Icons.close, size: 19),
                                    ),
                            ),
                          ),
                          const SizedBox(height: 18),
                        ],
                      ),
                    ),
                  ),
                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  sliver: SliverToBoxAdapter(
                    child: SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Row(
                        children: [
                          Padding(
                            padding: const EdgeInsets.only(right: 8),
                            child: ChoiceChip(
                              label: const SizedBox(
                                width: 32,
                                height: 24,
                                child: Center(child: Text('ALL')),
                              ),
                              selected: source == null,
                              onSelected: (_) => setState(() => source = null),
                              showCheckmark: false,
                            ),
                          ),
                          ...MessageSource.values.map(
                            (s) => Padding(
                              padding: EdgeInsets.only(
                                right: s == MessageSource.values.last ? 0 : 8,
                              ),
                              child: Badge(
                                isLabelVisible: controller.messages.any(
                                  (m) => m.source == s && !m.isRead,
                                ),
                                backgroundColor: const Color(0xFF278554),
                                smallSize: 7,
                                offset: const Offset(1, 1),
                                child: Tooltip(
                                  message: s.label,
                                  child: ChoiceChip(
                                    label: controller.icons.containsKey(s)
                                        ? ClipRRect(
                                            borderRadius: BorderRadius.circular(
                                              6,
                                            ),
                                            child: Image.memory(
                                              controller.icons[s]!,
                                              width: 24,
                                              height: 24,
                                              semanticLabel: s.label,
                                            ),
                                          )
                                        : Icon(
                                            s.icon,
                                            size: 24,
                                            color: s.color,
                                            semanticLabel: s.label,
                                          ),
                                    selected: source == s,
                                    onSelected: (_) =>
                                        setState(() => source = s),
                                    showCheckmark: false,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                if (controller.error != null)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 24),
                      child: Card(
                        color: const Color(0xFFFFEADC),
                        child: ListTile(
                          leading: const Icon(Icons.info_outline),
                          title: Text(controller.error!),
                          trailing: IconButton(
                            tooltip: '重试加载',
                            onPressed: controller.reload,
                            icon: const Icon(Icons.refresh),
                          ),
                        ),
                      ),
                    ),
                  ),
                if (controller.loading && controller.messages.isEmpty)
                  const SliverToBoxAdapter(
                    child: Padding(
                      padding: EdgeInsets.all(50),
                      child: Center(child: CircularProgressIndicator()),
                    ),
                  )
                else if (visible.isEmpty)
                  SliverFillRemaining(
                    hasScrollBody: false,
                    child: _EmptyState(
                      filtered: query.isNotEmpty || source != null,
                      connected: controller.status.connectedCount,
                      onConnections: widget.onConnections,
                    ),
                  )
                else
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                    sliver: SliverList.builder(
                      itemCount: visible.length,
                      itemBuilder: (context, index) {
                        final message = visible[index];
                        final day = dateLabel(message.receivedAt);
                        final showDay =
                            index == 0 ||
                            dateLabel(visible[index - 1].receivedAt) != day;
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (showDay)
                              Padding(
                                padding: const EdgeInsets.fromLTRB(
                                  2,
                                  10,
                                  0,
                                  12,
                                ),
                                child: Text(
                                  day,
                                  style: const TextStyle(
                                    fontSize: 12,
                                    color: Color(0xFF849087),
                                  ),
                                ),
                              ),
                            _MessageCard(
                              message: message,
                              onTap: () => details(message),
                              onAction: (action) => perform(() async {
                                if (action == 'read') {
                                  await controller.update(
                                    message,
                                    isRead: !message.isRead,
                                  );
                                }
                                if (action == 'open') {
                                  toast(
                                    await controller.gateway.invoke(
                                      'openMessage',
                                      {
                                        'source': message.source.name,
                                        'target': message.target,
                                      },
                                    ) as String,
                                  );
                                }
                              }),
                            ),
                          ],
                        );
                      },
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Future<void> details(InboxMessage original) async {
    bool explicitReadChoice = false;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (sheetContext) => ListenableBuilder(
        listenable: widget.controller,
        builder: (context, _) {
          final message = widget.controller.messages.firstWhere(
            (m) => m.id == original.id,
            orElse: () => original,
          );
          return SafeArea(
            child: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 4, 24, 26),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        SourceIcon(message.source),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Text(
                            '${message.source.label} · ${dateLabel(message.receivedAt)} ${timeLabel(message.receivedAt)}',
                            style: const TextStyle(color: Color(0xFF65716A)),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    SelectableText(
                      message.title,
                      style: const TextStyle(
                        fontSize: 23,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    if (message.sender.isNotEmpty &&
                        message.sender != message.title)
                      Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: SelectableText(
                          message.sender,
                          style: const TextStyle(color: Color(0xFF65716A)),
                        ),
                      ),
                    const SizedBox(height: 18),
                    SelectableText(
                      message.body.isEmpty ? '通知未提供正文，请打开原应用查看。' : message.body,
                      style: const TextStyle(height: 1.7, fontSize: 16),
                    ),
                    const SizedBox(height: 24),
                    const Text(
                      '关闭详情后自动标为本地已读。',
                      style: TextStyle(fontSize: 12, color: Color(0xFF65716A)),
                    ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton(
                            style: FilledButton.styleFrom(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 4,
                              ),
                              textStyle: const TextStyle(fontSize: 12),
                            ),
                            onPressed: () => perform(() async {
                              final result =
                                  await widget.controller.gateway.invoke(
                                    'openMessage',
                                    {
                                      'source': message.source.name,
                                      'target': message.target,
                                    },
                                  ) as String;
                              toast(result);
                            }),
                            child: const Text('打开原应用'),
                          ),
                        ),
                        const SizedBox(width: 4),
                        Expanded(
                          child: FilledButton(
                            style: FilledButton.styleFrom(
                              backgroundColor: const Color(0xFFE2EDDF),
                              foregroundColor: const Color(0xFF194F46),
                              shape: const StadiumBorder(),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 4,
                              ),
                              textStyle: const TextStyle(fontSize: 12),
                            ),
                            onPressed: () => perform(() async {
                              explicitReadChoice = true;
                              await widget.controller.update(
                                message,
                                isRead: !message.isRead,
                              );
                            }),
                            child: Text(message.isRead ? '标为未读' : '标为已读'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
    if (!explicitReadChoice) {
      await perform(() => widget.controller.update(original, isRead: true));
    }
  }
}

class _MessageCard extends StatelessWidget {
  const _MessageCard({
    required this.message,
    required this.onTap,
    required this.onAction,
  });
  final InboxMessage message;
  final VoidCallback onTap;
  final ValueChanged<String> onAction;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(15, 16, 8, 16),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SourceIcon(message.source),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        if (!message.isRead) ...[
                          Container(
                            width: 6,
                            height: 6,
                            decoration: const BoxDecoration(
                              color: Color(0xFF278554),
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 6),
                        ],
                        Expanded(
                          child: Text(
                            message.title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: message.isRead
                                  ? FontWeight.w500
                                  : FontWeight.w700,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 7),
                    Text(
                      message.body.isEmpty ? '打开查看通知' : message.body,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 13,
                        height: 1.5,
                        color: Color(0xFF6F7A73),
                      ),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      '${message.source.label} · ${timeLabel(message.receivedAt)}',
                      style: const TextStyle(
                        fontSize: 11,
                        color: Color(0xFF87938A),
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(
                width: 30,
                height: 28,
                child: PopupMenuButton<String>(
                  tooltip: '消息操作',
                  padding: EdgeInsets.zero,
                  icon: const Icon(
                    Icons.more_horiz,
                    size: 20,
                    color: Color(0xFF87938A),
                  ),
                  onSelected: onAction,
                  itemBuilder: (_) => [
                    PopupMenuItem(
                      value: 'read',
                      child: Text(message.isRead ? '标为未读' : '标为已读'),
                    ),
                    const PopupMenuItem(value: 'open', child: Text('打开原应用')),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.filtered,
    required this.connected,
    required this.onConnections,
  });
  final bool filtered;
  final int connected;
  final VoidCallback onConnections;
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            filtered
                ? '没有匹配消息'
                : connected == 0
                ? '尚未接入消息来源'
                : '暂无消息',
            style: const TextStyle(fontSize: 15, color: Color(0xFF7D8980)),
          ),
          if (!filtered && connected == 0) ...[
            const SizedBox(height: 16),
            FilledButton(onPressed: onConnections, child: const Text('接入消息来源')),
          ],
        ],
      ),
    ),
  );
}

String timeLabel(DateTime time) =>
    '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
String dateLabel(DateTime time) {
  final now = DateTime.now();
  final day = DateTime(time.year, time.month, time.day);
  final today = DateTime(now.year, now.month, now.day);
  if (day == today) return '今天';
  if (day == DateTime(now.year, now.month, now.day - 1)) return '昨天';
  return '${time.year}年${time.month}月${time.day}日';
}
