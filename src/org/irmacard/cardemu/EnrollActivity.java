package org.irmacard.cardemu;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.gson.Gson;

import org.irmacard.android.util.pindialog.EnterPINDialogFragment;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.idemix.IdemixService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Created by fabianbr on 30-3-15.
 */
public class EnrollActivity extends Activity implements EnterPINDialogFragment.PINDialogListener {
    private String TAG = "CardEmuEnrollActivity";

    // PIN handling
    private int tries = -1;

    // State variables
    private IRMACard card = null;
    private IdemixService is = null;

    private int screen;
    private static final int SCREEN_START = 1;
    private static final int SCREEN_PASSPORT = 2;
    private static final int SCREEN_ISSUE = 3;



    /** Called when the activity is first created. */
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.enroll_activity_start);
        screen = SCREEN_START;
        enableContinueButton();

    }

    private void enableContinueButton(){
        final Button button = (Button) findViewById(R.id.se_button_continue);
        button.setVisibility(View.VISIBLE);
        button.setEnabled(true);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(TAG,"continue button pressed");
                advanceScreen();
            }
        });
    }

    private void advanceScreen(){
        switch (screen) {
            case SCREEN_START:
                setContentView(R.layout.enroll_activity_passport);
                screen = SCREEN_PASSPORT;
                break;
            case SCREEN_PASSPORT:
                setContentView(R.layout.enroll_activity_issue);
                screen = SCREEN_ISSUE;
                break;
            case SCREEN_ISSUE:
                screen = SCREEN_START;
                finish();
                break;
            default:
                Log.e(TAG, "Error, screen switch fall through");
                break;
        }
    }



    private void passportVerified(){
        ((TextView)findViewById(R.id.se_feedback_text)).setText(R.string.se_passport_verified);
        findViewById(R.id.se_statusimage).setVisibility(View.GONE);
        enableContinueButton();
    }

    public void onMainTouch(View v) {
        if (screen == SCREEN_PASSPORT){
            passportVerified();
        }
        if (screen == SCREEN_ISSUE){
            enableContinueButton();
        }
    }

    @Override
    protected void onResume (){
        super.onResume();
        Intent intent = getIntent();
        Log.i(TAG, "Action: " + intent.getAction());
        if (intent.hasExtra("card_json")) {
            String card_json = intent.getExtras().getString("cardservice");
            Gson gson = new Gson();
            card = gson.fromJson(card_json, IRMACard.class);
            is = new IdemixService(new SmartCardEmulatorService(card));
        }
    }

    @Override
    public void onPINEntry(String s) {

    }

    @Override
    public void onPINCancel() {

    }

    @Override
    public void finish() {
        // Prepare data intent
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        Gson gson = new Gson();
        String card_json = gson.toJson(card);
        data.putExtra("card_json", card_json);
        // Activity finished ok, return the data
        setResult(RESULT_OK, data);
        super.finish();
    }


}
