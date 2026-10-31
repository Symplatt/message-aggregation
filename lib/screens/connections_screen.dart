import 'package:flutter/material.dart';

import '../aggregation_service.dart';
import '../models.dart';
import '../widgets/source_icon.dart';

class ConnectionsScreen extends StatefulWidget {
  const ConnectionsScreen({super.key, required this.controller});
  final InboxController controller;
  @override
  State<ConnectionsScreen> createState() => _ConnectionsScreenState();
}

class _ConnectionsScreenState extends State<ConnectionsScreen> {
  void toast(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> run(Future<void> Function() task) async {
    try {
      await task();
    } catch (e) {
      toast(readableError(e));
    }
  }

  Future<void> invoke(String method) => run(() async {
    await widget.controller.gateway.invoke(method);
    await widget.controller.reload();
  });

  Future<void> smsConsent() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('允许汇总短信？'),
        content: const Text(
          '讯息聚合需要读取和接收短信权限。首次接入导入最近 7 天的未读短信，此后汇总新收到的短信。内容仅保存在本机，不发送短信，也不修改系统短信的已读状态。\n\n部分系统或安装方式会限制短信权限，授权失败时可查看系统设置。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('暂不接入'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('继续授权'),
          ),
        ],
      ),
    );
    if (accepted == true) await invoke('requestSms');
  }

  @override
  Widget build(BuildContext context) {
    final status = widget.controller.status;
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 24),
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                '接入管理',
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.w600),
              ),
            ),
            IconButton(
              tooltip: '系统应用设置',
              onPressed: () => invoke('appSettings'),
              icon: const Icon(Icons.settings_outlined),
            ),
          ],
        ),
        const SizedBox(height: 24),
        sectionTitle('通知来源'),
        ...[MessageSource.wechat, MessageSource.qq].map(
          (source) => sourceCard(
            source: source,
            state: status.enabled[source] != true
                ? '已暂停'
                : !status.notificationAccess
                ? '未授权'
                : status.listenerConnected
                ? '监听中'
                : '等待连接',
            healthy: status.connected(source),
            trailing: Switch(
              value:
                  status.enabled[source] == true && status.notificationAccess,
              onChanged: (v) => run(() async {
                await widget.controller.setSource(source, v);
                if (v && !status.notificationAccess)
                  await invoke('notificationSettings');
              }),
            ),
          ),
        ),
        const SizedBox(height: 12),
        sectionTitle('短信来源'),
        sourceCard(
          source: MessageSource.sms,
          state: status.enabled[MessageSource.sms] != true
              ? '未启用'
              : !status.smsRead
              ? '未授权读取'
              : !status.smsReceive
              ? '仅定期读取'
              : '已授权',
          healthy: status.connected(MessageSource.sms),
          trailing: Switch(
            value: status.enabled[MessageSource.sms] == true && status.smsRead,
            onChanged: (v) {
              if (v) {
                smsConsent();
              } else {
                run(
                  () => widget.controller.setSource(MessageSource.sms, false),
                );
              }
            },
          ),
        ),
        sectionTitle('邮箱来源'),
        ...[MessageSource.qqmail, MessageSource.netease].map((source) {
          final accounts = status.accounts
              .where((a) => a.provider == source)
              .toList();
          return sourceCard(
            source: source,
            state: accounts.isEmpty
                ? '未接入'
                : accounts.any((a) => a.error.isNotEmpty)
                ? '需检查'
                : '已配置',
            healthy: status.connected(source),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ...accounts.map(
                  (a) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                a.address,
                                style: const TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                a.error.isNotEmpty
                                    ? a.error
                                    : a.lastSync == null
                                    ? '等待同步'
                                    : '同步时间：' +
                                          a.lastSync!
                                              .toLocal()
                                              .toString()
                                              .substring(0, 16),
                                style: TextStyle(
                                  fontSize: 11,
                                  color: a.error.isEmpty
                                      ? const Color(0xFF7D8980)
                                      : const Color(0xFFAD4F39),
                                ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          tooltip: '移除邮箱',
                          onPressed: () => removeAccount(a),
                          icon: const Icon(Icons.link_off, size: 20),
                        ),
                      ],
                    ),
                  ),
                ),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton(
                    onPressed: () => addAccount(source),
                    child: const Text('添加邮箱'),
                  ),
                ),
              ],
            ),
          );
        }),
        sourceCard(source: MessageSource.gmail, state: '待接入', healthy: false),
        const SizedBox(height: 12),
      ],
    );
  }

  Widget sectionTitle(String title) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Text(
      title,
      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
    ),
  );

  Widget sourceCard({
    required MessageSource source,
    required String state,
    required bool healthy,
    Widget? child,
    Widget? trailing,
  }) => Container(
    margin: const EdgeInsets.only(bottom: 12),
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            SourceIcon(
              source,
              size: 36,
              bytes: widget.controller.icons[source],
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                source.label,
                style: const TextStyle(
                  fontWeight: FontWeight.w500,
                  fontSize: 15,
                ),
              ),
            ),
            const SizedBox(width: 12),
            Text(
              state,
              textAlign: TextAlign.right,
              style: TextStyle(
                fontSize: 12,
                color: healthy
                    ? const Color(0xFF278554)
                    : const Color(0xFF87938A),
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 12), trailing],
          ],
        ),
        if (child != null) ...[
          const SizedBox(height: 12),
          Theme(
            data: Theme.of(context).copyWith(
              textButtonTheme: TextButtonThemeData(
                style: TextButton.styleFrom(
                  padding: EdgeInsets.zero,
                  alignment: Alignment.centerLeft,
                ),
              ),
            ),
            child: child,
          ),
        ],
      ],
    ),
  );

  Future<void> addAccount(MessageSource source) async {
    final saved = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) =>
          _AccountDialog(source: source, gateway: widget.controller.gateway),
    );
    if (saved == true) {
      await widget.controller.reload();
      toast('邮箱已保存，可在来源卡片查看同步状态');
    }
  }

  Future<void> removeAccount(MailAccount account) async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('移除这个邮箱？'),
        content: Text('将删除 ${account.address} 的本机配置和已汇总邮件。服务器上的邮件不受影响。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    if (accepted == true) {
      await run(() async {
        await widget.controller.gateway.invoke('removeAccount', {
          'id': account.id,
        });
        await widget.controller.reload();
      });
    }
  }
}

class _AccountDialog extends StatefulWidget {
  const _AccountDialog({required this.source, required this.gateway});
  final MessageSource source;
  final AggregationGateway gateway;
  @override
  State<_AccountDialog> createState() => _AccountDialogState();
}

class _AccountDialogState extends State<_AccountDialog> {
  final address = TextEditingController(), secret = TextEditingController();
  final form = GlobalKey<FormState>();
  bool saving = false;
  String? error;
  @override
  void dispose() {
    address.dispose();
    secret.dispose();
    super.dispose();
  }

  Future<void> save() async {
    if (!form.currentState!.validate()) return;
    setState(() {
      saving = true;
      error = null;
    });
    try {
      await widget.gateway.invoke('saveAccount', {
        'provider': widget.source.name,
        'address': address.text.trim(),
        'secret': secret.text.trim(),
      });
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) {
        setState(() {
          error = readableError(e);
          saving = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !saving,
    child: AlertDialog(
      title: Text('添加${widget.source.label}'),
      content: SizedBox(
        width: 360,
        child: SingleChildScrollView(
          child: Form(
            key: form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '请先在邮箱网页设置中开启 IMAP，并生成客户端授权码。',
                  style: TextStyle(fontSize: 13, height: 1.6),
                ),
                const SizedBox(height: 18),
                TextFormField(
                  controller: address,
                  enabled: !saving,
                  keyboardType: TextInputType.emailAddress,
                  autocorrect: false,
                  decoration: const InputDecoration(
                    labelText: '邮箱地址',
                    hintText: 'name@example.com',
                  ),
                  validator: (v) =>
                      v == null ||
                          !RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
                              .hasMatch(v.trim())
                      ? '请输入有效的邮箱地址'
                      : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: secret,
                  enabled: !saving,
                  obscureText: true,
                  autocorrect: false,
                  enableSuggestions: false,
                  decoration: const InputDecoration(
                    labelText: '客户端授权码',
                    helperText: '授权码加密保存在设备上',
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? '请输入授权码' : null,
                  onFieldSubmitted: (_) {
                    if (!saving) save();
                  },
                ),
                const SizedBox(height: 14),
                const Text(
                  '首次导入最近 200 封未读邮件；不修改服务器已读状态。相同邮箱再次添加会更新授权码。',
                  style: TextStyle(
                    fontSize: 11,
                    height: 1.6,
                    color: Color(0xFF7D8980),
                  ),
                ),
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 14),
                    child: Text(
                      error!,
                      style: const TextStyle(
                        color: Color(0xFFAD4F39),
                        fontSize: 12,
                      ),
                    ),
                  ),
                if (saving)
                  const Padding(
                    padding: EdgeInsets.only(top: 18),
                    child: LinearProgressIndicator(),
                  ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: saving ? null : () => Navigator.pop(context, false),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: saving ? null : save,
          child: Text(saving ? '验证并同步中…' : '连接邮箱'),
        ),
      ],
    ),
  );
}
