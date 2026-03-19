import 'dart:convert';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_callkit_incoming/entities/entities.dart';
import 'package:flutter_callkit_incoming/flutter_callkit_incoming.dart';
import 'package:flutter_callkit_incoming_example/app_router.dart';
import 'package:flutter_callkit_incoming_example/navigation_service.dart';
import 'package:http/http.dart';

class CallingPage extends StatefulWidget {
  const CallingPage({super.key});

  @override
  State<StatefulWidget> createState() {
    return CallingPageState();
  }
}

class CallingPageState extends State<CallingPage> {
  late CallKitParams? calling;

  Timer? _timer;
  int _start = 0;
  int? _audioRoute; // 현재 오디오 라우트
  StreamSubscription? _eventSub;

  @override
  void initState() {
    super.initState();
    _eventSub = FlutterCallkitIncoming.onEvent.listen((event) {
      if (!mounted) return;
      if (event?.event == Event.actionCallAudioStateChanged) {
        setState(() {
          _audioRoute = event!.body['audioRoute'] as int?;
        });
      }
    });
  }

  void startTimer() {
    const oneSec = Duration(seconds: 1);
    _timer = Timer.periodic(
      oneSec,
      (Timer timer) {
        setState(() {
          _start++;
        });
      },
    );
  }

  String intToTimeLeft(int value) {
    int h, m, s;
    h = value ~/ 3600;
    m = ((value - h * 3600)) ~/ 60;
    s = value - (h * 3600) - (m * 60);
    String hourLeft = h.toString().length < 2 ? '0$h' : h.toString();
    String minuteLeft = m.toString().length < 2 ? '0$m' : m.toString();
    String secondsLeft = s.toString().length < 2 ? '0$s' : s.toString();
    String result = "$hourLeft:$minuteLeft:$secondsLeft";
    return result;
  }


  @override
  Widget build(BuildContext context) {
    final params = jsonDecode(jsonEncode(
        ModalRoute.of(context)!.settings.arguments as Map<dynamic, dynamic>));
    print(ModalRoute.of(context)!.settings.arguments);
    calling = CallKitParams.fromJson(params);

    var timeDisplay = intToTimeLeft(_start);

    return Scaffold(
      body: SizedBox(
        height: MediaQuery.of(context).size.height,
        width: double.infinity,
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Text(timeDisplay),
              const Text('Calling...'),
              TextButton(
                style: ButtonStyle(
                  foregroundColor:
                      MaterialStateProperty.all<Color>(Colors.blue),
                ),
                onPressed: () async {
                  if (calling != null) {
                    await makeFakeConnectedCall(calling!.id!);
                    startTimer();
                  }
                },
                child: const Text('Fake Connected Call'),
              ),
              const SizedBox(height: 24),
              Text('Audio Route: ${_audioRouteLabel(_audioRoute)}',
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _audioRouteButton('Earpiece', 1, Icons.hearing),
                  const SizedBox(width: 8),
                  _audioRouteButton('Speaker', 8, Icons.volume_up),
                  const SizedBox(width: 8),
                  _audioRouteButton('Bluetooth', 2, Icons.bluetooth_audio),
                ],
              ),
              const SizedBox(height: 24),
              TextButton(
                style: ButtonStyle(
                  foregroundColor:
                      MaterialStateProperty.all<Color>(Colors.blue),
                ),
                onPressed: () async {
                  if (calling != null) {
                    await makeEndCall(calling!.id!);
                    calling = null;
                  }
                  NavigationService.instance.goBack();
                  await requestHttp('END_CALL');
                },
                child: const Text('End Call'),
              )
            ],
          ),
        ),
      ),
    );
  }


  // 요청만 하고 반환 - UI는 actionCallAudioStateChanged 이벤트로 업데이트
  Future<void> changeAudioRoute(int route) async {
    await FlutterCallkitIncoming.setAudioRoute(route);
  }

  Widget _audioRouteButton(String label, int route, IconData icon) {
    final isActive = _audioRoute == route;
    return ElevatedButton.icon(
      icon: Icon(icon),
      label: Text(label),
      style: ElevatedButton.styleFrom(
        backgroundColor: isActive ? Colors.blue : null,
        foregroundColor: isActive ? Colors.white : null,
      ),
      onPressed: () => changeAudioRoute(route),
    );
  }

  String _audioRouteLabel(int? route) {
    switch (route) {
      case 1: return 'Earpiece';
      case 2: return 'Bluetooth';
      case 8: return 'Speaker';
      default: return 'Unknown';
    }
  }

  Future<void> makeFakeConnectedCall(id) async {
    await FlutterCallkitIncoming.setCallConnected(id);
  }

  Future<void> makeEndCall(id) async {
    await FlutterCallkitIncoming.endCall(id);
  }

  //check with https://webhook.site/#!/2748bc41-8599-4093-b8ad-93fd328f1cd2
  Future<void> requestHttp(content) async {
    get(Uri.parse(
        'https://webhook.site/2748bc41-8599-4093-b8ad-93fd328f1cd2?data=$content'));
  }

  @override
  void dispose() {
    _timer?.cancel();
    _eventSub?.cancel();
    if (calling != null) FlutterCallkitIncoming.endCall(calling!.id!);
    super.dispose();
  }
}
