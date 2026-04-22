# Liquid Browser – Konzeptstand

Dieses Repo ist auf ein iOS-Browser-Konzept im **Dark Mode** ausgelegt:

- **Liquid-Glass UI** mit zwei unteren Bubbles (Optionen + URL-Leiste).
- **Safari-ähnlicher Look** in modernem, cleanem Stil.
- **Option Sheet** mit:
  - Built-in Ad Blocker
  - Anti-New-Tab gegen Werbe-Popups
  - Proxy-Support mit Liste und Auto-Wechsel
  - Developer-Tools Umschalter
- **Devtools Network Recorder** (Start/Stop/Clear, URL + Status).
- **iOS Player + PiP** als integrierte Video-Vorschau.

## GitHub Action: IPA Build

Es gibt jetzt einen Workflow unter `.github/workflows/build-ipa.yml`, der auf macOS:

1. Web-Dateien in das iOS-Bundle kopiert,
2. per XcodeGen ein Xcode-Projekt erzeugt,
3. eine iOS-App baut,
4. eine **unsigned IPA** als Artifact bereitstellt (`LiquidBrowser-unsigned.ipa`).

Damit bekommst du direkt eine IPA aus GitHub Actions als Download-Artefakt.
