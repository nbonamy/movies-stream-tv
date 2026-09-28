# Details screen design QA

## Target

The selected full-screen artwork mockup: compact left text column, large title,
year/rating, larger synopsis, and Watch beneath the description. Retain native
MediaStation typography, colors, and focused action styling. Use each provider's
actual artwork; the concept's generated movie artwork is not shipped.

## Implementation checks

- Full-screen backdrop with poster fallback and a dark left scrim.
- Title 38sp (previously 30sp), synopsis 16sp (previously 13sp), 4dp extra line spacing.
- Movie/TV label and Watch/Episodes action follow the existing title type.
- Missing year/rating/synopsis collapses; long titles allow two lines and synopses five.
- Existing Watch focus, episode picker, playback, and physical Back paths retained.
- `./gradlew :app:lintDebug :app:assembleDebug` passed.
- `git diff --check` passed.

## Visual verification

Pending: the user reserved the emulator for other work. No emulator or TV was
used, installed on, or launched. A same-viewport native screenshot comparison
and remote navigation check remain to be done when the emulator is available.

final result: blocked
