#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace {
const char* gl_string(GLenum name) {
    auto value = glGetString(name);
    return value ? reinterpret_cast<const char*>(value) : "unknown";
}
bool inspect_version(EGLDisplay display, EGLint version, char* report, size_t size) {
    const EGLint config_attrs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
        version == 3 ? 0x0040 : EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_NONE };
    EGLConfig config;
    EGLint n;
    if (!eglChooseConfig(display, config_attrs, &config, 1, &n) || n < 1) return false;
    const EGLint pbuffer_attrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    const EGLint context_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbuffer_attrs);
    if (surface == EGL_NO_SURFACE) return false;
    EGLContext ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    bool bound = ctx != EGL_NO_CONTEXT && eglMakeCurrent(display, surface, surface, ctx);
    if (bound) {
        unsigned char pixel[4] = {};
        glClearColor(0, 1, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        bool passed = glGetError() == GL_NO_ERROR && pixel[0] == 0 && pixel[1] == 255 && pixel[2] == 0;
        snprintf(report, size, "GPU: %.256s\nVendor: %.256s\nGL: %.256s\nGLSL: %.256s\n1px render/readback: %s",
            gl_string(GL_RENDERER), gl_string(GL_VENDOR), gl_string(GL_VERSION),
            gl_string(GL_SHADING_LANGUAGE_VERSION), passed ? "PASS" : "FAIL");
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (ctx != EGL_NO_CONTEXT) eglDestroyContext(display, ctx);
    eglDestroySurface(display, surface);
    return bound;
}

// Minimal Vulkan ABI types for dynamic runtime probing (API 23 safe: no link-time dependency).
struct VkApplicationInfo {
    uint32_t sType;
    const void* pNext;
    const char* pApplicationName;
    uint32_t applicationVersion;
    const char* pEngineName;
    uint32_t engineVersion;
    uint32_t apiVersion;
};
struct VkInstanceCreateInfo {
    uint32_t sType;
    const void* pNext;
    uint32_t flags;
    const VkApplicationInfo* pApplicationInfo;
    uint32_t enabledLayerCount;
    const char* const* ppEnabledLayerNames;
    uint32_t enabledExtensionCount;
    const char* const* ppEnabledExtensionNames;
};
struct VkPhysicalDeviceProperties {
    uint32_t apiVersion;
    uint32_t driverVersion;
    uint32_t vendorID;
    uint32_t deviceID;
    uint32_t deviceType;
    char deviceName[256];
    uint8_t pipelineCacheUUID[16];
    uint8_t padding[1024]; // Safe headroom for limits + sparseProperties
};

const char* vk_device_type(uint32_t t) {
    switch (t) {
        case 1: return "integrated";
        case 2: return "discrete";
        case 3: return "virtual";
        case 4: return "cpu";
        default: return "other";
    }
}

void inspect_vulkan(char* out, size_t size) {
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        snprintf(out, size, "Vulkan: unavailable (no libvulkan.so)");
        return;
    }
    using PFN_vkGetInstanceProcAddr = void* (*)(void*, const char*);
    auto getProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!getProc) {
        dlclose(lib);
        snprintf(out, size, "Vulkan: unavailable (missing entry point)");
        return;
    }
    using PFN_vkCreateInstance = int32_t (*)(const VkInstanceCreateInfo*, const void*, void**);
    using PFN_vkDestroyInstance = void (*)(void*, const void*);
    using PFN_vkEnumeratePhysicalDevices = int32_t (*)(void*, uint32_t*, void**);
    using PFN_vkGetPhysicalDeviceProperties = void (*)(void*, VkPhysicalDeviceProperties*);
    auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(getProc(nullptr, "vkCreateInstance"));
    if (!createInstance) {
        dlclose(lib);
        snprintf(out, size, "Vulkan: unavailable (no vkCreateInstance)");
        return;
    }
    VkApplicationInfo app = {};
    app.sType = 0; // VK_STRUCTURE_TYPE_APPLICATION_INFO
    app.pApplicationName = "LumenCraft";
    app.apiVersion = (1u << 22); // VK_API_VERSION_1_0
    VkInstanceCreateInfo info = {};
    info.sType = 1; // VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
    info.pApplicationInfo = &app;
    void* instance = nullptr;
    int32_t rc = createInstance(&info, nullptr, &instance);
    if (rc != 0 || !instance) {
        dlclose(lib);
        snprintf(out, size, "Vulkan: unavailable (vkCreateInstance %d)", rc);
        return;
    }
    auto destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(getProc(instance, "vkDestroyInstance"));
    auto enumDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(getProc(instance, "vkEnumeratePhysicalDevices"));
    auto getProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(getProc(instance, "vkGetPhysicalDeviceProperties"));
    uint32_t count = 0;
    if (enumDevices && getProps && enumDevices(instance, &count, nullptr) == 0 && count > 0) {
        void* devices[8] = {};
        uint32_t cap = count > 8 ? 8 : count;
        if (enumDevices(instance, &cap, devices) == 0 && cap > 0 && devices[0]) {
            VkPhysicalDeviceProperties props = {};
            getProps(devices[0], &props);
            props.deviceName[255] = '\0';
            uint32_t major = (props.apiVersion >> 22) & 0x7fu;
            uint32_t minor = (props.apiVersion >> 12) & 0x3ffu;
            uint32_t patch = props.apiVersion & 0xfffu;
            snprintf(out, size, "Vulkan: %u.%u.%u · %.180s (%s)",
                major, minor, patch, props.deviceName, vk_device_type(props.deviceType));
        } else {
            snprintf(out, size, "Vulkan: device query failed");
        }
    } else {
        snprintf(out, size, "Vulkan: unavailable (0 physical devices)");
    }
    if (destroyInstance) destroyInstance(instance, nullptr);
    dlclose(lib);
}
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_lumencraft_runtime_GraphicsProbe_readOnWorker(JNIEnv* env, jclass) {
    char glReport[1280] = {};
    char vkReport[384] = {};
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
        snprintf(glReport, sizeof(glReport), "EGL initialization failed (0x%x)", eglGetError());
    } else {
        if (!eglBindAPI(EGL_OPENGL_ES_API) ||
            (!inspect_version(display, 3, glReport, sizeof(glReport)) && !inspect_version(display, 2, glReport, sizeof(glReport))))
            snprintf(glReport, sizeof(glReport), "EGL pbuffer context unavailable (0x%x)", eglGetError());
        eglTerminate(display);
    }
    eglReleaseThread();
    inspect_vulkan(vkReport, sizeof(vkReport));
    char combined[1792] = {};
    snprintf(combined, sizeof(combined), "%s\n%s", glReport, vkReport);
    return env->NewStringUTF(combined);
}
