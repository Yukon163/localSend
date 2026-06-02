import 'package:flutter_test/flutter_test.dart';
import 'package:localsend_flutter/main.dart';

void main() {
  testWidgets('shows LocalSend shell', (tester) async {
    await tester.pumpWidget(const LocalSendFlutterApp());

    expect(find.text('LocalSend Flutter'), findsOneWidget);
    expect(find.text('Devices'), findsOneWidget);
    expect(find.text('Send'), findsOneWidget);
  });
}
