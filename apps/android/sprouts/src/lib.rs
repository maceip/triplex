//! DiceBear **sprouts** avatars for Triplex.
//!
//! Official Rust SDK (`dicebear-core` + `dicebear-styles` / `sprouts`). Same
//! seed ⇒ same SVG as the JS docs. Built as `libtriplex_sprouts.so` with the
//! NDK clang linker (see `scripts/build-sprouts.sh`) — no gomobile, no npm.

mod jni_bridge;

use std::sync::OnceLock;

use dicebear_core::{Avatar, Style};
use serde_json::json;

static STYLE: OnceLock<Style> = OnceLock::new();

fn sprouts_style() -> &'static Style {
    STYLE.get_or_init(|| {
        Style::from_str(dicebear_styles::SPROUTS).expect("sprouts style definition must parse")
    })
}

/// Generate a sprouts SVG for `seed`.
///
/// `animation` is a DiceBear `animationVariant`
/// (`none`/`slowest`/`slow`/`medium`/`fast`/`fastest`). Empty → `medium`.
pub fn svg_for(seed: &str, size: u32, animation: &str) -> Result<String, String> {
    let size = if size == 0 { 128 } else { size };
    let animation = if animation.is_empty() {
        "medium"
    } else {
        animation
    };
    let avatar = Avatar::new(
        sprouts_style(),
        json!({
            "seed": seed,
            "size": size,
            "animationVariant": animation,
        }),
    )
    .map_err(|e| e.to_string())?;
    Ok(avatar.to_svg().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stable_seed() {
        let a = svg_for("42:555:Alex", 128, "medium").unwrap();
        let b = svg_for("42:555:Alex", 128, "medium").unwrap();
        assert_eq!(a, b);
        assert!(a.contains("<svg"));
        let c = svg_for("99:555:Alex", 128, "medium").unwrap();
        assert_ne!(a, c);
    }
}
