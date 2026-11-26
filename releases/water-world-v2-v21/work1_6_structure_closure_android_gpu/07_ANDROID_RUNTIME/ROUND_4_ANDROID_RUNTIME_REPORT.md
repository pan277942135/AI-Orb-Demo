# Round 4 — Android preview source, pending runtime proof

The Work 1.5 Android renderer rendered only into its offscreen FBO. `WaterWorldStaticRenderer.present(width,height)` now blits that exact color target to the default framebuffer with square letterboxing. A debug-only `WaterWorldPreviewActivity` creates an ES 3 GLSurfaceView, initializes on the GL thread, rebuilds after context creation, draws T=0, and exposes a PNG export button. The production MainActivity and original assets are unchanged.

`app/build.gradle.kts` stages the frozen 65-file V2 archive and checked-in geometry/config as APK assets. The master image is excluded. The archive hash is checked before extraction. `verify_android_asset_plan.py` verified 65 source members, 32 unique draws, mesh references and texture SHA values. The Water World Kotlin sources compiled against Android 36.1 stubs with Kotlin 2.2.21.

Android Gradle build, installation, launch and GPU readback: **NOT RUN**. The local overlay has approximately 96 MB free; running Gradle 9.3.1 consumed all remaining space while creating its cache and failed before project configuration. The generated Gradle 9.3.1 cache was removed; no repository asset was deleted. A separate CI runner must complete the full Android build and an Android device/emulator must establish runtime results. This source change must not be counted as Android Runtime PASS or hardware GPU evidence.

Debug launch once installed: `adb shell am start -n com.aistudio.aiorbdemo.vxkrmq/com.example.waterworld.WaterWorldPreviewActivity`. Use the in-app button to export the final FBO PNG under the app's external files directory. No claim of 11-pass rendering or optical completion is made.
