COC OTTE SOUNDBOX - AUDIO ASSETS

To enable audio for the Soundbox, you must add the following files to this directory (app/src/main/res/raw/).

EXPECTED FILES:

- soundbox_horn.mp3 (Back-end: horn) - Air horn / Klaxon sound
- soundbox_whistle.mp3 (Back-end: whistle) - Referee whistle sound
- soundbox_cheer.mp3 (Back-end: cheer) - Stadium ambiance / cheering
- soundbox_goal.mp3 (Back-end: goal) - Goal celebration sound

FILE REQUIREMENTS:

- Format: .mp3 (recommended), .wav, or .ogg.
- Naming: LOWER CASE ONLY, no spaces, no dashes, no accents (e.g., soundbox_horn.mp3).
- Duration: Short effects (1 to 5 seconds) are best for SoundPool.

TROUBLESHOOTING:

If no sound is playing:
1. Verify that the files are present in app/src/main/res/raw/.
2. Check Android Logcat for messages like "Sound resource missing for type: horn".
3. Ensure the media volume is not muted on the Android/DeX device.
