//! Expand the vertex order for desktop GL_QUADS and GL_QUAD_STRIP.
#![no_std]

/// Writes GL_UNSIGNED_SHORT triangle indices to caller-provided storage.
/// Incomplete trailing primitives are ignored as required by OpenGL assembly.
/// Returns -1 for an unsupported mode, overflow, or insufficient output.
#[no_mangle]
pub unsafe extern "C" fn lumen_quad_indices(mode: u32, vertices: usize, out: *mut u16, capacity: usize) -> isize {
    if vertices > 65536 { return -1; }
    let (quads, step) = match mode {
        7 => (vertices / 4, 4),
        8 => ((vertices / 2).saturating_sub(1), 2),
        _ => return -1,
    };
    let needed = quads * 6;
    if needed > capacity || (needed != 0 && out.is_null()) { return -1; }
    for face in 0..quads {
        let a = (face * step) as u16;
        let indices = if mode == 7 {
            [a, a+1, a+2, a, a+2, a+3]
        } else {
            [a, a+1, a+3, a, a+3, a+2]
        };
        core::ptr::copy_nonoverlapping(indices.as_ptr(), out.add(face*6), 6);
    }
    needed as isize
}
