# UI guidelines (v0.4 responsive overhaul)

## Problems found and their causes

| Problem | Root cause | Fix |
|---|---|---|
| Keyboard covers the chat input or pushes it to an odd place | Edge-to-edge is on, so `adjustResize` no longer resizes the window by itself. Each home layout added `imePadding()` *inside* a Scaffold that already reserved the bottom bar, so the padding was counted twice (input floated too high) or, on the default Great Sage layout, fixed-size content (280 dp crystal) pushed the input off the bottom. | Insets are applied **once** in `MainShell`: `padding(scaffold) → consumeWindowInsets → imePadding`. The bottom bar hides while the keyboard is open. Layouts never add inset padding. Fixed-size content became adaptive (`BoxWithConstraints`), and the crystal shrinks smoothly while typing. |
| Can't see what you type in long messages | Fixed field height | Input grows up to 168 dp, then scrolls inside itself; keyboard Send key sends; unsent text survives keyboard, rotation and recomposition (`rememberSaveable`). |
| Chat jumps or hides the newest message | Top-anchored list; always force-scrolled | Bottom-anchored list (`reverseLayout`), auto-follows only when you are at the bottom, "latest" button otherwise. |
| Theme cards cut off / outside the screen | `requiredSize` larger than its parent is centred, then scaled from the top-left, so previews were shifted up-left and clipped; themes sat in a horizontal row inside a vertical list | `ScaledPreview` measures at design size and places at (0,0) with a scale layer; themes are an adaptive grid (`GridCells.Adaptive(150.dp)`) of complete cards with name, preview, colours, description, selected badge, Preview and Apply; full-screen preview with tabs. |
| Buttons/chips overflow on small screens | Fixed `Row`s | `FlowRow` everywhere a row can exceed the width. |
| Dialogs taller than the screen with the keyboard open | Fixed `heightIn(max = 520–560.dp)` | Scrollable dialog bodies with no fixed height: the dialog fits the window; buttons stay reachable. |
| Floating Ciel could leave the screen | `FLAG_LAYOUT_NO_LIMITS`, fixed 280 dp card | Clamped to the display (also after expanding), card width ≤ screen − 32 dp, long text scrolls. |

## Rules

1. **Insets once.** Only the app shell applies system bars, cutouts and the keyboard. Screens use
   `imeVisible()` to adapt their content, never to pad.
2. **Width.** Every non-home screen is wrapped in `ScreenFrame` (max 680 dp, centred) for
   tablets, foldables and landscape.
3. **Scroll before shrink.** Content that can exceed the screen scrolls; text wraps; font scaling
   is respected. Never reduce text size to make things fit.
4. **Rows wrap.** Use `FlowRow` for chips and button groups.
5. **Dialogs** have a scrollable body and no fixed height.
6. **Spacing** comes from `Spacing` (4/8/12/16/24 dp). Touch targets ≥ 48 dp.
7. **Motion.** Every infinite animation is periodic in whole cycles and driven by `MotionClock`
   (or fades in and out over its cycle), so loops never cut. State changes ease speeds and
   fade effects instead of switching. Screen transitions: 240 ms fade + 2 % slide.

## Manual test checklist (real phone)

Keyboard: open chat → tap input → type short and long text (beyond 8 lines) → send with the
keyboard key → dismiss and reopen keyboard → scroll up while the keyboard is open (no forced
jump; "latest" button appears) → rotate with text typed (text kept). Try gesture and 3-button
navigation, and Gboard plus one other keyboard.

Themes: Settings → Appearance → Browse themes → scroll all 8 cards → Preview each (Home, Chat,
Floating, Voice tabs) → Back keeps the current theme → Apply each → home, chat, floating crystal
and notch follow it. Repeat at largest font size and in landscape.
