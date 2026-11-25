//! JNI entry points for `io.github.batatinha.pestana.Native`.

use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jfloatArray, jint, jlong, jstring};
use jni::JNIEnv;

use crate::pitch::Detector;
use crate::sheet::{Options, View};

fn get(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn out(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_batatinha_pestana_Native_extractPage(
    mut env: JNIEnv,
    _: JClass,
    html: JString,
    url: JString,
) -> jstring {
    let h = get(&mut env, &html);
    let u = get(&mut env, &url);
    let r = crate::html::extract(&h, &u);
    out(&mut env, &r)
}

#[no_mangle]
pub extern "system" fn Java_io_github_batatinha_pestana_Native_playlistLinks(
    mut env: JNIEnv,
    _: JClass,
    html: JString,
    url: JString,
) -> jstring {
    let h = get(&mut env, &html);
    let u = get(&mut env, &url);
    let r = crate::html::playlist_links(&h, &u);
    out(&mut env, &r)
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_io_github_batatinha_pestana_Native_render(
    mut env: JNIEnv,
    _: JClass,
    text: JString,
    key: JString,
    transpose: jint,
    simplify: jboolean,
    view: jint,
    show_tabs: jboolean,
    accidentals: jint,
    cols: jint,
) -> jstring {
    let t = get(&mut env, &text);
    let k = get(&mut env, &key);
    let opts = Options {
        transpose,
        simplify: simplify != 0,
        view: View::from(view),
        show_tabs: show_tabs != 0,
        accidentals,
        cols: cols.max(8) as usize,
    };
    let r = crate::sheet::render(&t, &k, opts);
    out(&mut env, &r.json)
}

#[no_mangle]
pub extern "system" fn Java_io_github_batatinha_pestana_Native_chordShape(
    mut env: JNIEnv,
    _: JClass,
    name: JString,
) -> jstring {
    let n = get(&mut env, &name);
    let r = crate::shapes::shape_json(&n);
    out(&mut env, &r)
}

#[no_mangle]
pub extern "system" fn Java_io_github_batatinha_pestana_Native_newDetector(_env: JNIEnv, _: JClass) -> jlong {
    Box::into_raw(Box::new(Detector::new())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_batatinha_pestana_Native_freeDetector(_env: JNIEnv, _: JClass, ptr: jlong) {
    if ptr != 0 {
        unsafe { drop(Box::from_raw(ptr as *mut Detector)) };
    }
}

/// Returns [frequency Hz (-1 if none), clarity 0..1, rms].
#[no_mangle]
pub extern "system" fn Java_io_github_batatinha_pestana_Native_detect(
    env: JNIEnv,
    _: JClass,
    ptr: jlong,
    samples: JFloatArray,
    count: jint,
    sample_rate: jint,
    min_hz: f32,
    max_hz: f32,
) -> jfloatArray {
    let mut result = [-1.0f32, 0.0, 0.0];
    if ptr != 0 {
        let det = unsafe { &mut *(ptr as *mut Detector) };
        let len = env.get_array_length(&samples).unwrap_or(0).min(count).max(0) as usize;
        let mut buf = vec![0f32; len];
        if env.get_float_array_region(&samples, 0, &mut buf).is_ok() {
            let p = det.detect(&buf, sample_rate as f32, min_hz, max_hz);
            result = [p.freq, p.clarity, p.rms];
        }
    }
    match env.new_float_array(3) {
        Ok(arr) => {
            let _ = env.set_float_array_region(&arr, 0, &result);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}
