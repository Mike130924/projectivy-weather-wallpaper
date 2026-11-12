# Christmas GSD Weather Plugin — build status

## Christmas overlay redesign — source 2.8.4

- Replaced the legacy top-left neon weather overlay with the centred Christmas weather card.
- Reference geometry: 4K X=1080, Y=160, W=1680, H=920, rendered at half scale on the 1920x1080 canvas.
- Styling: contained deep-burgundy glass panel, gold trim and warm cream typography.
- Current conditions and feels-like/high/low/wind are laid out in separate cells.
- Seven daily forecast columns are supported and populated with six days after today (today's high/low are in the main metrics).
- The forecast request remains seven calendar days including today; the parser now correctly supplies all six following days.
- The card is transparent outside its bounds; no full-frame scrim or colour grading is applied to the Christmas wallpaper.
- The card clock uses 24-hour HH:mm and includes the date.
- All existing SettingsFragment categories and setting actions are retained; this redesign changes the rendered weather card, not the settings navigation.

## Verification status

This is a source package, not a verified APK. Run the GitHub Actions Build APK workflow to perform the Android/Gradle build. Install the resulting APK and check the card and settings on the target TV before calling the redesign accepted.
