# DerpiViewer 2.0 UI Migration Plan

## Status

Architecture audit is complete. This remains a staged migration of the existing Android Views application, not a completed DerpiViewer 2.0 UI. Reusable View components are being added so existing media workflows can migrate without an all-at-once toolkit rewrite.

## Audit Findings

- **UI toolkit:** Android Views, XML layouts, ViewBinding, and runtime-created Views. Jetpack Compose is not configured.
- **Navigation:** `MainActivity` owns the home grid, drawer, and a `BottomNavigationView`; Video, Comments, Profile, Search, Detail, Settings, and utility flows are separate Activities. Featured content can also be embedded as `FeaturedPanel` in Main.
- **Screens:** Main/Home, Video Feed, Featured/Hot, Recent Comments, Profile, Search, Image Detail, Fullscreen Image, Settings, Filters, Login, Challenge, Deep Optimize, Downloads, Favorites, Forum, Gallery, and Tag Search.
- **Theme:** `Theme.DerpiViewer` uses Material 3 Views DayNight, while selectable palettes and system-following mode are generated through `ThemeGenerator`, `PaletteDefinitions`, and `PaletteManager`. Semantic color support is partial; typography, spacing, shapes, elevation, glass, and motion are not centralized.
- **Image loading:** Glide 4.16 is the app-wide image engine, with `CdnImageGate` for common thumbnail loading/prefetch and some direct Glide calls in utility screens. Keep Glide as the single image pipeline during this migration; do not add Coil in parallel.
- **Artwork card:** `ImageAdapter` + `item_image.xml` currently render a square card, a heavy bottom metadata row, dimensions, and spoiler/media overlays. The card is reused by Home, Search, Profile, Featured, and other image lists.
- **Bars and actions:** Main uses XML AppBar/Toolbar, Material BottomNavigationView, and a FAB. Many Activities use `SafeToolbar`; some build toolbars dynamically, and Video is immersive. There is no reusable Top Island, Bottom Island, or Floating Action Island.
- **Dialogs and sheets:** A mixture of `AlertDialog`, `BottomSheetDialog`, custom dynamic layouts, Snackbars, and Toasts.
- **Loading/error/empty states:** Implemented independently per Activity with `ProgressBar`, text, Toast, or inline state; no unified skeleton/crossfade/error/empty components.
- **Insets:** Main applies status/navigation insets manually; `SafeToolbar` handles status bar padding itself; Video opts into immersive edge-to-edge; other Activities vary. No app-wide edge-to-edge/inset contract exists.
- **Adaptivity:** Main chooses fixed 2 columns portrait / 4 landscape, density controls can override it, while Search/Profile/Featured commonly hardcode 2 columns. `layout-land` exists but there are no `sw600dp`/window-size-class resources or shared content max-width policy.
- **Motion:** Motion is local and inconsistent: Search has custom header `ValueAnimator`, Image Detail has swipe transitions, Challenge has small alpha animations. Navigation selection, top island scroll response, screen transitions, artwork loading, and FAB state do not share a Motion system.
- **Existing UI 2.0 beta helper:** `Ui2DesignSystem` now centralizes semantic palette tokens, spacing, shapes, elevation, motion interpolators, island styling, and press feedback. It still does not implement backdrop blur or a complete component system.
- **Tests/runtime:** Only template unit/instrumentation tests are present. `adb devices -l` reports no attached Android device, so install, runtime interaction, screenshot, and physical 60 FPS checks are unavailable in this environment unless a device/emulator is started.
- **Worktree:** The repository contains extensive pre-existing user edits, generated artifacts, and untracked files. Preserve all of them; keep migration edits narrowly scoped.

## Component Migration Map

| Existing implementation | DerpiViewer 2.0 target |
| --- | --- |
| `PaletteDefinitions` + `ThemeGenerator` + scattered theme reads | `ui/designsystem/ColorTokens` semantic palette, preserving brand accents and system light/dark behavior |
| Ad hoc dp values and XML margins | `SpacingScale` tokens: 4, 8, 12, 16, 20, 24, 32, 40, 48dp |
| XML shape/elevation literals | `ShapeTokens` (8/12/16/24-28dp/pill) and `ElevationTokens` |
| `Ui2DesignSystem` static helper | `DesignSystem` facade for colors, type, spacing, shapes, elevation, glass, motion, icons, and shared components |
| Per-view translucent drawable treatment | `GlassSurface` / `GlassIsland` / `GlassButton` / `GlassDialog` / `GlassBottomSheet`; one encapsulated backdrop-blur implementation with Full/Reduced/Off modes and graceful fallback |
| Main XML AppBar and scattered toolbars | `TopIsland` with unified inset handling, scroll-linked compact state, and a search container-transform/morph path |
| Main `BottomNavigationView` and separate-Activity tab jumps | Floating `BottomIsland`, adaptive compact/rail strategy, animated moving indicator and coordinated icon state; navigation state preserved across primary destinations |
| Main FAB and Filters FAB | `FloatingActionIsland` with shared placement, press/expand/exit motion and safe-area coordination |
| `item_image.xml` + `ImageAdapter` metadata treatment | `ArtworkCard` + skeleton variant, image-first overlay for favorite/score/comment, lightweight glass overlay, spoiler/media badges, and shared-element-ready transition identity |
| Independent Activity navigation | `AppNavigator` contract plus shared Fade Through/Shared Axis transition policy; preserve Activity boundaries where media/OS flows require them |
| Raw `AlertDialog` / `BottomSheetDialog` styling | Shared Glass Dialog and Glass Bottom Sheet containers, while retaining Material accessibility/focus behavior |
| Per-screen spinners, blank states, and Toast-only failures | Shared skeleton/crossfade, loading, empty, and error components with inline retry where appropriate |
| Hardcoded grid span counts | `AdaptiveGridPolicy` based on available window width/content max width and minimum artwork cell width; no scattered device-width checks |
| Manual/duplicated WindowInsets logic | One edge-to-edge `WindowInsetsPolicy` used by shared shells, with scroll content insets and floating navigation safe area |
| Local animation durations and press behavior | `MotionSpec` with Fast/Normal/Slow and Standard/Emphasized/Decelerate/Accelerate/Spring curves; shared press feedback |
| Per-screen ad hoc image requests | Existing Glide `AppGlideModule`/`CdnImageGate` path remains the single image loader and is wrapped by shared artwork components |

## Rollout Phases

1. **Foundation:** Define semantic tokens, MotionSpec, inset policy, shared surface/island components, and a documented/licensed backdrop blur implementation. Add adaptive grid and content-width helpers. Keep legacy UI available while each surface migrates.
2. **Primary shell:** Rebuild Main/Home around Top Island, image-first artwork grid, Floating Action Island, and Bottom Island. Implement continuous scroll-linked top-island collapse, moving bottom indicator, and meaningful screen transitions.
3. **Primary destinations:** Migrate Video, Hot, Comments, and Profile to the same shell/component contracts; use rail/expanded navigation for wide windows while respecting Video's immersive playback mode.
4. **Secondary workflows:** Migrate Search, Detail, Settings, Filters, Favorites, Downloads, Forum, Gallery, Tag Search, Login, and utility screens. Dialogs, sheets, loading, empty, and error states must use shared components.
5. **Responsive/motion verification:** Verify phone portrait/landscape, small and large tablet portrait/landscape; inspect navigation, search morph, top scroll, FAB, image load, pull refresh, and card-to-detail readiness. Profile CPU/GPU/frame timing and tune blur tiers.

## Acceptance Gates

- No screen claims 2.0 migration while it still owns its old bar/card/loading/dialog behavior without an explicit documented exception.
- Full/Reduced/Off glass modes must be real, visually distinct implementations; Reduced/Off must not capture or blur the backdrop.
- Top Island collapse is directly linked to scroll progress; Bottom Island indicator visibly moves between destinations; search uses a single-container morph; FAB changes geometry/rotation/scale/alpha; loading transitions from skeleton to image.
- Adaptive image columns derive from available width and target cell size, not orientation-only constants. Content and islands have bounded widths on wide windows.
- Bottom/side navigation and floating actions must not cover scroll content or system gesture areas.
- Build and tests must pass. Device installation, screenshots, interaction, and frame-rate claims require an attached emulator/device; absent one, these gates remain `NOT COMPLETED` and must be reported as such.

## Current Completion

### Implemented Foundation (Partial)

- Central semantic color, spacing, shape, elevation, motion, and press-feedback tokens in `Ui2DesignSystem`.
- Shared `SafeToolbar` now switches between legacy and Beta appearance/inset behavior based on the setting.
- Main screen Beta shell has inset-aware floating top/bottom surfaces, an enabled Material active indicator with checked-state tint, and a stateful FAB geometry/rotation/scale/alpha transition.
- Adaptive artwork grid chooses column count from available window width and centers content with a 1280dp maximum content width; applied to Home, Search, Profile, and Featured grids where previously wired.
- Shared artwork card was changed to image-first interaction overlays and omits image dimensions from the card.
- Beta-setting description now identifies the migration as partial rather than implying only Home or claiming broad completion.

### NOT COMPLETED

- Genuine backdrop blur/glass implementation and Full/Reduced/Off performance modes. Current translucent island drawable is not backdrop glass and must not be described as such.
- Search container morph; unified shell/navigation host; all primary destinations sharing the same navigation; universal dialog, sheet, loading, error, empty, and skeleton components.
- Continuous top-island collapse with coordinated blur/tint/elevation; the current Home toolbar responds to AppBar scroll but still uses the legacy collapsing AppBar structure.
- Complete Bottom Island migration across all screens, verified moving indicator behavior, tablet navigation rail strategy, and consistent safe-area content padding across all destinations.
- Complete Floating Action Island/staggered action menu and shared press behavior for all controls.
- Full responsive migration of Video, Hot, Comments, Profile, Search, Detail, Settings, and utility screens; current adaptive policy is presently used by selected image grids only.
- Image skeleton-to-image crossfade, pull-refresh motion standardization, shared-element transition implementation, and unified page transitions.
- Licensed, maintained third-party backdrop implementation review and integration. No library was added without compatibility/license verification.
- Build/install/runtime interaction/screenshots and performance checks on phone portrait/landscape, small/large tablet portrait/landscape. No Android device is attached in this environment.

The application does **not** meet the full 2.0 acceptance gates. Every item above remains an explicit migration task, not an implied completion.
