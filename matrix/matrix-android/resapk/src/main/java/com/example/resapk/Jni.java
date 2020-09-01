package com.example.resapk;

public class Jni {
    static {
        System.loadLibrary("threadid");
    }

    public static native int[] getIds();

}
