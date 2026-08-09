# Triplex UI reskin — architecture & wiring plan

Status: proposed, awaiting approval
Scope: `apps/android` UI + presentation-layer wiring only. The Rust agent core, PJSIP stack, SODA ASR, Inflect TTS, and gateway contracts do not change except at the narrow seams named below.

Ground rules for this effort:

1. **No Compose is written until this plan and the custom design system are approved.** This document defines where the design system plugs in, not what it looks like.
2. Every screen consumes state through a ViewModel and dispatches sealed intents. No screen talks to a service companion object, a global singleton `object`, or a ContentProvider directly.
3. The two call stacks (Telecom/SIM and Plivo/SIP) stay separate below the domain layer; the UI sees exactly one call model.

---

## 1. Where the app is today

Three disconnected UI surfaces, two call stacks, three visual languages:

| Surface | Entry | What it holds | Problems |
|---|---|---|---|
| Agent app | `MainActivity` → `ui/navigation/Navigation.kt` NavHost | Enrollment, Dashboard (tasks), CreateTask, VoiceClone | Second launcher icon; owns native-runtime lifecycle in `onCreate`/`onDestroy` (rotation tears down live SIP); Dashboard mixes agent status + outbound tasks |
| Dialer | `DialerActivity` (launcher #2, `DIAL`/`tel:`, singleTask, showWhenLocked) | 1,133-line file containing keypad, recents, contacts, favorites, in-call UI, screening UI, and the slide-to-answer control, all as private composables switching on internal state | Telecom capability is complete in code (`TriplexInCallService`) but the UI is a monolith; raw Material widgets; per-section search instead of unified search; directory repo is one-shot and manually constructed |
| Screening | `AgentScreeningActivity` | Live SIP screening conversation with automation chips | Own hard-coded cream/moss palette; duplicates `ScreeningContent` inside `DialerActivity`; reachable only via notification |

Call state today:

- **Telecom stack:** `TriplexInCallService` → `DialerCallStore` (global `object` + `StateFlow`) → `InCallContent`. Actions are `companion object` statics (`answerCurrent()`, `toggleMute()`, …). Capabilities already implemented: answer/decline/reject-with-text, deflect-to-agent, mute, speaker + `CallEndpoint` routing, hold, DTMF, add call, switch, merge, swap, multi-SIM account selection, voicemail, missed-call + CallStyle notifications.
- **SIP stack:** `TelephonyController` (PJSIP) exposes `callState`, `callTranscript` (SODA streaming partials), `agentUtterance`, `conversationTurns`; `ScreeningCoordinator` polls it and builds an ephemeral `ScreeningSession`; agent auto-answers inbound SIP calls and speaks via Inflect. Outbound: `OutboundCallCoordinator` (task → grant → INVITE).
- **Nothing is persisted.** Conversation turns, screening sessions, and automation outcomes evaporate when the process dies. There is no Room database; DataStore is declared in Gradle but unused.

Dead/duplicated code that this plan retires: `ui/screens/ActiveCallScreen.kt` + `ActiveCallViewModel` (never routed), `ScreeningContent` in `DialerActivity` (duplicates `AgentScreeningActivity`), the legacy green `res/values/colors.xml` palette.

---

## 2. Target shape

### 2.1 One shell activity

`DialerActivity` becomes the single application shell (it already holds the manifest identity that matters: launcher, `DIAL`/`tel:` filters, `singleTask`, `showWhenLocked`, `turnScreenOn`, and it is the full-screen-intent target). Renaming is optional and deferred.

- `MainActivity` is retired: its NavHost content moves into the shell; the manifest entry becomes an `activity-alias` pointing at `DialerActivity` (preserves launcher shortcuts/pinning), then is deleted once we are comfortable.
- `AgentScreeningActivity` is retired in Phase 4; its content becomes the incoming-call sheet rendered by the shell.
- **Prerequisite:** native runtime + SIP lifecycle ownership moves out of `MainActivity` (`initializeNativeRuntime()` / `onDestroy` → `telephonyController.shutdown()`) into application scope driven by `TriplexSipService`, which already exists and runs foreground. Today a configuration change on `MainActivity` shuts down a live SIP call; that bug disappears with the move.

### 2.2 Navigation map

Bottom navigation with exactly two sections, matching the four surfaces requested:

```
DialerActivity (shell)
├─ [gate] enrollment            — full-screen, shown until device token exists (existing EnrollmentScreen)
├─ Tab: keypad (start)          — dialer home: search + history + dialpad (§3.1)
├─ Tab: agent                   — agent home: previous runs + status (§3.2)
│   ├─ agent/voice              — voice clone enrollment (existing VoiceCloneScreen, moved route)
│   ├─ agent/inbound            — inbound call agent setup (new)
│   ├─ agent/outbound           — outbound call agent setup (new; absorbs CreateTask)
│   └─ agent/run/{runId}        — run detail: full transcript timeline (new)
├─ [overlay] incoming call sheet — bottom sheet, state-driven, not a nav destination (§3.3)
└─ [state-driven] in-call        — full-screen surface presented while a call is active (§3.4)
```

Two rules that keep telephony sane:

- **Call UI is derived from state, not from the back stack.** The shell observes `CallSessionRepository.session`; a ringing session presents the sheet over whatever is on screen, an active session presents the in-call surface. Process death and re-entry through notifications recompose the correct surface with zero navigation bookkeeping.
- The incoming sheet is an overlay, not a destination, so it survives tab switches (user can browse contacts while the agent screens a call, Pixel-style).

Navigation Compose 2.8.5 is already in the catalog; routes migrate to the type-safe kotlinx-serialization API while we are moving them.

### 2.3 Unified call session layer (the core wiring)

The single biggest cause of the current fragmentation is that Telecom calls and SIP calls have separate models, separate action paths, and separate notification pipelines. All four requested surfaces need both. We introduce one domain layer:

```
domain/call/
├── CallSession.kt        # the one model every call surface renders
├── CallTimeline.kt       # transcript timeline entries
├── CallIntent.kt         # sealed user intents
└── CallSessionRepository.kt (interface) + data/call/CallSessionRepositoryImpl.kt
```

Model sketch (final field set to be settled during Phase 1 review):

```kotlin
enum class CallStack { TELECOM, SIP }
enum class CallPhase { RINGING, DIALING, SCREENING, ACTIVE, HOLDING, ENDING, ENDED }
enum class AgentMode { NONE, SCREENING, AUTOMATION, HANDOFF }   // who is driving the call

data class CallParty(
    val number: String,
    val displayName: String,      // resolved via directory repo
    val contactId: Long?,
    val photoUri: String?,
)

data class CallCapabilities(
    val canAnswer: Boolean, val canDecline: Boolean, val canTextReply: Boolean,
    val canHold: Boolean, val canMute: Boolean, val canSendDtmf: Boolean,
    val canAddCall: Boolean, val canMerge: Boolean, val canSwap: Boolean,
    val canDeflectToAgent: Boolean,        // telecom, pre-answer, carrier-dependent
    val agentHasMedia: Boolean,            // true only on the SIP stack
    val audioEndpoints: List<AudioEndpointUi>,
)

sealed interface TimelineEntry {                       // one transcript model for every surface
    data class AgentUtterance(
        val text: String, val atMs: Long,
        val playbackDurationMs: Long,                  // known: pcm.size / 16 kHz
        val complete: Boolean,
    ) : TimelineEntry
    data class CallerUtterance(val text: String, val atMs: Long, val final: Boolean) : TimelineEntry
    data class UserReply(val text: String, val atMs: Long) : TimelineEntry   // chip the agent speaks
    data class SystemEvent(val kind: SystemEventKind, val atMs: Long) : TimelineEntry
}

data class CallSession(
    val id: String,
    val stack: CallStack,
    val direction: CallDirection,
    val party: CallParty,
    val phase: CallPhase,
    val agentMode: AgentMode,
    val connectedAtMs: Long?,
    val capabilities: CallCapabilities,
    val timeline: List<TimelineEntry>,
    val otherCallCount: Int,
    val statusMessage: String,             // transfer errors, transcription status, …
)

sealed interface CallIntent {
    data object Answer : CallIntent                      // human takes the call
    data object Decline : CallIntent
    data object DeclineWithMessage : CallIntent
    data object HangUp : CallIntent
    data class HandOffToAgent(val automationId: String?) : CallIntent
    data class SpeakReply(val text: String) : CallIntent // agent speaks user's chosen reply
    data object ToggleMute : CallIntent
    data object ToggleHold : CallIntent
    data class SelectAudioEndpoint(val id: String) : CallIntent
    data class SendDtmf(val digit: Char) : CallIntent
    data object AddCall : CallIntent
    data object SwitchCall : CallIntent
    data object MergeCalls : CallIntent
    data object SwapConference : CallIntent
}
```

`CallSessionRepositoryImpl` responsibilities:

- Combine the telecom source and the SIP source into `StateFlow<CallSession?>` (plus `StateFlow<List<CallSession>>` for the multi-call chip). Telecom wins the "primary" slot when both stacks have a call; the SIP screening session renders inside the sheet regardless.
- `dispatch(intent: CallIntent)` routes to the right stack. No UI code ever branches on `CallStack` to perform an action; it only branches on `capabilities` to decide what to render.
- Resolve caller identity through `DialerDirectoryRepository` (number → contact name/photo), replacing the raw `callerDisplayName` handling in `TriplexInCallService.publish` and the `PhoneNumberUtils.formatNumber` call in the screening activity.
- Emit lifecycle events consumed by `AgentRunRecorder` (§2.4) and the unified notification controller.

Refactors this forces (behavior-preserving):

| Today | Becomes |
|---|---|
| `DialerCallStore` global `object` | Internal telecom source owned by the repository; deleted as a public API |
| `TriplexInCallService` companion statics (`answerCurrent`, `toggleMute`, …) | `TelecomCallController` interface; the service registers itself with the repository in `onCreate`/`onDestroy` (`@AndroidEntryPoint` + Hilt injection). Same active-service pattern, but behind a seam ViewModels can fake |
| `ScreeningCoordinator` building `ScreeningSession` + its own notification | Publishes into the same repository as the SIP source; keeps SIP registration bootstrap. `ScreeningSession` (gateway DTO) stops being a UI model |
| `DialerCallNotificationManager` + `ScreeningCoordinator.showScreeningNotification` (two parallel pipelines) | One `CallNotificationController` observing the repository: CallStyle incoming/ongoing/missed for both stacks; actions post `CallIntent`s through a single receiver; full-screen intent targets the shell |
| `TelephonyController.conversationTurns` / `agentUtterance` / `callTranscript` consumed ad hoc | Mapped once into `TimelineEntry` inside the SIP source |

Seam changes inside `TelephonyController` (small, named, reviewed with the telephony owner):

1. `speakUserReply(text: String)` — public wrapper over the existing private `speakAgent`, used by the sheet's reply chips. Appends a `UserReply` turn instead of a `TRIPLEX` turn.
2. `ConversationSpeaker` gains `USER` (or the mapping happens at the source layer — decide in Phase 1 review).
3. Auto-answer (`onSipEvent` → `INCOMING_CALL` → `answer()`) is gated on `AgentConfigRepository.inbound.autoAnswerAll` instead of being unconditional. Default stays **on** ("agent answers all calls").
4. Greeting text and automation openings read from config instead of the hard-coded `SCREENING_GREETING` / `when (automationId)` strings.

### 2.4 New data layer

**Room (new dependency: `room-runtime`, `room-ktx`, KSP compiler — KSP is already configured):**

```
data/local/db/
├── TriplexDatabase.kt
├── AgentRunDao.kt
├── AgentCallRunEntity   (id, stack, direction, agentMode, counterpartNumber, counterpartName,
│                         automationId?, taskId?, startedAtMs, endedAtMs?, endReason?, outcomeSummary?)
└── AgentTurnEntity      (runId, seq, speaker AGENT|CALLER|USER|SYSTEM, text, atMs, final)
```

`AgentRunRecorder` (Hilt singleton, app-scope coroutine) subscribes to `CallSessionRepository` and writes runs incrementally — a run row on agent attach, turn rows as the timeline grows, end reason on disconnect — so a crash preserves the partial transcript. This is what makes "main agent view shows previous agent call runs" possible; today that data does not outlive the call.

**DataStore (dependency already declared, currently unused):**

```
data/local/AgentConfigRepository.kt
├── inbound:  autoAnswerAll (default true), greetingText (default = current SCREENING_GREETING),
│             enabledAutomationIds (seeded from AutomationCatalog.inbound),
│             voicePolicy PRESET|CLONED, quickReplies (seeded defaults)
└── outbound: defaultVoicePolicy, confirmBeforeDial
```

Cloned-voice selection stays fail-closed per `docs/RUNTIME_INVARIANTS.md` §7.6: `CLONED` is selectable only while `GatewayApi.getVoiceProfile` reports `synthesis_ready`, and a missing profile at call time is an error, never a silent downgrade.

**`DialerDirectoryRepository` promotion** (currently manual construction, one-shot `refresh()` on resume):

- Becomes a Hilt `@Singleton` with `@ApplicationContext`.
- Registers `ContentObserver`s on `CallLog.Calls.CONTENT_URI` and `ContactsContract` so recents/contacts are live flows (call log updates the moment a call ends).
- Adds: starred/favorites (already queried, unused), photo/lookup URIs, per-number history (for a recents detail row), missed-call read marking (`WRITE_CALL_LOG` is already granted), and a search index — normalized number matching plus name matching, with an optional T9 digit-map (decision 5).
- Keeps the 100-row page for recents but pages on scroll.

### 2.5 Package layout after the move

```
ui/
├── shell/        # shell scaffold, bottom nav, state-driven call presentation, nav graph
├── keypad/       # KeypadScreen + KeypadViewModel + search models
├── agent/        # AgentHomeScreen, InboundSetupScreen, OutboundSetupScreen, RunDetailScreen,
│                 # VoiceCloneScreen (moved), + ViewModels
├── call/
│   ├── incoming/ # IncomingCallSheet + IncomingCallViewModel
│   ├── incall/   # InCallScreen + InCallViewModel + AgentPanel
│   └── shared/   # TranscriptTimeline, CallActionGrid, AudioRoutePicker, DtmfPad (slot components)
├── enrollment/   # existing EnrollmentScreen (moved)
├── components/   # design-system wrappers — the ONLY place Material primitives may appear
└── theme/        # token surface the custom design system will replace
```

`dev.triplex.dialer` keeps only platform integration: `TriplexInCallService`, `TriplexCallScreeningService`, notification controller, receivers. Everything visual leaves that package.

---

## 3. The four surfaces

### 3.1 Keypad (dialer home)

Contact search and call history move into this one screen; the four-tab `DialerSection` enum is deleted.

```
┌──────────────────────────────┐
│ [search field: name/number]  │ ← unified search: contacts + call history
│ (setup banners, if pending)  │ ← default-dialer / screening role / notifications / directory
│                              │
│ Recents (grouped by day)     │ ← default body; favorites strip above it
│  · row → call / expand:      │
│    history, add contact, …   │
│                              │
│           ( ⌨ FAB )          │ ← toggles dialpad overlay
│ ┌──────────────────────────┐ │
│ │ smart-dial matches       │ │ ← typing digits filters contacts by number (+ T9 name)
│ │ dial display  [paste]    │ │
│ │ 1 2 3 / 4 5 6 / 7 8 9    │ │
│ │ * 0(+) #                 │ │
│ │ SIM chooser · call btn   │ │ ← multi-account picker only when >1 account
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

- **State:** `KeypadViewModel` — `KeypadUiState(query, searchResults, dialString, smartDialMatches, recents, favorites, setupState, phoneAccounts, selectedAccountId)`; intents `SetQuery`, `AppendDigit`, `DeleteDigit`/`ClearAll` (long-press), `PasteDial`, `PlaceCall(number)`, `SelectAccount(id)`, `CallVoicemail`, `RequestRole/Permission(kind)`.
- **Reused as-is:** everything in `DialerActivity` that talks to the platform — `placeCall` (role check → CALL_PHONE check → `TelecomManager.placeCall` with account extras), `refreshCapabilities`, role/permission launchers, voicemail, `normalizeDialString`. These move from the activity into `KeypadViewModel` + a small `TelecomAccountsRepository` (activity keeps only the `ActivityResult` launchers, forwarded as intents).
- **New:** unified search + smart dial from the upgraded directory repository (§2.4); recents day-grouping; favorites strip; "return to call" banner comes from observing `CallSessionRepository` instead of the `addingCall` boolean.
- Long-press `0` → `+` and clipboard paste already exist and carry over.

### 3.2 Agent

Four destinations under the agent tab:

**Agent home (`agent`)** — replaces Dashboard as the tab root.
- Header: agent status (derived from `TelephonyController.sipState` + voice profile readiness + config), entry points to the three setup screens.
- Body: **previous agent call runs** from `AgentRunDao.observeRuns()` — each row: direction glyph, counterpart, relative time, agent mode (screening / automation title / outbound task), outcome pill, first-line transcript preview. Tap → `agent/run/{id}` with the full `TranscriptTimeline` (same component that renders live calls — one component, three surfaces).
- The active outbound task card (start/stop/poll from `DashboardViewModel`) survives as a pinned card while a task is active.

**Voice clone enroll (`agent/voice`)** — `VoiceCloneScreen` + `VoiceCloneViewModel` move unchanged (route + package only). Its consent → record → upload → preview flow and its two unit tests (`VoiceCaptureMeterTest`, `VoicePromptProgressTest`) are kept green.

**Inbound agent setup (`agent/inbound`)** — new. Edits `AgentConfigRepository.inbound`: auto-answer-all toggle, greeting text, enabled automations (seeded from `AutomationCatalog.inbound`), voice policy (cloned option gated on profile readiness, linking to `agent/voice` when not enrolled), quick-reply chip set for the incoming sheet. `ScreeningCoordinator`/`TelephonyController` read this config (§2.3 seam 3–4).

**Outbound agent setup (`agent/outbound`)** — new home for the outbound path: task list (pending/completed from `TaskRepository`), create-task form (logic of `CreateTaskViewModel` reused; `CreateTaskScreen` absorbed), launch through the existing `OutboundCallCoordinator.startTask` (task → grant → INVITE), outbound defaults from config. Launching a task hands off to the in-call surface via the session repository (the SIP source already emits `Calling`/`Active`).

`EnrollmentScreen`/`EnrollmentViewModel` stay the onboarding gate in front of the shell, unchanged.

### 3.3 Incoming call — bottom sheet, Pixel "Call Screen" style

One sheet component replaces three implementations (`ScreeningContent`, `AgentScreeningActivity`, `InCallContent`'s ringing branch). Rendered by the shell whenever the session phase is `RINGING`/`SCREENING`; expanded full-height over the lockscreen via the existing full-screen-intent path (now targeting the shell).

```
┌──────────────────────────────┐
│  ═ (drag handle)             │
│  Caller name / number chip   │
│  "Triplex is answering…"     │ ← agent status line
│ ┌──────────────────────────┐ │
│ │ AGENT  Hi, this is …     │ │ ← agent bubble, text revealed over playbackDurationMs
│ │ CALLER I'm calling about…│ │ ← streamed SODA partials, restyled live (final=false → true)
│ │ YOU    I'll call back    │ │ ← user reply chip the agent spoke
│ └──────────────────────────┘ │
│  [chip] [chip] [chip]        │ ← quick replies from inbound config → SpeakReply(text)
│  [ Answer ]  [ Hang up ]     │ ← Answer = human takeover; automation picker on overflow
└──────────────────────────────┘
```

- **State:** `IncomingCallViewModel` over `CallSessionRepository.session` + `AgentConfigRepository`. All actions are `CallIntent`s.
- **"Agent answers all calls":** already true on the SIP stack — `TelephonyController.onSipEvent` auto-answers after the transcriber warms. This becomes config-gated but stays default-on. Caller transcription (streaming partials) and agent utterances already flow (`callTranscript`, `conversationTurns`); they are mapped to `TimelineEntry` in Phase 1.
- **User responses:** reply chips dispatch `SpeakReply(text)` → new `TelephonyController.speakUserReply` → Inflect speaks it on the call; the timeline shows it as a `YOU` bubble. This is the Pixel Call Screen interaction. Free-text reply input is a stretch behind the same intent.
- **Streamed agent text:** phase one renders the full utterance with a reveal animation timed to `playbackDurationMs` (already computable — the controller sleeps exactly `pcm.size * 1000 / 16_000` ms today). True word-level timing waits on TTS alignment marks (`RUNTIME_INVARIANTS.md` §2.3 TtsHost); the timeline model already carries what it needs, so that lands later without UI changes.
- **Telecom-stack ringing calls** (SIM leg, no media access — see §5) render the same sheet with the transcript region replaced by capability-appropriate actions: answer, decline, reply-with-text (`CAPABILITY_RESPOND_VIA_TEXT`), and "Send to agent" (deflect, `CAPABILITY_SUPPORT_DEFLECT` + `AGENT_TRANSFER_NUMBER`) — all existing code. The sheet never pretends the agent has media it does not have.
- The slide-to-answer control (`IncomingCallDecisionSlider`, with its accessibility custom actions) is retained as the lockscreen-expanded variant's primary control; buttons on the collapsed sheet.
- **Retired by this surface:** `AgentScreeningActivity`, `ScreeningContent`, the separate screening notification channel/pipeline.

### 3.4 In call — Pixel-parity + agent panel

Full-screen surface presented while a session is `ACTIVE`/`HOLDING`/`DIALING`, replacing `InCallContent`.

```
┌──────────────────────────────┐
│  status ("00:42" / "On hold")│
│  Caller name                 │
│  number · SIM label          │
│  [second-call chip: swap]    │ ← only when otherCallCount > 0
│                              │
│ ┌──────────────────────────┐ │
│ │ ⚡ Agent — hand off call  │ │ ← collapsed agent panel (§ below)
│ └──────────────────────────┘ │
│                              │
│   mute    keypad   speaker   │ ← 3×2 action grid, capability-gated
│  add call  hold     more     │   (more → merge/swap/audio-route sheet)
│                              │
│         (  end call  )       │
└──────────────────────────────┘
```

- **State:** `InCallViewModel` over the same session flow; every tile is driven by `CallCapabilities`, so SIP-hosted agent calls and Telecom calls render from identical code. DTMF pad, audio-endpoint picker (CallEndpoint API ≥ 34 with legacy route fallback), hold, add-call/switch/merge/swap — all existing service capabilities, re-plumbed through `CallIntent`.
- **Agent panel** (the one new in-call element). Collapsed: a hand-off affordance. Expanded, by stack:
  - **SIP-hosted call:** "Hand to agent" resumes the agent pipeline on the live call (existing `startAutomation` path, plus a plain "continue screening" mode); the panel grows the shared `TranscriptTimeline` and a "Take back" control (`interruptPlayback()` exists; full human-audio takeover on SIP is a flagged spike — see §5).
  - **Telecom call, ringing:** deflect to `AGENT_TRANSFER_NUMBER` (existing `transferCurrentToAgent`).
  - **Telecom call, mid-call:** **conference-merge hand-off** — place a second call to the agent's Plivo number (existing `placeCall`), agent auto-answers on SIP, then `mergeCalls()` (existing). The agent is now in the conference with media; the panel streams its SIP-side transcript. Capability-gated on `canMerge`; carrier-dependent, so the panel explains when it is unavailable.
- `ActiveCallScreen`/`ActiveCallViewModel` (dead code) are deleted; their agent-state readout (`AgentState` headline/description) folds into the agent panel.

---

## 4. Design-system seam

The custom design system arrives after this plan is approved. To make that a drop-in:

1. **Single token surface.** `ui/theme/` (`TriplexTheme`, `TriplexDesign` — colors/spacing/elevation/motion CompositionLocals) remains the only source of visual constants. The screening activity's private palette (`Ink`/`Moss`/`Paper`/…) and the legacy green `res/values/colors.xml` are deleted; the XML window theme keeps only what the OS needs pre-Compose (window background, splash).
2. **Token contract the design system must fill** (this is the ask we hand to design): Material3 scheme + extended semantic roles — `agentAccent`, `answer`, `decline`, `warning`, `success`, and conversation roles `agentBubble` / `callerBubble` / `userBubble` / `screeningSurface`; typography scale incl. a numeric/dial style; spacing, shape, elevation, motion (all four already exist as `TriplexDesign` data classes); haptic conventions for dial keys and the answer control.
3. **Component inventory** — new screens are composed only from these wrappers (existing ones marked ✓):
   - Shell: `TriplexBackground` ✓, `TriplexTopBar` ✓, nav bar, sheet scaffold
   - Lists: list row (avatar/title/subtitle/trailing), section header, `TriplexStatusPill` ✓, empty state, setup banner, `TriplexCard` ✓
   - Dialer: dial key, dial display, search field (`OutlinedTextInput` ✓ as the base), FAB
   - Call: call header, action tile + grid, end-call button, answer control (slide), audio-route row, DTMF pad
   - Conversation: bubble (agent/caller/user variants), streaming-text reveal, suggestion chip, listening indicator
   - Agent: run list item, `TriplexSignalOrb` ✓, `TriplexVoiceSphere` ✓, form field, `TriplexButton` ✓
4. **Enforcement.** `DesignSystemGuardTest` currently scans only `ui/screens`. After the re-org it scans all of `ui/` except `ui/components` and `ui/theme`, so the dialer and call surfaces are held to the same boundary. The forbidden-snippet list gains the raw-Material imports the dialer uses today.

Until the design system lands, phases 2–5 build screens against the *existing* wrappers — structure and wiring get real; pixels are placeholder. Phase 6 swaps wrapper internals without touching screens.

---

## 5. Constraints stated plainly

- **A third-party default dialer gets call state, not call audio.** Android's `InCallService` exposes no media stream, so the agent cannot listen/speak on a SIM-leg Telecom call. Pixel's Call Screen is privileged software. Triplex's architecture already answers this: calls that should be agent-answered arrive on the **Plivo SIP leg** (Triplex number, or carrier conditional call forwarding), where PJSIP owns the media (`TriplexCallScreeningService`'s doc comment records this decision). The UI consequences are the `agentHasMedia` capability flag and the §3.3/§3.4 telecom fallbacks (deflect, conference-merge). The inbound setup screen should carry a "how calls reach your agent" explainer with the forwarding setup.
- **Human takeover of a SIP call ("Answer" on the sheet for a SIP call) is not yet proven end-to-end.** The screening ACCEPT path calls `TelephonyController.answer()`, but two-way human audio (device mic → SIP tx, SIP rx → device speaker) depends on takeover-mode audio topology that `RUNTIME_INVARIANTS.md` explicitly defers to a spike. The sheet ships with "Answer" wired to the existing path; the spike is scheduled inside Phase 4 and, until it passes on hardware, SIP-call answer surfaces an honest "agent-only call" state rather than fake duplex.
- **Word-level agent-text streaming** waits on TTS alignment marks; utterance-timed reveal (duration already known) ships first. No UI rework needed later — the timeline entry already carries timing.
- **Deflect and conference-merge are carrier-dependent** (`CAPABILITY_SUPPORT_DEFLECT`, conference capabilities). Both are capability-gated in the model, with visible explanations instead of dead buttons.
- **Run history is local-only** (Room) in this plan — transcripts of screened calls are sensitive; nothing leaves the device. Gateway sync is a separate decision (decision 4).

---

## 6. Delivery phases

Each phase is one reviewable PR that builds and keeps `:app:testDebugUnitTest` (CI's android-build job) green. Phases 2–5 are parallelizable after Phase 1.

| Phase | Contents | Exit criteria |
|---|---|---|
| **0 — Shell consolidation** | Single-activity shell with bottom nav; existing screens mounted unchanged at new routes; runtime/SIP lifecycle moved to app scope + `TriplexSipService`; `MainActivity` → alias; type-safe routes | App builds; every current feature reachable; rotating during a SIP call no longer kills it; zero visual redesign |
| **1 — Call domain unification** | `CallSession`/`CallIntent`/`CallSessionRepository`; `TelecomCallController` extracted from `TriplexInCallService` statics; `ScreeningCoordinator` becomes the SIP source; one `CallNotificationController`; `TelephonyController` seams 1–4; delete `DialerCallStore` public API + `ActiveCallScreen` | Both stacks drive one `StateFlow`; existing dialer UI re-pointed at it with behavior parity; unit tests cover the merge + intent routing with fake sources |
| **2 — Keypad** | Reactive `DialerDirectoryRepository` (Hilt, ContentObservers, search index); `KeypadViewModel`; keypad screen per §3.1; delete `DialerSection` tabs | Search returns contacts + history; smart-dial filters while typing; recents update live after a call; multi-SIM + voicemail + setup banners intact |
| **3 — Agent data + screens** | Room runs schema + `AgentRunRecorder`; DataStore `AgentConfigRepository`; agent home (runs list), run detail, inbound setup, outbound setup (absorbs CreateTask); move VoiceClone route | A screened call produces a persisted, replayable run; config round-trips and actually gates auto-answer/greeting/automations; voice-clone tests still green |
| **4 — Incoming sheet** | Sheet host in shell; `IncomingCallViewModel`; quick replies (`SpeakReply` → `speakUserReply`); lockscreen full-screen variant; retire `AgentScreeningActivity` + `ScreeningContent`; SIP-answer takeover spike | Live screening renders agent + caller + user bubbles with streamed caller partials; chips speak on the call; telecom ringing shows honest fallback actions; notification tap lands on the sheet |
| **5 — In-call + agent panel** | In-call screen per §3.4 on `CallIntent`s; agent panel with per-stack hand-off (SIP resume, deflect, conference-merge); delete `InCallContent` | All existing call controls work through the new surface on a real device; hand-off paths demonstrated per stack (or capability-gated off with visible reason) |
| **6 — Design system application** | Swap `ui/components` internals to the approved design system; expand `DesignSystemGuardTest` to all of `ui/`; delete legacy palettes; motion/haptics pass | Guard test enforces the boundary repo-wide; one visual language on all four surfaces |

## 7. Testing

- **Unit (JVM, runs in CI):** repository merge logic and intent routing against fake telecom/SIP sources; `KeypadViewModel` search/smart-dial; `AgentRunRecorder` write-through on simulated lifecycles; config gating (auto-answer honors the toggle); timeline mapping (partials update in place, user replies attributed). Existing tests (`DesignSystemGuardTest`, `VoiceCaptureMeterTest`, `VoicePromptProgressTest`) stay green throughout.
- **Debug drivers (exist, extended):** `DEBUG_INCOMING_CALL` currently fabricates a Telecom ringing state; add a debug SIP-screening fabricator that pushes scripted `TimelineEntry` sequences through the repository so the sheet and panels can be exercised without Plivo credentials.
- **Device smoke per phase (manual, evidence-attached):** default-dialer + screening role grants, real SIM inbound/outbound, hold/mute/DTMF/audio-route, SIP screening with a forwarded call, missed-call flow. The telecom stack cannot be meaningfully tested on JVM; this stays a hardware checklist as it is today.

## 8. Decisions requested before implementation starts

1. **Shell layout:** two tabs (Keypad | Agent) with recents/contacts folded into keypad search — confirm, or keep a separate Recents tab for one-thumb reach.
2. **Retire `MainActivity` and `AgentScreeningActivity`** into the single shell (recommended) — confirm.
3. **Mid-call hand-off on carrier calls:** conference-merge as the wired mechanism (recommended); acoustic speaker+mic takeover stays out of scope as a spike.
4. **Run history:** local-only Room (recommended) vs. synced to the gateway.
5. **T9 smart dial:** in Phase 2, or number-prefix matching only for v1.
6. **Room + activity-alias additions** are the only manifest/dependency changes; flag anything else you want frozen.

---

## Appendix A — file-level change map

**New**

```
domain/call/{CallSession,CallTimeline,CallIntent,CallSessionRepository}.kt
data/call/{CallSessionRepositoryImpl,TelecomCallSource,SipCallSource,TelecomCallController}.kt
data/local/db/{TriplexDatabase,AgentRunDao,AgentRunEntities}.kt
data/local/AgentConfigRepository.kt
data/repository/AgentRunRecorder.kt
ui/shell/*  ui/keypad/*  ui/call/{incoming,incall,shared}/*  ui/agent/{AgentHome,InboundSetup,OutboundSetup,RunDetail}*
dialer/CallNotificationController.kt
```

**Moved / refactored**

```
ui/screens/VoiceCloneScreen.kt + VoiceCloneViewModel.kt        → ui/agent/
ui/screens/EnrollmentScreen.kt + EnrollmentViewModel.kt        → ui/enrollment/
ui/screens/CreateTaskViewModel.kt (logic)                      → ui/agent/OutboundSetupViewModel.kt
ui/screens/DashboardViewModel.kt (task/status logic)           → ui/agent/AgentHomeViewModel.kt
dialer/DialerActivity.kt      → thin shell host; content decomposed into ui/keypad + ui/call
dialer/TriplexInCallService.kt → publishes via TelecomCallSource; statics → TelecomCallController
dialer/ScreeningCoordinator.kt → SIP source + config-driven bootstrap
dialer/DialerDirectoryRepository.kt → Hilt singleton, reactive, search index
telephony/sip/TelephonyController.kt → seams: speakUserReply, USER speaker, config-gated auto-answer/greeting
MainActivity.kt → runtime init removed; activity-alias, then deleted
```

**Deleted (after replacement lands)**

```
ui/screens/ActiveCallScreen.kt, ui/screens/ActiveCallViewModel.kt      (dead code)
dialer/AgentScreeningActivity.kt + private palette                     (→ incoming sheet)
ScreeningContent / InCallContent / DialerSection inside DialerActivity (→ ui/call, ui/keypad)
DialerCallStore public object                                          (→ repository-internal)
ui/navigation/Navigation.kt                                            (→ ui/shell)
legacy green values in res/values/colors.xml
```
