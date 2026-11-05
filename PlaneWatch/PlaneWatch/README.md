# PlaneWatch

Android app that notifies you when any aircraft registration you add takes off (and optionally when it lands).

## Build and install

1. Install Android Studio (Koala or newer).
2. File > Open, pick this `PlaneWatch` folder. Let Gradle sync (Android Studio creates the Gradle wrapper if it's missing).
3. Plug in your phone (USB debugging on) or start an emulator, then press Run.
   To get an installable file instead: Build > Build APK(s).

## Using it

1. Allow notifications when prompted.
2. Type a registration (e.g. `N12345`, `G-ABCD`) and tap Add. Add as many as you like.
3. Turn Monitoring on. A small persistent notification appears while it runs; that's what keeps Android from killing it.
4. You get an alert when a watched aircraft goes from "not airborne" to "airborne".

## How it works

- `MonitorService` is a foreground service that checks every 60 seconds.
- `AdsbClient` queries free community ADS-B feeds by registration (airplanes.live, falling back to adsb.lol).
- An aircraft counts as airborne if the feed reports a barometric altitude above zero (not "ground") or a ground speed above 60 kt.
- Alerts fire on state changes only, so you won't be spammed while a flight is in progress. If the feed can't be reached, the previous state is kept to avoid false alerts.

## Limitations

- Only aircraft with ADS-B transponders in range of volunteer receivers are visible. Aircraft that don't broadcast, or that are blocked from public feeds, won't show up.
- Monitoring stops if you reboot the phone; open the app to resume it.
- Some manufacturers aggressively kill background apps. If alerts stop, set the app to "Unrestricted" under Settings > Apps > PlaneWatch > Battery.
- The feeds are free community services with no guarantees; their terms and availability can change. To change the polling interval or feed URLs, edit `POLL_INTERVAL_MS` in `MonitorService.kt` and `bases` in `AdsbClient.kt`.
