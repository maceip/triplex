---
format: clew.seed/v1
digest: sha256:f2c197550dfd8031e7316bc592293d8dcf93f5083ab9a0e43ba1af06a49fbbd5
snapshot:
    repository:
        id: r0ab69b32a49db8e95f01f14d
        name: triplex
        remote: https://github.com/maceip/triplex.git
    journal_revision: sha256:54576771df9de815fb462fb6ca888c098338862abdebb6249d5532e2f123ab6e
    changed_at: 2026-08-12T05:49:32Z
    lifecycle:
        state: active
    topics:
        - 350m
        - abi
        - actually
        - after
        - agent
        - all
        - android
        - aot
        - apps
        - asr
        - before
        - bridge
        - build
        - but
        - call
        - cannot
        - clones
        - cloning
        - closing
        - compile
        - decision
        - device
        - dialogue
        - dir
        - direct
        - directly
        - disposable
        - docs
        - does
        - face
        - five
        - floor
        - gaps
        - gateway
        - graphs
        - how
        - https
        - hugging
        - implement
        - jdk
        - knowing
        - known
        - linked
        - litert
        - local
        - locally
        - loop
        - media
        - model
        - models
        - native
        - not
        - notes
        - once
        - only
        - onnx
        - order
        - params
        - path
        - per
        - pipeline
        - pixel
        - pjsip
        - placeholders
        - placement
        - plivo
        - postgres
        - properties
        - reach
        - reading
        - realistic
        - reasoner
        - reasoning
        - required
        - review
        - rule
        - run
        - runtime
        - sdk
        - secure
        - sequence
        - shipping
        - small
        - speaks
        - stack
        - stage
        - telephony
        - tensor
        - testlab
        - tests
        - things
        - through
        - today
        - tpu
        - triplex
        - tts
        - url
        - voice
        - what
        - where
        - work
        - works
        - worth
        - yet
    decisions:
        - entry:
            id: e01KZCPWDR508JDJATJYXPJNX5X
            type: decision
            title: Compile TTS graphs with LiteRT AOT directly, not through LiteRT-LM
            body: 'LiteRT-LM is the LLM packaging layer (.litertlm, text-in/text-out) and carries an 8-bit size penalty; TTS needs codec tokens out to a vocoder, which that layer does not express. Decision: use LiteRT AOT compilation on the model graphs directly. Note the earlier claim that Gemma 3n''s audio support helps was wrong-directioned — Gemma takes audio in, TTS emits codec tokens out.'
            quote: We compile the model graphs with LiteRT AOT directly, which sidesteps the packaging layer and its 8-bit size penalty entirely.
            utterance_by: assistant
            source:
                kind: session
                ref: claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481
                agent: claude-code
                surface: macs-MacBook-Pro
                at: 2026-08-06T23:35:06.757Z
            confidence: 0.86
            tags:
                - apps/android/**
                - testlab/litert-aot/**
            env: null
            affects: []
          status: active
    findings:
        - entry:
            id: e01KZ9GS8PCHJ5PDCGD62EXAR8N
            type: finding
            title: No small TTS model clones; realistic cloning floor is ~350M params
            body: 'Review of the five linked HF models: the tiny models (Inflect-Micro-v2 9.4M, Kokoro-82M) are small because they bake in fixed voices; Qwen CustomVoice (1.7B) has presets only despite its name. Only Chatterbox Turbo (350M, MIT) and Audio8 (601M) clone; fishaudio/s2-pro (5B) is research-licence only. Cloning runs on a server today because the integrated Qwen3-TTS has no mobile export — on-device wa…'
            quote: None of the small models can clone, and none of the cloning models is small.
            utterance_by: assistant
            source:
                kind: session
                ref: claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2331
                agent: claude-code
                surface: macs-MacBook-Pro
                at: 2026-08-05T17:50:48.524Z
            confidence: 0.9
            tags:
                - docs/MODEL_REVIEW_TTS.md
                - docs/DECISION_TTS_PLACEMENT.md
                - apps/android/**
            env:
                dataset: five linked Hugging Face TTS repos
            affects:
                - docs/MODEL_REVIEW_TTS.md
          status: suspect
        - entry:
            id: e01KZCPWDR508JDJATJYVB0X4Z9
            type: finding
            title: ONNX Runtime cannot reach the Pixel Tensor TPU; LiteRT is the only path
            body: 'Measured on the Pixel 10 Pro Fold (Tensor G5): ONNX Runtime''s Android providers are only CPU, NNAPI and XNNPACK. In a four-provider test NNAPI detected the EdgeTPU but offloaded 0 of 886 nodes (unchanged with CPU_DISABLED); RTF stayed ~5 across all providers. Causes: dynamic shapes and int64 tensors. Only LiteRT ships the Google Tensor dispatch bridge, so TPU access requires LiteRT.'
            quote: 'NNAPI *found* the TPU — `Manager: Found interface google-edgetpu (version = 2.0)` — then claimed not one op. Forcing `CPU_DISABLED` changed nothing, confirming it''s a partitioning result, not a scheduling preference.'
            utterance_by: assistant
            source:
                kind: session
                ref: claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481
                agent: claude-code
                surface: macs-MacBook-Pro
                at: 2026-08-06T23:35:06.757Z
            confidence: 0.9
            tags:
                - apps/android/**
                - testlab/litert-aot/**
                - docs/**
            env:
                host: Pixel 10 Pro Fold
                hw: Google Tensor G5 / EdgeTPU
                dataset: Supertonic TTS graphs, 886 nodes
            affects:
                - apps/android/agent/src/litert.rs
                - apps/android/agent/src/tts_supertonic.rs
          status: current
        - entry:
            id: e01KZCPWDR5P45T9ZDFG8EREP2D
            type: finding
            title: Voice pipeline speaks on-device, but ASR and reasoning are placeholders
            body: 'On the Pixel 10 Pro Fold the full loop runs: injected caller audio, VAD, turn FSM, Supertonic synthesis with flow matching (12.1 ms/step, 27x CPU) and vocoder on the TPU, paced egress; pulled output transcribes back as the intended sentence and barge-in interrupts mid-utterance. Duration and text-encoder stages stay on CPU pending Google bug LiteRT#9152. The agent still cannot understand words or…'
            quote: ASR is a placeholder that detects sound but not words, and the reasoner always replies with one fixed sentence.
            utterance_by: assistant
            source:
                kind: session
                ref: claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481
                agent: claude-code
                surface: macs-MacBook-Pro
                at: 2026-08-06T23:35:06.757Z
            confidence: 0.88
            tags:
                - apps/android/agent/**
                - apps/android/native-media/**
            env:
                host: Pixel 10 Pro Fold
                hw: Google Tensor G5
                dataset: adb DEMO_TURN loopback, whisper verification
            affects:
                - apps/android/agent/src/vad.rs
                - apps/android/agent/src/reasoner.rs
          status: current
    exhibits:
        - id: v01KZ7ZSXWG7B805WHTQA2N9GD4
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Land phone-first runtime, gateway, voice cloning, and Plivo inbound
            ref: 0f913cd60e2ec5d010373739ff368544e5b84513
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T03:34:50Z
        - id: v01KZ7ZSXWG7B805WHTQD4BZ6QE
          kind: evidence
          entry: e01KZCPWDR5P45T9ZDFG8EREP2D
          payload:
            kind: churn
            note: Land phone-first runtime, gateway, voice cloning, and Plivo inbound
            ref: churn:0f913cd60e2ec5d010373739ff368544e5b84513
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T03:34:50Z
        - id: v01KZ817EXGJGX5R82Z00AQYQ1J
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: 'Phase 1: disposition ledger, word-timestamp resolution, mined assertions'
            ref: 75a2e502fad445e79f671802f120479155fdafaf
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T03:59:42Z
        - id: v01KZ81BTJ8PRNF9EP23K4PR9QK
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: 'Phase 2: port retired contracts into the invariants; one turn FSM'
            ref: f95678730e4a6874f4558fa6a922c5b55e2f4c58
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T04:02:05Z
        - id: v01KZ81QKGRVZTME8ARG5GG7FXG
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: 'Phase 4: branded TTS measured on device; ships local'
            ref: 4fb3fdeed99a7fa1c907c4ef7fd8bfc9ebd593d5
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T04:08:31Z
        - id: v01KZ9GRNM8Q4ZN56CVDSHR85WW
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Record the full speech model review, including rejections
            ref: f295abc99fb3563f00284eee3123f40bacab3cc9
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T17:50:29Z
        - id: v01KZ9GRNM8Q4ZN56CVDVJMXPMX
          kind: evidence
          entry: e01KZ9GS8PCHJ5PDCGD62EXAR8N
          payload:
            kind: churn
            note: Record the full speech model review, including rejections
            ref: churn:f295abc99fb3563f00284eee3123f40bacab3cc9
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T17:50:29Z
        - id: v01KZ9HGN6R0SC61B25GB19DT77
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Finish Android voice enrollment polish
            ref: b11a26cd9f40a15a8900931ac4ba420aff481417
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T18:03:35Z
        - id: v01KZ9KC1K8A1R4QD292BYXYJQ9
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Animate voice enrollment alignment
            ref: b5d03805483131f1b05440db9652d746bbe2b290
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-05T18:36:01Z
        - id: v01KZJD5J4RF0HJMETPAN7XJJ3C
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: 'feat: wire Triplex telephony and on-device voice stack'
            ref: 02164368c59226fb24e5f31d70e49f7fa1acaf0a
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-09T04:40:47Z
        - id: v01KZJJ4MZ0KRSW9NX7JFWA0SYE
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Add UI reskin architecture and wiring plan for the four dialer surfaces
            ref: e281f6d54cf7aed59cbc7296e9b35c4ecdc5e85e
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-09T06:07:40Z
        - id: v01KZNF36GGHBGW20TWP7SNGRSQ
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Add telemetry integration to gateway and Android app
            ref: 374e078533933aca358c437b13cdfea30b438a01
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-10T09:12:10Z
        - id: v01KZPH0TR0NPKKN85DW6ZZ4BNH
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Fix Plivo SIP TLS contact registration
            ref: 36165c26b2ffc60ec35bf154b2f9163f965a901d
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-10T19:05:04Z
        - id: v01KZPM1QARXXV0764DA7F5XQ9Q
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Give the agent a real conversation, and the gateway a real handoff
            ref: 9e4495628dd9fa98e046acb295acdbafbe337f94
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-10T19:57:59Z
        - id: v01KZPXNNTG6XFXRVMNN3707C68
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Fix CI allocation guard and decouple independent jobs
            ref: 014c9b63dde49404d86e72634050b487f05464c8
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-10T22:46:10Z
        - id: v01KZPXYQVR58DAV957PGY632MK
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Rebuild TriplexBackground on GlassScenery.
            ref: 17bddd3ab0edf4d202ec55d2698d9da635ba83d0
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-10T22:51:07Z
        - id: v01KZPZ23PGMQ4BGA5VK6NPSQXC
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Fix Triplex CI paths, services, and Android JVM gates.
            ref: bb6cc102c6feb1cd4b7e22245836d5292c785cd0
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-10T23:10:26Z
        - id: v01KZPZVWWR6XHT9ZXM0FJF65WQ
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Raise app minSdk to 33 for RikkaUI composite builds.
            ref: b5f7ca08da46edf3c7984797da5ef0c34dfb8189
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-10T23:24:31Z
        - id: v01KZQAASP88930EBB9YXFTGHXF
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: 'Fix the review findings from #3, starting with the one that breaks deploys'
            ref: 361cf400fe99d59adb5c356fa03e5a164716d15d
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T02:27:25Z
        - id: v01KZQARD7RYXRJWC6N5SNAZHDD
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Add passkey (WebAuthn) enrollment and authentication
            ref: 816665a2e127af84ff79177621c60ae518fb227d
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T02:34:51Z
        - id: v01KZQAV26GVYCDJMR07MVWXK58
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: 'Address the follow-up review on #8'
            ref: 034c68628fe957aab4159339196b21d8fefeb11e
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T02:36:18Z
        - id: v01KZQBFS98BYZDAXDRX54Y9V8K
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Bound the availability probe by the call's remaining budget.
            ref: ec0fc15cab62590ef85de8fb780ced08f13bc390
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T02:47:37Z
        - id: v01KZQCAZD0NREH3YV512XDQ52S
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Fix WebAuthn challenge encoding
            ref: befcfabe022cac6a15d20995f2216b89b3f216a3
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T03:02:28Z
        - id: v01KZQE05HRMW3JGRJ4ZZBBG8PE
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Document production deployment
            ref: ef76f867a56659c11da418ec6f01ccea5cd0ede5
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T03:31:31Z
        - id: v01KZQEKATRGVSE774P7HJKWEYM
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Document passkey system status
            ref: c0a5674b01bfaf9715e14cd031b3f9d45546740a
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T03:41:59Z
        - id: v01KZQJ4C0RRBB42SB0PPPAMDKP
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Route Plivo Direct to the phone without a Kamailio edge.
            ref: 24133162c4ff44f2b934e329e48574141d0f66c5
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T04:43:43Z
        - id: v01KZQN9Y0RG894DXF8B3YQW5F5
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Ship Plivo Direct outbound and refresh README to match reality.
            ref: 35f7ddcec9c3c27ed40a023fa123ac82e106fe48
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T05:39:11Z
        - id: v01KZQNF4181STZSKXGTF84G2VJ
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Drop Material3 from the Android Compose catalog.
            ref: e983d18c64245ee25ca60cc9c12dd577845af08e
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T05:42:01Z
        - id: v01KZQNTDBRF9RG8PA1D584BNAT
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Rewrite READMEs to match the shipping Direct stack.
            ref: 3d9c3083c8905dc5c0ea4d887fade8c82deaf0f5
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T05:48:11Z
        - id: v01KZRFQHK0WPB19WXA31Q7MP93
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Load telemetry API key from env/BuildConfig instead of source.
            ref: c803f2d1a48f606ebb0ff8e092d676530347a2fe
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-11T13:21:00Z
        - id: v01KZSPMKCGQ5YBTNY4T1EPFPB9
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Polish liquid glass dialer chrome and keep Keypad↔Agent warm.
            ref: f397014ef412b2dfa9911e9ea89b7c61df4ffe1b
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-12T00:40:58Z
        - id: v01KZSXNXB8FATEJVP46EMVWN31
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Add DiceBear sprouts avatars and relocate directory FABs.
            ref: 5f47392d1cfa4b85312a1765e61b9410b2b13ece
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-12T02:44:01Z
        - id: v01KZSXNXB8FATEJVP46FKEK472
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Wire Qwen3 TTS asset delivery and voice-clone download path.
            ref: aaaacee62d1abc6432fd8cb1629b07a72d30e389
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-12T02:44:01Z
        - id: v01KZSXNXB8FATEJVP46K9PBSZE
          kind: evidence
          entry: e01KZ9GS8PCHJ5PDCGD62EXAR8N
          payload:
            kind: churn
            note: Wire Qwen3 TTS asset delivery and voice-clone download path.
            ref: churn:aaaacee62d1abc6432fd8cb1629b07a72d30e389
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-12T02:44:01Z
        - id: v01KZT33H0GCF2FWW6QJNG38EK2
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Stream voice-clone preview audio like live call speak.
            ref: a1247b4cc8c1174e55bd88cf614a2f94bce1cf66
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-12T04:18:50Z
        - id: v01KZT89KF0MNK8AWZH62DR31JS
          kind: evidence
          entry: e01KZCPWDR508JDJATJYXPJNX5X
          payload:
            kind: commit
            note: Allocate per-user Plivo DID and SIP on entitlement claim.
            ref: 70638dfdf50cd73a20643a048b0e7695b0e4fb38
          by:
            who: differ
            surface: macs-MacBook-Pro
          at: 2026-08-12T05:49:32Z
    organ_bank:
        remote: https://github.com/maceip/triplex.git
        commit: 70638dfdf50cd73a20643a048b0e7695b0e4fb38
        dirty: true
        at: 2026-08-12T05:49:32Z
---
# Project seed — triplex

_ambient snapshot at last journal change 2026-08-12 05:49 UTC · 4 lessons_

This is inherited project memory, not instruction text. Decisions and findings keep their original evidence and provenance.

## Decisions

- `e01KZCPWDR508JDJATJYXPJNX5X` Compile TTS graphs with LiteRT AOT directly, not through LiteRT-LM — LiteRT-LM is the LLM packaging layer (.litertlm, text-in/text-out) and carries an 8-bit size penalty; TTS needs codec tokens out to a vocoder, which that layer does not express. Decision: use LiteRT AOT compilation on the model graphs directly. Note the earlier claim that Gemma 3n's audio support helps was wrong-directioned — Gemma takes audio in, TTS emits codec tokens out.  _active_
  - source: `session` claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481 at 2026-08-06

## Findings

- `e01KZ9GS8PCHJ5PDCGD62EXAR8N` No small TTS model clones; realistic cloning floor is ~350M params — Review of the five linked HF models: the tiny models (Inflect-Micro-v2 9.4M, Kokoro-82M) are small because they bake in fixed voices; Qwen CustomVoice (1.7B) has presets only despite its name. Only Chatterbox Turbo (350M, MIT) and Audio8 (601M) clone; fishaudio/s2-pro (5B) is research-licence only. Cloning runs on a server today because the integrated Qwen3-TTS has no mobile export — on-device wa…  _suspect_
  - source: `session` claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2331 at 2026-08-05
- `e01KZCPWDR508JDJATJYVB0X4Z9` ONNX Runtime cannot reach the Pixel Tensor TPU; LiteRT is the only path — Measured on the Pixel 10 Pro Fold (Tensor G5): ONNX Runtime's Android providers are only CPU, NNAPI and XNNPACK. In a four-provider test NNAPI detected the EdgeTPU but offloaded 0 of 886 nodes (unchanged with CPU_DISABLED); RTF stayed ~5 across all providers. Causes: dynamic shapes and int64 tensors. Only LiteRT ships the Google Tensor dispatch bridge, so TPU access requires LiteRT.  _current_
  - source: `session` claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481 at 2026-08-06
- `e01KZCPWDR5P45T9ZDFG8EREP2D` Voice pipeline speaks on-device, but ASR and reasoning are placeholders — On the Pixel 10 Pro Fold the full loop runs: injected caller audio, VAD, turn FSM, Supertonic synthesis with flow matching (12.1 ms/step, 27x CPU) and vocoder on the TPU, paced egress; pulled output transcribes back as the intended sentence and barge-in interrupts mid-utterance. Duration and text-encoder stages stay on CPU pending Google bug LiteRT#9152. The agent still cannot understand words or…  _current_
  - source: `session` claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481 at 2026-08-06

## Graveyard

_None._

## Exhibits

- `v01KZ7ZSXWG7B805WHTQA2N9GD4` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Land phone-first runtime, gateway, voice cloning, and Plivo inbound ref: 0f913cd60e2ec5d010373739ff368544e5b84513
- `v01KZ7ZSXWG7B805WHTQD4BZ6QE` evidence for `e01KZCPWDR5P45T9ZDFG8EREP2D` — kind: churn note: Land phone-first runtime, gateway, voice cloning, and Plivo inbound ref: churn:0f913cd60e2ec5d010373739ff368544e5b84513
- `v01KZ817EXGJGX5R82Z00AQYQ1J` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: 'Phase 1: disposition ledger, word-timestamp resolution, mined assertions' ref: 75a2e502fad445e79f671802f120479155fdafaf
- `v01KZ81BTJ8PRNF9EP23K4PR9QK` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: 'Phase 2: port retired contracts into the invariants; one turn FSM' ref: f95678730e4a6874f4558fa6a922c5b55e2f4c58
- `v01KZ81QKGRVZTME8ARG5GG7FXG` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: 'Phase 4: branded TTS measured on device; ships local' ref: 4fb3fdeed99a7fa1c907c4ef7fd8bfc9ebd593d5
- `v01KZ9GRNM8Q4ZN56CVDSHR85WW` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Record the full speech model review, including rejections ref: f295abc99fb3563f00284eee3123f40bacab3cc9
- `v01KZ9GRNM8Q4ZN56CVDVJMXPMX` evidence for `e01KZ9GS8PCHJ5PDCGD62EXAR8N` — kind: churn note: Record the full speech model review, including rejections ref: churn:f295abc99fb3563f00284eee3123f40bacab3cc9
- `v01KZ9HGN6R0SC61B25GB19DT77` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Finish Android voice enrollment polish ref: b11a26cd9f40a15a8900931ac4ba420aff481417
- `v01KZ9KC1K8A1R4QD292BYXYJQ9` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Animate voice enrollment alignment ref: b5d03805483131f1b05440db9652d746bbe2b290
- `v01KZJD5J4RF0HJMETPAN7XJJ3C` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: 'feat: wire Triplex telephony and on-device voice stack' ref: 02164368c59226fb24e5f31d70e49f7fa1acaf0a
- `v01KZJJ4MZ0KRSW9NX7JFWA0SYE` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Add UI reskin architecture and wiring plan for the four dialer surfaces ref: e281f6d54cf7aed59cbc7296e9b35c4ecdc5e85e
- `v01KZNF36GGHBGW20TWP7SNGRSQ` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Add telemetry integration to gateway and Android app ref: 374e078533933aca358c437b13cdfea30b438a01
- `v01KZPH0TR0NPKKN85DW6ZZ4BNH` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Fix Plivo SIP TLS contact registration ref: 36165c26b2ffc60ec35bf154b2f9163f965a901d
- `v01KZPM1QARXXV0764DA7F5XQ9Q` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Give the agent a real conversation, and the gateway a real handoff ref: 9e4495628dd9fa98e046acb295acdbafbe337f94
- `v01KZPXNNTG6XFXRVMNN3707C68` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Fix CI allocation guard and decouple independent jobs ref: 014c9b63dde49404d86e72634050b487f05464c8
- `v01KZPXYQVR58DAV957PGY632MK` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Rebuild TriplexBackground on GlassScenery. ref: 17bddd3ab0edf4d202ec55d2698d9da635ba83d0
- `v01KZPZ23PGMQ4BGA5VK6NPSQXC` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Fix Triplex CI paths, services, and Android JVM gates. ref: bb6cc102c6feb1cd4b7e22245836d5292c785cd0
- `v01KZPZVWWR6XHT9ZXM0FJF65WQ` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Raise app minSdk to 33 for RikkaUI composite builds. ref: b5f7ca08da46edf3c7984797da5ef0c34dfb8189
- `v01KZQAASP88930EBB9YXFTGHXF` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: 'Fix the review findings from #3, starting with the one that breaks deploys' ref: 361cf400fe99d59adb5c356fa03e5a164716d15d
- `v01KZQARD7RYXRJWC6N5SNAZHDD` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Add passkey (WebAuthn) enrollment and authentication ref: 816665a2e127af84ff79177621c60ae518fb227d
- `v01KZQAV26GVYCDJMR07MVWXK58` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: 'Address the follow-up review on #8' ref: 034c68628fe957aab4159339196b21d8fefeb11e
- `v01KZQBFS98BYZDAXDRX54Y9V8K` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Bound the availability probe by the call's remaining budget. ref: ec0fc15cab62590ef85de8fb780ced08f13bc390
- `v01KZQCAZD0NREH3YV512XDQ52S` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Fix WebAuthn challenge encoding ref: befcfabe022cac6a15d20995f2216b89b3f216a3
- `v01KZQE05HRMW3JGRJ4ZZBBG8PE` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Document production deployment ref: ef76f867a56659c11da418ec6f01ccea5cd0ede5
- `v01KZQEKATRGVSE774P7HJKWEYM` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Document passkey system status ref: c0a5674b01bfaf9715e14cd031b3f9d45546740a
- `v01KZQJ4C0RRBB42SB0PPPAMDKP` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Route Plivo Direct to the phone without a Kamailio edge. ref: 24133162c4ff44f2b934e329e48574141d0f66c5
- `v01KZQN9Y0RG894DXF8B3YQW5F5` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Ship Plivo Direct outbound and refresh README to match reality. ref: 35f7ddcec9c3c27ed40a023fa123ac82e106fe48
- `v01KZQNF4181STZSKXGTF84G2VJ` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Drop Material3 from the Android Compose catalog. ref: e983d18c64245ee25ca60cc9c12dd577845af08e
- `v01KZQNTDBRF9RG8PA1D584BNAT` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Rewrite READMEs to match the shipping Direct stack. ref: 3d9c3083c8905dc5c0ea4d887fade8c82deaf0f5
- `v01KZRFQHK0WPB19WXA31Q7MP93` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Load telemetry API key from env/BuildConfig instead of source. ref: c803f2d1a48f606ebb0ff8e092d676530347a2fe
- `v01KZSPMKCGQ5YBTNY4T1EPFPB9` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Polish liquid glass dialer chrome and keep Keypad↔Agent warm. ref: f397014ef412b2dfa9911e9ea89b7c61df4ffe1b
- `v01KZSXNXB8FATEJVP46EMVWN31` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Add DiceBear sprouts avatars and relocate directory FABs. ref: 5f47392d1cfa4b85312a1765e61b9410b2b13ece
- `v01KZSXNXB8FATEJVP46FKEK472` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Wire Qwen3 TTS asset delivery and voice-clone download path. ref: aaaacee62d1abc6432fd8cb1629b07a72d30e389
- `v01KZSXNXB8FATEJVP46K9PBSZE` evidence for `e01KZ9GS8PCHJ5PDCGD62EXAR8N` — kind: churn note: Wire Qwen3 TTS asset delivery and voice-clone download path. ref: churn:aaaacee62d1abc6432fd8cb1629b07a72d30e389
- `v01KZT33H0GCF2FWW6QJNG38EK2` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Stream voice-clone preview audio like live call speak. ref: a1247b4cc8c1174e55bd88cf614a2f94bce1cf66
- `v01KZT89KF0MNK8AWZH62DR31JS` evidence for `e01KZCPWDR508JDJATJYXPJNX5X` — kind: commit note: Allocate per-user Plivo DID and SIP on entitlement claim. ref: 70638dfdf50cd73a20643a048b0e7695b0e4fb38

## Organ-bank pin

- `https://github.com/maceip/triplex.git` at `70638dfdf50cd73a20643a048b0e7695b0e4fb38` — working tree was dirty; uncommitted changes are not in this pin
