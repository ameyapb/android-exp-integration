# Basic UX Polish (Deep Theme) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the native app's unstyled default Compose UI with the approved "Deep" visual design: a fixed dark navy/teal theme, a layered app-bar-plus-rounded-surface layout, and icon-based composer actions.

**Architecture:** Add a new `ui/theme/` package (`Color.kt`, `Theme.kt`) providing a fixed dark `ColorScheme` and a `GeminiAppTheme` wrapper composable. Wire it into `MainActivity`. Restructure `AskGeminiScreen` to use a `TopAppBar`, a rounded-corner `Surface` for body content, and icon buttons (`FilledIconButton`/`OutlinedIconButton`) for send/mic instead of full-width text `Button`s. No changes to `AskGeminiViewModel`, `AskGeminiUiState`, or any `data`/`di` layer code.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Hilt (unchanged). New dependency: `androidx.compose.material:material-icons-core`.

## Global Constraints

- No magic numbers/strings: every literal that carries meaning gets a named constant (spacing/padding literals that already appear as bare `.dp` values in the existing file follow that same existing precedent — only newly-introduced, semantically-significant, or reused values get named).
- DRY: don't repeat the same color/dimension value as separate literals — reference the token constants from `ui/theme/Color.kt`.
- Existing unit tests (`AskGeminiViewModelTest`, repository/client/notifier tests) must keep passing unmodified — this is a UI-only change with no behavior change. If any existing test needs to change, stop and confirm before touching it.
- No new unit tests required for this plan (per the design spec's Testing section) — no new business logic is introduced, matching the existing precedent that `AskGeminiScreen` has no direct tests yet.
- Fixed dark color scheme only — no light-mode or dynamic (Material You) theming.
- Update `CLAUDE.md`'s native app architecture section in the same change, per the project's own rule to keep it current when architecture changes.

---

### Task 1: Add the `material-icons-core` dependency

**Files:**
- Modify: `android-app/gradle/libs.versions.toml`
- Modify: `android-app/app/build.gradle.kts`

**Interfaces:**
- Produces: `androidx.compose.material.icons.Icons` / `androidx.compose.material.icons.filled.*` available for later tasks to import.

- [x] **Step 1: Add the library entry to the version catalog**

In `android-app/gradle/libs.versions.toml`, add to the `[libraries]` block (keep alphabetical grouping consistent with the existing entries, insert after `hilt-navigation-compose`):

```toml
material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
```

No `version.ref` — this artifact's version is managed by the `compose-bom` platform already declared in `build.gradle.kts`, same as `androidx.compose.material3:material3` and `androidx.compose.ui:ui` are today.

- [x] **Step 2: Add the dependency in the app module**

In `android-app/app/build.gradle.kts`, add this line in the `dependencies` block, directly after `implementation("androidx.compose.material3:material3")`:

```kotlin
    implementation(libs.material.icons.core)
```

- [x] **Step 3: Verify the project builds with the new dependency**

Run: `cd android-app && ./gradlew build`
Expected: `BUILD SUCCESSFUL` — confirms the version catalog entry resolves correctly against the Compose BOM.

- [x] **Step 4: Commit**

```bash
git add android-app/gradle/libs.versions.toml android-app/app/build.gradle.kts
git commit -m "Add material-icons-core dependency for the Deep theme's icon buttons"
```

---

### Task 2: Add the Deep theme color tokens

**Files:**
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/theme/Color.kt`

**Interfaces:**
- Produces: `SurfaceBase`, `SurfaceRaised`, `SurfaceComposer`, `BorderComposer`, `Accent`, `AccentMuted`, `TextPrimary`, `TextMuted` (all `androidx.compose.ui.graphics.Color`) — consumed by Task 3 (`Theme.kt`) and Task 4 (`AskGeminiScreen.kt`).

- [x] **Step 1: Create the color token file**

```kotlin
package com.ameyapb.androidexp.ui.theme

import androidx.compose.ui.graphics.Color

val SurfaceBase = Color(0xFF0E1B29)
val SurfaceRaised = Color(0xFF0F2536)
val SurfaceComposer = Color(0xFF122A3D)
val BorderComposer = Color(0xFF1E3C52)
val Accent = Color(0xFF3FA98A)
val AccentMuted = Color(0xFF8FD9C4)
val TextPrimary = Color(0xFFE7EEF5)
val TextMuted = Color(0xFF6E8398)
```

- [x] **Step 2: Verify the project still builds**

Run: `cd android-app && ./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/ui/theme/Color.kt
git commit -m "Add Deep theme color tokens"
```

---

### Task 3: Add the theme wrapper and apply it in MainActivity

**Files:**
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/theme/Theme.kt`
- Modify: `android-app/app/src/main/java/com/ameyapb/androidexp/MainActivity.kt`

**Interfaces:**
- Consumes: `SurfaceBase`, `SurfaceRaised`, `TextPrimary`, `Accent` from Task 2's `Color.kt`.
- Produces: `@Composable fun GeminiAppTheme(content: @Composable () -> Unit)` — consumed by `MainActivity` in this task, and available to any future screen.

- [x] **Step 1: Create the theme file**

```kotlin
package com.ameyapb.androidexp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GeminiDarkColorScheme = darkColorScheme(
    background = SurfaceBase,
    onBackground = TextPrimary,
    surface = SurfaceRaised,
    onSurface = TextPrimary,
    primary = Accent,
    onPrimary = SurfaceBase,
)

@Composable
fun GeminiAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GeminiDarkColorScheme,
        content = content,
    )
}
```

This is a fixed dark scheme (no `isSystemInDarkTheme()` branching, no dynamic color) — the app is single-user and the Deep direction is inherently dark, per the design spec's YAGNI call on light-mode theming. Roles not listed here (`error`, `secondary`, `outline`, etc.) fall back to Material3's baseline dark-theme defaults; `AskGeminiScreen` reaches directly into `Color.kt` tokens (`SurfaceComposer`, `BorderComposer`, `AccentMuted`, `TextMuted`) wherever it needs a color outside these roles, rather than overloading the `ColorScheme` with roles that don't map cleanly onto Material3's semantics.

- [x] **Step 2: Wrap the screen content in MainActivity**

In `android-app/app/src/main/java/com/ameyapb/androidexp/MainActivity.kt`, add the import:

```kotlin
import com.ameyapb.androidexp.ui.theme.GeminiAppTheme
```

Replace:

```kotlin
        setContent {
            AskGeminiScreen()
        }
```

with:

```kotlin
        setContent {
            GeminiAppTheme {
                AskGeminiScreen()
            }
        }
```

- [x] **Step 3: Verify build and run the existing test suite**

Run: `cd android-app && ./gradlew build testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all existing tests pass unchanged (`MainActivity` has no direct tests; this confirms the wrap didn't break anything else).

- [x] **Step 4: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/ui/theme/Theme.kt android-app/app/src/main/java/com/ameyapb/androidexp/MainActivity.kt
git commit -m "Add GeminiAppTheme and apply it in MainActivity"
```

---

### Task 4: Restructure AskGeminiScreen to the Deep layout

**Files:**
- Modify: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiScreen.kt` (full replacement of the file body)

**Interfaces:**
- Consumes: `SurfaceComposer`, `BorderComposer`, `AccentMuted` from Task 2's `Color.kt`; `AskGeminiUiState`'s existing fields (`promptText`, `isLoading`, `isListening`, `errorMessage`, `replyText`, `pendingVoiceTranscript`) and `AskGeminiViewModel`'s existing methods (`onPromptTextChanged`, `onSendClicked`, `onMicClicked`, `onMicPermissionDenied`, `onVoiceTranscriptConfirmed`, `onVoiceTranscriptDiscarded`) — all unchanged from before this plan.
- Produces: same public `@Composable fun AskGeminiScreen(viewModel: AskGeminiViewModel = hiltViewModel())` signature — no caller (`MainActivity`) needs to change beyond Task 3's wrap.

The mockup's timestamp eyebrow label ("Today, 9:41 AM") is intentionally omitted: `AskGeminiUiState` has no timestamp field, and adding one is out of scope per the design spec (no new state fields in this plan).

- [x] **Step 1: Replace the file contents**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ameyapb.androidexp.ui.theme.AccentMuted
import com.ameyapb.androidexp.ui.theme.BorderComposer
import com.ameyapb.androidexp.ui.theme.SurfaceComposer
import com.ameyapb.androidexp.util.isPermissionGranted

private const val APP_BAR_TITLE = "Gemini"
private const val OVERFLOW_MENU_CONTENT_DESCRIPTION = "More options"
private const val PROMPT_FIELD_LABEL = "Ask Gemini"
private const val MIC_BUTTON_CONTENT_DESCRIPTION = "Speak prompt"
private const val MIC_BUTTON_LISTENING_CONTENT_DESCRIPTION = "Listening for prompt"
private const val SEND_BUTTON_CONTENT_DESCRIPTION = "Send prompt"
private const val REPLY_AUTHOR_LABEL = "Gemini"
private const val VOICE_CONFIRM_DIALOG_TITLE = "Confirm prompt"
private const val VOICE_CONFIRM_SEND_LABEL = "Send"
private const val VOICE_CONFIRM_DISCARD_LABEL = "Discard"
private const val VOICE_TRANSCRIPT_DIALOG_MAX_HEIGHT_DP = 240
private const val CONTENT_SURFACE_CORNER_RADIUS_DP = 24
private const val ICON_BUTTON_SIZE_DP = 44
private const val AVATAR_DOT_SIZE_DP = 10

@Composable
fun AskGeminiScreen(viewModel: AskGeminiViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onMicClicked() else viewModel.onMicPermissionDenied()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(APP_BAR_TITLE) },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.MoreVert, contentDescription = OVERFLOW_MENU_CONTENT_DESCRIPTION)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            shape = RoundedCornerShape(
                topStart = CONTENT_SURFACE_CORNER_RADIUS_DP.dp,
                topEnd = CONTENT_SURFACE_CORNER_RADIUS_DP.dp,
            ),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val errorMessage = uiState.errorMessage
                    if (errorMessage != null) {
                        Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
                    } else if (uiState.replyText.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(AVATAR_DOT_SIZE_DP.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                            Text(
                                text = REPLY_AUTHOR_LABEL,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(text = uiState.replyText, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.promptText,
                        onValueChange = viewModel::onPromptTextChanged,
                        modifier = Modifier.weight(1f),
                        label = { Text(PROMPT_FIELD_LABEL) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceComposer,
                            unfocusedContainerColor = SurfaceComposer,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = BorderComposer,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )

                    OutlinedIconButton(
                        onClick = {
                            if (isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
                                viewModel.onMicClicked()
                            } else {
                                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !uiState.isListening && !uiState.isLoading,
                        modifier = Modifier.size(ICON_BUTTON_SIZE_DP.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            containerColor = SurfaceComposer,
                            contentColor = AccentMuted,
                        ),
                        border = BorderStroke(1.dp, BorderComposer),
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = if (uiState.isListening) {
                                MIC_BUTTON_LISTENING_CONTENT_DESCRIPTION
                            } else {
                                MIC_BUTTON_CONTENT_DESCRIPTION
                            },
                        )
                    }

                    FilledIconButton(
                        onClick = viewModel::onSendClicked,
                        enabled = !uiState.isLoading && !uiState.isListening && uiState.promptText.isNotBlank(),
                        modifier = Modifier.size(ICON_BUTTON_SIZE_DP.dp),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = SEND_BUTTON_CONTENT_DESCRIPTION)
                    }
                }
            }
        }
    }

    val pendingVoiceTranscript = uiState.pendingVoiceTranscript
    if (pendingVoiceTranscript != null) {
        AlertDialog(
            onDismissRequest = viewModel::onVoiceTranscriptDiscarded,
            title = { Text(VOICE_CONFIRM_DIALOG_TITLE) },
            text = {
                Text(
                    text = pendingVoiceTranscript,
                    modifier = Modifier
                        .heightIn(max = VOICE_TRANSCRIPT_DIALOG_MAX_HEIGHT_DP.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Button(onClick = viewModel::onVoiceTranscriptConfirmed) { Text(VOICE_CONFIRM_SEND_LABEL) }
            },
            dismissButton = {
                Button(onClick = viewModel::onVoiceTranscriptDiscarded) { Text(VOICE_CONFIRM_DISCARD_LABEL) }
            },
        )
    }
}
```

Note what changed from the pre-plan version: the two full-width `Button`s (Send / Speak, with their text-swap loading states) are replaced by `FilledIconButton`/`OutlinedIconButton` with real icons; the loading/listening states now drive `enabled` and `contentDescription` instead of visible label text, so `SEND_BUTTON_LABEL`, `SEND_BUTTON_LOADING_LABEL`, `SPEAK_BUTTON_LABEL`, and `SPEAK_BUTTON_LISTENING_LABEL` are removed (dead code — nothing references them anymore). The voice-confirm `AlertDialog` is untouched structurally; it now inherits the Deep palette automatically through `MaterialTheme`'s default `AlertDialog` colors (`GeminiAppTheme`'s `surface`/`onSurface`/`background` roles), so no separate dialog styling is needed.

- [x] **Step 2: Run the full verification suite**

Run: `cd android-app && ./gradlew build lint testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, zero lint issues, all existing tests pass unchanged. This confirms the restructure compiles cleanly and didn't alter any tested behavior (`AskGeminiViewModelTest` and the repository/client/notifier tests don't touch Compose UI, so they must pass exactly as before).

- [x] **Step 3: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiScreen.kt
git commit -m "Restructure AskGeminiScreen to the Deep theme layout"
```

---

### Task 5: On-device verification and documentation

**Files:**
- Modify: `CLAUDE.md`

- [x] **Step 1: Install and visually verify on the physical device**

Run: `cd android-app && ./gradlew installDebug`

On the device, open the app and confirm, against the approved "Deep" mockup from the brainstorming session:
- Status bar area and app bar sit on the dark navy background (`#0E1B29`), with the "Gemini" title and a visible overflow (kebab) icon on the right.
- Body content sits in a slightly lighter rounded-top surface (`#0F2536`).
- The prompt field, mic button, and send button are a single row at the bottom of the content area, with the send button shown as a filled teal circle and the mic button as an outlined circle.
- Sending a prompt and receiving a reply shows the "Gemini" label with a small teal avatar dot above the reply text.
- Tapping the mic button, confirming/discarding the voice transcript dialog, and sending a voice-confirmed prompt still work exactly as before (Phase 2 behavior unchanged) — only the dialog's colors should look different (dark surface instead of the previous unstyled default).

If anything looks visually wrong (wrong color, misaligned icon, overlapping text), fix it in `AskGeminiScreen.kt` or `Theme.kt`/`Color.kt` before proceeding, then re-run `./gradlew installDebug` and re-check.

- [x] **Step 2: Update CLAUDE.md's native app architecture section**

In `CLAUDE.md`, in the "### Native Android app (`android-app/`)" section, add a new bullet after the existing `data/voice/` bullet documenting the new theme package:

```markdown
- `ui/theme/`: `Color.kt` (named color tokens: `SurfaceBase`, `SurfaceRaised`, `SurfaceComposer`, `BorderComposer`, `Accent`, `AccentMuted`, `TextPrimary`, `TextMuted`) and `Theme.kt` (`GeminiAppTheme`, wrapping a fixed dark `ColorScheme` — no light-mode or dynamic/Material You theming, since the app is single-user and the chosen "Deep" visual direction is inherently dark). Applied in `MainActivity` around `AskGeminiScreen`. `AskGeminiScreen` was restructured to match: a `TopAppBar` (title plus an overflow icon button that is currently a visual anchor only, not wired to a menu yet) over a rounded-top-corner `Surface` for body content, and `FilledIconButton`/`OutlinedIconButton` (`material-icons-core` dependency) for send/mic instead of full-width text buttons, with loading/listening state now expressed via `enabled`/`contentDescription` rather than button-label text swaps. Chosen via `superpowers:brainstorming`'s visual companion; design spec: `docs/superpowers/specs/2026-09-15-basic-ux-polish-design.md`.
```

- [x] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "Document the Deep theme in CLAUDE.md's native app architecture section"
```

## Self-Review Notes

- **Spec coverage:** color tokens (Task 2), fixed dark `ColorScheme` (Task 3), `TopAppBar` + rounded surface + icon composer row + avatar reply header (Task 4), `material-icons-core` dependency (Task 1), on-device visual verification against the mockup (Task 5), `CLAUDE.md` update (Task 5) — every Decision/Scope bullet from the spec maps to a task.
- **Placeholder scan:** no TBDs; every step has literal file paths and complete code.
- **Type consistency:** `AskGeminiScreen`'s consumed `AskGeminiViewModel` members (`onPromptTextChanged`, `onSendClicked`, `onMicClicked`, `onMicPermissionDenied`, `onVoiceTranscriptConfirmed`, `onVoiceTranscriptDiscarded`) and `AskGeminiUiState` fields (`promptText`, `isLoading`, `isListening`, `errorMessage`, `replyText`, `pendingVoiceTranscript`) are used exactly as named in the current (unmodified) `AskGeminiViewModel`/`AskGeminiUiState` — verified against the pre-plan file read during brainstorming.
