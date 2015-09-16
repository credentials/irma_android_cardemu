package org.irmacard.cardemu.selfenrol;

import android.app.*;
import android.content.*;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.*;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.*;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.gson.Gson;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.sf.scuba.smartcards.*;

import org.irmacard.cardemu.*;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.descriptions.IdemixVerificationDescription;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.cardemu.HttpClient.HttpClientException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;
import org.irmacard.mno.common.*;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;

import java.io.*;
import java.lang.reflect.Type;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import com.google.gson.GsonBuilder;
import org.jmrtd.lds.DG14File;
import org.jmrtd.lds.DG15File;
import org.jmrtd.lds.DG1File;
import org.jmrtd.lds.SODFile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class Passport extends Activity implements ServerUrlDialogFragment.ServerUrlDialogListener {
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
    private static final int SCREEN_BAC = 2;
    private static final int SCREEN_PASSPORT = 3;
    private static final int SCREEN_ISSUE = 4;
    private static final int SCREEN_ERROR = 5;
    private String imsi;

    public static final String CARD_STORAGE = "card";
    public static final String SETTINGS = "cardemu";

    private AlertDialog urldialog = null;
    private String enrollServerUrl;
    private SharedPreferences settings;
    private SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd");
    private DateFormat hrDateFormat = DateFormat.getDateInstance();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Disable screenshots in release builds
        if (!BuildConfig.DEBUG) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }

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
        client = new HttpClient(gson, getSocketFactory());

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

        if (nfcA == null) {
            showErrorScreen(R.string.error_nfc_notsupported);
            return;
        }
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
        finish();
    }

    //TODO: move all card functionality to a specific class, so we don't need this ugly code duplication and can do explicit card state checks there.
    protected void logCard() {
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
        if (screen != SCREEN_PASSPORT)
            return;

        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        assert (tagFromIntent != null);

        IsoDep tag = IsoDep.get(tagFromIntent);
        // Prevents "Tag is lost" messages (at least on a Nexus 5)
        // TODO how about on other devices?
        tag.setTimeout(1500);

        CardService cs = new IsoDepCardService(tag);
        PassportService passportService = null;

        // Spongycastle provides the MAC ISO9797Alg3Mac, which JMRTD uses
        // in the doBAC method below (at DESedeSecureMessagingWrapper.java,
        // line 115)
        // TODO examine if Android's BouncyCastle version causes other problems;
        // perhaps we should use SpongyCastle over all projects.
        Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());

        try {
            cs.open();
            passportService = new PassportService(cs);
            passportService.sendSelectApplet(false);

            //TODO: if the user is very fast and there is no connection with the server, this will result in a null-pointer deref instead of server-unreachable error.
            if (passportMsg == null) {
                passportMsg = new PassportDataMessage(enrollSession.getSessionToken(), imsi,enrollSession.getNonce());
            }
            readPassport(passportService, passportMsg);
        } catch (CardServiceException e) {
            // TODO under what circumstances does this happen? Maybe handle it more intelligently?
            showErrorScreen(getString(R.string.error_enroll_passporterror),
                    getString(R.string.abort), 0,
                    getString(R.string.retry), SCREEN_PASSPORT);
        }
    }

    /*
     * reads the datagroups 1, 14 and 15, and the SOD file and requests an active authentication from an e-passport
     * in a seperate thread.
     */

    private void readPassport (PassportService ps, PassportDataMessage pdm){

        AsyncTask<Object,Void,PassportDataMessage> task = new AsyncTask<Object,Void,PassportDataMessage>(){
            ProgressBar progressBar = (ProgressBar) findViewById(R.id.se_progress_bar);
            Exception exception = null;
            boolean success = false;

            @Override
            protected PassportDataMessage doInBackground(Object... params) {
                if (params.length <2) {
                    return null; //TODO appropriate error
                }
                PassportService ps = (PassportService) params[0];
                PassportDataMessage pdm = (PassportDataMessage) params[1];
                try {
                    ps.doBAC(getBACKey());
                    success = generatePassportDataMessage(ps,pdm);
                } catch (Exception e) {
                    exception = e;
                    return null;
                }
                return pdm;
            }

            @Override
            protected void onProgressUpdate(Void... values) {
                progressBar.incrementProgressBy(1);
            }

            /**
             * Do the AA protocol with the passport using the passportService, and put the response in a new
             * PassportDataMessage. Also read some data groups.
             */
            public boolean generatePassportDataMessage(PassportService passportService, PassportDataMessage pdm)
                    throws CardServiceException, IOException {
                publishProgress();
                for (int i = 0; i<=5; i++) {
                    if (pdm.getDg1File() == null) {
                        pdm.setDg1File(new DG1File(passportService.getInputStream(PassportService.EF_DG1)));
                        publishProgress();
                    }
                    if (pdm.getSodFile() == null) {
                        pdm.setSodFile(new SODFile(passportService.getInputStream(PassportService.EF_SOD)));
                        publishProgress();
                    }
                    if (pdm.getDg14File() == null) {
                        pdm.setDg14File(new DG14File(passportService.getInputStream(PassportService.EF_DG14)));
                        publishProgress();
                    }
                    if (pdm.getDg15File() == null) {
                        pdm.setDg15File(new DG15File(passportService.getInputStream(PassportService.EF_DG15)));
                        publishProgress();
                    }
                    // The doAA() method does not use its first three arguments, it only passes the challenge
                    // on to another functio within JMRTD.
                    if (pdm.getResponse() == null) {
                        pdm.setResponse(passportService.doAA(null, null, null, pdm.getChallenge()));
                        publishProgress();
                    }

                    //Passport reading finished
                    if (pdm.isComplete()){
                        return true;
                    }
                }
                return false;
            }

            protected void onPostExecute(PassportDataMessage pdm){
                //first set the result, since it may be partially okay.
                passportMsg = pdm;
                //then check for errors or failures.
                if (exception==null) {
                    if (success) {
                        advanceScreen();
                    } else {
                        //TODO: at this point (or after ending the thread) we could test if the progress >= 0.
                        // in that case, some communication with the passport worked, so perhaps offer the user
                        // a new attempt with the current data?
                        showErrorScreen(R.string.error_enroll_passporterror);
                    }

                } else {
                    //an exception was thrown in the async thread
                    if (exception instanceof CardServiceException){
                        showErrorScreen(getString(R.string.error_enroll_bacfailed), getString(R.string.abort), 0,
                                getString(R.string.retry), SCREEN_BAC);
                    } else if (exception instanceof IOException){
                        showErrorScreen(getString(R.string.error_enroll_nobacdata), getString(R.string.abort), 0,
                                getString(R.string.retry), SCREEN_BAC);
                    } else {
                       showErrorScreen(R.string.unknown_error);
                    }
                }

            }

        }.execute(ps,pdm);
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
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmadarkblue));
            case SCREEN_BAC:
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmadarkblue));
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmadarkblue));
        }
    }

    private void showErrorScreen(int errormsgId) {
        showErrorScreen(getString(errormsgId));
    }

    private void showErrorScreen(String errormsg) {
        showErrorScreen(errormsg, getString(R.string.abort), 0, null, 0);
    }

    /**
     * Show the error screen.
     *
     * @param errormsg The message to show.
     * @param rightButtonString The text that the right button should show.
     * @param leftButtonScreen The screen that we shoul;d go to when the right button is clicked. Pass 0 if the
     *                         activity should be canceled.
     * @param leftButtonString The text that the left button should show. Pass null if this button should be hidden.
     * @param rightButtonScreen The screen that we should go to when the left button is clicked.
     */
    private void showErrorScreen(String errormsg, final String rightButtonString, final int rightButtonScreen,
                                 final String leftButtonString, final int leftButtonScreen) {
        prepareErrowScreen();

        TextView view = (TextView)findViewById(R.id.enroll_error_msg);
        Button rightButton = (Button)findViewById(R.id.se_button_continue);
        Button leftButton = (Button)findViewById(R.id.se_button_cancel);

        view.setText(errormsg);

        rightButton.setText(rightButtonString);
        rightButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rightButtonScreen == 0) {
                    screen = SCREEN_START;
                    finish();
                } else {
                    screen = rightButtonScreen - 1;
                    advanceScreen();
                }
            }
        });

        if (leftButtonString != null) {
            leftButton.setText(leftButtonString);
            leftButton.setVisibility(View.VISIBLE);
            leftButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (leftButtonScreen == 0) {
                        screen = SCREEN_START;
                        finish();
                    } else {
                        screen = leftButtonScreen - 1;
                        advanceScreen();
                    }
                }
            });
        } else {
            leftButton.setVisibility(View.INVISIBLE);
        }
    }

    private void prepareErrowScreen() {
        setContentView(R.layout.enroll_activity_error);

        Resources r = getResources();
        switch (screen) {
            case SCREEN_ISSUE:
                ((TextView)findViewById(R.id.step3_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_PASSPORT:
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_BAC:
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_START:
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmared));
        }
    }

    private void advanceScreen() {
        switch (screen) {
        case SCREEN_START:
            setContentView(R.layout.enroll_activity_bac);
            screen = SCREEN_BAC;
            updateProgressCounter();
            enableContinueButton();

            // Disable context menu, switching enroll server shouldn't be possible here
            invalidateOptionsMenu();

            // Restore the BAC input fields from the settings, if present
            long bacDob = settings.getLong("enroll_bac_dob", 0);
            long bacDoe = settings.getLong("enroll_bac_doe", 0);
            String docnr = settings.getString("enroll_bac_docnr", "");

            EditText docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
            EditText dobEditText = (EditText) findViewById(R.id.dob_edittext);
            EditText doeEditText = (EditText) findViewById(R.id.doe_edittext);

            Calendar c = Calendar.getInstance();
            if (bacDob != 0) {
                c.setTimeInMillis(bacDob);
                setDateEditText(dobEditText, c);
            }
            if (bacDoe != 0) {
                c.setTimeInMillis(bacDoe);
                setDateEditText(doeEditText, c);
            }
            if (docnr.length() != 0)
                docnrEditText.setText(docnr);

            break;

        case SCREEN_BAC:
            // Store the entered document number and dates in the settings.
            docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
            dobEditText = (EditText) findViewById(R.id.dob_edittext);
            doeEditText = (EditText) findViewById(R.id.doe_edittext);

            if (docnrEditText != null && dobEditText != null && doeEditText != null) {
                bacDob = 0;
                bacDoe = 0;
                try {
                    String dobString = dobEditText.getText().toString();
                    String doeString = doeEditText.getText().toString();
                    if (dobString.length() != 0)
                        bacDob = hrDateFormat.parse(dobString).getTime();
                    if (doeString.length() != 0)
                        bacDoe = hrDateFormat.parse(doeString).getTime();
                } catch (ParseException e) {
                    // Should not happen: the DOB and DOE EditTexts are set only by the DatePicker's,
                    // OnDateSetListener, which should always set a properly formatted string.
                    e.printStackTrace();
                }

                settings.edit()
                        .putLong("enroll_bac_dob", bacDob)
                        .putLong("enroll_bac_doe", bacDoe)
                        .putString("enroll_bac_docnr", docnrEditText.getText().toString())
                        .apply();
            }

            // Get the BasicClientMessage containing our nonce to send to the passport.
            getEnrollmentSession(new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    EnrollmentStartResult result = (EnrollmentStartResult) msg.obj;

                    if (result.exception != null) { // Something went wrong
                        showErrorScreen(result.errorId);
                    }
                    else {
                        enrollSession = result.msg;
                    }
                }
            });

            // Update the UI
            screen = SCREEN_PASSPORT;
            setContentView(R.layout.enroll_activity_passport);
            updateProgressCounter();

            break;

        case SCREEN_PASSPORT:
            setContentView(R.layout.enroll_activity_issue);
            screen = SCREEN_ISSUE;
            updateProgressCounter();

            // Save the card before messing with it so we can roll back if
            // something goes wrong
            storeCard();

            // Do it!
            // POSSIBLE CAVEAT: If we are here, then it is because the passport-present-intent called advanceFunction().
            // In principle, this can happen as soon as the app advances from SCREEN_BAC to SCREEN_PASSPORT - that is,
            // when the code from the case above this one runs. In that case statement, we fetch an enrollment session
            // asynchroneously. This means that the enroll() method (which assumes an enrollment session has been
            // set in the member enrollSession) could conceivably be invoked before this member is set.
            // However, assuming the server responds to our get-session-request at normal speeds, the user would have
            // to put his phone on the passport absurdly fast to achieve this, so for now we simply assume this does
            // not happen. TODO prevent this from going wrong, perhaps using some timer
            enroll(new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (msg.obj == null) {
                        // Success, save our new credentials
                        storeCard();
                        enableContinueButton();
                        ((TextView) findViewById(R.id.se_done_text)).setVisibility(View.VISIBLE);
                    } else {
                        // Rollback the card
                        loadCard();
                        String errormsg;
                        if (msg.what != 0) // .what may contain a string identifier saying what went wrong
                            showErrorScreen(msg.what);
                        else
                            showErrorScreen(R.string.unknown_error);
                    }
                }
            });

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
        //remove "old" passportdatamessage object
        passportMsg = null;
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        storeCard();
        setResult(RESULT_OK, data);
        super.finish();
    }

    public void onDateTouch(View v) {
        final EditText dateView = (EditText) v;
        final String name = v.getId() == R.id.dob_edittext ? "dob" : "doe";
        Long current = settings.getLong("enroll_bac_" + name, 0);

        final Calendar c = Calendar.getInstance();
        if (current != 0)
            c.setTimeInMillis(current);

        int currentYear = c.get(Calendar.YEAR);
        int currentMonth = c.get(Calendar.MONTH);
        int currentDay = c.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog dpd = new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                Calendar date = new GregorianCalendar(year, monthOfYear, dayOfMonth);
                setDateEditText(dateView, date);
            }
        }, currentYear, currentMonth, currentDay);
        dpd.show();
    }

    private void setDateEditText(EditText dateView, Calendar c) {
        dateView.setText(hrDateFormat.format(c.getTime()));
    }

    /**
     * Get the BAC key using the input from the user from the BAC screen.
     *
     * @return The BAC key.
     * @throws IllegalStateException when the BAC fields in the BAC screen have not been set.
     */
    private BACKey getBACKey() throws IOException {
        long dob = settings.getLong("enroll_bac_dob", 0);
        long doe = settings.getLong("enroll_bac_doe", 0);
        String docnr = settings.getString("enroll_bac_docnr", "");

        if (dob == 0 || doe == 0 || docnr.length() == 0)
            throw new IOException("BAC fields have not been set");

        String dobString = bacDateFormat.format(new Date(dob));
        String doeString = bacDateFormat.format(new Date(doe));
        return new BACKey(docnr, dobString, doeString);
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

            ServerUrlDialogFragment dialog = new ServerUrlDialogFragment();
            dialog.show(getFragmentManager(), "urldialog");

            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onServerUrlEntry(String url) {
        enrollServerUrl = url;
        updateHelpText();
    }

    //region Network and issuing

    String serverUrl;

    /**
     * Get a SSLSocketFactory that uses public key pinning: it only accepts the
     * CA certificate obtained from the file res/raw/ca.cert.
     * See https://developer.android.com/training/articles/security-ssl.html#Pinning
     * and https://op-co.de/blog/posts/java_sslsocket_mitm/
     *
     * Alternatively, https://github.com/Flowdalic/java-pinning
     *
     * If we want to trust our own CA instead, we can import it using
     * keyStore.setCertificateEntry("ourCa", ca);
     * instead of using the keyStore.load method. See the first link.
     *
     * @return A client whose SSL with our certificate pinnned. Will be null
     * if something went wrong.
     */
    private SSLSocketFactory getSocketFactory() {
        try {
            Resources r = getResources();

            // Get the certificate from the res/raw folder and parse it
            InputStream ins = r.openRawResource(r.getIdentifier("ca", "raw", getPackageName()));
            Certificate ca;
            try {
                ca = CertificateFactory.getInstance("X.509").generateCertificate(ins);
                System.out.println("ca=" + ((X509Certificate) ca).getSubjectDN());
            } finally {
                ins.close();
            }

            // Put the certificate in the keystore, put that in the TrustManagerFactory,
            // put that in the SSLContext, from which we get the SSLSocketFactory
            KeyStore keyStore = KeyStore.getInstance("BKS");
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
            tmf.init(keyStore);
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);

            return new SecureSSLSocketFactory(context.getSocketFactory());
        } catch (KeyManagementException|NoSuchAlgorithmException|KeyStoreException|CertificateException|IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    final Gson gson = new GsonBuilder()
            .registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer())
            .registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer())
            .registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter())
            .registerTypeAdapter(PassportDataMessage.class, new PassportDataMessageSerializer())
            .create();
    HttpClient client = null;
    EnrollmentStartMessage enrollSession = null;
    PassportDataMessage passportMsg = null;

    /**
     * Simple class to store the result of getEnrollmentSession
     * (either an EnrollmentStartMessage or an exception plus error message id (use R.strings))
     */
    private class EnrollmentStartResult {
        public EnrollmentStartMessage msg = null;
        public HttpClientException exception = null;
        public int errorId = 0;

        public EnrollmentStartResult(EnrollmentStartMessage msg) {
            this(msg, null, 0);
        }

        public EnrollmentStartResult(HttpClientException exception) {
            this(null, exception, 0);
        }

        public EnrollmentStartResult(HttpClientException exception, int errorId) {
            this(null, exception, errorId);
        }

        public EnrollmentStartResult(EnrollmentStartMessage msg, HttpClientException exception, int errorId) {
            this.msg = msg;
            this.exception = exception;
            this.errorId = errorId;
        }
    }

    public void getEnrollmentSession(final Handler uiHandler) {
        serverUrl  = "http://" + enrollServerUrl + ":8080/irma_mno_server/api/v1";

        AsyncTask<Void, Void, EnrollmentStartResult> task = new AsyncTask<Void, Void, EnrollmentStartResult>() {
            @Override
            protected EnrollmentStartResult doInBackground(Void... params) {
                try {
                    EnrollmentStartMessage msg = client.doGet(EnrollmentStartMessage.class, serverUrl + "/start");
                    return new EnrollmentStartResult(msg);
                } catch (HttpClientException e) { // TODO
                    if (e.cause instanceof JsonSyntaxException)
                        return new EnrollmentStartResult(e, R.string.error_enroll_invalidresponse);
                    else
                        return new EnrollmentStartResult(e, R.string.error_enroll_cantconnect);
                }
            }

            @Override
            protected void onPostExecute(EnrollmentStartResult result) {
                Message msg = Message.obtain();
                msg.obj = result;
                uiHandler.sendMessage(msg);
            }
        }.execute();
    }


    /**
     * Do the enrolling and send a message to uiHandler when done. If something
     * went wrong, the .obj of the message sent to uiHandler will be an exception;
     * if everything went OK the .obj will be null.
     * TODO return our result properly using a class like EnrollmentStartResult above
     *
     * @param uiHandler The handler to message when done.
     */
    public void enroll(final Handler uiHandler) {
        serverUrl  = "http://" + enrollServerUrl + ":8080/irma_mno_server/api/v1";

        // Doing HTTP(S) stuff on the main thread is not allowed.
        AsyncTask<PassportDataMessage, Void, Message> task = new AsyncTask<PassportDataMessage, Void, Message>() {
            @Override
            protected Message doInBackground(PassportDataMessage... params) {
                Message msg = Message.obtain();
                try {
                    // Get a passportMsg token
                    PassportDataMessage passportMsg = params[0];

                    // Send passport response and let server check it
                    PassportVerificationResultMessage result = client.doPost(
                            PassportVerificationResultMessage.class,
                            serverUrl + "/verify-passport",
                            passportMsg
                    );

                    if (result.getResult() != PassportVerificationResult.SUCCESS) {
                        throw new CardServiceException("Server rejected passport proof");
                    }

                    // Get a list of credential that the client can issue
                    BasicClientMessage bcm = new BasicClientMessage(passportMsg.getSessionToken());
                    Type t = new TypeToken<HashMap<String, Map<String, String>>>() {}.getType();
                    HashMap<String, Map<String, String>> credentialList =
                            client.doPost(t, serverUrl + "/issue/credential-list", bcm);

                    // Get them all!
                    for (String credentialType : credentialList.keySet()) {
                        issue(credentialType, passportMsg);
                    }
                } catch (CardServiceException // Issuing the credential to the card failed
                        |InfoException // VerificationDescription not found in configurarion
                        |CredentialsException e) { // Verification went wrong
                    e.printStackTrace();
                    msg.obj = e;
                    msg.what = R.string.error_enroll_issuing_failed;
                } catch (HttpClientException e) {
                    e.printStackTrace();
                    msg.obj = e;
                    if (e.cause instanceof JsonSyntaxException)
                        msg.what = R.string.error_enroll_invalidresponse;
                    else
                        msg.what = R.string.error_enroll_cantconnect;
                }
                return msg;
            }

            private void issue(String credentialType, BasicClientMessage session)
            throws HttpClientException, CardServiceException, InfoException, CredentialsException {
                // Get the first batch of commands for issuing
                RequestStartIssuanceMessage startMsg = new RequestStartIssuanceMessage(
                        session.getSessionToken(),
                        is.execute(IdemixSmartcard.selectApplicationCommand).getData()
                );
                ProtocolCommands issueCommands = client.doPost(ProtocolCommands.class,
                        serverUrl + "/issue/" + credentialType + "/start", startMsg);

                // Execute the retrieved commands
                is.sendCardPin("000000".getBytes());
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
            protected void onPostExecute(Message msg) {
                uiHandler.sendMessage(msg);
            }
        }.execute(passportMsg);
    }

    //endregion
}
