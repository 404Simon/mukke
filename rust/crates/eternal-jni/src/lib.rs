//! Android JNI bindings for the Eternal Jukebox playback engine.
//!
//! Kotlin loads `libeternal_jni.so` and calls the static methods on
//! `com.eddyizm.tempus.eternal.EternalEngine`. The crate exposes a decoded
//! track as an endless, beat-aligned PCM stream; all analysis and rendering
//! stay native.

mod engine;
mod jni;
