package com.adsaff.mywebrtc;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ImageView imageView = findViewById(R.id.connect_button);
        EditText editText = findViewById(R.id.room_edittext);
        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(editText.getText().toString().isEmpty()){
                   Toast.makeText(getApplicationContext(),"Please enter a room Id",Toast.LENGTH_LONG).show();
                }else {
                    startActivity(new Intent(getApplicationContext(),WebSocketActivityKubma.class).putExtra("room_id",editText.getText().toString()));
                }
            }
        });
    }
}
