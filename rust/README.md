# Eternal Jukebox JNI wrapper

This directory contains the thin Rust JNI wrapper used by Tempus for Eternal mode. The analysis and
branching playback engine itself is **not vendored here**; it is consumed from the published
[`eternal-core`](https://crates.io/crates/eternal-core) crate at version 1.1.0.

```
rust/
├── Cargo.toml                 # workspace + shared release/test profiles
├── Cargo.lock                 # pinned dependency graph (commit this)
├── rust-toolchain.toml        # Rust 1.98 + clippy/rustfmt
├── LICENSE                    # MIT license for this Rust subtree
└── crates/eternal-jni/
    ├── Cargo.toml             # cdylib; depends on eternal-core 1.1.0 + jni
    └── src/
        ├── engine.rs          # sample-accurate renderer and beat planner adapter
        ├── jni.rs             # JNI entry points + Java exception translation
        └── lib.rs
```

## Why only a wrapper is checked in

The engine is a reusable library. Keeping a single published copy avoids two divergent versions of
the analysis/planner code and lets the wrapper track upstream fixes through Cargo. This crate owns
only the Android-specific boundary: handle lifetime, render/seek locking, panic containment, Java
exception translation, and the flat transition map used by the beat-map UI.

## Build requirements

- Rust 1.98.0 (installed automatically by `rustup` from `rust-toolchain.toml`)
- `cargo-ndk` (`cargo install cargo-ndk`)
- Android NDK **28.2.13676358** (the project default) and SDK platform 24+
- Rust Android targets:

```bash
rustup target add \
  aarch64-linux-android \
  armv7-linux-androideabi \
  i686-linux-android \
  x86_64-linux-android
```

## Checks

Run the host checks from this directory:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
```

The tests generate a small WAV in the system temp directory, prepare the engine, render several
seconds of PCM, verify beat jumps, and validate the branch-map indices. No audio files are committed.

## Android libraries

Gradle builds the four ABIs automatically before packaging. To run the same build by hand:

```bash
./bin/build-eternal-jni.sh
```

The script:

- builds this workspace against the checked-in `Cargo.lock`;
- uses the NDK and `cargo-ndk` with `--platform 24`;
- writes `libeternal_jni.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` into
  `app/build/generated/eternalJni`;
- strips the libraries and verifies that all 12 expected JNI symbols are exported.

The generated directory is a Gradle `jniLibs` source set and is not committed. Android Gradle
Plugin runs its own strip task, which is harmless on the already-stripped inputs. Developers who
need native symbols for a local investigation can run `cargo ndk` directly without the strip step.

## Provenance and license

The wrapper and the published `eternal-core` crate originate from the
[Eternal Jukebox Rust project](https://github.com/404Simon/eternal-jukebox-rs). Both are MIT
licensed. The MIT notice is in [`LICENSE`](LICENSE); it applies to this `rust/` subtree. The
Android application as a whole remains GPLv3 (see the repository root `LICENSE`).
