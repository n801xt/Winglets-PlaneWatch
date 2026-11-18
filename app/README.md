# PlaneWatch

Android app that notifies you when any aircraft registration you add takes off (and optionally when it lands).

## Build and install

1. Install Android Studio (Koala or newer).
2. File > Open, pick this `PlaneWatch` folder. Let Gradle sync (Android Studio creates the Gradle wrapper if it's missing).
3. Plug in your phone (USB debugging on) or start an emulator, then press Run.
   To get an installable file instead: Build > Build APK(s).

## Build the APK without Android Studio (GitHub Actions)

1. Create a free GitHub account and a new empty repository.
2. Upload the contents of this folder to it, including the hidden `.github` folder (on github.com: Add file > Upload files, and drag the whole folder in; or use git push).
3. Open the repo's Actions tab. The "Build APK" workflow runs automatically on each push (or run it manually with "Run workflow").
4. When it finishes (a few minutes), open the run and download the `PlaneWatch-debug-apk` artifact. Unzip it to get `app-debug.apk`.
5. Copy the APK to your phone, open it, and allow "install unknown apps" when asked.

## Using it

1. Allow notifications when prompted.
2. Type a registration (e.g. `N12345`, `G-ABCD`) and tap Add. Add as many as you like.
3. Turn Monitoring on. A small persistent notification appears while it runs; that's what keeps Android from killing it.
4. You get an alert when a watched aircraft goes from "not airborne" to "airborne".

## Radar, location and rare aircraft

- **Radar** (button on the main screen): shows aircraft around your phone's location on a live radar. Pick a range (10 to 200 nm), tap an aircraft for details. Red = rare type you selected, yellow = a registration you're watching, green = everything else. Lines show heading.
- **Rare aircraft** (button on the main screen): turn on alerts, choose how close (10 to 100 nm), then tick the types you want (A380, 747-8, B-52, Spitfire and more) or add any ICAO type code (e.g. `A225`, `F16`). There is also an "any military aircraft" option.
- You get one notification per aircraft when it enters your radius. It can alert again after it has been out of range for 15 minutes.
- Rare alerts run from the same Monitoring switch on the main screen, so keep Monitoring on.
- Location permission is requested the first time you open the radar or turn on rare alerts. Your position is rounded to about 1 km and sent to the free ADS-B feed to look up nearby aircraft; it is not stored anywhere else.

## How it works

- `MonitorService` is a foreground service that checks every 60 seconds.
- `AdsbClient` queries free community ADS-B feeds by registration (airplanes.live, falling back to adsb.lol).
- An aircraft counts as airborne if the feed reports a barometric altitude above zero (not "ground") or a ground speed above 60 kt.
- Alerts fire on state changes only, so you won't be spammed while a flight is in progress. If the feed can't be reached, the previous state is kept to avoid false alerts.

## Limitations

- Only aircraft with ADS-B transponders in range of volunteer receivers are visible. Aircraft that don't broadcast, or that are blocked from public feeds, won't show up.
- Monitoring stops if you reboot the phone; open the app to resume it.
- For rare alerts with the screen off, allow location "while using the app" and start Monitoring while the app is open. If Android restarts the service in the background it may lose location access until you open the app again.
- Some manufacturers aggressively kill background apps. If alerts stop, set the app to "Unrestricted" under Settings > Apps > PlaneWatch > Battery.
- The feeds are free community services with no guarantees; their terms and availability can change. To change the polling interval or feed URLs, edit `POLL_INTERVAL_MS` in `MonitorService.kt` and `bases` in `AdsbClient.kt`.
