package com.example.resapk;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

public class MainActivity extends AppCompatActivity {

    String type = "layout";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        class holder {
            String rest = "activity_main";
        }

        int identifier = getResources().getIdentifier(new holder().rest, type, Pkg.PKG);
        setContentView(R.layout.activity_main);

        int[] arr = new int[]{
                R.layout.activity_main, R.string.app_name
        };

        findViewById(R.id.tx).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int x = 10;
            }
        });
    }
}
