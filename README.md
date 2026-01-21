# Safari Cookie Injector for iOS

A powerful Safari extension for iOS that enables macOS browsing emulation, zoom controls, and cookie injection capabilities.

## Features

- **macOS Emulation**: Browse websites as if you're on a Mac
- **Zoom Controls**: Easy zoom in/out functionality with custom zoom levels
- **Cookie Injection**: Load and inject Netflix session cookies from justpaste.it
- **Auto-Redirect**: Automatically navigate to Netflix after cookie injection

## Building

This project uses GitHub Actions for automated building:

1. Push to the main/master branch
2. GitHub Actions will automatically build the unsigned IPA
3. Download the IPA from the Actions artifacts

### Local Building

Requirements:
- macOS with Xcode 15.0 or later
- iOS 15.0+ device or simulator

```bash
cd main
xcodebuild -project SafariCookieInjector.xcodeproj \
  -scheme SafariCookieInjector \
  -configuration Release \
  -sdk iphoneos
```

## Installation

### Method 1: AltStore / SideStore (Recommended)
1. Download the IPA from GitHub Actions artifacts
2. Install using AltStore or SideStore
3. Trust the certificate in Settings > General > VPN & Device Management

### Method 2: Xcode
1. Open `SafariCookieInjector.xcodeproj` in Xcode
2. Connect your iOS device
3. Select your device as the target
4. Click Run to build and install

### Method 3: Other Sideloading Tools
- TrollStore (jailbroken devices)
- Sideloadly
- iOS App Signer

## Usage

1. **Enable Extension**
   - Open Settings on your iOS device
   - Navigate to Safari > Extensions
   - Enable "Cookie Injector"
   - Grant necessary permissions

2. **Configure Settings**
   - Open Safari
   - Tap the Extensions button (puzzle piece icon)
   - Select Cookie Injector
   - Toggle macOS emulation on/off
   - Adjust zoom level as needed

3. **Inject Cookies**
   - In the extension popup, select a session from the dropdown
   - Click "Inject & Open Netflix"
   - Wait for cookies to be injected and Netflix to load

## How It Works

The extension fetches session data from justpaste.it/a7vyr, parses the cookie information, and injects it into your Safari session. It then navigates to Netflix.com where the cookies are automatically applied.

## Session Format

The extension expects sessions in this format on justpaste.it:
```
Session 1
cookieName=cookieValue;cookieName2=cookieValue2

Session 2
cookieName=cookieValue;cookieName2=cookieValue2
```

Or:
```
sess1:"cookieName=cookieValue;cookieName2=cookieValue2"
sess2:"cookieName=cookieValue;cookieName2=cookieValue2"
```

## Permissions

This extension requires:
- **Tabs**: To navigate and manage browser tabs
- **Storage**: To save your preferences
- **Cookies**: To inject session cookies
- **Web Request**: For macOS user-agent emulation
- **Scripting**: To inject scripts for enhanced functionality

## Troubleshooting

**Extension not appearing in Safari:**
- Make sure you've installed the app on your device
- Check Settings > Safari > Extensions
- Try restarting Safari

**Cookies not working:**
- Ensure the session format is correct
- Check that justpaste.it/a7vyr is accessible
- Try different session

**macOS emulation not working:**
- Reload the page after enabling
- Some sites may detect mobile devices differently

## License

This project is provided as-is for educational purposes.

## Disclaimer

This tool is for educational purposes only. Use responsibly and in accordance with Netflix's Terms of Service and applicable laws.
