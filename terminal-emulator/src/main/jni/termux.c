#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))

static int throw_runtime_exception(JNIEnv* env, char const* message) {
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cellWidth,
        jint cellHeight) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt/unlockpt/ptsname_r");
    }

    // Set initial window size on master before forking
    struct winsize sz = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns,
                          .ws_xpixel = (unsigned short)cellWidth, .ws_ypixel = (unsigned short)cellHeight };
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        // Parent
        *pProcessId = (int)pid;
        return ptm;
    }

    // Child process
    close(ptm);
    setsid();

    int pts = open(devname, O_RDWR);
    if (pts < 0) _exit(1);

    dup2(pts, 0);
    dup2(pts, 1);
    dup2(pts, 2);
    if (pts > 2) close(pts);

    // Close all other file descriptors
    DIR* self_dir = opendir("/proc/self/fd");
    if (self_dir != NULL) {
        int self_dir_fd = dirfd(self_dir);
        struct dirent* entry;
        while ((entry = readdir(self_dir)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd > 2 && fd != self_dir_fd) close(fd);
        }
        closedir(self_dir);
    }

    if (envp) {
        for (; *envp; envp++) putenv(*envp);
    }

    if (chdir(cwd) != 0) {
        char* home = getenv("HOME");
        if (home) chdir(home);
    }

    execvp(cmd, argv);
    _exit(1);
    return -1;
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns,
        jint cellWidth,
        jint cellHeight) {

    const char* cmd_cstr = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char* cwd_cstr = (*env)->GetStringUTFChars(env, cwd, NULL);

    // Build argv
    int argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = (char**)malloc((argc + 1) * sizeof(char*));
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        argv[i] = (char*)(*env)->GetStringUTFChars(env, arg, NULL);
    }
    argv[argc] = NULL;

    // Build envp
    int envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = (char**)malloc((envc + 1) * sizeof(char*));
    for (int i = 0; i < envc; i++) {
        jstring var = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
        envp[i] = (char*)(*env)->GetStringUTFChars(env, var, NULL);
    }
    envp[envc] = NULL;

    int processId = 0;
    int ptm = create_subprocess(env, cmd_cstr, cwd_cstr, argv, envp, &processId, rows, columns, cellWidth, cellHeight);

    // Store process ID
    if (processIdArray) {
        jint pid = processId;
        (*env)->SetIntArrayRegion(env, processIdArray, 0, 1, &pid);
    }

    // Release strings (only in parent - child already exec'd or exited)
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_cstr);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_cstr);
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        (*env)->ReleaseStringUTFChars(env, arg, argv[i]);
    }
    free(argv);
    for (int i = 0; i < envc; i++) {
        jstring var = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
        (*env)->ReleaseStringUTFChars(env, var, envp[i]);
    }
    free(envp);

    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
        JNIEnv* TERMUX_UNUSED(env),
        jclass TERMUX_UNUSED(clazz),
        jint fd,
        jint rows,
        jint cols,
        jint cellWidth,
        jint cellHeight) {
    struct winsize sz = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols,
                          .ws_xpixel = (unsigned short)cellWidth, .ws_ypixel = (unsigned short)cellHeight };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(
        JNIEnv* TERMUX_UNUSED(env),
        jclass TERMUX_UNUSED(clazz),
        jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(
        JNIEnv* TERMUX_UNUSED(env),
        jclass TERMUX_UNUSED(clazz),
        jint fd) {
    close(fd);
}
