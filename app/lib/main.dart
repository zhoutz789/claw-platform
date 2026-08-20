import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'l10n/app_localizations.dart';

/// S0 骨架：三语框架 + 路由占位。
/// S1 起按域拆分：features/{auth, home, swap, asset, account, station_app}
void main() {
  runApp(const ClawApp());
}

class ClawApp extends StatelessWidget {
  const ClawApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Claw',
      supportedLocales: AppLocalizations.supportedLocales,
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: ThemeData(colorSchemeSeed: const Color(0xFF00A86B)),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.appTitle)),
      body: Center(child: Text(l10n.scaffoldReady)),
    );
  }
}
