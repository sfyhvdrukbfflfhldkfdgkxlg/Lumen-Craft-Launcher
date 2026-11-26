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
void* watch_parent(void* arg) {
    const pid_t parent = static_cast<pid_t>(reinterpret_cast<intptr_t>(arg));
    while (getppid() == parent) sleep(2);
    kill(getpid(),SIGTERM);
    return nullptr;
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
    // LM_JAR reads Main-Class from the selected JAR and honors its manifest class path.
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
        env->SetObjectArrayElement(args,i,value);
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
    if (owner == 1 || pthread_create(&watchdog,nullptr,watch_parent,
            reinterpret_cast<void*>(static_cast<intptr_t>(owner))) != 0) return 1;
    pthread_detach(watchdog);
    if (argc < 6) { fprintf(stderr, "usage: lumenjvm <libjvm.so> <java-home> <java-major> <heap-MiB> <server.jar|--probe>\n"); return 2; }
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    char* end = nullptr;
    long heap = strtol(argv[4], &end, 10);
    if (!end || *end || heap < 32 || heap > 8192 || strlen(argv[2]) >= PATH_MAX || strlen(argv[5]) >= PATH_MAX) return 2;
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { fprintf(stderr, "Cannot load HotSpot: %s\n", dlerror()); return 1; }
    auto create = reinterpret_cast<jint (*)(JavaVM**, void**, void*)>(dlsym(lib, "JNI_CreateJavaVM"));
    if (!create) { fprintf(stderr, "HotSpot has no invocation API: %s\n", dlerror()); return 1; }
    char home[PATH_MAX + 32], classpath[PATH_MAX + 32], memory[40], temp[PATH_MAX + 32];
    snprintf(home, sizeof(home), "-Djava.home=%s", argv[2]);
    snprintf(classpath, sizeof(classpath), "-Djava.class.path=%s", argv[5]);
    snprintf(memory, sizeof(memory), "-Xmx%ldm", heap);
    snprintf(temp, sizeof(temp), "-Djava.io.tmpdir=%s", getenv("TMPDIR") ? getenv("TMPDIR") : ".");
    JavaVMOption options[] = { {home,nullptr}, {classpath,nullptr}, {memory,nullptr}, {temp,nullptr},
        {const_cast<char*>("-Djava.awt.headless=true"),nullptr}, {const_cast<char*>("-XX:-UsePerfData"),nullptr} };
    JavaVMInitArgs config = { JNI_VERSION_1_6, static_cast<jint>(sizeof(options)/sizeof(options[0])), options, JNI_FALSE };
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    int rc = create(&vm, reinterpret_cast<void**>(&env), &config);
    if (rc != JNI_OK) { fprintf(stderr, "JNI_CreateJavaVM returned %d\n", rc); return 1; }
    char no_gui[] = "nogui";
    char* server_args[] = {no_gui};
    rc = argc > 6 ? launch(env,argv[3],argv[6],1,argc-7,argv+7)
                  : launch(env,argv[3],argv[5],2,1,server_args);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    vm->DestroyJavaVM();
    return rc;
}

