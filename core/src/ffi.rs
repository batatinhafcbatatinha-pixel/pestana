//! C ABI for the Apple apps (Swift links the static library and calls these).
//! Strings in are UTF-8, NUL-terminated; strings out must be released with
//! `pestana_free_string`.

use std::ffi::{c_char, CStr, CString};

use crate::pitch::Detector;
use crate::sheet::{Options, View};

fn read(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn give(s: String) -> *mut c_char {
    CString::new(s.replace('\0', "")).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn pestana_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe { drop(CString::from_raw(p)) };
    }
}

#[no_mangle]
pub extern "C" fn pestana_extract_page(html: *const c_char, url: *const c_char) -> *mut c_char {
    give(crate::html::extract(&read(html), &read(url)))
}

#[no_mangle]
pub extern "C" fn pestana_playlist_links(html: *const c_char, url: *const c_char) -> *mut c_char {
    give(crate::html::playlist_links(&read(html), &read(url)))
}

#[no_mangle]
pub extern "C" fn pestana_artist_page(html: *const c_char, url: *const c_char) -> *mut c_char {
    give(crate::html::artist_page(&read(html), &read(url)))
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn pestana_render(
    text: *const c_char,
    key: *const c_char,
    transpose: i32,
    simplify: bool,
    view: i32,
    show_tabs: bool,
    accidentals: i32,
    cols: i32,
) -> *mut c_char {
    let opts = Options {
        transpose,
        simplify,
        view: View::from(view),
        show_tabs,
        accidentals,
        cols: cols.max(8) as usize,
    };
    give(crate::sheet::render(&read(text), &read(key), opts).json)
}

#[no_mangle]
pub extern "C" fn pestana_chord_shape(name: *const c_char) -> *mut c_char {
    give(crate::shapes::shape_json(&read(name)))
}

#[no_mangle]
pub extern "C" fn pestana_bass_shape(name: *const c_char) -> *mut c_char {
    give(crate::shapes::bass_shape_json(&read(name)))
}

#[no_mangle]
pub extern "C" fn pestana_detector_new() -> *mut Detector {
    Box::into_raw(Box::new(Detector::new()))
}

#[no_mangle]
pub extern "C" fn pestana_detector_free(d: *mut Detector) {
    if !d.is_null() {
        unsafe { drop(Box::from_raw(d)) };
    }
}

/// Writes [frequency (-1 if none), clarity, rms] into `out`.
#[no_mangle]
pub extern "C" fn pestana_detect(
    d: *mut Detector,
    samples: *const f32,
    count: i32,
    sample_rate: i32,
    min_hz: f32,
    max_hz: f32,
    out: *mut f32,
) {
    if d.is_null() || samples.is_null() || out.is_null() || count <= 0 {
        return;
    }
    let det = unsafe { &mut *d };
    let buf = unsafe { std::slice::from_raw_parts(samples, count as usize) };
    let p = det.detect(buf, sample_rate as f32, min_hz, max_hz);
    unsafe {
        *out = p.freq;
        *out.add(1) = p.clarity;
        *out.add(2) = p.rms;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let t = CString::new("C  G\nOla mundo").unwrap();
        let k = CString::new("C").unwrap();
        let r = pestana_render(t.as_ptr(), k.as_ptr(), 2, false, 0, true, 0, 40);
        let s = unsafe { CStr::from_ptr(r) }.to_str().unwrap().to_string();
        pestana_free_string(r);
        assert!(s.contains("\"key\":\"D\""), "{s}");

        let d = pestana_detector_new();
        let x: Vec<f32> = (0..4096).map(|i| (2.0 * std::f32::consts::PI * 110.0 * i as f32 / 48000.0).sin()).collect();
        let mut out = [0f32; 3];
        pestana_detect(d, x.as_ptr(), 4096, 48000, 50.0, 1600.0, out.as_mut_ptr());
        pestana_detector_free(d);
        assert!((out[0] - 110.0).abs() < 0.1, "{out:?}");
    }
}
