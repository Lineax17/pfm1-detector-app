# How to Build the ASL Sign Detector APK

This guide provides step-by-step instructions to build the Android Package (APK) for your application.

- **Hardware Support**: Optimized specifically for **Pixel 9 Pro** (AI Chip/TPU via GPU Delegate).
- **Target OS**: **Android 16 (API 36)** and newer.
- **Android Studio** (Koala or newer recommended)
- **Java Development Kit (JDK)** version 17

---

## Method 1: Building via Android Studio (Recommended)

1. **Open the Project**: Open Android Studio and select "Open" to navigate to the `pfm1-detector-app` folder.
2. **Sync Project**: Wait for the "Gradle Sync" to finish. If prompted, click "Sync Project with Gradle Files".
3. **Build Menu**:
   - Go to the top menu bar.
   - Select **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
4. **Locate APK**:
   - Once the build is complete, a notification will appear in the bottom right corner.
   - Click **"locate"** in that notification to open the folder containing `app-debug.apk`.
   - Path: `app/build/outputs/apk/debug/app-debug.apk`

---

## Method 2: Building via Command Line (Gradle)

If you have Gradle or the project's `gradlew` wrapper, you can build from the terminal:

1. **Open Terminal**: Open a terminal/PowerShell in the project root directory.
2. **Run Build Command**:
   ```powershell
   ./gradlew assembleDebug
   ```
3. **Locate APK**:
   The generated APK will be at:
   `app\build\outputs\apk\debug\app-debug.apk`

---

## Method 3: Building a Release (Signed) APK

To share your app or upload it to a store, you should build a **Release APK**:

1. In Android Studio, go to **Build** > **Generate Signed Bundle / APK...**
2. Select **APK** and click **Next**.
3. **Create a Key Store**: If you don't have one, click "Create new...". Follow the prompts to create a `.jks` file.
4. **Configure Signing**: Enter your password and alias details.
5. **Select Build Type**: Choose `release`.
6. **Finish**: Click **Finish**. The APK will be generated in `app/release/`.

---

## Troubleshooting
- **Memory Issues**: If the build fails with "Out of Memory", increase the heap size in `gradle.properties` (e.g., `org.gradle.jvmargs=-Xmx4g`).
- **Missing SDK**: If you see "SDK not found", ensure your `local.properties` file points to the correct Android SDK location.
- **TFLite Model**: Ensure `hand_signs.tflite` is present in `app/src/main/assets/`.
