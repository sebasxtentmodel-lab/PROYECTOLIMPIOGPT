package com.wasimaster.wmkeyboard.core.voice.whisper;

/** WM Keyboard's MIT licensed whisper.cpp JNI ABI; implements multilingual CPU ASR. */
public final class WhisperCpp {
    static { System.loadLibrary("wmwhispercpp"); }
    private WhisperCpp() {}
    public static native long nativeOpen(String modelFile);
    public static native void nativeClose(long handle);
    public static native byte[] nativeTranscribe(long handle,float[] samples,String[] languages,
        boolean translate,String prompt,int beamSize,int threads);
    public static native byte[] nativePartial(long handle);
    public static native void nativeAbort(long handle);
}
