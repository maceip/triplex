# Triplex Liquid Glass Reskin Implementation Plan

## Overview
This document provides a complete file-by-file implementation plan for reskinning Triplex with the liquid glass design system, integrating RikkaUI components, and adding custom dialer-specific components.

Phases 0–2 are **done and building**; they are recorded below as they were actually
built, not as they were originally sketched. Phases 3–5 are still forward-looking
and defer to `reskin.md`, which remains the authority on architecture.

---

## Phase 0: Dependencies & Configuration — DONE

### 0.1 How RikkaUI is consumed: vendored sources, not Maven

RikkaUI is **not** published to any repository, and it cannot be consumed as a
binary here: it is built with Kotlin 2.3.0 while Triplex is on 2.2.21, and Kotlin
metadata is not forward-compatible. `mavenLocal()` was never added and no
`dev.rikkaui:*` artifact exists.

Instead the sources are vendored shadcn-style into
`app/src/main/java/dev/triplex/ui/rikka/`, re-packaged under `dev.triplex.ui.rikka.*`,
and compiled by the app's own compiler:

```
ui/rikka/
├── foundation/          # 14 files: RikkaTheme, RikkaColors, RikkaSpacing, RikkaShapes,
│                        #   RikkaTypography, RikkaIcons, RikkaGlass (glass tokens), …
└── components/ui/       # 23 packages, incl. glass/ (6 files, see Phase 1.1)
```

Upstream lives in `~/RikkaUi`. Changes flow **RikkaUi → triplex** by re-vendoring;
never hand-patch the copy under `ui/rikka/` without making the same change upstream.

`RikkaFontFamily.kt` is deliberately not vendored — it pulls
`org.jetbrains.compose.resources`, which this app does not use.

### 0.2 Real dependency changes

**File: `apps/android/gradle/libs.versions.toml`**
```toml
[versions]
composeBom     = "2025.10.01"   # bumped; material3 1.4.x is what the glass work targets
activityCompose = "1.10.1"
navigationCompose = "2.9.5"
materialIcons  = "1.7.8"
backdrop       = "1.0.0"
swipe          = "1.3.0"
extendedspans  = "1.4.0"

[libraries]
backdrop = { module = "io.github.kyant0:backdrop", version.ref = "backdrop" }
swipe = { module = "me.saket.swipe:swipe", version.ref = "swipe" }
extendedspans = { module = "me.saket.extendedspans:extendedspans", version.ref = "extendedspans" }
androidx-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core", version.ref = "materialIcons" }
```

Two constraints worth remembering:

- **`backdrop` is pinned to 1.0.0.** 2.x requires Kotlin 2.3.21. Do not bump it
  without moving the whole toolchain.
- **`material-icons-core` is now explicit.** material3 1.4.x stopped bringing icons
  in transitively, which broke `Icons.*` in five existing screens
  (`ActiveCallScreen`, `CreateTaskScreen`, `DashboardScreen`, `EnrollmentScreen`,
  `VoiceCloneScreen`). Only core glyphs are used, so `-core`, not `-extended`.

### 0.3 Theme foundation

**File: `ui/theme/LiquidGlassColors.kt`** — Triplex *role* colours only
(`agentAccent`, `callerBubble`, `userBubble`, `answerGreen`, `declineRed`,
`timeline*`), in dark and light sets behind `LocalLiquidGlassColors`.
It deliberately holds **no glass geometry**: blur, refraction and tint live in
`RikkaTheme.glass` (`ui/rikka/foundation/RikkaGlass.kt`).

**File: `ui/theme/LiquidGlassTheme.kt`** — stacks the three token systems:

```kotlin
LiquidGlassTheme(darkTheme) {           // provides LocalLiquidGlassColors
    RikkaTheme(Zinc, Violet, isDark) {  // provides RikkaTheme.{colors,glass,spacing,…}
        TriplexTheme(darkTheme, dynamicColor = false) { … }   // Material 3 still underneath
    }
}
```

Material 3 stays available on purpose: screens that have not been reskinned yet
keep rendering. `LiquidGlassDesign.colors` is the single accessor for the roles.

---

## Phase 1: Core Liquid Glass Components — DONE

### 1.1 Glass primitives live in RikkaUI, not in `ui/components/`

The originally-planned `ui/components/{GlassButton,GlassCard,GlassSurface}.kt` were
**not** created, and a first-draft `ui/components/GlassComponents.kt` has been
**deleted** — it duplicated the real implementations. The primitives are RikkaUI
components, vendored at `ui/rikka/components/ui/glass/`:

| File | Provides |
| --- | --- |
| `GlassBackdrop.kt` | `LocalGlassBackdrop`, `rememberGlassBackdrop()`, `Modifier.glassBackdropSource()`, `GlassContainer` |
| `GlassSurface.kt` | `GlassSurface`, `Modifier.glassSurface(…)`, `GlassLevel`, `GlassDefaults` |
| `GlassCard.kt` | `GlassCard` (optionally clickable) |
| `GlassButton.kt` | `GlassButton`, `GlassIconButton`, `GlassButtonDefaults` |
| `GlassPanel.kt` | `GlassPanel` — floating chrome (nav bars, sheets) |
| `GlassChip.kt` | `GlassChip` — quick-reply pills |

They are **Android-only** (`components/src/androidMain` upstream) because
`io.github.kyant0:backdrop` is an Android library.

**Glass needs a backdrop or it is just a tinted rectangle.** `LocalGlassBackdrop`
defaults to `emptyBackdrop()`, so a glass component outside a `GlassContainer`
still draws — it just has nothing to refract. The app-wide source is
`TriplexBackground` (`ui/components/TriplexComponents.kt`), which is now a
`GlassContainer`: its gradient and aura circles are recorded as the backdrop layer
and every screen already using it gets real refraction with no call-site change.
This is exactly the drop-in seam `reskin.md` §4 asks for.

Two rules that follow from how the recording works:

- Glass surfaces must be **siblings drawn after** the backdrop source, never
  children of it — sampling a layer you belong to feeds back into itself.
- `RikkaShapes.*` are typed `Shape`, not `CornerBasedShape`. Do not pass them
  straight to `GlassSurface(shape = …)`; go through `GlassDefaults.shape()` /
  `GlassButtonDefaults.shape()`, which cast safely. `backdrop`'s `lens()` throws
  `UnsupportedOperationException` on a non-`CornerBasedShape`.

Graceful degradation is built into the library: `blur()` is a no-op below API 31
and `lens()` below API 33, so glass thins out on old devices instead of crashing.

### 1.2 Dialer-specific components

**File: `ui/components/dialer/GlassDialPad.kt`** — the 12-key grid plus
`GlassDialKey`, built on `GlassIconButton`. Layout is data
(`private val DIAL_PAD_ROWS`), haptics are `HapticFeedbackConstants.KEYBOARD_TAP`
fired in `onClick`, and each key carries `clearAndSetSemantics` so TalkBack reads
"2, A B C" rather than the raw glyph pile.

> **Signature note:** `GlassDialKey` is `(digit, onClick, modifier, letters, size)`.
> The parameter order changed during the rewrite; it currently has no external
> callers, so nothing needed updating.

---

## Phase 2: Custom Agent Components — DONE

Files were consolidated rather than split one-per-component: each cluster below is
a single file, because the pieces are only ever used together and share private
helpers.

### 2.1 Voice Clone Enrollment — `ui/components/agent/VoiceCloneEnroll.kt`

Breathing orb driven by mic amplitude, plus the consent → record → upload → preview
states. Integrates with the existing `VoiceCloneViewModel`.

**There is no 3D library.** The orb is a plain `Canvas` — cheap, and it matches
`TriplexVoiceSphere`, which already existed. (An earlier draft of this document
named a garbled non-existent dependency here; disregard it.)

The orb takes `diameter: Dp = 220.dp` and derives its centre from `DrawScope.center`
and its radius from `size.minDimension`. The previous version mixed dp and px —
it sized the `Canvas` in dp and then placed the centre at raw pixel coordinates,
so the orb drifted off-centre on every density but one.

### 2.2 & 2.3 Call History and Incoming Call — `ui/components/call/CallHistoryComponents.kt`

Holds `CallHistoryRow`, the day-grouped list, and `IncomingCallSheet`.

**`SwipeToDismiss` is gone.** `rememberDismissState`, `DismissValue` and
`DismissDirection` were removed in material3 1.4.x, which is what broke this file.
Swipe actions are now `me.saket.swipe`:

```kotlin
SwipeableActionsBox(
    startActions = listOf(SwipeAction(onSwipe = …, icon = { … }, background = …)),
    endActions   = listOf(SwipeAction(…)),
) { /* row content */ }
```

Not yet implemented, despite the original sketch: quick-reply chips on the sheet
(`GlassChip` exists and is ready for them), the slide-to-answer control, and the
transcript timeline wiring — the last two land in `reskin.md` Phase 4, since they
need `CallSession`. `SlideToAnswerControl.kt` was not created; the existing
`IncomingCallDecisionSlider` in `DialerActivity.kt` is the code to move.

> **Signature note:** `onBlock` (on `CallHistoryRow`) and `onQuickReply` (on
> `IncomingCallSheet`) were dropped — both were unimplemented and uncalled.

### 2.4 Live Agent Chat — `ui/components/chat/AgentChatComponents.kt`

Agent/caller/user bubbles as `GlassSurface`s tinted per speaker, plus the timeline
and a three-dot typing indicator (one `rememberInfiniteTransition`, three phase
offsets — not three separate animations).

The previous version did not compile: it called a non-existent
`androidx.compose.ui.unit.sp(12)` and passed `AnnotatedString` to RikkaUI's `Text`,
which accepts `String` only.

`extendedspans` is in the catalog but **not yet used** — it is for the rich-text
pass (timestamps, links) once real transcript data lands in Phase 4.

### 2.5 Telephony icons — `ui/icons/TriplexIcons.kt`

`RikkaIcons` has no telephony glyphs, so `Phone`, `PhoneOff`, `Mic` and `MicOff`
are drawn here in the same lucide idiom (24dp, 2f stroke, round caps) to sit
consistently beside the Rikka set. `RikkaIcons` also has no `Message`; `Mail` is
used where a message glyph is needed.

---

## Phase 3: Architecture Refactoring (8-12 hours)

### 3.1 Unified Call Session Layer

**File: `domain/call/CallSession.kt` (NEW)**
- Single model for all call states (Telecom + SIP)
- Fields: id, stack, phase, party, capabilities, timeline

**File: `domain/call/CallSessionRepository.kt` (NEW)**
- Interface for observing/dispatching call state
- Combines TelecomCallSource + SipCallSource

**File: `data/call/CallSessionRepositoryImpl.kt` (NEW)**
- Merges two call stacks into unified StateFlow
- Dispatches intents to appropriate stack

**File: `data/call/TelecomCallSource.kt` (NEW)**
- Refactored from `DialerCallStore` global object
- Observes `TriplexInCallService` callbacks

**File: `data/call/SipCallSource.kt` (NEW)**
- Refactored from `ScreeningCoordinator`
- Maps SIP timeline to `CallSession` timeline

### 3.2 Shell Activity Consolidation

**File: `dialer/DialerActivity.kt` (MODIFIED)**
- Becomes single shell activity
- Bottom nav: Keypad | Agent
- State-driven incoming/in-call overlays

**File: `MainActivity.kt` (DELETED)**
- Move routes to shell
- Runtime init to `TriplexSipService`

**File: `dialer/AgentScreeningActivity.kt` (DELETED)**
- Content becomes incoming call sheet

### 3.3 Room Database Integration

**File: `data/local/db/TriplexDatabase.kt` (NEW)**
- Room database schema
- Entities: AgentRunEntity, AgentTurnEntity

**File: `data/local/db/AgentRunDao.kt` (NEW)**
- CRUD operations for call runs
- Observe runs for agent home list

---

## Phase 4: Screen Reskinning (6-10 hours)

### 4.1 Keypad Screen

**File: `ui/keypad/KeypadScreen.kt` (NEW)**
- Unified search (contacts + history)
- Glass search field
- Dialpad FAB toggle
- Favorites strip

**File: `ui/keypad/KeypadViewModel.kt` (NEW)**
- Merged from `DialerActivity` logic
- Places calls through `CallSessionRepository`

### 4.2 Agent Screens

**File: `ui/agent/AgentHomeScreen.kt` (NEW)**
- Replaces DashboardScreen
- Previous runs list (glass cards)
- Agent status header

**File: `ui/agent/InboundSetupScreen.kt` (NEW)**
- Config for auto-answer, greeting, automations
- Glass toggle switches
- Voice policy selector

**File: `ui/agent/OutboundSetupScreen.kt` (NEW)**
- Task list + create form
- Glass form fields
- Launch button

### 4.3 Call Screens

**File: `ui/call/incall/InCallScreen.kt` (NEW)**
- Caller info + status
- Glass action grid (mute, speaker, etc.)
- Agent panel (collapsed/expanded)

**File: `ui/call/incoming/IncomingCallSheet.kt` (NEW)**
- Renders over anything (state-driven)
- Agent transcript + chips
- Slide controls

---

## Phase 5: Testing & Polish (4-6 hours)

### 5.1 Update Tests

**File: `ui/DesignSystemGuardTest.kt` (MODIFIED)**
- Scan all of `ui/` for Material imports
- Enforce glass component usage

> **Must exclude `ui/rikka/`.** The guard currently scans a hardcoded
> `src/main/java/dev/triplex/ui/screens`, so the vendored sources are out of reach
> today. Widening the scope to `ui/` will fail immediately: vendored RikkaUI is a
> design system, so it legitimately contains `Color(0x…)`, `Color.White/Black` and
> `TextStyle(` throughout. Exclude the directory — do **not** "fix" the vendored
> sources to satisfy the guard; the next re-vendor would revert it.

**File: `test/java/dev/triplex/...` (NEW)**
- Unit tests for CallSessionRepository
- ViewModel tests for new screens

### 5.2 Device Testing

- Real SIM inbound/outbound
- SIP screening flows
- Multi-SIM account selection
- Call endpoints (Bluetooth, speaker, earpiece)
- Accessibility (TalkBack, Switch Access)

---

## Migration Script — abandoned, do not resurrect

`scripts/migrate_to_liquid_glass.sh` was written to batch-replace Material
components with glass equivalents by pattern substitution. It has been **deleted**,
and nothing like it should be written.

Component migration is not a rename. Every file converted so far needed judgement
that no substitution rule encodes:

- `SwipeToDismiss` has no glass equivalent — it no longer exists at all, and the
  replacement (`SwipeableActionsBox`) has a different shape, different state model
  and different call signature.
- RikkaUI's `Text` takes `String`, Material's takes `String` *or* `AnnotatedString`.
  A blind swap compiles in some files and fails in others, silently dropping
  formatting where it does compile.
- `IconButton`'s `icon` parameter is an `ImageVector`, not the trailing composable
  lambda Material uses.
- `GlassSurface` needs a `CornerBasedShape`; the obvious `RikkaShapes.md` is typed
  `Shape` and throws at runtime, not compile time.
- Real dp/px bugs (§2.1) and non-compiling leftovers (§2.4) were found *because*
  each file was read and rewritten by hand.

The correct process is the one used here: rewrite one file, compile, fix, move on.
`./gradlew :app:compileDebugKotlin` after each file is the whole methodology.

---

## Success Metrics

These are targets, not claims. Checked items are verified; the rest are open.

- [x] **Build passes**: `:app:compileDebugKotlin` succeeds
- [x] **Tests pass**: `:app:testDebugUnitTest` green (8 tests)
- [x] **Design system enforced**: `DesignSystemGuardTest` passes (still `ui/screens`-scoped only)
- [ ] **Glass renders**: verified on a device — blur and refraction actually visible
- [ ] **Agent components work**: voice clone, history, incoming sheet functional against real state
- [ ] **Glass everywhere**: no raw Material3 components in visible UI
- [ ] **Single activity**: MainActivity deleted, shell works
- [ ] **Unified call model**: both Telecom/SIP calls render from one repository

---

## Risk Mitigation

**Risk**: `backdrop` is pinned at 1.0.0 by the Kotlin version  
**Mitigation**: 2.x needs Kotlin 2.3.21; treat the bump as a toolchain project, not a version bump  

**Risk**: RikkaUI is vendored, so upstream fixes do not arrive automatically  
**Mitigation**: Change `~/RikkaUi` first, then re-vendor; never hand-patch `ui/rikka/`  

**Risk**: Performance (blur is expensive)  
**Mitigation**: Not yet measured — no on-device profiling has been done. Blur is a
no-op below API 31 and lensing below API 33, so the floor is safe, but the cost on
a modern device with a full-screen backdrop is unknown. Profile before Phase 4  

**Risk**: Glass renders as flat tint when no backdrop is in scope  
**Mitigation**: `TriplexBackground` provides it app-wide; any new screen that does
not use it must supply its own `GlassContainer`  

**Risk**: Telecom features can't be tested on JVM  
**Mitigation**: Manual smoke tests per phase, checklist-driven  

---

## Status

Verified at the time of writing:

```
./gradlew :app:compileDebugKotlin   # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest    # 8 tests, 0 failures
```

Green: `DesignSystemGuardTest` (1), `VoiceCaptureMeterTest` (3),
`VoicePromptProgressTest` (4).

**Not yet verified:** nothing has been run on a device or emulator. Everything
above is compile-and-unit-test evidence only — no glass effect has been seen
rendering. That is the next checkpoint, and it should happen before Phase 3
architecture work starts, because it is the last cheap moment to discover that the
backdrop plumbing is wrong.

Remaining before the reskin proper (`reskin.md` Phase 0): none of the screens have
been reskinned. The components exist and compile; the screens still render Material.

---

## Branch Strategy

Create feature branch: `feature/liquid-glass-reskin`

```bash
git checkout -b feature/liquid-glass-reskin
# Phase 0-1 commits
# Phase 2 commits (custom components)
# Phase 3 commits (architecture)
# Phase 4 commits (screens)
# Phase 5 commits (tests)
# PR after Phase 2 for review
# Merge after Phase 5 passes
```
