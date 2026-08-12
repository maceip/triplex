#!/usr/bin/env bash
# Build libtriplex_sprouts.so for Android and stage it into the app jniLibs.
# Uses the same NDK clang linker pattern as scripts/prepare-native.sh — no gomobile.
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ANDROID_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SPROUTS_ROOT="${ANDROID_ROOT}/sprouts"
readonly ABI="${1:-arm64-v8a}"
readonly API_LEVEL="${TRIPLEX_ANDROID_API_LEVEL:-33}"
readonly RUST_TOOLCHAIN="${TRIPLEX_RUST_TOOLCHAIN:-1.95.0}"

case "${ABI}" in
    arm64-v8a) readonly RUST_TARGET="aarch64-linux-android"; readonly NDK_CLANG_PREFIX="aarch64-linux-android" ;;
    x86_64)    readonly RUST_TARGET="x86_64-linux-android";  readonly NDK_CLANG_PREFIX="x86_64-linux-android" ;;
    *) echo "Unsupported ABI: ${ABI}" >&2; exit 2 ;;
esac

for tool in cargo rustup; do
    command -v "${tool}" >/dev/null 2>&1 || { echo "Missing ${tool}" >&2; exit 2; }
done

readonly NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${NDK_ROOT}" || ! -f "${NDK_ROOT}/source.properties" ]]; then
    echo "Set ANDROID_NDK_HOME to an installed Android NDK." >&2
    exit 2
fi

toolchain_bin=""
for candidate in \
    "${NDK_ROOT}/toolchains/llvm/prebuilt/darwin-arm64/bin" \
    "${NDK_ROOT}/toolchains/llvm/prebuilt/darwin-x86_64/bin" \
    "${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin"; do
    if [[ -x "${candidate}/${NDK_CLANG_PREFIX}${API_LEVEL}-clang" ]]; then
        toolchain_bin="${candidate}"
        break
    fi
done
if [[ -z "${toolchain_bin}" ]]; then
    echo "Could not resolve NDK clang for ${ABI} api ${API_LEVEL}." >&2
    exit 2
fi

rustup toolchain install "${RUST_TOOLCHAIN}" --profile minimal >/dev/null
rustup target add "${RUST_TARGET}" --toolchain "${RUST_TOOLCHAIN}" >/dev/null

readonly TARGET_CLANG="${toolchain_bin}/${NDK_CLANG_PREFIX}${API_LEVEL}-clang"
readonly TARGET_AR="${toolchain_bin}/llvm-ar"
readonly RUST_LINKER_ENV="CARGO_TARGET_$(echo "${RUST_TARGET}" | tr '[:lower:]-' '[:upper:]_')_LINKER"
readonly PINNED_RUSTC="$(rustup which --toolchain "${RUST_TOOLCHAIN}" rustc)"
readonly OUT_DIR="${ANDROID_ROOT}/app/src/main/jniLibs/${ABI}"
mkdir -p "${OUT_DIR}"

# First run may need to create Cargo.lock
if [[ ! -f "${SPROUTS_ROOT}/Cargo.lock" ]]; then
    rustup run "${RUST_TOOLCHAIN}" cargo generate-lockfile --manifest-path "${SPROUTS_ROOT}/Cargo.toml"
fi

# dicebear-core → jsonschema → rustls → aws-lc-sys needs the NDK clang as CC,
# not only as the Rust linker.
env \
    "${RUST_LINKER_ENV}=${TARGET_CLANG}" \
    "CC=${TARGET_CLANG}" \
    "CXX=${TARGET_CLANG}++" \
    "AR=${TARGET_AR}" \
    "CFLAGS=--target=${RUST_TARGET}${API_LEVEL}" \
    "CXXFLAGS=--target=${RUST_TARGET}${API_LEVEL}" \
    "ANDROID_NDK_ROOT=${NDK_ROOT}" \
    "ANDROID_NDK_HOME=${NDK_ROOT}" \
    "RUSTC=${PINNED_RUSTC}" \
    "RUSTFLAGS=-C link-arg=-Wl,-soname,libtriplex_sprouts.so -C link-arg=-Wl,-z,max-page-size=16384" \
    rustup run "${RUST_TOOLCHAIN}" cargo build \
        --manifest-path "${SPROUTS_ROOT}/Cargo.toml" \
        --release \
        --target "${RUST_TARGET}" \
        --locked

install -m 0755 \
    "${SPROUTS_ROOT}/target/${RUST_TARGET}/release/libtriplex_sprouts.so" \
    "${OUT_DIR}/libtriplex_sprouts.so"

echo "Staged ${OUT_DIR}/libtriplex_sprouts.so"
