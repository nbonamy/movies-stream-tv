# MediaStation UI provenance

MediaStation is the UI reference. Reuse its resources before writing a new style. The copies below come from `mediastation/android/tv/src/main`, except the browser header and search styling from `mediastation/android/music/src/main`.

| Surface | Reused source |
| --- | --- |
| Player top bar | `res/layout/activity_playback.xml` |
| Player menu buttons and focus | `res/layout/playback_button.xml`, `res/drawable/playback_button_bg_selector.xml`, `res/drawable/white_border_bottom.xml` |
| Timeline | `res/layout/playback_controller.xml`, `res/drawable/playback_controls_bg.xml` |
| Pickers | `ui/DialogUtils.java`, `res/layout/dialog_tv_action.xml`, `res/layout/item_tv_dialog_action.xml`, associated drawable and text selectors |
| Gallery | `res/layout/item_media_item_video.xml`, `res/layout/section_header.xml`, card backgrounds, foreground focus selector, elevation animator |
| Movie details | `res/layout/item_movie_details.xml`, poster background, action selector, backdrop scrim |
| Header and search | Music's `res/layout/browser_header.xml`, `res/drawable/search_input_background.xml`, native Leanback SearchBar and Lato typography |

Colors, dimensions, font weights, and styles are imported from the corresponding MediaStation resource definitions. The launcher retains Music's blue background, cream artwork, and yellow accent, with a movie glyph.

## Adaptations

- The gallery layout uses `LinearLayout` in place of `NonOverlappingLinearLayout`; provider-inapplicable badges and episode tags are removed. Card sizing, artwork, title styling, focus foreground, and elevation come from MediaStation. Continue watching cards restore MediaStation's resume progress bar; TV entries show the season and episode in their title.
- The details layout omits the cast RecyclerView. Binding hides unavailable metadata and library-management actions. Available title, year, rating, artwork, synopsis, and Watch action retain their original layout.
- `DialogUtils` changes package and theme lookup, and omits the unused legacy builder. Bottom positioning, selected/focused styling, dim amount, scrolling, and entrance/dismissal animations are preserved.
- Android 26–27 uses `textStyle="bold"` for the action label; Android 28+ retains MediaStation's original font-weight style.
- Search binds Vidbox movie results to the gallery. It uses the same native search widget, input background, font, and speech-orb colors as Music.

## Playback behavior

- Menu order: **Subtitles**, **Quality**, **Source**. Menu labels do not contain selected values.
- Up focuses Subtitles; Left/Right moves between menus; Down returns to the timeline.
- Back dismisses a picker, then hides controls, then exits playback. Focus returns to Watch, then to the original gallery card.
- Start with Media3's highest supported bitrate. Quality offers Auto and the supported video tracks; an explicit selection changes the track without restarting playback.
- Pickers use MediaStation's selected-row state, independently of the currently focused row.

## Verification

On the Android TV emulator: inspect gallery, details, player underline, bottom sheets, and search; verify the three Back layers and quality selection. For The Odyssey, the default rendition decoded at 1920×1000 (1080p with cropped letterboxing), and selecting 360p switched decoding to 640×334 while retaining playback position.

Run `./gradlew check :app:lintDebug :app:assembleDebug` before deployment. TV deployments install the APK without launching it.
