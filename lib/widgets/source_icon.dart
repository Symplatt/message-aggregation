import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../models.dart';

extension SourceVisual on MessageSource {
  Color get color => switch (this) {
    MessageSource.wechat => const Color(0xFF278554),
    MessageSource.qq => const Color(0xFF2B7EA8),
    MessageSource.qqmail => const Color(0xFFAF7B26),
    MessageSource.netease => const Color(0xFFC35B4B),
    MessageSource.gmail => const Color(0xFFAF514B),
    MessageSource.sms => const Color(0xFF388567),
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
        ? Semantics(
            label: source.label,
            child: Padding(
              padding: EdgeInsets.all(size * .16),
              child: CustomPaint(painter: _SourceSilhouette(source)),
            ),
          )
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

// Use vector silhouettes so message badges remain legible at small sizes.
class _SourceSilhouette extends CustomPainter {
  _SourceSilhouette(this.source);
  final MessageSource source;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 32, size.height / 32);
    final ink = Paint()..color = source.color;
    final cutout = Paint()
      ..color = Color.alphaBlend(
        source.color.withValues(alpha: .10),
        Colors.white,
      );
    switch (source) {
      case MessageSource.wechat:
        canvas.drawOval(const Rect.fromLTWH(1, 3, 23, 19), ink);
        canvas.drawPath(
          Path()
            ..moveTo(5, 17)
            ..lineTo(3, 25)
            ..lineTo(12, 20)
            ..close(),
          ink,
        );
        canvas.drawOval(const Rect.fromLTWH(12, 12, 20, 17), cutout);
        canvas.drawOval(const Rect.fromLTWH(14, 14, 18, 15), ink);
        canvas.drawPath(
          Path()
            ..moveTo(26, 26)
            ..lineTo(30, 31)
            ..lineTo(29, 23)
            ..close(),
          ink,
        );
        for (final point in [
          const Offset(8, 10),
          const Offset(16, 10),
          const Offset(20, 20),
          const Offset(26, 20),
        ]) {
          canvas.drawCircle(point, 1.3, cutout);
        }
      case MessageSource.qq:
        canvas.drawOval(const Rect.fromLTWH(7, 2, 18, 28), ink);
        canvas.drawOval(const Rect.fromLTWH(10, 17, 12, 11), cutout);
        canvas.drawOval(const Rect.fromLTWH(11, 7, 4, 6), cutout);
        canvas.drawOval(const Rect.fromLTWH(17, 7, 4, 6), cutout);
        canvas.drawCircle(const Offset(13.5, 10), .8, ink);
        canvas.drawCircle(const Offset(18.5, 10), .8, ink);
        canvas.drawOval(const Rect.fromLTWH(11, 13, 10, 3), cutout);
        canvas.drawPath(
          Path()
            ..moveTo(8, 16)
            ..quadraticBezierTo(0, 27, 5, 27)
            ..lineTo(10, 22)
            ..moveTo(24, 16)
            ..quadraticBezierTo(32, 27, 27, 27)
            ..lineTo(22, 22),
          ink,
        );
        canvas.drawOval(const Rect.fromLTWH(5, 27, 11, 4), ink);
        canvas.drawOval(const Rect.fromLTWH(16, 27, 11, 4), ink);
      case MessageSource.sms:
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            const Rect.fromLTWH(2, 4, 28, 22),
            const Radius.circular(9),
          ),
          ink,
        );
        canvas.drawPath(
          Path()
            ..moveTo(7, 22)
            ..lineTo(5, 30)
            ..lineTo(15, 25)
            ..close(),
          ink,
        );
        for (final x in [9.0, 16.0, 23.0]) {
          canvas.drawCircle(Offset(x, 15), 1.5, cutout);
        }
      case MessageSource.qqmail:
        ink
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.8
          ..strokeJoin = StrokeJoin.round;
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            const Rect.fromLTWH(2, 6, 28, 21),
            const Radius.circular(3),
          ),
          ink,
        );
        canvas.drawPath(
          Path()
            ..moveTo(3, 8)
            ..lineTo(16, 19)
            ..lineTo(29, 8),
          ink,
        );
      case MessageSource.netease:
        final text = TextPainter(
          text: TextSpan(
            text: '邮',
            style: TextStyle(
              color: source.color,
              fontSize: 29,
              fontWeight: FontWeight.w700,
              height: 1,
            ),
          ),
          textDirection: TextDirection.ltr,
        )..layout();
        text.paint(
          canvas,
          Offset((32 - text.width) / 2, (32 - text.height) / 2),
        );
      case MessageSource.gmail:
        ink
          ..style = PaintingStyle.stroke
          ..strokeWidth = 4.5
          ..strokeCap = StrokeCap.square
          ..strokeJoin = StrokeJoin.round;
        canvas.drawPath(
          Path()
            ..moveTo(4, 26)
            ..lineTo(4, 7)
            ..lineTo(16, 17)
            ..lineTo(28, 7)
            ..lineTo(28, 26),
          ink,
        );
    }
  }

  @override
  bool shouldRepaint(_SourceSilhouette oldDelegate) =>
      source != oldDelegate.source;
}
