# Implementation Plan - Finalizing Soundbox (V4)

This plan outlines the finalization of the Soundbox feature in the Cocotte Android Overlay, enabling real-time audio playback for events like horn, whistle, cheer, and goal.

## Proposed Changes

### Logic & Services

#### [OverlayService.kt](file:///C:/cocotte-android-overlay-prototype/app/src/main/java/com/cocotte/overlayprototype/OverlayService.kt)
- **Sound Management**:
    - Use `SoundPool` for efficient, low-latency playback of short sound effects.
    - Implement a `loadSoundboxResources()` method to pre-load audio assets into the `SoundPool`.
    - Maintain a mapping between sound types (`horn`, `whistle`, etc.) and `SoundPool` IDs.
    - Update `handleSoundboxEvent(event: SoundboxEvent?)`:
        - Verify ID uniqueness (already tracked).
        - Verify event hasn't expired using `expiresAt` (if parsing is reliable).
        - Trigger the appropriate sound in `SoundPool`.
        - Add a brief visual feedback (discreet toast-like TextView) when a sound is played.
- **Error Handling**:
    - Safely check for resource existence using `getIdentifier` to prevent build/runtime crashes if files are missing.
    - Log explicit errors if a type is unknown or a file is missing.
- **Resource Cleanup**:
    - Release the `SoundPool` in `onDestroy()`.

### UI Layouts

#### [activity_dex_display.xml](file:///C:/cocotte-android-overlay-prototype/app/src/main/res/layout/activity_dex_display.xml)
- Add a small, discreet `TextView` (e.g., `tvSoundFeedback`) that appears for 2 seconds when a sound is triggered.

### Resources

#### [raw/](file:///C:/cocotte-android-overlay-prototype/app/src/main/res/raw/)
- Expecting (but not strictly requiring for build):
    - `soundbox_horn.mp3/wav`
    - `soundbox_whistle.mp3/wav`
    - `soundbox_cheer.mp3/wav`
    - `soundbox_goal.mp3/wav`

## Verification Plan

### Automated Tests
- Build debug APK (must succeed even without raw files).
- Logcat monitoring for "PLAYING SOUND" events and SoundPool loading status.

### Manual Verification
- **De-duplication**: Mock two consecutive states with the same `soundboxEvent.id` and verify the sound plays only once.
- **Mapping**: Verify different sound types trigger different log messages (and sounds if files are present).
- **Expiration**: Verify a sound with a past `expiresAt` is ignored.
- **Volume**: Ensure the sound plays at the current media volume without interfering with match mode fades.
- **Visual Feedback**: Verify the discreet "Klaxon" or "Goal" text appears briefly.
