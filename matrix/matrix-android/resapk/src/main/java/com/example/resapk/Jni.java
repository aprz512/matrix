package com.example.resapk;

public class Jni {
    static {
        System.loadLibrary("threadid");
    }

    public static native int[] getIds();
    public static native int[] getId3s();

}
