package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.app.PendingIntent;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.*;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.*;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.gson.Gson;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.loopj.android.http.*;
import net.sf.scuba.smartcards.*;

import org.apache.http.Header;
import org.apache.http.entity.ByteArrayEntity;
import org.irmacard.cardemu.ByteArrayToBase64TypeAdapter;
import org.irmacard.cardemu.ProtocolCommandDeserializer;
import org.irmacard.cardemu.ProtocolResponseSerializer;
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
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.descriptions.IdemixVerificationDescription;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;
import org.irmacard.mno.common.BasicClientMessage;
import org.irmacard.mno.common.EnrollmentStartMessage;
import org.irmacard.mno.common.RequestFinishIssuanceMessage;
import org.irmacard.mno.common.RequestStartIssuanceMessage;
import org.jmrtd.PassportService;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;

public class Passport extends Activity {
    private NfcAdapter nfcA;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private String[][] mTechLists;

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
    private static final int SCREEN_ERROR = 4;
    private String imsi;

    private final String CARD_STORAGE = "card";
    private final String SETTINGS = "cardemu";

    private AlertDialog urldialog = null;
    private String enrollServerUrl;
    private SharedPreferences settings;

    private EnrollClient client;


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

        setContentView(R.layout.enroll_activity_start);
        updateHelpText();
        setTitle(R.string.app_name_enroll);

        TextView descriptionTextView = (TextView)findViewById(R.id.se_feedback_text);
        descriptionTextView.setMovementMethod(LinkMovementMethod.getInstance());
        descriptionTextView.setLinksClickable(true);

        screen = SCREEN_START;
        enableContinueButton();

        if (nfcA == null)
            showErrorScreen(R.string.error_nfc_notsupported);
        if (!nfcA.isEnabled())
            showErrorScreen(R.string.error_nfc_disabled);
    }

    private void updateHelpText() {
        String helpHtml = String.format(getString(R.string.se_connect_mno), enrollServerUrl);
        TextView helpTextView = (TextView)findViewById(R.id.se_feedback_text);
        helpTextView.setText(Html.fromHtml(helpHtml));
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
        if (client != null)
            client.closeConnection();
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
        } catch (CardServiceException|InfoException|CredentialsException e) {
            e.printStackTrace();
        }
    }

    private void storeCard() {
        Log.d(TAG, "Storing card");
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

    public void processIntent(Intent intent) {
        // Only handle this event if we expect it
        if (screen != SCREEN_START)
            return;

        screen = SCREEN_PASSPORT;
        advanceScreen();

        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        assert (tagFromIntent != null);

        IsoDep tag = IsoDep.get(tagFromIntent);
        CardService cs = new IsoDepCardService(tag);
        PassportService passportService = null;

        try {
            cs.open();
            passportService = new PassportService(cs);
        } catch (CardServiceException e) {
            // TODO under what circumstances does this happen? Maybe handle it more intelligently?
            showErrorScreen(e.getMessage());
        }

        client = new EnrollClient();
        client.enroll(passportService, is, new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (msg.obj == null) {
                    enableContinueButton();
                    ((TextView) findViewById(R.id.se_done_text)).setVisibility(View.VISIBLE);
                } else
                    showErrorScreen((String) msg.obj);
            }
        });
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

    private void updateProgressCounter() {
        Resources r = getResources();
        switch (screen) {
            case SCREEN_ISSUE:
                ((TextView)findViewById(R.id.step3_text)).setTextColor(r.getColor(R.color.irmadarkblue));
            case SCREEN_PASSPORT:
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmadarkblue));
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmadarkblue));
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmadarkblue));
        }
    }

    private void showErrorScreen(int errormsgId) {
        showErrorScreen(getString(errormsgId));
    }

    private void showErrorScreen(String errormsg) {
        setContentView(R.layout.enroll_activity_error);

        Resources r = getResources();
        switch (screen) {
            case SCREEN_ISSUE:
                ((TextView)findViewById(R.id.step3_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_PASSPORT:
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmared));
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_START:
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmared));
        }

        TextView view = (TextView)findViewById(R.id.enroll_error_msg);
        view.setText(errormsg);

        screen = SCREEN_ERROR;
        enableContinueButton();
    }

    private void advanceScreen() {
        switch (screen) {
        case SCREEN_START:
            setContentView(R.layout.enroll_activity_issue);
            screen = SCREEN_ISSUE; // Skip the passport screen for now
            invalidateOptionsMenu();
            updateProgressCounter();
            enroll(new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (msg.obj == null) {
                        enableContinueButton();
                        ((TextView) findViewById(R.id.se_done_text)).setVisibility(View.VISIBLE);
                    } else
                        showErrorScreen(((Exception) msg.obj).getMessage());
                }
            });
            break;

        case SCREEN_PASSPORT:
            setContentView(R.layout.enroll_activity_issue);
            screen = SCREEN_ISSUE;
            updateProgressCounter();
            break;

        case SCREEN_ISSUE:
        case SCREEN_ERROR:
            screen = SCREEN_START;
            finish();
            break;

        default:
            Log.e(TAG, "Error, screen switch fall through");
            break;
        }
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
        if (screen == SCREEN_START) {
            // Inflate the menu; this adds items to the action bar if it is present.
            getMenuInflater().inflate(R.menu.enroll_activity_start, menu);
            return true;
        }

        return false;
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
                        updateHelpText();
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
                        enrollServerUrl = getString(R.string.enroll_default_url);
                        urlfield.setText(enrollServerUrl);
                        settings.edit().putString("enroll_server_url", enrollServerUrl).apply();
                        // Move cursor to end of field
                        urlfield.setSelection(urlfield.getText().length());
                        updateHelpText();
                    }
                });

                // Move cursor to end of field
                urlfield.setSelection(urlfield.getText().length());
            }
        });

        // If the text from the input field changes to something that we do not consider valid
        // (i.e., it is not a valid IP or domain name), we disable the OK button
        urlfield.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Button okbutton = urldialog.getButton(DialogInterface.BUTTON_POSITIVE);
                okbutton.setEnabled(isValidURL(s.toString()));
            }
        });

        return urldialog;
    }

    //region Network and issuing

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

    private SyncHttpClient getClient() {
        SyncHttpClient c = new SyncHttpClient();
        c.setTimeout(5000); // TODO is this a suitable value?
        c.setUserAgent("org.irmacard.cardemu.enrollclient");
        return c;
    }

    Gson gson;
    String serverUrl;

    /**
     * Do the enrolling and send a message to uiHandler when done. If something
     * went wrong, the .obj of the message sent to uiHandler will be an exception;
     * if everything went OK the .obj will be null.
     *
     * @param uiHandler The handler to message when done.
     */
    public void enroll(final Handler uiHandler) {
        serverUrl  = "http://" + enrollServerUrl + ":8080/irma_mno_server/api/v1";

        // Doing HTTP(S) stuff on the main thread is not allowed.
        AsyncTask<Void, Void, Exception> task = new AsyncTask<Void, Void, Exception>() {
            @Override
            protected Exception doInBackground(Void... params) {
                try {
                    gson = new GsonBuilder()
                            .registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer())
                            .registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer())
                            .registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter())
                            .create();
                    HttpClient client = new HttpClient(getClient(), gson);

                    // Get a session token
                    EnrollmentStartMessage session = client.doGet(EnrollmentStartMessage.class, serverUrl + "/start");

                    // Get a list of credential that the client can issue
                    BasicClientMessage bcm = new BasicClientMessage(session.getSessionToken());
                    Type t = new TypeToken<HashMap<String, Map<String, String>>>() {}.getType();
                    HashMap<String, Map<String, String>> credentialList =
                            client.doPost(t, serverUrl + "/issue/credential-list", bcm);

                    // Get them all!
                    for (String credentialType : credentialList.keySet()) {
                        issue(credentialType, session, client);
                    }
                } catch (Exception e) {
                    // TODO more specific exception catching and handling
                    e.printStackTrace();
                    return e;
                }
                return null;
            }

            private void issue(String credentialType, EnrollmentStartMessage session, HttpClient client)
            throws HttpClientException, CardServiceException, InfoException, CredentialsException {
                // Get the first batch of commands for issuing
                RequestStartIssuanceMessage startMsg = new RequestStartIssuanceMessage(
                        session.getSessionToken(),
                        is.execute(IdemixSmartcard.selectApplicationCommand).getData()
                );
                ProtocolCommands issueCommands = client.doPost(ProtocolCommands.class,
                        serverUrl + "/issue/" + credentialType + "/start", startMsg);

                // Execute the retrieved commands
                is.sendCardPin("0000".getBytes()); // TODO
                ProtocolResponses responses = is.execute(issueCommands);

                // Get the second batch of commands for issuing
                RequestFinishIssuanceMessage finishMsg
                        = new RequestFinishIssuanceMessage(session.getSessionToken(), responses);
                issueCommands = client.doPost(ProtocolCommands.class,
                        serverUrl + "/issue/" + credentialType + "/finish", finishMsg);

                // Execute the retrieved commands
                is.execute(issueCommands);

                // Check if it worked
                IdemixCredentials ic = new IdemixCredentials(is);
                IdemixVerificationDescription ivd = new IdemixVerificationDescription(
                        "MijnOverheid", credentialType + "All");
                Attributes attributes = ic.verify(ivd);

                if (attributes != null)
                    Log.d(TAG, "Enrollment issuing succes!");
                else
                    Log.d(TAG, "Enrollment issuing failed.");
            }

            @Override
            protected void onPostExecute(Exception result) {
                Message msg = Message.obtain();
                msg.obj = result;
                uiHandler.sendMessage(msg);
            }
        }.execute();
    }


    /**
     * Exception class for HttpClient.
     */
    public class HttpClientException extends Exception {
        public int status;
        private Throwable e;

        public HttpClientException(int status, Throwable e) {
            super(e);
            this.e = e;
            this.status = status;
        }
    }

    /**
     * Convenience class to synchroniously do HTTP GET and PUT requests,
     * and serialize the in- and output automatically using Gson. <br/>
     * NOTE: the methods of this class must not be used on the main thread,
     * as otherwise a NetworkOnMainThreadException will occur.
     */
    public class HttpClient {
        Gson gson;
        SyncHttpClient client;

        // The doGet and doPost use these members to temporarily store their result
        Object returnValue;
        HttpClientException exception;

        /**
         * Instantiate a new HttpClient.
         *
         * @param client The SyncHttpClient that will be used.
         * @param gson The Gson object that will handle (de)serialization.
         */
        public HttpClient(SyncHttpClient client, Gson gson) {
            this.client = client;
            this.gson = gson;
        }

        /**
         * Performs a GET on the specified url. See the javadoc of doPost.
         *
         * @param type The type to which the return value should be cast. If the casting fails
         *             an exception will be raised.
         * @param url The url to post to.
         * @param <T> The object to post. May be null, in which case we do a GET instead
         *            of post.
         * @return The T returned by the server, if successful.
         * @throws HttpClientException
         */
        public <T> T doGet(final Type type, String url) throws HttpClientException {
            return doPost(type, url, null);
        }

        /**
         * POSTs the specified object to the specified url, and attempts to cast the response
         * of the server to the type specified by T and type. (Note: apparently it is not possible
         * get a Type from T or vice versa, so the type variable T and Type type must both be
         * given. Fortunately, the T is inferrable so this method can be called like so:<br/>
         * <code>YourType x = doPost(YourType.class, "http://www.example.com/post", object);</code><br/><br/>
         *
         * This method does not (yet) support the posting of generics to the server.
         *
         * @param type The type to which the return value should be cast. If the casting fails
         *             an exception will be raised.
         * @param url The url to post to.
         * @param object The object to post. May be null, in which case we do a GET instead
         *               of post.
         * @param <T> The type to which the return value should be cast.
         * @return The T returned by the server, if successful.
         * @throws HttpClientException If the casting failed, <code>status</code> will be zero.
         * Otherwise, if the communication with the server failed, <code>status</code> will
         * be an HTTP status code.
         */
        public <T> T doPost(final Type type, String url, Object object) throws HttpClientException {
            exception = null;

            // We use the returnValue and exception members to store our result.
            AsyncHttpResponseHandler handler = new AsyncHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                    try {
                        returnValue = gson.fromJson(new String(responseBody), type);
                    } catch (JsonParseException e) {
                        exception = new HttpClientException(0, e);
                    }
                }
                @Override
                public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
                    exception = new HttpClientException(statusCode, error);
                }
            };

            // Do the request
            if (object == null)
                client.get(Passport.this, url, handler);
            else {
                ByteArrayEntity entity = new ByteArrayEntity(gson.toJson(object).getBytes());
                client.post(Passport.this, url, entity, "application/json", handler);
            }

            if (exception != null)
                throw exception;

            @SuppressWarnings("unchecked") // If we got this far, we can be sure returnValue is a T
                    T r = (T) returnValue;
            return r;
        }
    }

    /**
     * Contains all the network and issuing code, which it executes on its own
     * separate thread. See the enroll() method.
     */
    private class EnrollClient {
        private static final String TAG = "cardemu.Passport";

        private static final int MNO_SERVER_PORT = 6789;
        private static final int GOV_SERVER_PORT = 6788;
        private HandlerThread dbThread;
        private Socket clientSocket;
        private DataOutputStream outToServer;
        private BufferedReader inFromServer;
        private Handler networkHandler;
        private Handler uiHandler;

        private SubscriberDatabase subscribers = new MockupSubscriberDatabase();
        private MNOEnrol mno = new MNOEnrollImpl(subscribers);
        private PersonalRecordDatabase personalRecordDatabase = new MockupPersonalRecordDatabase();
        private GovernmentEnrol governmentEnrol = new GovernmentEnrolImpl(personalRecordDatabase);

        public EnrollClient() {
            dbThread = new HandlerThread("network");
            dbThread.start();
            networkHandler = new Handler(dbThread.getLooper());
        }

        //region Main method

        /**
         * The main enrolling method. This method talks to the MNO server and does all the work.
         * It does so on the networkHandler thread, and when it is done it reports its result
         * to the specified handler. This handler will receive a message whose .obj is either
         * null when everything went fine, or a string containing an error message if some
         * problem occured.
         *
         * @param passportService The passport to talk to
         * @param is The IdemixService to issue the credential to
         * @param h A handler whose handleMessage() method will be called when done
         */
        private void enroll(final PassportService passportService, final IdemixService is, Handler h) {
            uiHandler = h;
            // The address in enrollServerUrl should already have been checked on valididy
            // by the urldialog, so there is no need to check it here
            Log.d(TAG, "Connecting to: " + enrollServerUrl);

            openConnection(enrollServerUrl, MNO_SERVER_PORT);

            sendAndListen("IMSI: " + imsi, new Handler(dbThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    String response = (String) msg.obj;

                    if (!response.startsWith("SI: "))
                        return; // TODO do something here?

                    SimpleDateFormat iso = new SimpleDateFormat("yyyyMMdd");
                    String[] res = response.substring(4).split(", ");
                    MNOEnrol.MNOEnrolResult mnoResult;

                    // Get the MNO credential
                    try {
                        SubscriberInfo subscriberInfo = new SubscriberInfo(iso.parse(res[0]), iso.parse(res[1]),
                                res[2]);
                        send("PASP: found\n");
                        mnoResult = mno.enroll(imsi, subscriberInfo, "0000".getBytes(), passportService, is);
                        if (mnoResult == MNOEnrol.MNOEnrolResult.SUCCESS) {
                            send("PASP: verified");
                            send("ISSU: succesfull");
                        }
                    } catch (ParseException e) {
                        reportError(e.getMessage());
                        return;
                    } finally { // Always close the connection
                        closeConnection();
                    }

                    // Get the government credential
                    final GovernmentEnrol.GovernmentEnrolResult govResult = governmentEnrol.enroll("0000".getBytes(), is);
                    if (mnoResult != MNOEnrol.MNOEnrolResult.SUCCESS
                            || govResult != GovernmentEnrol.GovernmentEnrolResult.SUCCESS)
                        reportError(R.string.error_enroll_issuing_failed);
                    else {
                        storeCard();
                        reportSuccess();
                    }
                }
            });
        }

        //endregion

        //region Connection methods

        private void openConnection(final String ip, final int port) {
            inFromServer = null;

            networkHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(TAG, "deInBackground: Creating socket");
                        InetAddress serverAddr = InetAddress.getByName(ip);
                        clientSocket = new Socket(serverAddr, port);
                        outToServer = new DataOutputStream(clientSocket.getOutputStream());
                        inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.i(TAG, "doInBackground: Exception");
                        reportError(R.string.error_enroll_cantconnect);
                    }
                }
            });
        }

        private void closeConnection() {
            try {
                inFromServer.close();
                outToServer.close();
                clientSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        //endregion

        //region Sending methods

        private void send(final String msg) {
            if (outToServer == null || inFromServer == null)
                return;
            try {
                outToServer.writeBytes(msg + "\n");
            } catch (IOException e) {
                reportError(e.getMessage());
                e.printStackTrace();
            }
        }

        private void sendAndListen(final String msg, final Handler handler) {
            networkHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (outToServer == null || inFromServer == null)
                            return;
                        outToServer.writeBytes(msg + "\n");
                        String response = inFromServer.readLine();
                        if (handler != null && response != null) {
                            Message m = Message.obtain();
                            m.obj = response;
                            handler.sendMessage(m);
                        }
                    } catch (IOException e) {
                        reportError(e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }

        //endregion

        //region Methods for reporting to the handler given to enroll()

        private void reportSuccess() { report(null); }
        private void reportError(final String msg) { report(msg); }
        private void reportError(final int msgId) {
            reportError(getString(msgId));
        }
        private void report(final String msg) {
            Message m = new Message();
            m.obj = msg;
            uiHandler.sendMessage(m);
        }

        //endregion
    }

    //endregion
}
