# Triplex sprouts (DiceBear via Rust)

On-device DiceBear **sprouts** avatars using the official Rust crates:

```toml
dicebear-core = "10.5.0"
dicebear-styles = { version = "10.4.0", features = ["sprouts"] }
```

Kotlin loads `libtriplex_sprouts.so` and calls `SproutsNative.nativeSvg`. Same seed
always yields the same SVG (parity with DiceBear JS/HTTP).

## Build the Android `.so`

```bash
export ANDROID_NDK_HOME=...   # e.g. $ANDROID_HOME/ndk/26.1.10909125
./apps/android/scripts/build-sprouts.sh          # arm64-v8a
# ./apps/android/scripts/build-sprouts.sh x86_64  # emulator optional
```

Stages to `app/src/main/jniLibs/<abi>/libtriplex_sprouts.so`.

## Seed contract

```text
${contact.id}:${contact.number}:${displayName}
```

See `sproutSeed()` in `SproutAvatar.kt`.
