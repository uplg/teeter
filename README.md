# Teeter - Classic HTC Game Remake

A modern remake of the original HTC Teeter game, recreated from extracted resources.

## Description

Teeter is an accelerometer-based maze game where you must guide a ball through 32 levels while avoiding holes and reaching the goal.

## Features

- 32 original levels
- Accelerometer controls
- Sound effects and vibrations
- Original HTC game graphics
- Time and attempts tracking
- Compatible with modern Android devices (API 24+)

## Technologies

- **Language**: Kotlin
- **Minimum SDK**: Android 7.0 (API 24)
- **Target SDK**: Android 14 (API 34)
- **Architecture**: Custom Game Engine with SurfaceView

## How to Build

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 8 or newer
- Android SDK with API 34

### Steps

1. Open the project in Android Studio:
   ```
   File > Open > Select the folder
   ```

2. Sync Gradle:
   ```
   File > Sync Project with Gradle Files
   ```

3. Build and install:
   ```
   Run > Run 'app'
   ```
   Or via command line:
   ```bash
   cd TeeterGame
   ./gradlew assembleDebug
   # APK will be in app/build/outputs/apk/debug/
   ```

## How to Play

1. Launch the application
2. Tilt your device to control the ball
3. Avoid the black holes
4. Reach the green zone (goal) to complete the level
5. Complete all 32 levels!

## Technical Notes

- The game uses SensorManager to detect accelerometer movements
- Ball physics include velocity, friction and collisions
- Levels are dynamically loaded from XML files
- Rendering is done with Canvas on SurfaceView for better performance

## License

This project is an unofficial remake of the original HTC Teeter game, created for educational and preservation purposes.

## Credits

- Original game: HTC Corporation
- Remake: Created from resources extracted from the original application
