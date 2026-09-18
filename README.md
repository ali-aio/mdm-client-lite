# aio-mdm-client-lite

A standalone test app for the AIO MDM-lite library: a live status panel (Reporting / Not
reporting, last check-in, queued events), test buttons (check in now, crash, freeze, JS
error, bad URL, blank page) and a WebView with a sample menu page. Works on phones and on
TV boxes with a remote.

Needs `../aio-mdm-lite` checked out next to it. Build against a server:

    ./gradlew :app:assembleDebug \
      -PmdmServerUrl=https://mdm-stage.dev.aioapp.com -PmdmEnrollToken=enr_...

Optional `-PdemoUrl=https://...` loads a real page instead of the sample menu.
The same tests over adb:

    adb shell am start --activity-single-top -n com.aioapp.mdmlite.demo/.DemoActivity \
        --es trigger crash|anr|jserror|badurl|blank|home
