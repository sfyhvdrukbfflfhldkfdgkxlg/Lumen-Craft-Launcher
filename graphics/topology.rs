//! Desktop OpenGL primitive index expansion and panic-free GLSL ES shader translation.
#![cfg_attr(not(test), no_std)]

/// Writes GL_UNSIGNED_SHORT triangle indices to caller-provided storage.
/// Supports desktop GL_QUADS (7), GL_QUAD_STRIP (8), and convex GL_POLYGON (9).
/// Incomplete trailing primitives are ignored as required by OpenGL assembly.
/// Returns -1 for an unsupported mode, overflow, or insufficient output.
#[no_mangle]
pub unsafe extern "C" fn lumen_quad_indices(mode: u32, vertices: usize, out: *mut u16, capacity: usize) -> isize {
    if vertices > 65536 {
        return -1;
    }
    if mode == 9 {
        if vertices < 3 {
            return 0;
        }
        let needed = (vertices - 2) * 3;
        if needed > capacity || out.is_null() {
            return -1;
        }
        for i in 1..(vertices - 1) {
            let tri = [0u16, i as u16, (i + 1) as u16];
            core::ptr::copy_nonoverlapping(tri.as_ptr(), out.add((i - 1) * 3), 3);
        }
        return needed as isize;
    }
    let (quads, step) = match mode {
        7 => (vertices / 4, 4),
        8 => ((vertices / 2).saturating_sub(1), 2),
        _ => return -1,
    };
    let needed = quads * 6;
    if needed > capacity || (needed != 0 && out.is_null()) {
        return -1;
    }
    for face in 0..quads {
        let a = (face * step) as u16;
        let indices = if mode == 7 {
            [a, a + 1, a + 2, a, a + 2, a + 3]
        } else {
            [a, a + 1, a + 3, a, a + 3, a + 2]
        };
        core::ptr::copy_nonoverlapping(indices.as_ptr(), out.add(face * 6), 6);
    }
    needed as isize
}

struct Writer {
    out: *mut u8,
    cap: usize,
    pos: usize,
    overflow: bool,
}

impl Writer {
    unsafe fn push_ptr(&mut self, ptr: *const u8, len: usize) {
        if self.overflow || len > self.cap.saturating_sub(self.pos) {
            self.overflow = true;
            return;
        }
        if len > 0 {
            core::ptr::copy_nonoverlapping(ptr, self.out.add(self.pos), len);
            self.pos += len;
        }
    }

    unsafe fn push_bytes(&mut self, data: &[u8]) {
        self.push_ptr(data.as_ptr(), data.len());
    }

    unsafe fn push_byte(&mut self, b: u8) {
        if self.overflow || self.pos >= self.cap {
            self.overflow = true;
            return;
        }
        *self.out.add(self.pos) = b;
        self.pos += 1;
    }
}

#[inline]
fn is_ident(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

#[inline]
unsafe fn eq_at(base: *const u8, len: usize, offset: usize, pat: &[u8]) -> bool {
    if offset > len || pat.len() > len - offset {
        return false;
    }
    let mut j = 0;
    while j < pat.len() {
        if *base.add(offset + j) != *pat.as_ptr().add(j) {
            return false;
        }
        j += 1;
    }
    true
}

unsafe fn contains_raw(base: *const u8, len: usize, needle: &[u8]) -> bool {
    if needle.is_empty() || needle.len() > len {
        return false;
    }
    let limit = len - needle.len();
    let mut i = 0;
    while i <= limit {
        if eq_at(base, len, i, needle) {
            return true;
        }
        i += 1;
    }
    false
}

unsafe fn contains_word_raw(base: *const u8, len: usize, word: &[u8]) -> bool {
    if word.is_empty() || word.len() > len {
        return false;
    }
    let limit = len - word.len();
    let mut i = 0;
    while i <= limit {
        if eq_at(base, len, i, word) {
            let left_ok = i == 0 || !is_ident(*base.add(i - 1));
            let right_ok = i + word.len() == len || !is_ident(*base.add(i + word.len()));
            if left_ok && right_ok {
                return true;
            }
        }
        i += 1;
    }
    false
}

unsafe fn skip_ws(base: *const u8, len: usize) -> usize {
    let mut i = 0;
    while i < len {
        let b = *base.add(i);
        if b == b' ' || b == b'\t' {
            i += 1;
        } else {
            break;
        }
    }
    i
}

unsafe fn parse_version_raw(base: *const u8, len: usize) -> u32 {
    if !eq_at(base, len, 0, b"#version") {
        return 0;
    }
    let mut i = 8;
    while i < len && (*base.add(i) == b' ' || *base.add(i) == b'\t') {
        i += 1;
    }
    let mut num = 0u32;
    let mut seen = false;
    while i < len {
        let b = *base.add(i);
        if b >= b'0' && b <= b'9' {
            seen = true;
            num = num.saturating_mul(10).saturating_add((b - b'0') as u32);
            i += 1;
        } else {
            break;
        }
    }
    if seen { num } else { 0 }
}

unsafe fn write_replaced_raw(w: &mut Writer, base: *const u8, len: usize, from: &[u8], to: &[u8], word_boundary: bool) {
    let mut i = 0;
    while i < len {
        if eq_at(base, len, i, from) {
            let ok = if word_boundary {
                let left_ok = i == 0 || !is_ident(*base.add(i - 1));
                let right_ok = i + from.len() == len || !is_ident(*base.add(i + from.len()));
                left_ok && right_ok
            } else {
                true
            };
            if ok {
                w.push_bytes(to);
                i += from.len();
                continue;
            }
        }
        w.push_byte(*base.add(i));
        i += 1;
    }
}

/// Translates a subset of desktop GLSL (#version 110..460) into GLSL ES 1.00 (`target_es=2`)
/// or GLSL ES 3.00 (`target_es=3`) without claiming unsupported desktop stages.
/// `stage`: 0 = vertex, 1 = fragment.
/// Returns written byte count (>=0), -1 on invalid args / insufficient output,
/// or -2 when the source uses unsupported desktop-only shader features.
#[no_mangle]
pub unsafe extern "C" fn lumen_translate_glsl(
    src: *const u8,
    src_len: usize,
    target_es: u32,
    stage: u32,
    out: *mut u8,
    out_cap: usize,
) -> isize {
    if src.is_null() || out.is_null() || src_len == 0 || out_cap == 0 || (target_es != 2 && target_es != 3) || stage > 1 {
        return -1;
    }

    let unsupported: [&[u8]; 9] = [
        b"local_size_x",
        b"EmitVertex",
        b"EndPrimitive",
        b"gl_InvocationID",
        b"gl_TessCoord",
        b"imageStore",
        b"imageLoad",
        b"atomicAdd",
        b"gl_ClipDistance",
    ];
    let mut u = 0;
    while u < unsupported.len() {
        if contains_word_raw(src, src_len, *unsupported.as_ptr().add(u)) {
            return -2;
        }
        u += 1;
    }
    if contains_raw(src, src_len, b"layout(std430") {
        return -2;
    }

    let uses_frag_color = stage == 1
        && (contains_word_raw(src, src_len, b"gl_FragColor") || contains_raw(src, src_len, b"gl_FragData[0]"));
    let mut has_precision_float = false;
    let mut source_version = 120u32;

    let mut pos = 0usize;
    while pos < src_len {
        let start = pos;
        while pos < src_len && *src.add(pos) != b'\n' {
            pos += 1;
        }
        let mut line_len = pos - start;
        if pos < src_len && *src.add(pos) == b'\n' {
            pos += 1;
        }
        if line_len > 0 && *src.add(start + line_len - 1) == b'\r' {
            line_len -= 1;
        }
        let line_ptr = src.add(start);
        let lead = skip_ws(line_ptr, line_len);
        let trim_ptr = line_ptr.add(lead);
        let trim_len = line_len - lead;
        if eq_at(trim_ptr, trim_len, 0, b"#version") {
            let v = parse_version_raw(trim_ptr, trim_len);
            if v > 0 {
                source_version = v;
            }
        } else if eq_at(trim_ptr, trim_len, 0, b"precision ") && contains_word_raw(trim_ptr, trim_len, b"float") {
            has_precision_float = true;
        } else if target_es == 2 && eq_at(trim_ptr, trim_len, 0, b"layout(") {
            return -2;
        }
    }
    if target_es == 2 && source_version >= 330 && contains_word_raw(src, src_len, b"texelFetch") {
        return -2;
    }

    let mut writer = Writer { out, cap: out_cap, pos: 0, overflow: false };
    if target_es == 3 {
        writer.push_bytes(b"#version 300 es\n");
        if !has_precision_float {
            writer.push_bytes(b"precision highp float;\nprecision highp int;\n");
        }
        if uses_frag_color {
            writer.push_bytes(b"out vec4 lumen_FragColor;\n");
        }
    } else {
        writer.push_bytes(b"#version 100\n");
        if stage == 1 && !has_precision_float {
            writer.push_bytes(b"precision mediump float;\n");
        }
    }

    let mut scratch = [0u8; 2048];
    let mut mid = [0u8; 2048];
    let mut mid2 = [0u8; 2048];
    let mut mid3 = [0u8; 2048];

    pos = 0;
    while pos < src_len {
        let start = pos;
        while pos < src_len && *src.add(pos) != b'\n' {
            pos += 1;
        }
        let mut line_len = pos - start;
        if pos < src_len && *src.add(pos) == b'\n' {
            pos += 1;
        }
        if line_len > 0 && *src.add(start + line_len - 1) == b'\r' {
            line_len -= 1;
        }
        let line_ptr = src.add(start);
        let lead = skip_ws(line_ptr, line_len);
        let trim_ptr = line_ptr.add(lead);
        let trim_len = line_len - lead;

        if eq_at(trim_ptr, trim_len, 0, b"#version") {
            continue;
        }

        let mut old_p: &[u8] = b"";
        let mut new_p: &[u8] = b"";
        if target_es == 3 {
            if stage == 0 && eq_at(trim_ptr, trim_len, 0, b"attribute ") {
                old_p = b"attribute "; new_p = b"in ";
            } else if stage == 0 && eq_at(trim_ptr, trim_len, 0, b"varying ") {
                old_p = b"varying "; new_p = b"out ";
            } else if stage == 1 && eq_at(trim_ptr, trim_len, 0, b"varying ") {
                old_p = b"varying "; new_p = b"in ";
            }
        } else {
            if stage == 0 && eq_at(trim_ptr, trim_len, 0, b"in ") {
                old_p = b"in "; new_p = b"attribute ";
            } else if stage == 0 && eq_at(trim_ptr, trim_len, 0, b"out ") {
                old_p = b"out "; new_p = b"varying ";
            } else if stage == 1 && eq_at(trim_ptr, trim_len, 0, b"in ") {
                old_p = b"in "; new_p = b"varying ";
            }
        }

        let (active_ptr, active_len) = if !old_p.is_empty() {
            let tail_len = trim_len - old_p.len();
            let needed = lead + new_p.len() + tail_len;
            if needed <= scratch.len() {
                core::ptr::copy_nonoverlapping(line_ptr, scratch.as_mut_ptr(), lead);
                core::ptr::copy_nonoverlapping(new_p.as_ptr(), scratch.as_mut_ptr().add(lead), new_p.len());
                core::ptr::copy_nonoverlapping(trim_ptr.add(old_p.len()), scratch.as_mut_ptr().add(lead + new_p.len()), tail_len);
                (scratch.as_ptr(), needed)
            } else {
                (line_ptr, line_len)
            }
        } else {
            (line_ptr, line_len)
        };

        if target_es == 3 {
            let mut mw = Writer { out: mid.as_mut_ptr(), cap: mid.len(), pos: 0, overflow: false };
            write_replaced_raw(&mut mw, active_ptr, active_len, b"texture2D(", b"texture(", true);
            if mw.overflow { return -1; }
            let mut mw2 = Writer { out: mid2.as_mut_ptr(), cap: mid2.len(), pos: 0, overflow: false };
            write_replaced_raw(&mut mw2, mid.as_ptr(), mw.pos, b"textureCube(", b"texture(", true);
            if mw2.overflow { return -1; }
            if uses_frag_color {
                let mut mw3 = Writer { out: mid3.as_mut_ptr(), cap: mid3.len(), pos: 0, overflow: false };
                write_replaced_raw(&mut mw3, mid2.as_ptr(), mw2.pos, b"gl_FragData[0]", b"lumen_FragColor", false);
                if mw3.overflow { return -1; }
                write_replaced_raw(&mut writer, mid3.as_ptr(), mw3.pos, b"gl_FragColor", b"lumen_FragColor", true);
            } else {
                writer.push_ptr(mid2.as_ptr(), mw2.pos);
            }
        } else {
            write_replaced_raw(&mut writer, active_ptr, active_len, b"texture(", b"texture2D(", true);
        }
        writer.push_byte(b'\n');
        if writer.overflow {
            return -1;
        }
    }

    if writer.overflow { -1 } else { writer.pos as isize }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quad_and_polygon_expansion() {
        let mut idx = [0u16; 24];
        unsafe {
            assert_eq!(lumen_quad_indices(7, 4, idx.as_mut_ptr(), idx.len()), 6);
            assert_eq!(&idx[..6], &[0, 1, 2, 0, 2, 3]);
            assert_eq!(lumen_quad_indices(8, 4, idx.as_mut_ptr(), idx.len()), 6);
            assert_eq!(&idx[..6], &[0, 1, 3, 0, 3, 2]);
            assert_eq!(lumen_quad_indices(9, 5, idx.as_mut_ptr(), idx.len()), 9);
            assert_eq!(&idx[..9], &[0, 1, 2, 0, 2, 3, 0, 3, 4]);
            assert_eq!(lumen_quad_indices(10, 4, idx.as_mut_ptr(), idx.len()), -1);
        }
    }

    #[test]
    fn translate_glsl_120_to_es300() {
        let frag = b"#version 120\nvarying vec2 uv;\nuniform sampler2D tex;\nvoid main(){ gl_FragColor = texture2D(tex, uv); }\n";
        let mut out = [0u8; 512];
        let n = unsafe { lumen_translate_glsl(frag.as_ptr(), frag.len(), 3, 1, out.as_mut_ptr(), out.len()) };
        assert!(n > 0);
        let text = core::str::from_utf8(&out[..n as usize]).unwrap();
        assert!(text.starts_with("#version 300 es\n"));
        assert!(text.contains("out vec4 lumen_FragColor;\n"));
        assert!(text.contains("in vec2 uv;\n"));
        assert!(text.contains("lumen_FragColor = texture(tex, uv);"));
    }

    #[test]
    fn translate_glsl_330_core_vertex_to_es300() {
        let vert = b"#version 330 core\nlayout(location = 0) in vec3 pos;\nout vec3 vPos;\nvoid main(){ vPos = pos; gl_Position = vec4(pos, 1.0); }\n";
        let mut out = [0u8; 512];
        let n = unsafe { lumen_translate_glsl(vert.as_ptr(), vert.len(), 3, 0, out.as_mut_ptr(), out.len()) };
        assert!(n > 0);
        let text = core::str::from_utf8(&out[..n as usize]).unwrap();
        assert!(text.starts_with("#version 300 es\n"));
        assert!(text.contains("layout(location = 0) in vec3 pos;"));
    }

    #[test]
    fn rejects_unsupported_compute_and_geometry_shaders() {
        let compute = b"#version 430\nlayout(local_size_x = 8) in;\nvoid main(){}\n";
        let geom = b"#version 330\nvoid main(){ EmitVertex(); EndPrimitive(); }\n";
        let mut out = [0u8; 256];
        unsafe {
            assert_eq!(lumen_translate_glsl(compute.as_ptr(), compute.len(), 3, 0, out.as_mut_ptr(), out.len()), -2);
            assert_eq!(lumen_translate_glsl(geom.as_ptr(), geom.len(), 3, 0, out.as_mut_ptr(), out.len()), -2);
        }
    }
}
