#include <jni.h>

extern jbyte blob[];
extern int blob_size;

/*
 * Returns a direct ByteBuffer wrapping the embedded bootstrap zip in place.
 * The blob lives in the .so's mmap'd .rodata, so this is zero-copy and does
 * not allocate on the Java heap — loading it as a byte[] would need ~518 MB
 * of heap, which exceeds dalvik.vm.heapgrowthlimit on real devices.
 */
JNIEXPORT jobject JNICALL Java_com_termux_app_TermuxInstaller_getZip(JNIEnv *env, __attribute__((__unused__)) jobject This)
{
    return (*env)->NewDirectByteBuffer(env, blob, (jlong) blob_size);
}
