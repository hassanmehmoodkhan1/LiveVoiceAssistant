# Live Voice Assistant: say "Kali", then talk (Android)

Kotlin + Jetpack Compose. No text chat. Audio only, like a phone call.

- Wake word "Kali": offline, always listening in a background service (Picovoice Porcupine).
- After "Kali": a live two-way audio stream with the Gemini Live API (WebSocket).
- Interrupt Gemini by speaking. Say "goodbye" to hang up. 25 seconds of silence also hangs up.
- Then it goes back to waiting for "Kali".

## One-time setup (about 10 minutes)
1. Gemini key: https://aistudio.google.com/apikey -> Create API key.
2. Picovoice: sign up at https://console.picovoice.ai (free) -> copy your AccessKey.
3. Wake word: Picovoice Console -> Porcupine -> type "Kali", language English -> Train
   -> choose platform ANDROID -> Download. Unzip it and copy the .ppn file to your phone.

## Build and run
1. Install Android Studio. File > Open > this folder. Wait for Gradle sync.
2. Connect your phone (USB debugging on) and press Run.
3. In the app: Settings -> paste both keys -> Import kali.ppn -> Allow background running -> Save.
4. Tap "Start Kali", allow microphone and notifications. Lock the phone, say "Kali", and talk.

APK for sharing: Build > Build APK(s).
