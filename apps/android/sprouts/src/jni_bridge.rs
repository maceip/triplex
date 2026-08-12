//! JNI entry points for `dev.triplex.ui.components.SproutsNative`.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

use crate::svg_for;

/// `SproutsNative.nativeSvg(seed, size, animation): String?`
#[no_mangle]
pub extern "system" fn Java_dev_triplex_ui_components_SproutsNative_nativeSvg<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed: JString<'local>,
    size: jint,
    animation: JString<'local>,
) -> jstring {
    let seed = match env.get_string(&seed) {
        Ok(s) => match s.to_str() {
            Ok(v) => v.to_owned(),
            Err(_) => return std::ptr::null_mut(),
        },
        Err(_) => return std::ptr::null_mut(),
    };
    let animation = match env.get_string(&animation) {
        Ok(s) => match s.to_str() {
            Ok(v) => v.to_owned(),
            Err(_) => return std::ptr::null_mut(),
        },
        Err(_) => return std::ptr::null_mut(),
    };
    let size = if size <= 0 { 128 } else { size as u32 };

    match svg_for(&seed, size, &animation) {
        Ok(svg) => match env.new_string(svg) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}
