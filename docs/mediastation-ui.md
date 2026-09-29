# MediaStation UI provenance

MediaStation is the UI reference. Reuse its resources before writing a new style. The copies below come from `mediastation/android/tv/src/main`, except the browser header and search styling from `mediastation/android/music/src/main`.

| Surface | Reused source |
| --- | --- |
| Player top bar | `res/layout/activity_playback.xml` |
| Player menu buttons and focus | `res/layout/playback_button.xml`, `res/drawable/playback_button_bg_selector.xml`, `res/drawable/white_border_bottom.xml` |
| Timeline | `res/layout/playback_controller.xml`, `res/drawable/playback_controls_bg.xml` |
| Pickers | `ui/DialogUtils.java`, `res/layout/dialog_tv_action.xml`, `res/layout/item_tv_dialog_action.xml`, associated drawable and text selectors |
| Gallery | `res/layout/item_media_item_video.xml`, `res/layout/section_header.xml`, card backgrounds, foreground focus selector, elevation animator |
| Movie details | Lato typography, action selector, backdrop scrim; adapted full-screen artwork layout |
| Header and search | Music's `res/layout/browser_header.xml`, `res/drawable/search_input_background.xml`, native Leanback SearchBar and Lato typography |

Colors, dimensions, font weights, and styles are imported from the corresponding MediaStation resource definitions. The launcher retains Music's blue background, cream artwork, and yellow accent, with a movie glyph.

## Adaptations

- The gallery layout uses `LinearLayout` in place of `NonOverlappingLinearLayout`; provider-inapplicable badges and episode tags are removed. Card sizing, artwork, title styling, focus foreground, and elevation come from MediaStation. Continue watching uses landscape cards with MediaStation's resume progress bar and a small site label; TV entries show the season and episode in their title. Movies and TV share one row across all sites. Long-press opens the existing MediaStation action dialog with Remove.
- The details screen follows the approved full-screen artwork concept: provider backdrop (poster fallback), a dark left scrim, and a compact left column with type, larger title, year/rating, larger synopsis, and Watch/Episodes below. It retains MediaStation typography, colors, and action focus styling. Titles allow two lines, synopses five; the content can scroll if needed on smaller viewports. Missing metadata collapses without leaving a separator. No separate poster or library-management actions are rendered.
- `DialogUtils` changes package and theme lookup, and omits the unused legacy builder. Bottom positioning, selected/focused styling, dim amount, scrolling, and entrance/dismissal animations are preserved.
- Android 26–27 uses `textStyle="bold"` for the action label; Android 28+ retains MediaStation's original font-weight style.
- Universal search uses native Leanback vertical/horizontal grids, one row per site, with the existing MediaStation cards. It uses the same native search widget, input background, font, and speech-orb colors as Music.

## Playback behavior

- Menu order: **Subtitles**, **Quality**, **Source**. Menu labels do not contain selected values.
- Up focuses Subtitles; Left/Right moves between menus; Down returns to the timeline.
- Back dismisses a picker, then hides controls, then exits playback. Focus returns to Watch, then to the original gallery card.
- Start with Media3's highest supported bitrate. Quality offers Auto and the supported video tracks; an explicit selection changes the track without restarting playback.
- A compact white **Next episode** prompt appears at the bottom right during the last 30 seconds of TV playback, over the full-screen video. It receives focus once; Back dismisses it. It is separate from the three top-bar menus.
- Pickers use MediaStation's selected-row state, independently of the currently focused row.

## Verification

On the Android TV emulator: inspect gallery, details, player underline, bottom sheets, and search; verify the three Back layers and quality selection. For The Odyssey, the default rendition decoded at 1920×1000 (1080p with cropped letterboxing), and selecting 360p switched decoding to 640×334 while retaining playback position.

Run `./gradlew check :app:lintDebug :app:assembleDebug` before deployment. TV deployments install the APK without launching it.

## Phone search

Music's `RemoteSearchServer`, `RemoteSearchAddress`, `QrCodeBitmap`, QR frame,
and mini-site styles are reused for phone search. The server lives in `:core`
for socket-level tests; Android lifecycle and QR display live in `:app`.
The Movies adaptation includes only search, uses ports 8070–8079 to coexist
with Music, and stops listening when the activity is no longer visible.
The mini site has Movies branding and searches all registered sites.
