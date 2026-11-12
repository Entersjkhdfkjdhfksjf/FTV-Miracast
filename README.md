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

## Web control + replacement home screen (step 2)
`HomeActivity` is a minimal home screen. It starts a small HTTP server (port 8080) and shows
`http://<stick-ip>:8080/?k=<key>` on the TV. The page lists apps, can launch the stock
display-mirroring screen (search "mirror|wfd|display|cast" to find the exact Fire OS activity),
and opens `android.settings.*` screens. Every request needs the key shown on the TV.

Make it the default home (via ADB, once):
```
adb shell cmd package set-home-activity dev.wfdsink/.HomeActivity
```
Optional, to get rid of Amazon's launcher (reversible; do this only after ours is working):
```
adb shell pm disable-user --user 0 com.amazon.tv.launcher
adb shell pm enable com.amazon.tv.launcher     # undo
```
