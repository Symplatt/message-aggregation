import 'package:flutter/material.dart';

// Follows inch_light's rounded swipe background and restores the gesture after a failed save.
class SwipeDeleteCard extends StatefulWidget {
  const SwipeDeleteCard({
    super.key,
    required this.id,
    required this.child,
    required this.onDeleted,
  });
  final String id;
  final Widget child;
  final Future<void> Function() onDeleted;

  @override
  State<SwipeDeleteCard> createState() => _SwipeDeleteCardState();
}

class _SwipeDeleteCardState extends State<SwipeDeleteCard> {
  bool moving = false;
  bool dismissed = false;
  int attempt = 0;

  Future<void> delete(DismissDirection direction) async {
    setState(() => dismissed = true);
    try {
      await widget.onDeleted();
      // A new message can keep this conversation mounted while deletion is saving.
      if (mounted) {
        setState(() {
          dismissed = false;
          moving = false;
          attempt++;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          dismissed = false;
          moving = false;
          attempt++;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => dismissed
      ? const SizedBox.shrink()
      : Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            clipBehavior: moving ? Clip.antiAlias : Clip.none,
            child: Stack(
              children: [
                if (moving)
                  const Positioned.fill(
                    child: ColoredBox(
                      color: Color(0xFFAD4F39),
                      child: Align(
                        alignment: Alignment.centerRight,
                        child: Padding(
                          padding: EdgeInsets.symmetric(horizontal: 20),
                          child: Icon(
                            Icons.delete_outline,
                            color: Colors.white,
                          ),
                        ),
                      ),
                    ),
                  ),
                Dismissible(
                  key: ValueKey('${widget.id}:$attempt'),
                  direction: DismissDirection.endToStart,
                  onUpdate: (details) {
                    if (moving != (details.progress > 0))
                      setState(() => moving = details.progress > 0);
                  },
                  onDismissed: delete,
                  child: widget.child,
                ),
              ],
            ),
          ),
        );
}
