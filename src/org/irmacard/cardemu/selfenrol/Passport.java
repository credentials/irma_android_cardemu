package org.irmacard.cardemu.selfenrol;

import android.app.*;
import android.content.*;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
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
import android.widget.DatePicker;
import android.widget.EditText;
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
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;
import org.irmacard.mno.common.*;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import org.jmrtd.lds.DG15File;
import org.jmrtd.lds.DG1File;
import org.jmrtd.lds.SODFile;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

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
    private static final int SCREEN_BAC = 2;
    private static final int SCREEN_PASSPORT = 3;
    private static final int SCREEN_ISSUE = 4;
    private static final int SCREEN_ERROR = 5;
    private String imsi;

    private final String CARD_STORAGE = "card";
    private final String SETTINGS = "cardemu";

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
        } catch (CardServiceException e) {
            // TODO under what circumstances does this happen? Maybe handle it more intelligently?
            showErrorScreen(R.string.error_enroll_passporterror, e);
            return;
        }

        try {
            passportService.doBAC(getBACKey());
            passportMsg = generatePassportDataMessage(enrollSession.getNonce(),
                    enrollSession.getSessionToken(), this.imsi, passportService);

            if (passportMsg.getResponse() == null || passportMsg.getResponse().length == 0) {
                showErrorScreen(R.string.error_enroll_passporterror);
                return;
            }

            passportMsg.verify(enrollSession.getNonce());
            advanceScreen();
        } catch (CardServiceException e) {
            showErrorScreen(R.string.error_enroll_bacfailed, e);
        } catch (IOException e) {
            showErrorScreen(R.string.error_enroll_nobacdata, e);
        }
    }

    /**
     * Do the AA protocol with the passport using the passportService, and put the response in a new
     * PassportDataMessage.
     */
    public PassportDataMessage generatePassportDataMessage(byte[] challenge, String sessionToken, String imsi,
                                                           PassportService passportService)
    throws CardServiceException, IOException {
        PassportDataMessage msg = new PassportDataMessage(sessionToken, imsi);

        msg.setDg1File(new DG1File(passportService.getInputStream(PassportService.EF_DG1)));
        msg.setSodFile(new SODFile(passportService.getInputStream(PassportService.EF_SOD)));
        msg.setDg15File(new DG15File(passportService.getInputStream(PassportService.EF_DG15)));

        //Active Authentication
        //The following 5 rules do the same as the following commented out command, but set the expected length field to 0 instead of 256.
        //This can be replaced by the following rule once JMRTD is fixed.
        //response = passportService.sendInternalAuthenticate(passportService.getWrapper(), challenge);
        CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_INTERNAL_AUTHENTICATE, 0x00, 0x00, challenge, 256);
        // System.out.println("CAPDU: " + Hex.bytesToSpacedHexString(capdu.getBytes()));
        APDUWrapper wrapper = passportService.getWrapper();
        CommandAPDU wrappedCApdu = wrapper.wrap(capdu);

        //  System.out.println("CAPDU: " + Hex.bytesToSpacedHexString(wrappedCApdu.getBytes()));
        ResponseAPDU rapdu = passportService.transmit(wrappedCApdu);
        // int sw = rapdu.getSW();
        // System.out.println("STATUS WORDS: "+ sw);
        rapdu = wrapper.unwrap(rapdu, rapdu.getBytes().length);
        byte[] response = rapdu.getData();
        msg.setResponse(response);

        return msg;
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
        showErrorScreen(getString(errormsgId), null);
    }

    private void showErrorScreen(String errormsg) {
        showErrorScreen(errormsg, null);
    }

    private void showErrorScreen(Exception e) {
        showErrorScreen(e.getMessage(), e);
    }

    private void showErrorScreen(int errormsgId, Exception e) {
        showErrorScreen(getString(errormsgId), e);
    }

    private void showErrorScreen(String errormsg, Exception e) {
        prepareErrowScreen();

        TextView view = (TextView)findViewById(R.id.enroll_error_msg);
        TextView stacktraceView = (TextView)findViewById(R.id.error_stacktrace);
        Button button = (Button)findViewById(R.id.se_button_continue);

        view.setText(errormsg);

        if (e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stacktrace = sw.toString();

            stacktraceView.setText(stacktrace);
        }
        else
            stacktraceView.setText("");

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                screen = SCREEN_START;
                finish();
            }
        });
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

            // Get the BasicClientMessage containing our nonce to send to the passport.
            getEnrollmentSession(new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    EnrollmentStartResult result = (EnrollmentStartResult) msg.obj;

                    if (result.exception != null) { // Something went wrong
                        showErrorScreen(result.errorId, result.exception);
                    }
                    else {
                        enrollSession = result.msg;
                    }
                }
            });

            // Update the UI
            screen = SCREEN_PASSPORT;
            setContentView(R.layout.enroll_activity_passport);
            invalidateOptionsMenu();
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
                        if (msg.what != 0)
                            showErrorScreen(msg.what, (Exception) msg.obj);
                        else
                            showErrorScreen((Exception) msg.obj);
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

    public static String inputStreamToString(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null)
            sb.append(line).append("\n");

        br.close();
        is.close();
        return sb.toString();
    }

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
            protected void onPostExecute(Message msg) {
                uiHandler.sendMessage(msg);
            }
        }.execute(passportMsg);
    }


    /**
     * Exception class for HttpClient.
     */
    public class HttpClientException extends Exception {
        public int status;
        public Throwable cause;

        public HttpClientException(int status, Throwable cause) {
            super(cause);
            this.cause = cause;
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
        private Gson gson;
        private SSLSocketFactory socketFactory;
        private int timeout = 5000;

        /**
         * Instantiate a new HttpClient.
         *
         * @param gson The Gson object that will handle (de)serialization.
         */
        public HttpClient(Gson gson) {
            this(gson, null);
        }

        /**
         * Instantiate a new HttpClient.
         * @param gson The Gson object that will handle (de)serialization.
         * @param socketFactory The SSLSocketFactory to use.
         */
        public HttpClient(Gson gson, SSLSocketFactory socketFactory) {
            this.gson = gson;
            this.socketFactory = socketFactory;
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
            HttpURLConnection c = null;
            String method;

            if (object == null)
                method = "GET";
            else
                method = "POST";

            try {
                URL u = new URL(url);
                c = (HttpURLConnection) u.openConnection();
                if (url.startsWith("https") && socketFactory != null)
                    ((HttpsURLConnection) c).setSSLSocketFactory(socketFactory);
                c.setRequestMethod(method);
                c.setUseCaches(false);
                c.setConnectTimeout(timeout);
                c.setReadTimeout(timeout);
                c.setDoInput(true);

                byte[] objectBytes = new byte[] {};

                if (method.equals("POST")) {
                    objectBytes = gson.toJson(object).getBytes();
                    c.setDoOutput(true);
                    // See http://www.evanjbrunner.info/posts/json-requests-with-httpurlconnection-in-android/
                    c.setFixedLengthStreamingMode(objectBytes.length);
                    c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
                }

                c.connect();

                if (method.equals("POST")) {
                    OutputStream os = new BufferedOutputStream(c.getOutputStream());
                    os.write(objectBytes);
                    os.flush();
                }

                int status = c.getResponseCode();
                switch (status) {
                    case 200:
                    case 201:
                        return gson.fromJson(inputStreamToString(c.getInputStream()), type);
                    default:
                        throw new HttpClientException(status, null);
                }
            } catch (JsonSyntaxException|IOException e) { // IOException includes MalformedURLException
                e.printStackTrace();
                throw new HttpClientException(0, e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
    }

    //endregion
}
