package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.IntentFilter;
import android.content.Context;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.gson.Gson;

import net.sourceforge.scuba.smartcards.CardService;
import net.sourceforge.scuba.smartcards.CardServiceException;
import net.sourceforge.scuba.smartcards.IsoDepCardService;

import org.irmacard.cardemu.R;
import org.irmacard.cardemu.selfenrol.government.GovernmentEnrol;
import org.irmacard.cardemu.selfenrol.government.GovernmentEnrolImpl;
import org.irmacard.cardemu.selfenrol.government.MockupPersonalRecordDatabase;
import org.irmacard.cardemu.selfenrol.government.PersonalRecordDatabase;
import org.irmacard.cardemu.selfenrol.mno.MNOEnrol;
import org.irmacard.cardemu.selfenrol.mno.MNOEnrollImpl;
import org.irmacard.cardemu.selfenrol.mno.MockupSubscriberDatabase;
import org.irmacard.cardemu.selfenrol.mno.SubscriberDatabase;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.idemix.IdemixService;
import org.jmrtd.PassportService;

public class Passport extends Activity {
    private NfcAdapter nfcA;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private String[][] mTechLists;

    private SubscriberDatabase subscribers = new MockupSubscriberDatabase ();
    private MNOEnrol mno = new MNOEnrollImpl(subscribers);

    private PersonalRecordDatabase personalRecordDatabase = new MockupPersonalRecordDatabase ();
    private GovernmentEnrol governmentEnrol = new GovernmentEnrolImpl (personalRecordDatabase);

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
    private String imsi;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // NFC stuff
        nfcA = NfcAdapter.getDefaultAdapter(getApplicationContext());
        mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        // Setup an intent filter for all TECH based dispatches
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        mFilters = new IntentFilter[] { tech };

        // Setup a tech list for all IsoDep cards
        mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };

        if(getIntent() != null) {
            onNewIntent(getIntent());
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.enroll_activity_start);
        screen = SCREEN_START;
        enableContinueButton();
    }

    private void enableForegroundDispatch() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        Intent intent = new Intent(getApplicationContext(), this.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        String[][] filter = new String[][] { new String[] { "android.nfc.tech.IsoDep" } };
        adapter.enableForegroundDispatch(this, pendingIntent, null, filter);
    }

    public void onNewIntent(Intent intent) {
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(getIntent().getAction())) {
            processIntent(getIntent());
        }
        if (nfcA != null) {
            nfcA.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }

        Intent intent = getIntent();
        Log.i(TAG, "Action: " + intent.getAction());
        if (intent.hasExtra("card_json")) {
            String card_json = intent.getExtras().getString("card_json");
            Gson gson = new Gson();
            card = gson.fromJson(card_json, IRMACard.class);
            is = new IdemixService(new SmartCardEmulatorService(card));
            if (is!=null){
                Log.d(TAG,"good");
            }
            try {
                is.open ();
            } catch (CardServiceException e) {
                e.printStackTrace();
            }
        }

        Context context = getApplicationContext ();
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        imsi = telephonyManager.getSubscriberId();

        if (imsi == null)
            imsi = "FAKE_IMSI_" +  Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (screen == SCREEN_START)
            ((TextView)findViewById(R.id.IMSI)).setText("IMSI: "+ imsi);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (nfcA != null) {
            nfcA.disableForegroundDispatch(this);
        }
    }

    public void processIntent (Intent intent) {
        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        assert (tagFromIntent != null);

        IsoDep tag = IsoDep.get(tagFromIntent);
        CardService cs = new IsoDepCardService(tag);

        try {
            cs.open ();
            PassportService passportService = new PassportService(cs);
            mno.enroll(imsi, "0000".getBytes(), passportService, is);
        } catch (Exception e) {
            e.printStackTrace();
        }
        passportVerified();
}

    private void enableContinueButton(){
        final Button button = (Button) findViewById(R.id.se_button_continue);
        button.setVisibility(View.VISIBLE);
        button.setEnabled(true);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(TAG, "continue button pressed");
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
                issueGovCredentials();
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

    private void issueGovCredentials() {
        governmentEnrol.enroll ("0000".getBytes (), is);
    }

    public void onMainTouch(View v) {
        if (screen == SCREEN_PASSPORT){
            passportVerified();
        }
        if (screen == SCREEN_ISSUE){
            enableContinueButton();
        }
    }

    private void passportVerified(){
        ((TextView)findViewById(R.id.se_feedback_text)).setText(R.string.se_passport_verified);
        ImageView statusImage = (ImageView) findViewById(R.id.se_statusimage);
        if (statusImage != null)
            statusImage.setVisibility(View.GONE);
        enableContinueButton();
    }

    @Override
    public void finish() {
        // Prepare data intent
        is.close();
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
