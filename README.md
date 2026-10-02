# Codex

Background bridge for controlling this Android device from Codex.

Runtime transport:

Termux Ubuntu -> phone_adb.sh -> Ubuntu /usr/bin/adb -> adbd on 127.0.0.1:5555 -> shell-protected com.kaori.codex bridge -> Android APIs / Accessibility.

This runtime path does not depend on Android Code Studio or its SSH service. ACS SSH is only used while developing and building this project.

The exported bridge receiver remains protected by android.permission.DUMP, so ordinary third-party apps cannot invoke it. The APK itself remains an ordinary Android UID; receiving a broadcast from ADB shell does not transfer shell UID 2000 to app code.

For bridge ADB operations, phone_adb.sh starts the Termux Ubuntu ADB server on 127.0.0.1:5037 and connects 127.0.0.1:5555 first. The app can then use adb.status and adb.shell as an ADB-server client. Commands sent through adb.shell are executed by adbd as Android shell, while the app still keeps its normal UID.
