package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.IntentFilter;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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
import org.irmacard.cardemu.selfenrol.mno.SubscriberInfo;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.jmrtd.PassportService;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Passport extends Activity {
    private static String MNO_SERVER_IP = "10.0.1.32";//"192.168.0.10";
    private static final int SERVER_PORT = 6789;
    NetworkMNOTask networkMNOTask;
    //NetworkGovTask networkGovTask;
    public SubscriberInfo subscriberInfo=null;
    public boolean serverReady = true;

    private NfcAdapter nfcA;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private String[][] mTechLists;

    private SubscriberDatabase subscribers = new MockupSubscriberDatabase ();
    private MNOEnrol mno = new MNOEnrollImpl(subscribers);

    private PersonalRecordDatabase personalRecordDatabase = new MockupPersonalRecordDatabase ();
    private GovernmentEnrol governmentEnrol = new GovernmentEnrolImpl (personalRecordDatabase);

    private String TAG = "cardemu.Passport";

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

    private final String CARD_STORAGE = "card";
    private final String SETTINGS = "cardemu";


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
            loadCard();
            Log.d(TAG,"loaded card");
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
        if (screen == SCREEN_START) {
            ((TextView) findViewById(R.id.IMSI)).setText("IMSI: " + imsi);
            ((EditText) findViewById(R.id.se_mno_ip_field)).setText(MNO_SERVER_IP);
        }
    }

    //TODO: move all card functionality to a specific class, so we don't need this ugly code duplication and can do explicit card state checks there.
    protected void logCard(){
        Log.d(TAG, "Current card contents");
        // Retrieve list of credentials from the card
        IdemixCredentials ic = new IdemixCredentials(is);
        List<CredentialDescription> credentialDescriptions = new ArrayList<CredentialDescription>();
        // HashMap<CredentialDescription,Attributes> credentialAttributes = new HashMap<CredentialDescription,Attributes>();
        try {
            ic.connect();
            is.sendCardPin("000000".getBytes());
            credentialDescriptions = ic.getCredentials();
            for(CredentialDescription cd : credentialDescriptions) {
                Log.d(TAG,cd.getName());
            }
        } catch (CardServiceException e) {
            e.printStackTrace();
        } catch (InfoException e) {
            e.printStackTrace();
        } catch (CredentialsException e) {
            e.printStackTrace();
        }
    }

    private void storeCard() {
        Log.d(TAG,"Storing card");
        SharedPreferences settings = getSharedPreferences(SETTINGS, 0);
        SharedPreferences.Editor editor = settings.edit();
        Gson gson = new Gson();
        editor.putString(CARD_STORAGE, gson.toJson(card));
        editor.commit();
    }

    private void loadCard() {
        SharedPreferences settings = getSharedPreferences(SETTINGS, 0);
        String card_json = settings.getString(CARD_STORAGE, "");

        Gson gson = new Gson();
        card = gson.fromJson(card_json, IRMACard.class);
        is = new IdemixService(new SmartCardEmulatorService(card));
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
            networkMNOTask.setState(networkMNOTask.PASSPORT_FOUND);
            mno.enroll(imsi, subscriberInfo, "0000".getBytes(), passportService, is);
            //TODO: make verification result explicit
            passportVerified();
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    private boolean connectToServer(){
        String ip = ((EditText) findViewById(R.id.se_mno_ip_field)).getText().toString();
        Log.d(TAG, "retrieved ip: " + ip);
        //check IP
        Pattern IP_ADDRESS
                = Pattern.compile(
                "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
                        + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
                        + "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
                        + "|[1-9][0-9]|[0-9]))");
        Matcher matcher = IP_ADDRESS.matcher(ip);
        if (matcher.matches()){
            MNO_SERVER_IP = ip;
        }
        networkMNOTask = new NetworkMNOTask();
        networkMNOTask.execute();
        return subscriberInfo != null;
    }

    private void networkError(String msg){
        Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
        finish();
    }

    private void advanceScreen(){
        switch (screen) {
            case SCREEN_START:
                final Button button = (Button) findViewById(R.id.se_button_continue);
                button.setEnabled(false);
                connectToServer();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (subscriberInfo ==null) {
                    networkError("Could not connect to server");
                    finish();
                }else {
                    setContentView(R.layout.enroll_activity_passport);
                    screen = SCREEN_PASSPORT;
                }
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
        enableContinueButton();
    }

 //   public void onMainTouch(View v) {
//
//    }

    private void passportVerified(){
        networkMNOTask.setState(networkMNOTask.PASSPORT_VERIFIED);
        ((TextView)findViewById(R.id.se_feedback_text)).setText(R.string.se_passport_verified);
        ImageView statusImage = (ImageView) findViewById(R.id.se_statusimage);
        if (statusImage != null)
            statusImage.setVisibility(View.GONE);
        storeCard();
        enableContinueButton();
    }

    @Override
    public void finish() {
        // Prepare data intent
        is.close();
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        storeCard();
        setResult(RESULT_OK, data);
        super.finish();
    }

    private class NetworkMNOTask extends AsyncTask<Void, byte[], Boolean> {
        private static final int CONNECTING = 0;
        private static final int PASSPORT_FOUND = 1;
        private static final int PASSPORT_VERIFIED = 2;
        private int nState = CONNECTING;

        Socket clientSocket;
   //     Socket nsocket; //Network Socket
    //    InputStream nis; //Network Input Stream
    //    OutputStream nos; //Network Output Stream
        DataOutputStream outToServer;
        BufferedReader inFromServer;

        String TAG = "Passport-NetworkTask";

        private SimpleDateFormat iso = new SimpleDateFormat ("yyyyMMdd");

        public boolean setState (int state){
            if (nState == -1){
                synchronized (this){nState = state;}
                return true;
            } else
                return false;
        }

        @Override
        protected Boolean doInBackground(Void... params) { //This runs on a different thread
            boolean result = false;


            try {
                Log.i(TAG, "deInBackground: Creating socket");
                SocketAddress sockaddr = new InetSocketAddress(MNO_SERVER_IP, SERVER_PORT);
                InetAddress serverAddr = InetAddress.getByName(MNO_SERVER_IP);
                clientSocket = new Socket(serverAddr, SERVER_PORT);
                outToServer = new DataOutputStream(clientSocket.getOutputStream());
                inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                String input;
                sendMessage("IMSI: " + imsi + '\n');
                while (true) {
                    while ((input = inFromServer.readLine()) != null) {
                        handleServerMessage(input);
                        break;
                    }
                    if (nState != -1){
                        synchronized (this){
                            switch (nState){
                                case PASSPORT_FOUND:
                                    sendMessage("PASP: found");
                                    break;
                                case PASSPORT_VERIFIED:
                                    sendMessage("PASP: verified");
                                    break;
                            }
                            nState = -1;
                        }
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
                Log.i(TAG, "doInBackground: IOException");
                result = true;
            } catch (Exception e) {
                e.printStackTrace();
                Log.i(TAG, "doInBackground: Exception");
                result = true;
            }
            return result;
        }

        private void handleServerMessage(String input) {
            Log.d(TAG,"FROM SERVER: " + input);
          //  String serverResponse = new String(input);
            if (input.startsWith("SI: ")){
                retrieveSubscriberInfo(input.substring(4));
            }

        }

        private void sendMessage(String msg){
            try {
                outToServer.writeBytes(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void retrieveSubscriberInfo(String si) {
            if (!si.equals("error")) {
                String[] response = si.split(", ");
                Log.d(TAG, (response[0] + " <=> " + response[1]) + "<=>" + response[2]);
                try {
                    subscriberInfo = new SubscriberInfo(iso.parse(response[0]), iso.parse(response[1]), response[2]);
                    Log.d(TAG, subscriberInfo.toString());
                } catch (ParseException e) {
                    e.printStackTrace();
                }
                serverReady = true;
            }
            //TODO else
        }

        @Override
        protected void onProgressUpdate(byte[]... values) {
            if (values.length > 0) {
                Log.i(TAG, "onProgressUpdate: " + values[0].length + " bytes received.");
            }
        }
        @Override
        protected void onCancelled() {
            Log.i(TAG, "Cancelled.");
            super.onCancelled();
        }
        @Override
        protected void onPostExecute(Boolean result) {
            try {
                inFromServer.close();
                outToServer.close();
                clientSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (result) {
                Log.i(TAG, "onPostExecute: Completed with an Error.");
            } else {
                Log.i(TAG, "onPostExecute: Completed.");
            }
        }
    }
}
