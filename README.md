TripMate
========

The Android app uses Firebase Authentication for email/password signup and login.
Firebase sends the verification email. The TripMate dashboard opens only after the
user verifies their email and signs in successfully.

Android flow:

`Create Account -> Firebase verification email -> Verify link -> Sign In -> Dashboard`

Firebase setup
--------------

1. Create a Firebase project at [Firebase Console](https://console.firebase.google.com/).
2. Open **Project settings** and add an Android app with package name `com.tripmate.app`.
3. Download `google-services.json` and place it at `app/google-services.json`.
4. Open **Authentication** > **Sign-in method** and enable **Email/Password**.
5. In **Project settings** > **Service accounts**, generate a new private key.
6. In the Render service dashboard, add `FIREBASE_PROJECT_ID` with the Firebase project ID.
7. Add `FIREBASE_SERVICE_ACCOUNT_JSON` with the complete service-account JSON as a single-line value.
8. Deploy the backend, then rebuild the Android app:

```powershell
.\gradlew.bat assembleDebug
```

The generated APK is at `app/build/outputs/apk/debug/app-debug.apk`.

How authentication works
------------------------

- Android creates the Firebase account with full name, email, and password.
- Android stores the full name in the Firebase user profile.
- Firebase sends the verification link.
- Android blocks dashboard access until `emailVerified` is true.
- After login, Android sends the Firebase ID token to `POST /api/auth/firebase-session`.
- The Flask backend verifies the token with Firebase Admin and starts the existing
  TripMate session so trips, notes, memories, expenses, maps, and other dashboard
  features continue to work.
- Existing local TripMate records are reused when the Firebase email matches an
  existing account.

Do not commit `app/google-services.json` or Firebase service-account private keys.
