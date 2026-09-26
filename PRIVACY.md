# Privacy policy

Last updated: 25 September 2026

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
- **Clipboard.** When you open the new-profile sheet, the app reads the clipboard once, on the phone, to offer
  pasting an EQ profile. Nothing leaves the phone.
- **Sharing.** If you use SHARE on a profile, Android's share sheet sends the profile text to the app you pick.
- **Permissions.** USB host access (for the DAC) and vibration (for haptic feedback). Nothing else.

Questions: open an issue at https://github.com/Chronosauros/contour/issues.
