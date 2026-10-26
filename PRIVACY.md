# Privacy policy

Last updated: 26 October 2026

Contour is an offline Android app. It collects no personal data and sends nothing anywhere.

- **No internet.** The app does not request the internet permission, so it cannot send or receive data over
  a network.
- **No analytics, ads, crash reporting or accounts.** None of these are built in.
- **Your profiles stay on your phone.** EQ profiles and settings are stored in the app's private storage.
  App data is excluded from Android cloud backup and device-to-device transfer. Contour also makes local
  copies of library files under `Android/data/<package>/files/backup/` in its app-specific external files
  directory (including a pre-v1 copy and optional review dumps). These are not cloud backups or a substitute
  for exporting profiles; they remain readable through a connected computer while the app is installed.
  Existing copies are not deleted by this policy. Uninstalling the app normally deletes app-specific data,
  including those local copies.
- **USB.** The app talks only to the supported USB DAC you plug in, and only after you allow access in
  Android's USB permission dialog. It reads the DAC's EQ settings and writes new ones when you ask it to.
- **DAC factory reset.** Before a factory reset, Contour saves a copy of the DAC's USER slots, their names and its
  settings as a text file in the app's private storage, so it can write them back afterwards. Uninstalling the app
  deletes it.
- **Clipboard.** When you open the new-profile sheet, the app reads the clipboard on the phone to offer pasting an
  EQ profile, and reads it again if you plug in a different DAC while the sheet is open. Nothing leaves the phone.
- **Sharing.** If you use SHARE on a profile, Android's share sheet sends the profile text to the app you pick.
- **Files.** SAVE .TXT writes a profile to the file and place you pick in Android's file dialog. When you open or
  share a .txt file with Contour, it reads only that file, shows you what it found and imports it after you
  confirm; OPEN FILE in the new-profile sheet reads the file you pick. The app has no storage permission.
- **Permissions.** Vibration, for haptic feedback. Access to the DAC is granted in Android's USB dialog (see USB
  above). Nothing else.
- **This website.** contoureq.app counts anonymous visits with Vercel Web Analytics, without cookies. According to
  Vercel, a visitor is identified only by a hash created from the request, which is discarded after 24 hours, and the
  counts are not tied to any individual or IP address.

Questions: open an issue at https://github.com/Chronosauros/contour/issues.
