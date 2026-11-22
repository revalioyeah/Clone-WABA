# Warung Auto Bag Assistant 0.3.17 Offline
## Fast Order Recognition, Cache Repair & Safer Shelf Targets

Baseline: 0.3.16 Offline. The fishing and NPC minigame implementations are preserved.

## Findings in 0.3.16

1. `SmartItemDetector` rebuilt the same OCR line/element text and compiled alias
   regular expressions repeatedly inside the product loop. A full-screen
   location scan repeated this work across the catalog, increasing CPU work.
2. `rememberProductLocationCache()` returned immediately while the cache was
   still fresh. If the first scan missed a small product label, later full-screen
   scans did not attempt to add that missing product until cache expiry.
3. A compact panel scan always triggered an expanded second OCR pass when four
   or more items were parsed, even when all visible rows were already complete.
4. A new customer with exactly the same product/quantity signature was difficult
   to distinguish from the completed customer. The code fell back to repeated
   full-screen identity OCR every 300 ms. Current quantities are not part of the
   customer signature, so a reset to 0/required is a stronger signal for an
   identical new order.
5. Product-name and quantity OCR matching were exact/strict. Common one-character
   OCR errors and OCR confusion between slash, `I`, and `l` could make a product
   or quantity disappear from the parsed order.
6. All shelf product targets are still estimated from OCR label centers using a
   fixed per-catalog vertical offset. That estimate is not an object detector;
   inaccurate calibration can still produce a misplaced preview marker.

## Changes in 0.3.17

### Order speed and customer transitions
- Detect an identical new order when the same order signature returns and all
  current quantities reset to zero. The previous completed/auto-paused queue is
  stopped and the new order can start from its first complete OCR frame.
- Increase the full-screen identity fallback interval from 300 ms to 600 ms.
  Different order signatures and same-signature counter resets use the faster
  panel path.
- Do not expand the compact panel crop solely because four products were read.
  Expand only when OCR content reaches the lower part of the crop, indicating
  that more rows may be clipped.
- Preserve the two-frame stability guard for incomplete/ambiguous order panels.
  A complete, different order can still use the first-frame fast path.

### OCR and product matching
- Precompute line text and element bounding boxes once per OCR result.
- Compile catalog alias regexes once per product detection call rather than
  once per line/source.
- Add a conservative fuzzy fallback for longer item names only when exact
  matching finds nothing. Short names remain exact-only; ambiguous fuzzy matches
  are rejected.
- Accept common OCR separator confusions in quantities: `/`, `|`, `!`, `I`, and
  `l`; normalize `O` to 0 and `I`/`l` to 1 in the quantity digits.
- Count quantity rows that could not be paired with a recognized product name.
  Continuous execution now refuses to dispatch a partial order when an orphan
  quantity row is present and requests another OCR read instead.

### Shelf cache and coordinate safety
- A fresh but incomplete shelf cache now scans only catalog entries that are
  still missing, then merges successful detections into the cache.
- Expire the shelf cache after 3 minutes rather than 10 minutes, reducing the
  time stale shelf coordinates can remain in use.
- Reject a target estimate that falls outside its catalog shelf region instead
  of clamping it to an arbitrary screen coordinate. This is fail-closed: an
  invalid target is reported as not found and no tap is dispatched for it.

### Gesture pacing
- Keep tap duration at 55 ms.
- Reduce repeat-tap interval from 35 ms to 20 ms.
- Reduce item-switch delay from 45 ms to 20 ms.
- Batch verification remains in place; there is no OCR round-trip between
  different product types in one tap sequence.

## Files changed
1. `app/src/main/java/com/warung/autobag/MainActivity.kt`
2. `app/src/main/java/com/warung/autobag/ocr/OrderParser.kt`
3. `app/src/main/java/com/warung/autobag/detection/SmartItemDetector.kt`
4. `app/src/main/java/com/warung/autobag/gesture/TargetGameConfig.kt`
5. `app/build.gradle.kts` (versionCode 17, versionName 0.3.17-offline-fast-order)
6. `app/src/main/java/com/warung/autobag/gesture/GestureController.kt` (comment corrected to configured sequence size)
7. `scripts/verify_offline_apk.py` (offline APK version assertion updated)
8. `tests/OrderRecognitionRegression.kt` (new synthetic OCR regression)
9. `tests/test_offline_packaging.py` (packaging fixture updated to version 0.3.17)

## Explicitly unchanged
- `NpcAutomation.kt`
- `NpcVision.kt`
- fishing/minigame logic in `MainActivity.kt`
- `ScreenCaptureService.kt`
- `GestureAccessibilityService.kt`
- `AndroidManifest.xml`, offline permissions, bundled OCR model and workflows

The minigame, capture service, accessibility service and manifest files are
byte-identical to the 0.3.16 source package. MainActivity contains order-monitor
changes only; its minigame sections are retained.

## Regression test
A new standalone Kotlin regression checks:
- `Kopi sashet` -> canonical `Kopi sachet`
- `0I1` -> current 0 / required 1
- the same OCR typo can locate the shelf label
- target coordinates remain inside the catalog shelf region

The test is provided in `tests/OrderRecognitionRegression.kt`. It has not been
executed in this environment because no Kotlin compiler, Gradle installation,
Android SDK or Gradle wrapper is present.

## Build and on-device validation status
- Existing offline packaging unit tests: 7 tests passed.
- ZIP/source structure and static source checks passed.
- Braces/parentheses checks passed for all modified Kotlin files.
- Fuzzy matcher behavior was checked against representative OCR typos in a
  separate local test.
- Minigame and capture/accessibility files were verified unchanged.
- Full Android Gradle build has NOT been run here. GitHub Actions must report
  success before installing the APK.
- Real-device OCR timing, item coordinates, and gesture reliability still need
  to be tested on the game screen. Do not enable automatic tapping if the green
  preview marker is not centered on the correct product.

## Coordinate limitation
The detector estimates the product center from the OCR label center and the
catalog entry's `targetOffsetYReferencePx` (currently 55 px for most products).
This patch makes the estimate safer and refreshes missing cache entries, but
does not claim pixel-perfect object recognition. If a green marker is
consistently high/low/left/right for a particular item, that item's offset
needs calibration against a fresh screenshot.
