package com.example.resapk;

public class StaticCall {

    static {
        System.loadLibrary("StaticMethodCall");
    }

//    private native void nativeMethod2();

    private static void callback() {
        System.out.println("In Java");
    }

    public static void main(String[] args) {
        StaticCall c = new StaticCall();
//        c.nativeMethod();
    }
}
