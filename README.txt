TV Audio
Sends your Sony Bravia's sound over WiFi to your phone, so you can listen with wired earphones.
One APK, installed on both the TV and the phone.

BUILD THE APK (pick one)

A) No computer needed, using GitHub:
1. Create a free GitHub account and a new repository.
2. Upload all files from this zip (keep the folders as they are, including .github).
   If the .github folder didn't upload, use "Add file > Create new file", name it
   .github/workflows/build.yml and paste in the contents of that file from this zip.
3. Open the Actions tab. The "Build APK" job runs by itself after upload; wait ~3 minutes.
4. Open the finished run and download "TvAudio-apk". Unzip it to get app-debug.apk.

B) With a computer: open this folder in Android Studio and choose Build > Build APK(s).

INSTALL
Phone: open app-debug.apk and allow installing from this source.
TV: install the "Downloader" app (by AFTVnews) from the TV's Play Store, allow it to install
unknown apps, and enter a direct download link to the APK (for example a Google Drive or
GitHub release link). Or copy the APK to a USB stick and open it with a TV file manager.

USE
1. Connect the TV and phone to the same WiFi.
2. On the TV: open TV Audio, press "Send TV sound", allow audio access and the capture prompt.
3. On the phone: plug in the earphones, open TV Audio, press "Listen here".
   The phone finds the TV by itself. If it doesn't, type the address shown on the TV screen.
4. Turn the TV volume down; volume on the phone controls the earphones.

LIMITS
- Needs Android TV 10 or newer on the TV (most Bravias from 2020 on).
- Apps that block capture (Netflix, Disney+, some others) send silence. Live antenna/HDMI
  input may also not be capturable on some models; app content (YouTube, browsers, IPTV
  players) usually works.
- Delay is roughly 0.1–0.2 s.
- If pressing "Send" says the TV has no capture screen, connect via adb from a computer and run:
  adb shell appops set com.gaz.tvaudio PROJECT_MEDIA allow
