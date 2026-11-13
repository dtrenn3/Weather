# Radar

AccuWeather radar widget for the Galaxy S26 Ultra home screen.

## AccuWeather account

The AccuWeather app login cannot connect this widget. Premium membership inside the AccuWeather application has no sign-in that a third-party widget can use. AccuWeather's connector takes an API key from the [developer subscriptions page](https://developer.accuweather.com/). A free trial or Elite key is what their connector accepts.

Do not put that key in chat, in a screenshot, or in this repo. Set it as `ACCUWEATHER_API_KEY`. The Cursor connector is `.cursor/mcp.json`, which sends the key as `Authorization: Bearer ${env:ACCUWEATHER_API_KEY}` to `https://dataservice.accuweather.com/mcp`.

On the phone, open Radar and paste the same key. It is stored in encrypted preferences on the device.

## Home screen

The widget is four columns wide, two rows tall, and it can be dragged down to four rows on One UI. It shows the latest AccuWeather radar frame, temperature, RealFeel, the current condition, and MinuteCast when the key's plan includes it. Tap the map to open AccuWeather. Tap refresh to load a new frame. Frames also refresh about every 30 minutes.

If the key can sign in but the plan does not include radar, the widget keeps the observation and says so.

## Build

```bash
./gradlew :core:test :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it on the S26 Ultra, then touch and hold the home screen, choose Widgets, and add Radar.
