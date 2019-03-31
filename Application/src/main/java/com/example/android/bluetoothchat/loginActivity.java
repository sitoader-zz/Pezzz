package com.example.android.bluetoothchat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.example.android.common.activities.SampleActivityBase;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;




public class loginActivity extends SampleActivityBase {

        @Override
        protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                setContentView(R.layout.login);

        }

        public void launchSecondActivity(View view) {
                Intent intent = new Intent(this, MainActivity.class);

                startActivity(intent);
        }
}


