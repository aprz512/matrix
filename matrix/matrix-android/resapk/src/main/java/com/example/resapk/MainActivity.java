package com.example.resapk;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    String type = "layout";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        class holder {
            String rest = "activity_name";
        }

        int identifier = getResources().getIdentifier(new holder().rest, type, Pkg.PKG);
        setContentView(identifier);
    }
}
