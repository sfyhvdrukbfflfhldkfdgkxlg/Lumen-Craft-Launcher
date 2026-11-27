#include <jni.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdint.h>
#include <signal.h>
#include <unistd.h>

namespace {
constexpr size_t kMaxExtraOpts = 128;
constexpr size_t kMaxOptLen = 4096;

void* watch_parent(void* arg) {
    const pid_t parent = static_cast<pid_t>(reinterpret_cast<intptr_t>(arg));
    while (getppid() == parent) sleep(2);
    kill(getpid(), SIGTERM);
    return nullptr;
}

size_t load_extra_options(const char* path, char** storage, size_t maxCount) {
    if (!path || !*path) return 0;
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    size_t count = 0;
    char line[kMaxOptLen];
    while (count < maxCount && fgets(line, sizeof(line), f)) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = '\0';
        if (len == 0 || line[0] == '#') continue;
        char* copy = static_cast<char*>(malloc(len + 1));
        if (!copy) break;
        memcpy(copy, line, len + 1);
        storage[count++] = copy;
    }
    fclose(f);
    return count;
}

int launch(JNIEnv* env, const char* major, const char* entry_name, int mode, int count, char** values) {
    jclass system = env->FindClass("java/lang/System");
    if (!system) return 1;
    auto property = env->GetStaticMethodID(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    if (!property) return 1;
    auto version = static_cast<jstring>(env->CallStaticObjectMethod(system, property, env->NewStringUTF("java.specification.version")));
    if (!version || env->ExceptionCheck()) return 1;
    const char* actual = env->GetStringUTFChars(version, nullptr);
    if (!actual) return 1;
    const char* normalized = strncmp(actual, "1.", 2) == 0 ? actual + 2 : actual;
    bool matches = strcmp(normalized, major) == 0;
    fprintf(stdout, "OpenJDK specification %s (requested %s)\n", actual, major);
    env->ReleaseStringUTFChars(version, actual);
    if (!matches) { fprintf(stderr, "Runtime version mismatch.\n"); return 2; }
    if (strcmp(entry_name, "--probe") == 0) return 0;
    // LM_JAR (mode 2) reads Main-Class from the selected JAR; LM_CLASS (mode 1) loads a main class.
    jclass helper = env->FindClass("sun/launcher/LauncherHelper");
    if (!helper) return 1;
    auto load = env->GetStaticMethodID(helper, "checkAndLoadMain", "(ZILjava/lang/String;)Ljava/lang/Class;");
    if (!load) return 1;
    auto entry = static_cast<jclass>(env->CallStaticObjectMethod(helper, load, JNI_TRUE, mode, env->NewStringUTF(entry_name)));
    if (!entry || env->ExceptionCheck()) return 1;
    auto main_method = env->GetStaticMethodID(entry, "main", "([Ljava/lang/String;)V");
    if (!main_method) return 1;
    auto args = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    if (!args) return 1;
    for (int i = 0; i < count; ++i) {
        auto value = env->NewStringUTF(values[i]);
        if (!value) return 1;
        env->SetObjectArrayElement(args, i, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return 1;
    }
    env->CallStaticVoidMethod(entry, main_method, args);
    return env->ExceptionCheck() ? 1 : 0;
}
}

int main(int argc, char** argv) {
    // Android can reclaim the owner service. Terminate the supervised child with it.
    const pid_t owner = getppid();
    pthread_t watchdog;
    if (owner == 1 || pthread_create(&watchdog, nullptr, watch_parent,
            reinterpret_cast<void*>(static_cast<intptr_t>(owner))) != 0) return 1;
    pthread_detach(watchdog);
    if (argc < 6) {
        fprintf(stderr, "usage: lumenjvm <libjvm.so> <java-home> <java-major> <heap-MiB> <server.jar|classpath|--probe> [mainClass args...]\n");
        return 2;
    }
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    char* end = nullptr;
    long heap = strtol(argv[4], &end, 10);
    size_t cpLen = strlen(argv[5]);
    if (!end || *end || heap < 32 || heap > 8192 || strlen(argv[2]) >= PATH_MAX || cpLen == 0 || cpLen >= 131072) return 2;

    const char* glibcLib = getenv("LUMEN_GLIBC_LIB");
    if (glibcLib && *glibcLib) {
        fprintf(stdout, "LumenCraft glibc compat directory: %s\n", glibcLib);
    }

    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { fprintf(stderr, "Cannot load HotSpot: %s\n", dlerror()); return 1; }
    auto create = reinterpret_cast<jint (*)(JavaVM**, void**, void*)>(dlsym(lib, "JNI_CreateJavaVM"));
    if (!create) { fprintf(stderr, "HotSpot has no invocation API: %s\n", dlerror()); return 1; }

    char home[PATH_MAX + 32], memory[40], temp[PATH_MAX + 32];
    snprintf(home, sizeof(home), "-Djava.home=%s", argv[2]);
    snprintf(memory, sizeof(memory), "-Xmx%ldm", heap);
    snprintf(temp, sizeof(temp), "-Djava.io.tmpdir=%s", getenv("TMPDIR") ? getenv("TMPDIR") : ".");
    char* classpath = static_cast<char*>(malloc(cpLen + 24));
    if (!classpath) return 1;
    snprintf(classpath, cpLen + 24, "-Djava.class.path=%s", argv[5]);

    const char* headlessEnv = getenv("LUMEN_HEADLESS");
    bool headless = !(headlessEnv && strcmp(headlessEnv, "0") == 0);

    char* extraStorage[kMaxExtraOpts] = {};
    size_t extraCount = load_extra_options(getenv("LUMEN_JVM_ARGS_FILE"), extraStorage, kMaxExtraOpts);

    JavaVMOption options[8 + kMaxExtraOpts];
    size_t optCount = 0;
    options[optCount++] = {home, nullptr};
    options[optCount++] = {classpath, nullptr};
    options[optCount++] = {memory, nullptr};
    options[optCount++] = {temp, nullptr};
    if (headless) options[optCount++] = {const_cast<char*>("-Djava.awt.headless=true"), nullptr};
    options[optCount++] = {const_cast<char*>("-XX:-UsePerfData"), nullptr};
    for (size_t i = 0; i < extraCount; ++i) {
        options[optCount++] = {extraStorage[i], nullptr};
    }

    JavaVMInitArgs config = {JNI_VERSION_1_6, static_cast<jint>(optCount), options, JNI_FALSE};
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    int rc = create(&vm, reinterpret_cast<void**>(&env), &config);
    free(classpath);
    for (size_t i = 0; i < extraCount; ++i) free(extraStorage[i]);
    if (rc != JNI_OK) { fprintf(stderr, "JNI_CreateJavaVM returned %d\n", rc); return 1; }
    char no_gui[] = "nogui";
    char* server_args[] = {no_gui};
    rc = argc > 6 ? launch(env, argv[3], argv[6], 1, argc - 7, argv + 7)
                  : launch(env, argv[3], argv[5], 2, 1, server_args);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    vm->DestroyJavaVM();
    return rc;
}
