# WearSync

Get your iPhone's notifications on a Wear OS watch.

The watch talks to the iPhone directly over Bluetooth. There's nothing to install on the iPhone.

> **Early development.** This hasn't been tested widely yet. Expect rough edges.

## What works

- iPhone notifications appear on the watch (messages, mail, calendar, any app)
- Accept/decline incoming calls, and clear notifications from the watch
- Notifications dismissed on the iPhone disappear from the watch
- Reconnects automatically and restarts after the watch reboots

## What doesn't (iOS limits)

- No replying to messages from the watch
- No talking on calls through the watch (you can only accept or decline)
- No Google Play Store or Assistant through the iPhone. These only work when the watch is on Wi-Fi or LTE.

## Requirements

- A Wear OS 4+ watch (tested target: Galaxy Watch6 Classic)
- The watch must **already be set up**. Wear OS can only be set up with an Android phone, so borrow one for the first setup if you need to.
- A computer with [ADB](https://developer.android.com/tools/releases/platform-tools) to install the app

> ⚠️ On Galaxy watches, **don't unpair the watch from the Galaxy Wearable app**. That usually factory-resets the watch, and you'd need an Android phone again to set it up.

## Install

1. On the watch go to **Settings → About watch → Software information** and tap **Software version** about 7 times to turn on developer options.
2. Go to **Settings → Developer options** and turn on **ADB debugging** and **Wireless debugging**. Connect the watch to the same Wi-Fi network as your computer.
3. Download the latest `WearSync.apk` from [Releases](../../releases).
4. In **Wireless debugging**, tap **Pair new device**, then run:
   ```bash
   adb pair <ip>:<pairing-port>     # enter the code shown on the watch
   adb connect <ip>:<port>          # ip:port from the main Wireless debugging screen
   adb install WearSync.apk
   ```

## Connect to your iPhone

1. On the watch, open **WearSync**, tap **Start**, and allow the permissions.
2. On the iPhone, go to **Settings → Bluetooth**. Under *Other Devices*, tap your watch.
3. Accept the **pairing request**.
4. Tap **Allow** when asked to share notifications.

The watch should now say **Connected**. From then on it reconnects by itself.

## Troubleshooting

**The watch doesn't appear in iPhone Settings.** Make sure WearSync says "Waiting for iPhone". If it does, try giving the watch a shorter name. Long names don't fit in the Bluetooth broadcast.

**Connected, but no notifications.** On the iPhone go to **Settings → Bluetooth → ⓘ** next to the watch and turn on **Share System Notifications**.

**It stopped working.** On the watch, tap **Stop** then **Start**. If that doesn't help, *Forget This Device* on the iPhone, then pair again.

## Contributing

Build with JDK 17 and the Android SDK (platform 35): `./gradlew :wear:assembleDebug`. The APK lands in `wear/build/outputs/apk/debug/`.

## License

[MIT](LICENSE)
