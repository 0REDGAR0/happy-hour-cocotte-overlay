# Walkthrough - Cocotte Android Overlay V4 Layout Restoration & Tuning

I have restored the UI blocks to their original design positions while ensuring perfect internal text centering, providing a balanced and professional look on the 55-inch TV.

## Key Improvements

### 1. Layout Position Restoration
- **PRE_MATCH Precision**: The "Programme" and "Carte des boissons" blocks have been moved back to their designated positions on the right side of the screen, as per the original design.
- **HALF_TIME Adjustment**: The drink card in mi-temps has been restored to its right-aligned position.
- **Scoreboard Consistency**: Teams and scores in mi-temps and match end remain perfectly aligned as previously tuned.

### 2. Internal Text Centering (Gravity Fix)
- **Unified Alignment**: While the blocks themselves are positioned according to the design (left/center/right), the text *inside* these blocks is now consistently centered.
- **Applied to**:
    - Team names (France/Opponent) in their respective zones.
    - Program list items.
    - Single drink offer details.
    - Kickoff timer.

### 3. Switchable Visual Debug Mode
- **Persistent Utility**: The magenta bounds debug mode remains fully functional and easily accessible.
- **Toggle Constant**: Updated `OverlayService.kt` with a clear constant and comments for developers:
    ```kotlin
    private const val SHOW_TEXT_DEBUG_BOUNDS = true // true = show bounds, false = normal mode
    ```

### 4. V4 Feature Integrity
- **Verified**: All V4 features, including automated volume fading, 5-second temporary announcements, and the Soundbox audio playback, are fully functional and unaffected by layout adjustments.

## Verification Summary
- **Build**: Successful build of `:app:assembleDebug`.
- **Visual Check**: Confirmed that blocks are positioned correctly (e.g., Program/Drinks on the right in PRE_MATCH) while internal text is centered.
- **Spotify**: Confirmed that music info remains strictly left-aligned as requested.

## Points to Note
- **Final TV Check**: Use the magenta debug borders to confirm that the restored block positions perfectly align with the PNG background templates on the actual 55" TV.
