import 'package:flutter/material.dart';

import 'aggregation_service.dart';
import 'screens/inbox_screen.dart';

void main() =>
    runApp(AggregationApp(controller: InboxController(AndroidGateway())));

const forest = Color(0xFF194F46);
const canvas = Color(0xFFF6F7F2);

class AggregationApp extends StatelessWidget {
  const AggregationApp({super.key, required this.controller});
  final InboxController controller;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '讯息聚合',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      scaffoldBackgroundColor: canvas,
      colorScheme: ColorScheme.fromSeed(
        seedColor: forest,
        brightness: Brightness.light,
      ).copyWith(primary: forest, surface: canvas),
      appBarTheme: const AppBarTheme(
        backgroundColor: canvas,
        surfaceTintColor: Colors.transparent,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: Colors.white,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 18,
          vertical: 16,
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
      navigationBarTheme: const NavigationBarThemeData(
        backgroundColor: Colors.white,
        indicatorColor: Color(0xFFE2EDDF),
      ),
    ),
    home: AppHome(controller: controller),
  );
}

class AppHome extends StatefulWidget {
  const AppHome({super.key, required this.controller});
  final InboxController controller;
  @override
  State<AppHome> createState() => _AppHomeState();
}

class _AppHomeState extends State<AppHome> with WidgetsBindingObserver {
  int tab = 0;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.controller.start();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    widget.controller.setForeground(state == AppLifecycleState.resumed);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) => Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: InboxScreen(
              controller: widget.controller,
              tab: tab,
              onConnections: () => setState(() => tab = 1),
            ),
          ),
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (value) => setState(() => tab = value),
        destinations: [
          NavigationDestination(
            icon: Badge(
              isLabelVisible: widget.controller.unreadCount > 0,
              label: Text('${widget.controller.unreadCount}'),
              child: const Icon(Icons.inbox_outlined),
            ),
            selectedIcon: const Icon(Icons.inbox_rounded),
            label: '收件箱',
          ),
          const NavigationDestination(
            icon: Icon(Icons.tune_outlined),
            selectedIcon: Icon(Icons.tune),
            label: '接入管理',
          ),
        ],
      ),
    ),
  );
}
