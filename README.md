# FTV-Miracast

Experimental Miracast (Wi-Fi Display) receiver for the Fire TV Stick, aimed at Samsung DeX.

Status: **step 1, advertise-only test.** No video yet.

- `MainActivity` sets Wi-Fi Display sink info via reflection (`setWFDInfo`), creates a Wi-Fi Direct group, and logs everything (`adb logcat -s WFDTEST`).
- `WfdRtspClient` is the sink-side RTSP handshake (M1 to M7). Untested.

## Try it
```
adb install -r app-debug.apk
adb shell pm grant dev.wfdsink android.permission.ACCESS_FINE_LOCATION
adb shell pm grant dev.wfdsink android.permission.CONFIGURE_WIFI_DISPLAY   # may be refused
adb shell am start -n dev.wfdsink/.MainActivity
adb logcat -s WFDTEST
```
Then check whether the stick shows up in Smart View / DeX on the tablet.

## Next
RTP/MPEG-TS receiver, `MediaCodec` H.264 decode to a `SurfaceView`, LPCM to `AudioTrack`.
