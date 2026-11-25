//! Pestana core. Everything performance- or correctness-sensitive lives here
//! so it can be unit tested on the desktop: chord sheet parsing and layout,
//! transposition, chord diagrams and the tuner's pitch detector.

pub mod chord;
pub mod html;
pub mod json;
pub mod pitch;
pub mod shapes;
pub mod sheet;

#[cfg(target_os = "android")]
mod jni_api;
