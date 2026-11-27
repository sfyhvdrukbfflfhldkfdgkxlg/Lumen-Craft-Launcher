#include "legacy.h"
#include <GLES2/gl2.h>
#include <limits.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

extern "C" intptr_t lumen_quad_indices(unsigned, size_t, unsigned short *, size_t);
extern "C" intptr_t lumen_translate_glsl(const unsigned char *, size_t, unsigned, unsigned, unsigned char *, size_t);

namespace {

constexpr GLenum kMatrixModelview = 0x1700;
constexpr GLenum kMatrixProjection = 0x1701;
constexpr GLenum kMatrixTexture = 0x1702;

constexpr GLenum kCapAlphaTest = 0x0BC0;
constexpr GLenum kCapFog = 0x0B60;
constexpr GLenum kCapTexture2D = 0x0DE1;

constexpr GLenum kClientVertexArray = 0x8074;
constexpr GLenum kClientNormalArray = 0x8075;
constexpr GLenum kClientColorArray = 0x8076;
constexpr GLenum kClientTexCoordArray = 0x8078;

constexpr GLenum kFogMode = 0x0B65;
constexpr GLenum kFogDensity = 0x0B62;
constexpr GLenum kFogStart = 0x0B63;
constexpr GLenum kFogEnd = 0x0B64;
constexpr GLenum kFogColor = 0x0B66;
constexpr GLenum kFogLinear = 0x2601;
constexpr GLenum kFogExp = 0x0800;
constexpr GLenum kFogExp2 = 0x0801;

constexpr GLenum kGetModelviewMatrix = 0x0BA6;
constexpr GLenum kGetProjectionMatrix = 0x0BA7;
constexpr GLenum kGetTextureMatrix = 0x0BA8;

constexpr GLenum kCompile = 0x1300;
constexpr GLenum kCompileAndExecute = 0x1301;

constexpr size_t kMaxMVDepth = 32;
constexpr size_t kMaxProjDepth = 8;
constexpr size_t kMaxTexDepth = 8;
constexpr size_t kMaxDisplayLists = 4096;

struct Vertex {
    float xyz[3];
    float rgba[4];
    float uv[2];
    float normal[3];
};

struct ClientArray {
    bool enabled;
    GLint size;
    GLenum type;
    GLsizei stride;
    const void *pointer;
};

enum ListOpKind : uint8_t {
    OP_DRAW = 1,
    OP_MATRIX_MODE,
    OP_LOAD_IDENTITY,
    OP_LOAD_MATRIX,
    OP_MULT_MATRIX,
    OP_PUSH_MATRIX,
    OP_POP_MATRIX,
    OP_ENABLE,
    OP_DISABLE,
    OP_BIND_TEXTURE,
    OP_COLOR,
    OP_ALPHA_FUNC,
};

struct ListOp {
    ListOpKind kind;
    GLenum mode;
    GLuint uintVal;
    float floats[16];
    Vertex *vertices;
    size_t vertexCount;
};

struct DisplayList {
    bool allocated;
    ListOp *ops;
    size_t count;
    size_t capacity;
};

static void matIdentity(float m[16]) {
    memset(m, 0, sizeof(float) * 16);
    m[0] = m[5] = m[10] = m[15] = 1.0f;
}

static void matMultiply(float out[16], const float a[16], const float b[16]) {
    float r[16];
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            r[col * 4 + row] =
                a[0 * 4 + row] * b[col * 4 + 0] +
                a[1 * 4 + row] * b[col * 4 + 1] +
                a[2 * 4 + row] * b[col * 4 + 2] +
                a[3 * 4 + row] * b[col * 4 + 3];
        }
    }
    memcpy(out, r, sizeof(r));
}

static void clearDisplayList(DisplayList &dl) {
    for (size_t i = 0; i < dl.count; ++i) {
        free(dl.ops[i].vertices);
    }
    free(dl.ops);
    dl.ops = nullptr;
    dl.count = 0;
    dl.capacity = 0;
}

} // namespace

struct LumenGL {
    GLuint program, buffer, elements;
    GLint uMV, uMVP, uTexMat, uTexEnabled, uTex, uAlphaTest, uAlphaRef, uFogMode, uFogColor, uFogParams;

    float mvStack[kMaxMVDepth][16];
    float projStack[kMaxProjDepth][16];
    float texStack[kMaxTexDepth][16];
    size_t mvTop, projTop, texTop;
    GLenum matrixMode;

    float color[4];
    float uv[2];
    float normal[3];

    bool texture2DEnabled;
    bool alphaTestEnabled;
    GLenum alphaFunc;
    float alphaRef;

    bool fogEnabled;
    GLenum fogMode;
    float fogDensity;
    float fogStart;
    float fogEnd;
    float fogColor[4];

    ClientArray vertexArr, colorArr, texCoordArr, normalArr;

    Vertex *vertices;
    size_t size, capacity;
    size_t indexedVertices;
    GLenum indexedMode;
    GLenum mode, error;
    bool inside, dropped;

    DisplayList lists[kMaxDisplayLists];
    GLuint recordingList;
    GLenum recordingMode;
    int callDepth;
};

static thread_local LumenGL *current;

static float *activeMatrix(LumenGL *c) {
    switch (c->matrixMode) {
        case kMatrixProjection: return c->projStack[c->projTop];
        case kMatrixTexture: return c->texStack[c->texTop];
        default: return c->mvStack[c->mvTop];
    }
}

static void fail(GLenum e) {
    if (current && !current->error) current->error = e;
}

static bool recordOp(LumenGL *c, const ListOp &op) {
    if (!c || c->recordingList == 0 || c->recordingList > kMaxDisplayLists) return false;
    DisplayList &dl = c->lists[c->recordingList - 1];
    if (dl.count == dl.capacity) {
        size_t next = dl.capacity ? dl.capacity * 2 : 16;
        if (next > 65536) { fail(GL_OUT_OF_MEMORY); return false; }
        auto *grown = static_cast<ListOp *>(realloc(dl.ops, next * sizeof(ListOp)));
        if (!grown) { fail(GL_OUT_OF_MEMORY); return false; }
        dl.ops = grown;
        dl.capacity = next;
    }
    dl.ops[dl.count++] = op;
    return true;
}

static bool shouldExecute(const LumenGL *c) {
    return c->recordingList == 0 || c->recordingMode == kCompileAndExecute;
}

static GLuint compileShader(GLenum kind, const char *code) {
    GLuint s = glCreateShader(kind);
    glShaderSource(s, 1, &code, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glDeleteShader(s);
        return 0;
    }
    return s;
}

extern "C" LumenGL *lumenGLCreate() {
    static const char *kVertexShader =
        "attribute vec3 aPos;\n"
        "attribute vec4 aCol;\n"
        "attribute vec2 aUV;\n"
        "uniform mat4 uMV;\n"
        "uniform mat4 uMVP;\n"
        "uniform mat4 uTexMat;\n"
        "varying vec4 vCol;\n"
        "varying vec2 vUV;\n"
        "varying float vEyeDist;\n"
        "void main() {\n"
        "  vec4 eye = uMV * vec4(aPos, 1.0);\n"
        "  vEyeDist = length(eye.xyz);\n"
        "  gl_Position = uMVP * vec4(aPos, 1.0);\n"
        "  vCol = aCol;\n"
        "  vUV = (uTexMat * vec4(aUV, 0.0, 1.0)).xy;\n"
        "  gl_PointSize = 1.0;\n"
        "}\n";
    static const char *kFragmentShader =
        "precision mediump float;\n"
        "uniform int uTexEnabled;\n"
        "uniform sampler2D uTex;\n"
        "uniform int uAlphaTest;\n"
        "uniform float uAlphaRef;\n"
        "uniform int uFogMode;\n"
        "uniform vec4 uFogColor;\n"
        "uniform vec3 uFogParams;\n"
        "varying vec4 vCol;\n"
        "varying vec2 vUV;\n"
        "varying float vEyeDist;\n"
        "void main() {\n"
        "  vec4 outColor = vCol;\n"
        "  if (uTexEnabled != 0) {\n"
        "    outColor *= texture2D(uTex, vUV);\n"
        "  }\n"
        "  if (uAlphaTest != 0) {\n"
        "    float a = outColor.a;\n"
        "    bool pass = true;\n"
        "    if (uAlphaTest == 1) pass = false;\n"
        "    else if (uAlphaTest == 2) pass = (a < uAlphaRef);\n"
        "    else if (uAlphaTest == 3) pass = (a == uAlphaRef);\n"
        "    else if (uAlphaTest == 4) pass = (a <= uAlphaRef);\n"
        "    else if (uAlphaTest == 5) pass = (a > uAlphaRef);\n"
        "    else if (uAlphaTest == 6) pass = (a != uAlphaRef);\n"
        "    else if (uAlphaTest == 7) pass = (a >= uAlphaRef);\n"
        "    if (!pass) discard;\n"
        "  }\n"
        "  if (uFogMode != 0) {\n"
        "    float f = 1.0;\n"
        "    if (uFogMode == 1) {\n"
        "      float denom = max(0.00001, uFogParams.y - uFogParams.x);\n"
        "      f = (uFogParams.y - vEyeDist) / denom;\n"
        "    } else if (uFogMode == 2) {\n"
        "      f = exp(-uFogParams.z * vEyeDist);\n"
        "    } else if (uFogMode == 3) {\n"
        "      float d = uFogParams.z * vEyeDist;\n"
        "      f = exp(-(d * d));\n"
        "    }\n"
        "    f = clamp(f, 0.0, 1.0);\n"
        "    outColor.rgb = mix(uFogColor.rgb, outColor.rgb, f);\n"
        "  }\n"
        "  gl_FragColor = outColor;\n"
        "}\n";

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    GLuint program = glCreateProgram();
    if (vs && fs && program) {
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glBindAttribLocation(program, 0, "aPos");
        glBindAttribLocation(program, 1, "aCol");
        glBindAttribLocation(program, 2, "aUV");
        glLinkProgram(program);
    }
    if (vs) glDeleteShader(vs);
    if (fs) glDeleteShader(fs);
    GLint ok = 0;
    if (program) glGetProgramiv(program, GL_LINK_STATUS, &ok);
    LumenGL *ctx = ok ? static_cast<LumenGL *>(calloc(1, sizeof(LumenGL))) : nullptr;
    if (!ctx) {
        if (program) glDeleteProgram(program);
        return nullptr;
    }
    ctx->program = program;
    ctx->uMV = glGetUniformLocation(program, "uMV");
    ctx->uMVP = glGetUniformLocation(program, "uMVP");
    ctx->uTexMat = glGetUniformLocation(program, "uTexMat");
    ctx->uTexEnabled = glGetUniformLocation(program, "uTexEnabled");
    ctx->uTex = glGetUniformLocation(program, "uTex");
    ctx->uAlphaTest = glGetUniformLocation(program, "uAlphaTest");
    ctx->uAlphaRef = glGetUniformLocation(program, "uAlphaRef");
    ctx->uFogMode = glGetUniformLocation(program, "uFogMode");
    ctx->uFogColor = glGetUniformLocation(program, "uFogColor");
    ctx->uFogParams = glGetUniformLocation(program, "uFogParams");

    matIdentity(ctx->mvStack[0]);
    matIdentity(ctx->projStack[0]);
    matIdentity(ctx->texStack[0]);
    ctx->matrixMode = kMatrixModelview;
    ctx->color[0] = ctx->color[1] = ctx->color[2] = ctx->color[3] = 1.0f;
    ctx->normal[2] = 1.0f;
    ctx->alphaFunc = GL_ALWAYS;
    ctx->fogMode = kFogExp;
    ctx->fogDensity = 1.0f;
    ctx->fogStart = 0.0f;
    ctx->fogEnd = 1.0f;

    glGenBuffers(1, &ctx->buffer);
    glGenBuffers(1, &ctx->elements);
    if (!ctx->buffer || !ctx->elements) {
        lumenGLDestroy(ctx);
        return nullptr;
    }
    return ctx;
}

extern "C" void lumenGLDestroy(LumenGL *ctx) {
    if (!ctx) return;
    if (current == ctx) current = nullptr;
    for (size_t i = 0; i < kMaxDisplayLists; ++i) {
        clearDisplayList(ctx->lists[i]);
    }
    glDeleteProgram(ctx->program);
    glDeleteBuffers(1, &ctx->buffer);
    glDeleteBuffers(1, &ctx->elements);
    free(ctx->vertices);
    free(ctx);
}

extern "C" void lumenGLMakeCurrent(LumenGL *ctx) { current = ctx; }

extern "C" intptr_t lumenGLTranslateShader(const char *src, size_t srcLen, unsigned targetEs,
                                           unsigned stage, char *out, size_t outCap) {
    return lumen_translate_glsl(reinterpret_cast<const unsigned char *>(src), srcLen,
                                targetEs, stage, reinterpret_cast<unsigned char *>(out), outCap);
}

static void drawBatch(LumenGL *c, GLenum mode, const Vertex *verts, size_t count) {
    if (!count) return;
    float mvp[16];
    matMultiply(mvp, c->projStack[c->projTop], c->mvStack[c->mvTop]);

    glUseProgram(c->program);
    glUniformMatrix4fv(c->uMV, 1, GL_FALSE, c->mvStack[c->mvTop]);
    glUniformMatrix4fv(c->uMVP, 1, GL_FALSE, mvp);
    glUniformMatrix4fv(c->uTexMat, 1, GL_FALSE, c->texStack[c->texTop]);
    glUniform1i(c->uTexEnabled, c->texture2DEnabled ? 1 : 0);
    glUniform1i(c->uTex, 0);

    int alphaMode = 0;
    if (c->alphaTestEnabled) {
        alphaMode = static_cast<int>(c->alphaFunc - GL_NEVER) + 1;
        if (alphaMode < 1 || alphaMode > 8) alphaMode = 8;
    }
    glUniform1i(c->uAlphaTest, alphaMode);
    glUniform1f(c->uAlphaRef, c->alphaRef);

    int fogModeUniform = 0;
    if (c->fogEnabled) {
        if (c->fogMode == kFogLinear) fogModeUniform = 1;
        else if (c->fogMode == kFogExp) fogModeUniform = 2;
        else if (c->fogMode == kFogExp2) fogModeUniform = 3;
    }
    glUniform1i(c->uFogMode, fogModeUniform);
    glUniform4fv(c->uFogColor, 1, c->fogColor);
    glUniform3f(c->uFogParams, c->fogStart, c->fogEnd, c->fogDensity);

    glBindBuffer(GL_ARRAY_BUFFER, c->buffer);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(count * sizeof(Vertex)), verts, GL_STREAM_DRAW);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), nullptr);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void *>(offsetof(Vertex, rgba)));
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void *>(offsetof(Vertex, uv)));
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glEnableVertexAttribArray(2);

    if (mode <= 6) {
        glDrawArrays(mode, 0, static_cast<GLsizei>(count));
        return;
    }
    if (mode == 9) {
        if (count >= 3) glDrawArrays(GL_TRIANGLE_FAN, 0, static_cast<GLsizei>(count));
        return;
    }
    size_t faces = mode == 7 ? count / 4 : (count >= 4 ? count / 2 - 1 : 0);
    if (!faces) return;
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, c->elements);
    size_t needVerts = count > c->capacity ? count : c->capacity;
    if (needVerts < 128) needVerts = 128;
    if (needVerts > 65536) { fail(GL_OUT_OF_MEMORY); return; }
    if (c->indexedMode != mode || c->indexedVertices < count) {
        size_t idxCap = needVerts * 3;
        auto *indices = static_cast<unsigned short *>(malloc(idxCap * sizeof(unsigned short)));
        if (!indices) { fail(GL_OUT_OF_MEMORY); return; }
        intptr_t n = lumen_quad_indices(mode, needVerts, indices, idxCap);
        if (n >= 0) {
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, n * static_cast<GLsizeiptr>(sizeof(unsigned short)), indices, GL_STATIC_DRAW);
        }
        free(indices);
        GLint bytes = 0;
        glGetBufferParameteriv(GL_ELEMENT_ARRAY_BUFFER, GL_BUFFER_SIZE, &bytes);
        if (n < 0 || bytes != n * static_cast<intptr_t>(sizeof(unsigned short))) {
            c->indexedVertices = 0;
            fail(GL_OUT_OF_MEMORY);
            return;
        }
        c->indexedVertices = needVerts;
        c->indexedMode = mode;
    }
    glDrawElements(GL_TRIANGLES, static_cast<GLsizei>(faces * 6), GL_UNSIGNED_SHORT, nullptr);
}

static GLenum fnGetError() {
    if (!current) return GL_INVALID_OPERATION;
    if (current->error) {
        GLenum e = current->error;
        current->error = 0;
        return e;
    }
    return glGetError();
}

static void fnMatrixMode(GLenum mode) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (mode != kMatrixModelview && mode != kMatrixProjection && mode != kMatrixTexture) {
        fail(GL_INVALID_ENUM);
        return;
    }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_MATRIX_MODE;
        op.mode = mode;
        recordOp(current, op);
    }
    if (shouldExecute(current)) current->matrixMode = mode;
}

static void fnLoadIdentity() {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_LOAD_IDENTITY;
        recordOp(current, op);
    }
    if (shouldExecute(current)) matIdentity(activeMatrix(current));
}

static void fnLoadMatrixf(const GLfloat *m) {
    if (!current || current->inside || !m) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_LOAD_MATRIX;
        memcpy(op.floats, m, sizeof(op.floats));
        recordOp(current, op);
    }
    if (shouldExecute(current)) memcpy(activeMatrix(current), m, sizeof(float) * 16);
}

static void fnMultMatrixf(const GLfloat *m) {
    if (!current || current->inside || !m) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_MULT_MATRIX;
        memcpy(op.floats, m, sizeof(op.floats));
        recordOp(current, op);
    }
    if (shouldExecute(current)) {
        float *cur = activeMatrix(current);
        matMultiply(cur, cur, m);
    }
}

static void fnPushMatrix() {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_PUSH_MATRIX;
        recordOp(current, op);
    }
    if (!shouldExecute(current)) return;
    if (current->matrixMode == kMatrixModelview) {
        if (current->mvTop + 1 >= kMaxMVDepth) { fail(GL_OUT_OF_MEMORY); return; }
        memcpy(current->mvStack[current->mvTop + 1], current->mvStack[current->mvTop], sizeof(float) * 16);
        current->mvTop++;
    } else if (current->matrixMode == kMatrixProjection) {
        if (current->projTop + 1 >= kMaxProjDepth) { fail(GL_OUT_OF_MEMORY); return; }
        memcpy(current->projStack[current->projTop + 1], current->projStack[current->projTop], sizeof(float) * 16);
        current->projTop++;
    } else {
        if (current->texTop + 1 >= kMaxTexDepth) { fail(GL_OUT_OF_MEMORY); return; }
        memcpy(current->texStack[current->texTop + 1], current->texStack[current->texTop], sizeof(float) * 16);
        current->texTop++;
    }
}

static void fnPopMatrix() {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_POP_MATRIX;
        recordOp(current, op);
    }
    if (!shouldExecute(current)) return;
    if (current->matrixMode == kMatrixModelview) {
        if (current->mvTop == 0) { fail(GL_INVALID_OPERATION); return; }
        current->mvTop--;
    } else if (current->matrixMode == kMatrixProjection) {
        if (current->projTop == 0) { fail(GL_INVALID_OPERATION); return; }
        current->projTop--;
    } else {
        if (current->texTop == 0) { fail(GL_INVALID_OPERATION); return; }
        current->texTop--;
    }
}

static void fnTranslatef(GLfloat x, GLfloat y, GLfloat z) {
    float m[16];
    matIdentity(m);
    m[12] = x; m[13] = y; m[14] = z;
    fnMultMatrixf(m);
}

static void fnScalef(GLfloat x, GLfloat y, GLfloat z) {
    float m[16];
    matIdentity(m);
    m[0] = x; m[5] = y; m[10] = z;
    fnMultMatrixf(m);
}

static void fnRotatef(GLfloat angle, GLfloat x, GLfloat y, GLfloat z) {
    float len = sqrtf(x * x + y * y + z * z);
    if (len < 1e-7f) return;
    x /= len; y /= len; z /= len;
    float rad = angle * 3.14159265358979323846f / 180.0f;
    float c = cosf(rad), s = sinf(rad), ic = 1.0f - c;
    float m[16] = {
        x * x * ic + c,     y * x * ic + z * s, z * x * ic - y * s, 0.0f,
        x * y * ic - z * s, y * y * ic + c,     z * y * ic + x * s, 0.0f,
        x * z * ic + y * s, y * z * ic - x * s, z * z * ic + c,     0.0f,
        0.0f,               0.0f,               0.0f,               1.0f,
    };
    fnMultMatrixf(m);
}

static void fnOrtho(double l, double r, double b, double t, double n, double f) {
    if (l == r || b == t || n == f) { fail(GL_INVALID_VALUE); return; }
    float m[16] = {};
    m[0] = static_cast<float>(2.0 / (r - l));
    m[5] = static_cast<float>(2.0 / (t - b));
    m[10] = static_cast<float>(-2.0 / (f - n));
    m[12] = static_cast<float>(-(r + l) / (r - l));
    m[13] = static_cast<float>(-(t + b) / (t - b));
    m[14] = static_cast<float>(-(f + n) / (f - n));
    m[15] = 1.0f;
    fnMultMatrixf(m);
}

static void fnFrustum(double l, double r, double b, double t, double n, double f) {
    if (n <= 0.0 || f <= 0.0 || l == r || b == t || n == f) { fail(GL_INVALID_VALUE); return; }
    float m[16] = {};
    m[0] = static_cast<float>((2.0 * n) / (r - l));
    m[5] = static_cast<float>((2.0 * n) / (t - b));
    m[8] = static_cast<float>((r + l) / (r - l));
    m[9] = static_cast<float>((t + b) / (t - b));
    m[10] = static_cast<float>(-(f + n) / (f - n));
    m[11] = -1.0f;
    m[14] = static_cast<float>(-(2.0 * f * n) / (f - n));
    fnMultMatrixf(m);
}

static void fnBegin(GLenum mode) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (mode > 9) { fail(GL_INVALID_ENUM); return; }
    current->mode = mode;
    current->inside = true;
    current->size = 0;
    current->dropped = false;
}

static void fnColor4f(GLfloat r, GLfloat g, GLfloat b, GLfloat a) {
    if (!current) return;
    if (current->recordingList && !current->inside) {
        ListOp op = {};
        op.kind = OP_COLOR;
        op.floats[0] = r; op.floats[1] = g; op.floats[2] = b; op.floats[3] = a;
        recordOp(current, op);
        if (!shouldExecute(current)) return;
    }
    current->color[0] = r; current->color[1] = g; current->color[2] = b; current->color[3] = a;
}

static void fnColor3f(GLfloat r, GLfloat g, GLfloat b) { fnColor4f(r, g, b, 1.0f); }
static void fnColor4ub(GLubyte r, GLubyte g, GLubyte b, GLubyte a) {
    fnColor4f(r / 255.0f, g / 255.0f, b / 255.0f, a / 255.0f);
}

static void fnTexCoord2f(GLfloat s, GLfloat t) {
    if (!current) return;
    current->uv[0] = s; current->uv[1] = t;
}

static void fnNormal3f(GLfloat x, GLfloat y, GLfloat z) {
    if (!current) return;
    current->normal[0] = x; current->normal[1] = y; current->normal[2] = z;
}

static void fnVertex3f(GLfloat x, GLfloat y, GLfloat z) {
    if (!current || !current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (current->dropped) return;
    if (current->size == current->capacity) {
        size_t cap = current->capacity ? current->capacity * 2 : 128;
        Vertex *v = cap <= 65536 ? static_cast<Vertex *>(realloc(current->vertices, cap * sizeof(Vertex))) : nullptr;
        if (!v) { current->dropped = true; fail(GL_OUT_OF_MEMORY); return; }
        current->vertices = v;
        current->capacity = cap;
    }
    Vertex &v = current->vertices[current->size++];
    v.xyz[0] = x; v.xyz[1] = y; v.xyz[2] = z;
    memcpy(v.rgba, current->color, sizeof(v.rgba));
    memcpy(v.uv, current->uv, sizeof(v.uv));
    memcpy(v.normal, current->normal, sizeof(v.normal));
}

static void fnVertex2f(GLfloat x, GLfloat y) { fnVertex3f(x, y, 0.0f); }
static void fnVertex3d(double x, double y, double z) {
    fnVertex3f(static_cast<GLfloat>(x), static_cast<GLfloat>(y), static_cast<GLfloat>(z));
}

static void fnEnd() {
    if (!current || !current->inside) { fail(GL_INVALID_OPERATION); return; }
    auto *c = current;
    c->inside = false;
    if (!c->size || c->dropped) return;
    if (c->recordingList) {
        auto *copy = static_cast<Vertex *>(malloc(c->size * sizeof(Vertex)));
        if (!copy) { fail(GL_OUT_OF_MEMORY); return; }
        memcpy(copy, c->vertices, c->size * sizeof(Vertex));
        ListOp op = {};
        op.kind = OP_DRAW;
        op.mode = c->mode;
        op.vertices = copy;
        op.vertexCount = c->size;
        if (!recordOp(c, op)) free(copy);
    }
    if (shouldExecute(c)) drawBatch(c, c->mode, c->vertices, c->size);
}

static void fnEnable(GLenum cap) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_ENABLE;
        op.mode = cap;
        recordOp(current, op);
        if (!shouldExecute(current)) return;
    }
    if (cap == kCapTexture2D) current->texture2DEnabled = true;
    else if (cap == kCapAlphaTest) current->alphaTestEnabled = true;
    else if (cap == kCapFog) current->fogEnabled = true;
    else glEnable(cap);
}

static void fnDisable(GLenum cap) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_DISABLE;
        op.mode = cap;
        recordOp(current, op);
        if (!shouldExecute(current)) return;
    }
    if (cap == kCapTexture2D) current->texture2DEnabled = false;
    else if (cap == kCapAlphaTest) current->alphaTestEnabled = false;
    else if (cap == kCapFog) current->fogEnabled = false;
    else glDisable(cap);
}

static void fnAlphaFunc(GLenum func, GLfloat ref) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (func < GL_NEVER || func > GL_ALWAYS) { fail(GL_INVALID_ENUM); return; }
    if (ref < 0.0f) ref = 0.0f;
    if (ref > 1.0f) ref = 1.0f;
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_ALPHA_FUNC;
        op.mode = func;
        op.floats[0] = ref;
        recordOp(current, op);
        if (!shouldExecute(current)) return;
    }
    current->alphaFunc = func;
    current->alphaRef = ref;
}

static void fnFogi(GLenum pname, GLint param) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (pname == kFogMode) {
        if (param != static_cast<GLint>(kFogLinear) && param != static_cast<GLint>(kFogExp) && param != static_cast<GLint>(kFogExp2)) {
            fail(GL_INVALID_ENUM);
            return;
        }
        current->fogMode = static_cast<GLenum>(param);
    } else {
        fail(GL_INVALID_ENUM);
    }
}

static void fnFogf(GLenum pname, GLfloat param) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    switch (pname) {
        case kFogMode: fnFogi(pname, static_cast<GLint>(param)); break;
        case kFogDensity:
            if (param < 0.0f) { fail(GL_INVALID_VALUE); return; }
            current->fogDensity = param;
            break;
        case kFogStart: current->fogStart = param; break;
        case kFogEnd: current->fogEnd = param; break;
        default: fail(GL_INVALID_ENUM); break;
    }
}

static void fnFogfv(GLenum pname, const GLfloat *params) {
    if (!current || current->inside || !params) { fail(GL_INVALID_OPERATION); return; }
    if (pname == kFogColor) memcpy(current->fogColor, params, sizeof(current->fogColor));
    else fnFogf(pname, params[0]);
}

static void fnEnableClientState(GLenum arr) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    switch (arr) {
        case kClientVertexArray: current->vertexArr.enabled = true; break;
        case kClientNormalArray: current->normalArr.enabled = true; break;
        case kClientColorArray: current->colorArr.enabled = true; break;
        case kClientTexCoordArray: current->texCoordArr.enabled = true; break;
        default: fail(GL_INVALID_ENUM); break;
    }
}

static void fnDisableClientState(GLenum arr) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    switch (arr) {
        case kClientVertexArray: current->vertexArr.enabled = false; break;
        case kClientNormalArray: current->normalArr.enabled = false; break;
        case kClientColorArray: current->colorArr.enabled = false; break;
        case kClientTexCoordArray: current->texCoordArr.enabled = false; break;
        default: fail(GL_INVALID_ENUM); break;
    }
}

static void fnVertexPointer(GLint size, GLenum type, GLsizei stride, const void *ptr) {
    if (!current || size < 2 || size > 4 || stride < 0) { fail(GL_INVALID_VALUE); return; }
    current->vertexArr.size = size; current->vertexArr.type = type; current->vertexArr.stride = stride; current->vertexArr.pointer = ptr;
}

static void fnColorPointer(GLint size, GLenum type, GLsizei stride, const void *ptr) {
    if (!current || (size != 3 && size != 4) || stride < 0) { fail(GL_INVALID_VALUE); return; }
    current->colorArr.size = size; current->colorArr.type = type; current->colorArr.stride = stride; current->colorArr.pointer = ptr;
}

static void fnTexCoordPointer(GLint size, GLenum type, GLsizei stride, const void *ptr) {
    if (!current || size < 1 || size > 4 || stride < 0) { fail(GL_INVALID_VALUE); return; }
    current->texCoordArr.size = size; current->texCoordArr.type = type; current->texCoordArr.stride = stride; current->texCoordArr.pointer = ptr;
}

static void fnNormalPointer(GLenum type, GLsizei stride, const void *ptr) {
    if (!current || stride < 0) { fail(GL_INVALID_VALUE); return; }
    current->normalArr.size = 3; current->normalArr.type = type; current->normalArr.stride = stride; current->normalArr.pointer = ptr;
}

static float readComponent(const uint8_t *base, GLenum type, int idx, bool normalize) {
    switch (type) {
        case GL_FLOAT: {
            float v = 0.0f;
            memcpy(&v, base + idx * sizeof(float), sizeof(float));
            return v;
        }
        case GL_UNSIGNED_BYTE:
            return normalize ? (base[idx] / 255.0f) : static_cast<float>(base[idx]);
        case GL_SHORT: {
            int16_t v = 0;
            memcpy(&v, base + idx * sizeof(int16_t), sizeof(int16_t));
            return normalize ? (v / 32767.0f) : static_cast<float>(v);
        }
        default: return 0.0f;
    }
}

static size_t typeBytes(GLenum type) {
    switch (type) {
        case GL_UNSIGNED_BYTE: return 1;
        case GL_SHORT: return 2;
        case GL_FLOAT: return 4;
        default: return 0;
    }
}

static void fnDrawArrays(GLenum mode, GLint first, GLsizei count) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (mode > 9) { fail(GL_INVALID_ENUM); return; }
    if (first < 0 || count < 0) { fail(GL_INVALID_VALUE); return; }
    if (count == 0 || !current->vertexArr.enabled || !current->vertexArr.pointer) return;
    if (static_cast<size_t>(count) > 65536) { fail(GL_OUT_OF_MEMORY); return; }
    size_t vElem = typeBytes(current->vertexArr.type);
    if (!vElem) { fail(GL_INVALID_ENUM); return; }
    size_t vStride = current->vertexArr.stride ? static_cast<size_t>(current->vertexArr.stride) : vElem * static_cast<size_t>(current->vertexArr.size);

    size_t cElem = current->colorArr.enabled ? typeBytes(current->colorArr.type) : 0;
    size_t cStride = current->colorArr.stride ? static_cast<size_t>(current->colorArr.stride) : cElem * static_cast<size_t>(current->colorArr.size);

    size_t tElem = current->texCoordArr.enabled ? typeBytes(current->texCoordArr.type) : 0;
    size_t tStride = current->texCoordArr.stride ? static_cast<size_t>(current->texCoordArr.stride) : tElem * static_cast<size_t>(current->texCoordArr.size);

    auto *batch = static_cast<Vertex *>(malloc(static_cast<size_t>(count) * sizeof(Vertex)));
    if (!batch) { fail(GL_OUT_OF_MEMORY); return; }
    for (GLsizei i = 0; i < count; ++i) {
        size_t idx = static_cast<size_t>(first + i);
        Vertex &dst = batch[i];
        const auto *vp = static_cast<const uint8_t *>(current->vertexArr.pointer) + idx * vStride;
        dst.xyz[0] = readComponent(vp, current->vertexArr.type, 0, false);
        dst.xyz[1] = readComponent(vp, current->vertexArr.type, 1, false);
        dst.xyz[2] = current->vertexArr.size >= 3 ? readComponent(vp, current->vertexArr.type, 2, false) : 0.0f;

        if (current->colorArr.enabled && current->colorArr.pointer && cElem) {
            const auto *cp = static_cast<const uint8_t *>(current->colorArr.pointer) + idx * cStride;
            bool norm = current->colorArr.type == GL_UNSIGNED_BYTE;
            dst.rgba[0] = readComponent(cp, current->colorArr.type, 0, norm);
            dst.rgba[1] = readComponent(cp, current->colorArr.type, 1, norm);
            dst.rgba[2] = readComponent(cp, current->colorArr.type, 2, norm);
            dst.rgba[3] = current->colorArr.size >= 4 ? readComponent(cp, current->colorArr.type, 3, norm) : 1.0f;
        } else {
            memcpy(dst.rgba, current->color, sizeof(dst.rgba));
        }

        if (current->texCoordArr.enabled && current->texCoordArr.pointer && tElem) {
            const auto *tp = static_cast<const uint8_t *>(current->texCoordArr.pointer) + idx * tStride;
            dst.uv[0] = readComponent(tp, current->texCoordArr.type, 0, false);
            dst.uv[1] = current->texCoordArr.size >= 2 ? readComponent(tp, current->texCoordArr.type, 1, false) : 0.0f;
        } else {
            memcpy(dst.uv, current->uv, sizeof(dst.uv));
        }
        memcpy(dst.normal, current->normal, sizeof(dst.normal));
    }

    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_DRAW;
        op.mode = mode;
        op.vertices = batch;
        op.vertexCount = static_cast<size_t>(count);
        if (!recordOp(current, op)) { free(batch); return; }
        if (shouldExecute(current)) drawBatch(current, mode, batch, static_cast<size_t>(count));
        return;
    }
    drawBatch(current, mode, batch, static_cast<size_t>(count));
    free(batch);
}

static void fnBindTexture(GLenum target, GLuint tex) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (current->recordingList) {
        ListOp op = {};
        op.kind = OP_BIND_TEXTURE;
        op.mode = target;
        op.uintVal = tex;
        recordOp(current, op);
        if (!shouldExecute(current)) return;
    }
    glBindTexture(target, tex);
}

static GLuint fnGenLists(GLsizei range) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return 0; }
    if (range < 0) { fail(GL_INVALID_VALUE); return 0; }
    if (range == 0) return 0;
    size_t need = static_cast<size_t>(range);
    size_t run = 0;
    for (size_t i = 0; i < kMaxDisplayLists; ++i) {
        if (!current->lists[i].allocated) {
            if (++run == need) {
                size_t start = i + 1 - need;
                for (size_t j = start; j <= i; ++j) current->lists[j].allocated = true;
                return static_cast<GLuint>(start + 1);
            }
        } else {
            run = 0;
        }
    }
    return 0;
}

static void fnDeleteLists(GLuint list, GLsizei range) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (range < 0) { fail(GL_INVALID_VALUE); return; }
    if (list == 0) return;
    for (GLsizei i = 0; i < range; ++i) {
        size_t idx = static_cast<size_t>(list - 1) + static_cast<size_t>(i);
        if (idx >= kMaxDisplayLists) break;
        clearDisplayList(current->lists[idx]);
        current->lists[idx].allocated = false;
    }
}

static void fnNewList(GLuint list, GLenum mode) {
    if (!current || current->inside || current->recordingList != 0) { fail(GL_INVALID_OPERATION); return; }
    if (mode != kCompile && mode != kCompileAndExecute) { fail(GL_INVALID_ENUM); return; }
    if (list == 0 || list > kMaxDisplayLists) { fail(GL_INVALID_VALUE); return; }
    DisplayList &dl = current->lists[list - 1];
    dl.allocated = true;
    clearDisplayList(dl);
    current->recordingList = list;
    current->recordingMode = mode;
}

static void fnEndList() {
    if (!current || current->inside || current->recordingList == 0) { fail(GL_INVALID_OPERATION); return; }
    current->recordingList = 0;
    current->recordingMode = 0;
}

static void fnCallList(GLuint list) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION); return; }
    if (list == 0 || list > kMaxDisplayLists || current->callDepth >= 32) return;
    DisplayList &dl = current->lists[list - 1];
    if (!dl.allocated) return;
    current->callDepth++;
    for (size_t i = 0; i < dl.count; ++i) {
        const ListOp &op = dl.ops[i];
        switch (op.kind) {
            case OP_DRAW: drawBatch(current, op.mode, op.vertices, op.vertexCount); break;
            case OP_MATRIX_MODE: current->matrixMode = op.mode; break;
            case OP_LOAD_IDENTITY: matIdentity(activeMatrix(current)); break;
            case OP_LOAD_MATRIX: memcpy(activeMatrix(current), op.floats, sizeof(float) * 16); break;
            case OP_MULT_MATRIX: {
                float *cur = activeMatrix(current);
                matMultiply(cur, cur, op.floats);
                break;
            }
            case OP_PUSH_MATRIX: fnPushMatrix(); break;
            case OP_POP_MATRIX: fnPopMatrix(); break;
            case OP_ENABLE: fnEnable(op.mode); break;
            case OP_DISABLE: fnDisable(op.mode); break;
            case OP_BIND_TEXTURE: glBindTexture(op.mode, op.uintVal); break;
            case OP_COLOR: memcpy(current->color, op.floats, sizeof(current->color)); break;
            case OP_ALPHA_FUNC: current->alphaFunc = op.mode; current->alphaRef = op.floats[0]; break;
        }
    }
    current->callDepth--;
}

static void fnGetFloatv(GLenum pname, GLfloat *params) {
    if (!current || !params) { fail(GL_INVALID_OPERATION); return; }
    if (pname == kGetModelviewMatrix) memcpy(params, current->mvStack[current->mvTop], sizeof(float) * 16);
    else if (pname == kGetProjectionMatrix) memcpy(params, current->projStack[current->projTop], sizeof(float) * 16);
    else if (pname == kGetTextureMatrix) memcpy(params, current->texStack[current->texTop], sizeof(float) * 16);
    else glGetFloatv(pname, params);
}

static void fnClearDepth(double d) { glClearDepthf(static_cast<GLclampf>(d)); }

static void fnTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width,
                         GLsizei height, GLint border, GLenum format, GLenum type, const void *pixels) {
    GLint esInternal = internalformat;
    if (internalformat == 0x8058 /* GL_RGBA8 */ || internalformat == 4) esInternal = GL_RGBA;
    else if (internalformat == 0x8051 /* GL_RGB8 */ || internalformat == 3) esInternal = GL_RGB;
    glTexImage2D(target, level, esInternal, width, height, border, format, type, pixels);
}

static const GLubyte *fnGetString(GLenum name) {
    switch (name) {
        case GL_VERSION: return reinterpret_cast<const GLubyte *>("1.5 LumenGL (GLES2 fixed-function compat)");
        case GL_VENDOR: return reinterpret_cast<const GLubyte *>("LumenCraft");
        case GL_RENDERER: return reinterpret_cast<const GLubyte *>("LumenGL GLES2 Translator");
        case GL_EXTENSIONS: return reinterpret_cast<const GLubyte *>("GL_ARB_vertex_buffer_object");
        default: return glGetString(name);
    }
}

extern "C" void *lumenGLGetProcAddress(const char *s) {
    if (!s) return nullptr;
    struct Entry { const char *name; void *fn; };
    static const Entry functions[] = {
        {"glBegin", (void *)fnBegin},
        {"glEnd", (void *)fnEnd},
        {"glColor3f", (void *)fnColor3f},
        {"glColor4f", (void *)fnColor4f},
        {"glColor4ub", (void *)fnColor4ub},
        {"glTexCoord2f", (void *)fnTexCoord2f},
        {"glNormal3f", (void *)fnNormal3f},
        {"glVertex2f", (void *)fnVertex2f},
        {"glVertex3f", (void *)fnVertex3f},
        {"glVertex3d", (void *)fnVertex3d},
        {"glGetError", (void *)fnGetError},
        {"glMatrixMode", (void *)fnMatrixMode},
        {"glLoadIdentity", (void *)fnLoadIdentity},
        {"glLoadMatrixf", (void *)fnLoadMatrixf},
        {"glMultMatrixf", (void *)fnMultMatrixf},
        {"glPushMatrix", (void *)fnPushMatrix},
        {"glPopMatrix", (void *)fnPopMatrix},
        {"glTranslatef", (void *)fnTranslatef},
        {"glScalef", (void *)fnScalef},
        {"glRotatef", (void *)fnRotatef},
        {"glOrtho", (void *)fnOrtho},
        {"glFrustum", (void *)fnFrustum},
        {"glEnable", (void *)fnEnable},
        {"glDisable", (void *)fnDisable},
        {"glAlphaFunc", (void *)fnAlphaFunc},
        {"glFogi", (void *)fnFogi},
        {"glFogf", (void *)fnFogf},
        {"glFogfv", (void *)fnFogfv},
        {"glEnableClientState", (void *)fnEnableClientState},
        {"glDisableClientState", (void *)fnDisableClientState},
        {"glVertexPointer", (void *)fnVertexPointer},
        {"glColorPointer", (void *)fnColorPointer},
        {"glTexCoordPointer", (void *)fnTexCoordPointer},
        {"glNormalPointer", (void *)fnNormalPointer},
        {"glDrawArrays", (void *)fnDrawArrays},
        {"glGenLists", (void *)fnGenLists},
        {"glDeleteLists", (void *)fnDeleteLists},
        {"glNewList", (void *)fnNewList},
        {"glEndList", (void *)fnEndList},
        {"glCallList", (void *)fnCallList},
        {"glGetFloatv", (void *)fnGetFloatv},
        {"glGetIntegerv", (void *)glGetIntegerv},
        {"glGetString", (void *)fnGetString},
        {"glClear", (void *)glClear},
        {"glClearColor", (void *)glClearColor},
        {"glClearDepth", (void *)fnClearDepth},
        {"glViewport", (void *)glViewport},
        {"glScissor", (void *)glScissor},
        {"glBlendFunc", (void *)glBlendFunc},
        {"glDepthFunc", (void *)glDepthFunc},
        {"glDepthMask", (void *)glDepthMask},
        {"glCullFace", (void *)glCullFace},
        {"glFrontFace", (void *)glFrontFace},
        {"glGenTextures", (void *)glGenTextures},
        {"glDeleteTextures", (void *)glDeleteTextures},
        {"glBindTexture", (void *)fnBindTexture},
        {"glTexParameteri", (void *)glTexParameteri},
        {"glTexImage2D", (void *)fnTexImage2D},
        {"glTexSubImage2D", (void *)glTexSubImage2D},
        {"glFlush", (void *)glFlush},
        {"glFinish", (void *)glFinish},
    };
    for (const Entry &entry : functions) {
        if (!strcmp(s, entry.name)) return entry.fn;
    }
    return nullptr;
}
