# Codex

Background bridge for controlling this Android device from Codex.

Runtime transport:

Termux Ubuntu -> phone_adb.sh -> Ubuntu /usr/bin/adb -> adbd on 127.0.0.1:5555 -> shell-protected com.kaori.codex bridge -> Android APIs / Accessibility.

This runtime path does not depend on Android Code Studio or its SSH service. ACS SSH is only used while developing and building this project.

The exported bridge receiver remains protected by android.permission.DUMP, so ordinary third-party apps cannot invoke it. The APK itself remains an ordinary Android UID; receiving a broadcast from ADB shell does not transfer shell UID 2000 to app code.

For bridge ADB operations, phone_adb.sh starts the Termux Ubuntu ADB server on 127.0.0.1:5037 and connects 127.0.0.1:5555 first. The app can then use adb.status and adb.shell as an ADB-server client. Commands sent through adb.shell are executed by adbd as Android shell, while the app still keeps its normal UID.

Visual game automation:

- `ui.ocr` captures one in-memory frame through AccessibilityService. Use `language=zh`, `language=en`, or `language=ja`; `left`, `top`, `right`, and `bottom` select a normalized region, and `maxDimension` bounds OCR work.
- Store a PNG/JPEG template with `file.write` or the `file.write_begin`/`file.write_chunk`/`file.write_commit` flow, then call `ui.template_match` or `ui.template_tap` with `templatePath`. The result includes `matched`, `confidence`, normalized center coordinates, bounds, and an expiring `frameId`.
- `ui.tap`, `ui.double_tap`, `ui.long_press`, `ui.swipe`, and `ui.multi_gesture` accept normalized display or foreground-window coordinates. A returned `verified` value means the requested post-action evidence was observed; dispatch alone is not treated as visual success.
- Screen pixels are analyzed in memory and released after each request. Template matching is a bounded grayscale sampler so it works offline without an OpenCV native library. All bridge requests retain the existing DUMP permission gate and optional `targetPackage` foreground check.
