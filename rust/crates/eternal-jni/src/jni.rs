//! JNI surface used by `com.eddyizm.tempus.eternal.EternalEngine`.
//!
//! Every entry point catches panics and translates failures into Java
//! exceptions; a Rust panic must never cross the FFI boundary.

use std::{
    mem::{align_of, size_of},
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
    sync::{Arc, Mutex, MutexGuard},
};

use jni::{
    JNIEnv,
    objects::{JByteBuffer, JClass, JString},
    sys::{jdouble, jint, jintArray, jlong},
};

use crate::engine::{Engine, Status};

const ILLEGAL_ARGUMENT: &str = "java/lang/IllegalArgumentException";
const ILLEGAL_STATE: &str = "java/lang/IllegalStateException";
const RUNTIME_EXCEPTION: &str = "java/lang/RuntimeException";

/// Owned native state behind a Java `long` handle.
struct Handle {
    engine: Mutex<Engine>,
    status: Arc<Status>,
}

struct Failure {
    class: &'static str,
    message: String,
}

impl Failure {
    fn illegal_argument(message: impl Into<String>) -> Self {
        Self {
            class: ILLEGAL_ARGUMENT,
            message: message.into(),
        }
    }

    fn illegal_state(message: impl Into<String>) -> Self {
        Self {
            class: ILLEGAL_STATE,
            message: message.into(),
        }
    }
}

type JniResult<T> = Result<T, Failure>;

fn throw(env: &mut JNIEnv<'_>, class: &str, message: &str) {
    // If the JVM refuses to throw there is nothing sensible left to do; avoid
    // turning the error path into a second panic.
    let _ = env.throw_new(class, message);
}

fn panic_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "unknown panic in the eternal engine".to_string()
    }
}

/// Run a JNI body under `catch_unwind`, throwing a Java exception on error.
fn guard<T>(env: &mut JNIEnv<'_>, body: impl FnOnce(&mut JNIEnv<'_>) -> JniResult<T>) -> Option<T> {
    match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(Ok(value)) => Some(value),
        Ok(Err(failure)) => {
            throw(env, failure.class, &failure.message);
            None
        }
        Err(payload) => {
            throw(
                env,
                RUNTIME_EXCEPTION,
                &format!("native eternal engine panic: {}", panic_message(&*payload)),
            );
            None
        }
    }
}

fn java_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> JniResult<String> {
    if value.is_null() {
        return Err(Failure::illegal_argument("audio path must not be null"));
    }
    let path = env.get_string(value).map_err(|error| {
        Failure::illegal_argument(format!("could not read audio path: {error}"))
    })?;
    let path = String::from(path);
    if path.is_empty() {
        return Err(Failure::illegal_argument("audio path must not be empty"));
    }
    Ok(path)
}

/// Recover a handle previously returned by `nativeCreate`.
fn handle(raw: jlong) -> JniResult<&'static Handle> {
    if raw <= 0 {
        return Err(Failure::illegal_argument(format!(
            "unknown eternal engine handle: {raw}"
        )));
    }
    let pointer = raw as usize as *const Handle;
    if pointer.is_null() {
        return Err(Failure::illegal_argument("eternal engine handle is null"));
    }
    // SAFETY: the pointer comes from `Box::into_raw` in `nativeCreate` and
    // stays valid until `nativeDestroy`. Kotlin owns the handle lifetime.
    Ok(unsafe { &*pointer })
}

fn lock_engine(handle: &Handle) -> MutexGuard<'_, Engine> {
    // A panic is caught per JNI call, so a poisoned mutex still protects
    // consistent state; keep serving rather than failing every later call.
    handle
        .engine
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn direct_buffer_address(env: &JNIEnv<'_>, buffer: &JByteBuffer<'_>) -> JniResult<*mut u8> {
    if buffer.is_null() {
        return Err(Failure::illegal_argument(
            "destination buffer must not be null",
        ));
    }
    env.get_direct_buffer_address(buffer).map_err(|error| {
        Failure::illegal_argument(format!(
            "destination buffer must be a direct ByteBuffer: {error}"
        ))
    })
}

/// Prepare an eternal playback session for a local audio file.
///
/// Returns an opaque handle, or throws and returns zero on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeCreate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    start_seconds: jdouble,
    seed: jlong,
) -> jlong {
    let Some(raw) = guard(&mut env, |env| {
        let path = java_string(env, &path)?;
        let engine = Engine::prepare(Path::new(&path), start_seconds, seed as u64)
            .map_err(Failure::illegal_state)?;
        let status = Arc::clone(engine.status());
        let handle = Box::new(Handle {
            engine: Mutex::new(engine),
            status,
        });
        Ok(Box::into_raw(handle) as jlong)
    }) else {
        return 0;
    };
    raw
}

/// Fill `frames * channels` interleaved f32 samples into a direct `ByteBuffer`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeRender(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
    buffer: JByteBuffer<'_>,
    frames: jint,
) -> jint {
    let Some(rendered) = guard(&mut env, |env| {
        let handle = handle(raw_handle)?;
        if frames < 0 {
            return Err(Failure::illegal_argument(format!(
                "frame count must not be negative: {frames}"
            )));
        }

        let mut engine = lock_engine(handle);
        let channels = usize::from(engine.channels());
        let frame_count = usize::try_from(frames)
            .map_err(|_| Failure::illegal_argument("frame count is too large"))?;
        let samples = frame_count
            .checked_mul(channels)
            .ok_or_else(|| Failure::illegal_argument("frame count overflows the sample count"))?;
        let bytes = samples
            .checked_mul(size_of::<f32>())
            .ok_or_else(|| Failure::illegal_argument("frame count overflows the buffer size"))?;

        if buffer.is_null() {
            return Err(Failure::illegal_argument(
                "destination buffer must not be null",
            ));
        }
        let capacity = env.get_direct_buffer_capacity(&buffer).map_err(|error| {
            Failure::illegal_argument(format!(
                "destination buffer must be a direct ByteBuffer: {error}"
            ))
        })?;
        if capacity < bytes {
            return Err(Failure::illegal_argument(format!(
                "destination buffer is too small: {capacity} bytes available, {bytes} required"
            )));
        }

        let address = direct_buffer_address(env, &buffer)?;
        if !(address as usize).is_multiple_of(align_of::<f32>()) {
            return Err(Failure::illegal_argument(
                "destination buffer is not aligned for f32 samples",
            ));
        }
        if frame_count == 0 {
            return Ok(0);
        }

        // SAFETY: the JVM guarantees the direct buffer is valid and writable
        // for its capacity, which we checked against the requested length.
        // Alignment was checked above, satisfying `f32` access requirements.
        #[allow(clippy::cast_ptr_alignment)]
        let output = unsafe { std::slice::from_raw_parts_mut(address.cast::<f32>(), samples) };
        engine.render(output, frame_count);
        Ok(frames)
    }) else {
        return 0;
    };
    rendered
}

/// Sample rate of the decoded source.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeSampleRate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jint {
    guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        let rate = lock_engine(handle).sample_rate();
        i32::try_from(rate)
            .map_err(|_| Failure::illegal_state("sample rate does not fit into an int"))
    })
    .unwrap_or(0)
}

/// Channel count of the rendered output (1 or 2).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeChannels(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jint {
    guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        Ok(jint::from(lock_engine(handle).channels()))
    })
    .unwrap_or(0)
}

/// Duration of the source track in seconds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeDurationSeconds(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jdouble {
    guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        Ok(lock_engine(handle).duration_seconds())
    })
    .unwrap_or(0.0)
}

/// Source-track position of the beat currently being rendered, in milliseconds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeSourcePositionMs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jlong {
    guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        i64::try_from(handle.status.source_position_ms())
            .map_err(|_| Failure::illegal_state("source position does not fit into a long"))
    })
    .unwrap_or(0)
}

/// Index of the beat currently being rendered.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeCurrentBeat(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jint {
    guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        i32::try_from(handle.status.current_beat())
            .map_err(|_| Failure::illegal_state("beat index does not fit into an int"))
    })
    .unwrap_or(0)
}

/// Number of jumps taken since the session started.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeJumpCount(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jlong {
    guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        Ok(handle.status.jump_count())
    })
    .unwrap_or(0)
}

/// Number of detected beats in the source track.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeBeatCount(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jint {
    guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        i32::try_from(lock_engine(handle).beat_count())
            .map_err(|_| Failure::illegal_state("beat count does not fit into an int"))
    })
    .unwrap_or(0)
}

/// Flat `(source, destination)` beat pairs for every possible transition.
///
/// The result is an `int[]` with two entries per branch: `[source0, target0,
/// source1, target1, ...]`. It is immutable for the lifetime of the engine and
/// is fetched once per session for the UI visualization.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeBranches(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) -> jintArray {
    let Some(values) = guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        let engine = lock_engine(handle);
        let mut values = Vec::with_capacity(engine.branches().len() * 2);
        for &(source, destination) in engine.branches() {
            values.push(
                i32::try_from(source)
                    .map_err(|_| Failure::illegal_state("source beat does not fit into an int"))?,
            );
            values.push(i32::try_from(destination).map_err(|_| {
                Failure::illegal_state("destination beat does not fit into an int")
            })?);
        }
        Ok(values)
    }) else {
        return std::ptr::null_mut();
    };

    let Ok(length) = jint::try_from(values.len()) else {
        throw(
            &mut env,
            ILLEGAL_STATE,
            "branch map is too large for a Java int array",
        );
        return std::ptr::null_mut();
    };
    let array = match env.new_int_array(length) {
        Ok(array) => array,
        Err(error) => {
            throw(
                &mut env,
                RUNTIME_EXCEPTION,
                &format!("could not allocate the branch map: {error}"),
            );
            return std::ptr::null_mut();
        }
    };
    if let Err(error) = env.set_int_array_region(&array, 0, &values) {
        throw(
            &mut env,
            RUNTIME_EXCEPTION,
            &format!("could not fill the branch map: {error}"),
        );
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// Restart the walk at the beat containing `seconds`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeSeekSeconds(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
    seconds: jdouble,
) {
    let _ = guard(&mut env, |_env| {
        let handle = handle(raw_handle)?;
        if !seconds.is_finite() {
            return Err(Failure::illegal_argument(format!(
                "seek position must be finite: {seconds}"
            )));
        }
        lock_engine(handle)
            .seek_seconds(seconds)
            .map_err(Failure::illegal_state)
    });
}

/// Drop the native engine. Passing zero is a no-op.
///
/// Kotlin calls this exactly once, after the render thread has stopped.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eddyizm_tempus_eternal_EternalEngine_nativeDestroy(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_handle: jlong,
) {
    let _ = guard(&mut env, |_env| {
        if raw_handle == 0 {
            return Ok(());
        }
        if raw_handle < 0 {
            return Err(Failure::illegal_argument(format!(
                "unknown eternal engine handle: {raw_handle}"
            )));
        }
        let pointer = raw_handle as usize as *mut Handle;
        // SAFETY: the handle was produced by `Box::into_raw` in
        // `nativeCreate` and is destroyed only here.
        unsafe {
            drop(Box::from_raw(pointer));
        }
        Ok(())
    });
}
