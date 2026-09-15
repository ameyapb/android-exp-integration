# Design: Basic UX polish (Deep theme)

Date: 2026-09-15

## Problem

The Phase 1/2 native app has zero custom visual design: no `Theme.kt`,
no `MaterialTheme` wrapper in `MainActivity`, just an unstyled `Scaffold`
with a bare `Column` (text field, two full-width text buttons, a plain
reply `Text`). It works, but doesn't read as a considered product.
Explored via `superpowers:brainstorming`'s visual companion against
three initial directions (Material You, terminal-native, calm/paper)
and a refined second round informed by `frontend-design` and
`impeccable` skill guidance plus current chatbot-UI reference points
(ChatGPT's white-space/subtle-branding approach, Google's Material 3
Expressive redesigns of Messages/Chat). User selected the "Deep"
direction: a professional deep navy/teal surface with a single teal
accent, layered app bar + rounded content surface, icon-based actions.

This design turns that selection into a concrete token system and
component-level plan ready for an implementation plan. Explicitly a
visual/structural polish pass, not an interaction-model change (user
confirmed: keep the existing single prompt-in/reply-out screen and
voice flow as-is).

## Decision

Introduce a fixed dark `ColorScheme` (`ui/theme/Theme.kt`,
`ui/theme/Color.kt`) applied via a `MaterialTheme` wrapper in
`MainActivity`, following the layered-package convention the codebase
already uses (`ui/askgemini/`, `data/gemini/`, etc. — this adds
`ui/theme/`). The theme is a single fixed dark palette, not a
system-following light/dark pair: the app is single-user, the "Deep"
direction is inherently dark, and a light-mode variant would be
speculative work with no current use case (YAGNI).

Add `androidx.compose.material:material-icons-core` (new dependency,
approved by user) for `Icons.Filled.Send`, `Icons.Filled.Mic`,
`Icons.Filled.MoreVert` — same Compose BOM version family already in
use, first-party Jetpack library.

### Color tokens (`ui/theme/Color.kt`)

Named constants, not inline hex, per the project's no-magic-values rule:

| Token | Hex | Usage |
|---|---|---|
| `SurfaceBase` | `#0E1B29` | Screen background, status bar |
| `SurfaceRaised` | `#0F2536` | Rounded content surface below the app bar |
| `SurfaceComposer` | `#122A3D` | Prompt input field background |
| `BorderComposer` | `#1E3C52` | Prompt input field border, icon-button outlines |
| `Accent` | `#3FA98A` | Send button, assistant avatar mark |
| `AccentMuted` | `#8FD9C4` | Mic icon stroke, brand mark text |
| `TextPrimary` | `#E7EEF5` | Body text, reply text, app bar title |
| `TextMuted` | `#6E8398` | Placeholder text, timestamps/eyebrow labels |

### Type

Single family: Android's bundled system **Roboto Flex** (variable font,
no new dependency). Weight 700 for the app bar title and eyebrow-style
labels, weight 400 for body/reply/input text. No serif — the "Deep"
mockup read cleaner in plain sans than the earlier "Editorial" variant.

### Layout / components

- `MainActivity` wraps `AskGeminiScreen` in the new `MaterialTheme`.
- `AskGeminiScreen`'s `Scaffold` gains a `TopAppBar`: title "Gemini",
  trailing `IconButton` with `Icons.Filled.MoreVert` — no menu items
  wired up yet, this is the anchor for a future settings/history entry
  point so the next feature doesn't force a structural redesign.
- Body content moves into a `Surface` with top-rounded corners
  (`RoundedCornerShape(24.dp, 24.dp, 0.dp, 0.dp)`, color
  `SurfaceRaised`) layered over `SurfaceBase`, matching the mockup's
  layered-surface look.
- Reply area becomes a small header row (assistant avatar dot in
  `Accent`, "Gemini" label) above the reply text, instead of a bare
  `Text`.
- The prompt field + mic + send collapse from three stacked full-width
  elements into a single composer row: `OutlinedTextField` (weight 1,
  `SurfaceComposer`/`BorderComposer` colors) + two circular
  `IconButton`s (`Icons.Filled.Mic`, `Icons.Filled.Send`), matching the
  mockup. Loading/listening states keep disabling the same way they do
  today, just expressed as icon-button `enabled` instead of button-text
  swaps — the `SEND_BUTTON_LOADING_LABEL`/`SPEAK_BUTTON_LISTENING_LABEL`
  text constants are replaced by content-description changes for
  accessibility, since there's no visible label to swap anymore.
- The existing voice-confirm `AlertDialog` gets the same token palette
  (surface/text colors) applied via `MaterialTheme`'s
  `AlertDialog` defaults — no structural change to the dialog itself,
  it already has the right Send/Discard button structure.
- Error text keeps using `MaterialTheme.colorScheme.error` (Compose's
  built-in error color from the new dark scheme), no separate token
  needed.

## Scope

In scope:
- New `ui/theme/` package (`Color.kt`, `Theme.kt`) with the fixed dark
  `ColorScheme`.
- `MainActivity` wraps content in `MaterialTheme`.
- `AskGeminiScreen` restructured per the layout section above: top app
  bar, rounded content surface, icon-based composer row, avatar-header
  reply block.
- `material-icons-core` dependency addition.
- No change to `AskGeminiViewModel`, `AskGeminiUiState`, or any
  `data`/`di` layer code — this is UI-layer only. No new state fields;
  existing loading/listening/error/reply state drives the same
  conditionals, just rendered differently.

Out of scope (explicitly deferred, confirmed with user):
- Any interaction-model change (chat/history view, multi-turn
  conversation) — single prompt/reply screen stays as-is.
- Light-mode / dynamic (Material You) theming.
- Wiring up the new overflow menu to any real destination — it's a
  visual anchor only, until a future feature needs it.
- Any change to voice capture/TTS logic, permission flow, or the
  Gemini repository/client/notifier layers.

## Testing

This is a UI-layer visual/structural change with no new business logic,
so no new unit tests are introduced beyond what already exists.
`AskGeminiViewModelTest` and the repository/client/notifier tests are
unaffected (they don't touch Compose UI) and must continue to pass
unmodified — verifies the refactor didn't change any observable
behavior, only presentation. Per the project's testing rule, if any
existing test needs to change as a result of this work, that's a signal
to stop and confirm the behavior change was intentional before touching
the test.

Manual verification: `./gradlew build lint testDebugUnitTest`, then
install on the physical device (`./gradlew installDebug`) and visually
confirm the screen matches the approved "Deep" mockup — status bar
region, app bar, rounded surface, composer row, reply block, and the
voice-confirm dialog all using the new palette.
