#!/usr/bin/env bash
#
# Builds the Android JNI wrapper against the published eternal-core crate and writes the
# resulting libraries into app/build/generated/eternalJni for Gradle to package.
#
# The wrapper source lives in rust/. Gradle runs this script before packaging, so an Android
# build needs Rust, cargo-ndk, and the NDK. Generated libraries are not committed.
#
# Usage:
#   bin/build-eternal-jni.sh
#   ANDROID_NDK_HOME=/path/to/ndk bin/build-eternal-jni.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$REPO_ROOT/rust"
OUTPUT_DIR="${ETERNAL_JNI_OUTPUT_DIR:-$REPO_ROOT/app/build/generated/eternalJni}"
NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-24}"
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
RUST_TARGETS=(aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android)
EXPECTED_JNI_SYMBOLS=12

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
fi

if [[ ! -f "$RUST_DIR/Cargo.toml" || ! -f "$RUST_DIR/Cargo.lock" ]]; then
    echo "error: rust/Cargo.toml or rust/Cargo.lock is missing" >&2
    exit 1
fi

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_HOME="$ANDROID_NDK_HOME"
else
    for SDK_ROOT in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
        [[ -n "$SDK_ROOT" ]] || continue
        if [[ -d "$SDK_ROOT/ndk/$NDK_VERSION" ]]; then
            NDK_HOME="$SDK_ROOT/ndk/$NDK_VERSION"
            break
        fi
    done
    if [[ -z "${NDK_HOME:-}" ]]; then
        for SDK_ROOT in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/android-sdk"; do
            [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/ndk" ]] || continue
            NDK_HOME="$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
            if [[ -n "$NDK_HOME" ]]; then
                break
            fi
        done
    fi
fi
if [[ -z "${NDK_HOME:-}" || ! -d "$NDK_HOME" ]]; then
    echo "error: Android NDK $NDK_VERSION was not found" >&2
    echo "       set ANDROID_NDK_HOME, or install it with sdkmanager" >&2
    exit 1
fi
# The Opus dependency looks this variable up while cross-compiling.
export ANDROID_NDK_HOME="$NDK_HOME"

for tool in cargo cargo-ndk; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "error: $tool is not installed" >&2
        if [[ "$tool" == "cargo-ndk" ]]; then
            echo "       install it with: cargo install cargo-ndk" >&2
        fi
        exit 1
    fi
done

if command -v rustup >/dev/null 2>&1; then
    missing=()
    installed="$(cd "$RUST_DIR" && rustup target list --installed)"
    for target in "${RUST_TARGETS[@]}"; do
        if ! grep -qx "$target" <<<"$installed"; then
            missing+=("$target")
        fi
    done
    if ((${#missing[@]} > 0)); then
        echo "error: missing Rust Android targets: ${missing[*]}" >&2
        echo "       install them with: rustup target add ${missing[*]}" >&2
        exit 1
    fi
fi

mkdir -p "$OUTPUT_DIR"
for abi in "${ABIS[@]}"; do
    rm -f "$OUTPUT_DIR/$abi/libeternal_jni.so"
done

echo "Building eternal-jni for ${ABIS[*]}"
echo "  workspace: $RUST_DIR"
echo "  NDK:       $NDK_HOME"
echo "  platform:  $ANDROID_PLATFORM"
echo

TARGET_ARGS=()
for abi in "${ABIS[@]}"; do
    TARGET_ARGS+=(--target "$abi")
done

(
    cd "$RUST_DIR"
    cargo ndk \
        "${TARGET_ARGS[@]}" \
        --platform "$ANDROID_PLATFORM" \
        --output-dir "$OUTPUT_DIR" \
        build --release --locked -p eternal-jni
)

LLVM_NM="$(find "$NDK_HOME/toolchains/llvm/prebuilt" -maxdepth 3 -name llvm-nm 2>/dev/null | head -n 1 || true)"
LLVM_STRIP="$(find "$NDK_HOME/toolchains/llvm/prebuilt" -maxdepth 3 -name llvm-strip 2>/dev/null | head -n 1 || true)"

echo
echo "Built native libraries:"
for abi in "${ABIS[@]}"; do
    lib="$OUTPUT_DIR/$abi/libeternal_jni.so"
    if [[ ! -f "$lib" ]]; then
        echo "error: $lib was not produced" >&2
        exit 1
    fi
    if [[ -n "$LLVM_STRIP" ]]; then
        "$LLVM_STRIP" --strip-unneeded "$lib"
    fi
    if [[ -n "$LLVM_NM" ]]; then
        symbols="$("$LLVM_NM" -D --defined-only "$lib" || true)"
        count="$(printf '%s\n' "$symbols" | grep -c 'Java_com_eddyizm_tempus_eternal_EternalEngine_' || true)"
        if [[ "$count" -ne "$EXPECTED_JNI_SYMBOLS" ]]; then
            echo "error: $lib exports $count of $EXPECTED_JNI_SYMBOLS expected JNI symbols" >&2
            exit 1
        fi
    fi
    printf '  %s (%s bytes)\n' "$lib" "$(wc -c <"$lib" | tr -d ' ')"
done
if [[ -z "$LLVM_NM" ]]; then
    echo "warning: llvm-nm was not found; skipped JNI symbol verification" >&2
fi
if [[ -z "$LLVM_STRIP" ]]; then
    echo "warning: llvm-strip was not found; committed libraries are unstripped" >&2
fi
