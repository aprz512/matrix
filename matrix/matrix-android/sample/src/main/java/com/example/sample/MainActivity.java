package com.example.sample;

import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;


public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

/*        Matrix.Builder builder = new Matrix.Builder(this.getApplication());
        builder.patchListener(new PluginListener() {
            @Override
            public void onInit(Plugin plugin) {

            }

            @Override
            public void onStart(Plugin plugin) {

            }

            @Override
            public void onStop(Plugin plugin) {

            }

            @Override
            public void onDestroy(Plugin plugin) {

            }

            @Override
            public void onReportIssue(Issue issue) {

            }
        });


        //trace
        TraceConfig traceConfig = new TraceConfig.Builder()
//                .dynamicConfig(dynamicConfig)
                .enableFPS(true)
                .enableEvilMethodTrace(true)
                .enableAnrTrace(true)
//                .enableStartup(true)
//                .splashActivities("sample.tencent.matrix.SplashActivity;")
                .isDebug(true)
                .isDevEnv(true)
                .build();

        TracePlugin tracePlugin = (new TracePlugin(traceConfig));
        builder.plugin(tracePlugin);

        Matrix.init(builder.build());
        tracePlugin.start();*/

        Button test = findViewById(R.id.test);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                f();
            }
        });


    }

    void f() {
        E();
        A();
        A();
        A();
        A();
//        B();
//        C();
        D();
        F();
    }

    int F() {
        int i = 0;
        i++;
        i++;
        i++;
        i++;
        i++;
        i++;
        i++;
        i++;
        return i;
    }

    void A() {
        SystemClock.sleep(100);
    }

    void B() {
        SystemClock.sleep(200);
    }

    void C() {
        SystemClock.sleep(300);
    }

    private void D() {
        SystemClock.sleep(1);
    }

    private void E() {
        SystemClock.sleep(5);
    }


}
