Keno History Reader - starter Android project

What this prototype does:
- Requests Android's screen-capture permission when you tap Start screen scan.
- Runs on-device ML Kit text recognition on periodic screen frames.
- Attempts to extract round IDs and 20 unique numbers from OCR text.
- Stores accepted rounds locally in SQLite and ignores duplicate round IDs.
- Displays round history, frequency, hot/cold numbers, current gaps, and a heuristic top-20 list.
- Exports the saved rounds to CSV in the app's private external-files folder.

Important limitations:
- This is a STARTER SOURCE PROJECT, not a compiled APK and not yet a verified production app.
- OCR extraction is heuristic and depends on the exact Shamo.Bet results layout. It may miss or misread rounds. Inspect saved results before trusting the statistics.
- This prototype does not yet implement robust per-row OCR geometry, automatic missed-round reconciliation, multiple model comparison, or walk-forward backtesting. Those are next development steps.
- Screen capture stops when the app is closed. Android may restrict capture based on device version and app lifecycle.
- Keno outcomes are random; historical analysis cannot guarantee future numbers.

Build:
1. Open this folder in Android Studio.
2. Allow Gradle to sync and install the app on your Android phone.
3. Tap Start screen scan and approve the Android system screen-sharing prompt.
4. Switch to Shamo.Bet Fast Keno history and scroll slowly.
5. Return to the app, tap Stop scan, then Show saved history and analysis.
6. Check the CSV export if you want to inspect the captured rows.

This source is a development prototype and should be tested on your exact Android version and Shamo.Bet screen before use.
