#include "legacy.h"
#include <GLES2/gl2.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include <limits.h>

extern "C" intptr_t lumen_quad_indices(unsigned, size_t, unsigned short *, size_t);
struct Vertex { float xyz[3], rgba[4]; };
struct LumenGL {
    GLuint program, buffer, elements;
    GLint matrix;
    GLfloat mvp[16], color[4];
    Vertex *vertices;
    size_t size, capacity;
    GLenum mode, error;
    bool inside, dropped;
};
static thread_local LumenGL *current;

static GLuint compile(GLenum kind, const char *code) {
    GLuint s=glCreateShader(kind);
    glShaderSource(s,1,&code,nullptr); glCompileShader(s);
    GLint ok=0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { glDeleteShader(s); return 0; }
    return s;
}
extern "C" LumenGL *lumenGLCreate() {
    GLuint vs=compile(GL_VERTEX_SHADER,
        "attribute vec3 pos; attribute vec4 col; uniform mat4 mvp; varying vec4 color;"
        "void main(){gl_Position=mvp*vec4(pos,1.0);color=col;gl_PointSize=1.0;}");
    GLuint fs=compile(GL_FRAGMENT_SHADER,"precision mediump float; varying vec4 color;void main(){gl_FragColor=color;}");
    GLuint program=glCreateProgram();
    if (vs && fs && program) {
        glAttachShader(program,vs); glAttachShader(program,fs);
        glBindAttribLocation(program,0,"pos"); glBindAttribLocation(program,1,"col"); glLinkProgram(program);
    }
    if (vs) glDeleteShader(vs);
    if (fs) glDeleteShader(fs);
    GLint ok=0;
    if (program) glGetProgramiv(program,GL_LINK_STATUS,&ok);
    LumenGL *ctx=ok ? static_cast<LumenGL*>(calloc(1,sizeof(LumenGL))) : nullptr;
    if (!ctx) { if (program) glDeleteProgram(program); return nullptr; }
    ctx->program=program; ctx->matrix=glGetUniformLocation(program,"mvp");
    for (int i=0;i<4;i++) { ctx->mvp[i*5]=1.f; ctx->color[i]=1.f; }
    glGenBuffers(1,&ctx->buffer); glGenBuffers(1,&ctx->elements);
    if (!ctx->buffer || !ctx->elements) { lumenGLDestroy(ctx); return nullptr; }
    return ctx;
}
extern "C" void lumenGLDestroy(LumenGL *ctx) {
    if (!ctx) return;
    if (current==ctx) current=nullptr;
    glDeleteProgram(ctx->program); glDeleteBuffers(1,&ctx->buffer); glDeleteBuffers(1,&ctx->elements);
    free(ctx->vertices);free(ctx);
}
extern "C" void lumenGLMakeCurrent(LumenGL *ctx) { current=ctx; }
static void fail(GLenum e) { if (current && !current->error) current->error=e; }
static GLenum error() {
    if (!current) return GL_INVALID_OPERATION;
    if (current->error) { GLenum e=current->error;current->error=0;return e; }
    return glGetError();
}
static void begin(GLenum mode) {
    if (!current || current->inside) { fail(GL_INVALID_OPERATION);return; }
    if (mode>8) {fail(GL_INVALID_ENUM);return;}
    current->mode=mode;current->inside=true;current->size=0;current->dropped=false;
}
static void rgba(GLfloat r,GLfloat g,GLfloat b,GLfloat a) {
    if (!current) return;
    float c[]={r,g,b,a};memcpy(current->color,c,sizeof(c));
}
static void xyz(GLfloat x,GLfloat y,GLfloat z) {
    if (!current || !current->inside) {fail(GL_INVALID_OPERATION);return;}
    if (current->dropped) return;
    if (current->size==current->capacity) {
        size_t capacity=current->capacity ? current->capacity*2 : 128;
        Vertex *v=capacity<=65536 ? static_cast<Vertex*>(realloc(current->vertices,capacity*sizeof(Vertex))) : nullptr;
        if (!v) {current->dropped=true;fail(GL_OUT_OF_MEMORY);return;}
        current->vertices=v;current->capacity=capacity;
    }
    Vertex &v=current->vertices[current->size++];
    v.xyz[0]=x;v.xyz[1]=y;v.xyz[2]=z;memcpy(v.rgba,current->color,sizeof(v.rgba));
}
static void end() {
    if (!current || !current->inside) {fail(GL_INVALID_OPERATION);return;}
    auto *c=current;c->inside=false;
    if (!c->size || c->dropped) return;
    glUseProgram(c->program);glUniformMatrix4fv(c->matrix,1,GL_FALSE,c->mvp);
    glBindBuffer(GL_ARRAY_BUFFER,c->buffer);glBufferData(GL_ARRAY_BUFFER,c->size*sizeof(Vertex),c->vertices,GL_STREAM_DRAW);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),nullptr);
    glVertexAttribPointer(1,4,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsetof(Vertex,rgba)));
    glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);
    if (c->mode<=6) glDrawArrays(c->mode,0,static_cast<GLsizei>(c->size));
    else {
        size_t capacity=3*c->size;
        auto *indices=static_cast<unsigned short*>(malloc(capacity*sizeof(unsigned short)));
        if (!indices) { fail(GL_OUT_OF_MEMORY); return; }
        intptr_t n=lumen_quad_indices(c->mode,c->size,indices,capacity);
        if (n>=0) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,c->elements);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,n*sizeof(*indices),indices,GL_STREAM_DRAW);
            glDrawElements(GL_TRIANGLES,static_cast<GLsizei>(n),GL_UNSIGNED_SHORT,nullptr);
        }
        free(indices);
    }
}
extern "C" void *lumenGLGetProcAddress(const char *s) {
    if (!s) return nullptr;
    struct Entry { const char *name;void *fn; };
    static const Entry functions[]={
        {"glBegin",(void*)begin},{"glEnd",(void*)end},{"glColor4f",(void*)rgba},
        {"glVertex3f",(void*)xyz},{"glGetError",(void*)error}
    };
    for (const Entry &entry:functions) if (!strcmp(s,entry.name)) return entry.fn;
    return nullptr;
}
