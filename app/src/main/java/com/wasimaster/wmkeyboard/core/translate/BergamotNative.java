package com.wasimaster.wmkeyboard.core.translate;

/**
 * JNI ABI from the MIT-licensed WM Keyboard Bergamot bridge.
 * Implementation: Mozilla Firefox Translations/Marian, see NOTICE.
 * Must keep upstream package/class name: precompiled JNI symbols target this class.
 */
public final class BergamotNative {
    static { System.loadLibrary("wmbergamot"); }
    private BergamotNative() {}
    public static native long nativeNewService(int cacheSize);
    public static native void nativeFreeService(long handle);
    public static native long nativeLoadModel(String model,String lex,String[] vocab,boolean int8shiftAll);
    public static native void nativeFreeModel(long handle);
    public static native byte[][] nativeTranslate(long service,long model,byte[][] inputs);
    public static native byte[][] nativePivot(long service,long first,long second,byte[][] inputs);
}
