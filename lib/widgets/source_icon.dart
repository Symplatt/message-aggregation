import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../models.dart';

extension SourceVisual on MessageSource {
  Color get color => switch (this) {
    MessageSource.wechat => const Color(0xFF278554),
    MessageSource.qq => const Color(0xFF2B7EA8),
    MessageSource.qqmail => const Color(0xFFAF7B26),
    MessageSource.netease => const Color(0xFFC35B4B),
    MessageSource.gmail => const Color(0xFF6C6AA1),
    MessageSource.sms => const Color(0xFF5578AA),
  };
  IconData get icon => switch (this) {
    MessageSource.wechat => Icons.forum_rounded,
    MessageSource.qq => Icons.chat_bubble_rounded,
    MessageSource.qqmail => Icons.mark_email_unread_rounded,
    MessageSource.netease => Icons.alternate_email_rounded,
    MessageSource.gmail => Icons.mail_outline_rounded,
    MessageSource.sms => Icons.sms_rounded,
  };
}

class SourceIcon extends StatelessWidget {
  const SourceIcon(this.source, {super.key, this.size = 44, this.bytes});
  final MessageSource source;
  final double size;
  final Uint8List? bytes;
  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    decoration: BoxDecoration(
      color: source.color.withValues(alpha: .10),
      borderRadius: BorderRadius.circular(size * .30),
    ),
    child: bytes == null
        ? Icon(source.icon, color: source.color, size: size * .5)
        : ClipRRect(
            borderRadius: BorderRadius.circular(size * .30),
            child: Image.memory(
              bytes!,
              width: size,
              height: size,
              semanticLabel: source.label,
            ),
          ),
  );
}
