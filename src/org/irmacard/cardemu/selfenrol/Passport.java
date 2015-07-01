package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.app.PendingIntent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.KeyListener;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.IsoDepCardService;

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
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Passport extends Activity {
    private static final int MNO_SERVER_PORT = 6789;
    private static final int GOV_SERVER_PORT = 6788;
    // NetworkMNOTask networkMNOTask;
    //NetworkGovTask networkGovTask;
    public SubscriberInfo subscriberInfo=null;
    public boolean serverReady = true;

    Socket clientSocket;
    DataOutputStream outToServer;
    BufferedReader inFromServer;

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

    private Handler networkHandler;

    private final String CARD_STORAGE = "card";
    private final String SETTINGS = "cardemu";

    private AlertDialog urldialog = null;
    private String enrollServerUrl;
    private SharedPreferences settings;


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

        // Attempt to get the enroll server URL from the settings. If none is there,
        // use the default value (from res/values/strings.xml)
        settings = getSharedPreferences(SETTINGS, 0);
        enrollServerUrl = settings.getString("enroll_server_url", "");
        if (enrollServerUrl.length() == 0)
            enrollServerUrl = getString(R.string.enroll_default_url);

        if(getIntent() != null) {
            onNewIntent(getIntent());
        }

        //  super.onCreate(savedInstanceState);
        HandlerThread dbThread = new HandlerThread("network");
        dbThread.start();
        networkHandler = new Handler(dbThread.getLooper());

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
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        closeConnection();
        finish();
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
        SharedPreferences.Editor editor = settings.edit();
        Gson gson = new Gson();
        editor.putString(CARD_STORAGE, gson.toJson(card));
        editor.commit();
    }

    private void loadCard() {
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
            cs.open();
            PassportService passportService = new PassportService(cs);
            sendMessage("PASP: found\n");
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


    /**
     * Check if an IP address is valid.
     *
     * @param url The IP to check
     * @return True if valid, false otherwise.
     */
    private static Boolean isValidIPAddress(String url) {
        Pattern IP_ADDRESS = Pattern.compile(
                "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
                        + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
                        + "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
                        + "|[1-9][0-9]|[0-9]))");
        return IP_ADDRESS.matcher(url).matches();
    }

    /**
     * Check if a given domain name is valid. We consider it valid if it consists of
     * alphanumeric characters and dots, and if the first character is not a dot.
     *
     * @param url The domain to check
     * @return True if valid, false otherwise
     */
    private static Boolean isValidDomainName(String url) {
        Pattern VALID_DOMAIN = Pattern.compile("([\\w]+[\\.\\w]*)");
        return VALID_DOMAIN.matcher(url).matches();
    }


    /**
     * Check if a given URL is valid. We consider it valid if it is either a valid
     * IP address or a valid domain name, which is checked using
     * using {@link #isValidDomainName(String)} Boolean} and
     * {@link #isValidIPAddress(String) Boolean}.
     *
     * @param url The URL to check
     * @return True if valid, false otherwise
     */
    private static Boolean isValidURL(String url) {
        String[] parts = url.split("\\.");

        // If the part of the url after the rightmost dot consists
        // only of numbers, it must be an IP address
        if (Pattern.matches("[\\d]+", parts[parts.length-1]))
            return isValidIPAddress(url);
        else
            return isValidDomainName(url);
    }

    private void connectToMNOServer(){
        // The address in enrollServerUrl should already have been checked on valididy
        // by the urldialog, so there is no need to check it here
        Log.d(TAG, "Connecting to: " + enrollServerUrl);
        openConnection(enrollServerUrl, MNO_SERVER_PORT,1000);
    }

    private void openConnection(final String ip, final int port, long timeout){
        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "deInBackground: Creating socket");
                    // SocketAddress sockaddr = new InetSocketAddress(ip, port);
                    InetAddress serverAddr = InetAddress.getByName(ip);
                    clientSocket = new Socket(serverAddr, port);
                    outToServer = new DataOutputStream(clientSocket.getOutputStream());
                    inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.i(TAG, "doInBackground: Exception");
                }
            }
        });
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (inFromServer == null){
            networkError("Could not connect to server");
            finish();
        }
    }

    private void sendMessage (String msg){
        final String message = new String (msg);
        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    outToServer.writeBytes(message+"\n");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }


    private String response;
    private boolean resp = false;
    private void sendAndListen (String msg, long timeout){
        final String message = new String (msg);
        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String input;
                    outToServer.writeBytes(message+"\n");
                    while ((input = inFromServer.readLine()) != null) {
                        response = new String(input);
                        resp = true;
                        break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (resp){
            handleResponse (response);
            resp = false;
        } else {
            networkError("Could not connect to server");
            finish();
        }
    }

    private void handleResponse(String r) {
        if (r.startsWith("SI: ")) {
            SimpleDateFormat iso = new SimpleDateFormat("yyyyMMdd");
            String[] res = r.substring(4).split(", ");
            try {
                subscriberInfo = new SubscriberInfo(iso.parse(res[0]), iso.parse(res[1]), res[2]);
            } catch (ParseException e) {
                e.printStackTrace();
            }
        } else {
            //TODO logic
        }

    }

    private void closeConnection (){
        networkHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    inFromServer.close();
                    outToServer.close();
                    clientSocket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
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
                connectToMNOServer();
                sendAndListen("IMSI: " + imsi,1000);

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
        openConnection(enrollServerUrl, GOV_SERVER_PORT,1000);
        sendMessage("VERI: MNO credential");
        governmentEnrol.enroll("0000".getBytes(), is);
        //sendAndListen("DOCN: 123456",1000);
        enableContinueButton();
    }

    //   public void onMainTouch(View v) {
//
//    }

    private void passportVerified(){
        sendMessage("PASP: verified");
        ((TextView)findViewById(R.id.se_feedback_text)).setText(R.string.se_passport_verified);
        ImageView statusImage = (ImageView) findViewById(R.id.se_statusimage);
        if (statusImage != null)
            statusImage.setVisibility(View.GONE);
        storeCard();
        sendMessage("ISSU: succesfull");
        closeConnection();
        enableContinueButton();
    }

    @Override
    public void finish() {
        // Prepare data intent
        if (is != null) {
            is.close();
        }
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        storeCard();
        setResult(RESULT_OK, data);
        super.finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.enroll_activity_start, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "enroll menu press registered");

        // Handle item selection
        switch (item.getItemId()) {
        case R.id.set_enroll_url:
            Log.d(TAG, "set_enroll_url pressed");

            // Create the dialog only once
            if (urldialog == null)
                urldialog = getUrlDialog();

            // Show the dialog
            urldialog.show();
            // Pop up the keyboard
            urldialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }


    // Helper function to build the URL dialog and set the listeners.
    private AlertDialog getUrlDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // Simple view containing the actual input field
        View v = this.getLayoutInflater().inflate(R.layout.enroll_url_dialog, null);

        // Set the URL field to the appropriate value
        final EditText urlfield = (EditText)v.findViewById(R.id.enroll_url_field);
        urlfield.setText(enrollServerUrl);

        // Build the dialog
        builder.setTitle(R.string.enroll_url_dialog_title)
                .setView(v)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        enrollServerUrl = urlfield.getText().toString();
                        settings.edit().putString("enroll_server_url", enrollServerUrl).apply();
                        Log.d("Passport", enrollServerUrl);
                    }
                }).setNeutralButton(R.string.default_string, null)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Reset the URL field to the last known valid value
                        urlfield.setText(enrollServerUrl);
                    }
                });

        final AlertDialog urldialog = builder.create();


        urldialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                // By overriding the neutral button's onClick event in this onShow listener,
                // we prevent the dialog from closing when the default button is pressed.
                Button defaultbutton = urldialog.getButton(DialogInterface.BUTTON_NEUTRAL);
                defaultbutton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        urlfield.setText(R.string.enroll_default_url);
                        // Move cursor to end of field
                        urlfield.setSelection(urlfield.getText().length());
                    }
                });

                // Move cursor to end of field
                urlfield.setSelection(urlfield.getText().length());
            }
        });

        // If the text from the input field changes to something that we do not consider valid
        // (i.e., it is not a valid IP or domain name), we disable the OK button
        urlfield.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {  }
            @Override
            public void afterTextChanged(Editable s) {
                Button okbutton = urldialog.getButton(DialogInterface.BUTTON_POSITIVE);
                okbutton.setEnabled(isValidURL(s.toString()));
            }
        });

        return urldialog;
    }
}
