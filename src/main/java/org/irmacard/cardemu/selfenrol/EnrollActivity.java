/*
 * Copyright (c) 2015, the IRMA Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the IRMA project nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

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
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
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

import org.acra.ACRA;
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

abstract public class EnrollActivity extends Activity {
    private NfcAdapter nfcA;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private String[][] mTechLists;

    private String TAG = "cardemu.EnrollActivity";

    protected int screen;

    // PIN handling
    private int tries = -1;

    // State variables
    protected IRMACard card = null;
    protected IdemixService is = null;

    protected static final int SCREEN_START = 1;
    protected static final int SCREEN_ERROR = -1;
    private int progressCounter;

    // TODO rename
    protected int passportAttempts = 0;
    protected final static int MAX_PASSPORT_ATTEMPTS = 5;
    protected final static int MAX_PASSPORT_TIME = 15 * 1000;

    protected static final String CARD_STORAGE = "card";
    protected static final String SETTINGS = "cardemu";

    private AlertDialog urldialog = null; // ?
    protected SharedPreferences settings;
    protected SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd");
    protected DateFormat hrDateFormat = DateFormat.getDateInstance();

    protected Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "onCreate() called");

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

        settings = getSharedPreferences(SETTINGS, 0);
        client = new HttpClient(gson, new SecureSSLSocketFactory(getSocketFactory()));

        // Load the card and open the IdemixService
        card = CardManager.loadCard();
        is = new IdemixService(new SmartCardEmulatorService(card));
        try {
            is.open ();
        } catch (CardServiceException e) {
            e.printStackTrace();
        }

        setContentView(R.layout.enroll_activity_start);
        setTitle(R.string.app_name_enroll);

        enableContinueButton();

        if (nfcA == null) {
            showErrorScreen(R.string.error_nfc_notsupported);
            return;
        }
        if (!nfcA.isEnabled())
            showErrorScreen(R.string.error_nfc_disabled);
    }

    abstract protected void processIntent(final Intent intent);

    abstract protected void advanceScreen();

    @Override
    public void onNewIntent(Intent intent) {
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent intent = getIntent();
        Log.i(TAG, "onResume() called, action: " + intent.getAction());

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
             processIntent(intent);
        }

        if (nfcA != null) {
            nfcA.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Log.i(TAG, "onBackPressed() called");
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();

        Log.i(TAG, "onPause() called");

        if (nfcA != null) {
            nfcA.disableForegroundDispatch(this);
        }
    }


    protected void enableContinueButton(){
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

    protected void updateProgressCounter(int count) {
        progressCounter = count;
        Resources r = getResources();

        updateStepLabels(count, R.color.irmadarkblue);
    }

    private void updateStepLabels(int count, int color) {
        Resources r = getResources();

        if (count >= 0)
            ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(color));
        if (count >= 1)
            ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(color));
        if (count >= 2)
            ((TextView) findViewById(R.id.step2_text)).setTextColor(r.getColor(color));
        if (count >= 3)
            ((TextView) findViewById(R.id.step3_text)).setTextColor(r.getColor(color));
    }

    private void prepareErrowScreen() {
        setContentView(R.layout.enroll_activity_error);
        screen = SCREEN_ERROR;
        updateStepLabels(progressCounter, R.color.irmared);
    }


    protected void showErrorScreen(int errormsgId) {
        showErrorScreen(getString(errormsgId));
    }

    protected void showErrorScreen(String errormsg) {
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
    protected void showErrorScreen(String errormsg, final String rightButtonString, final int rightButtonScreen,
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

    @Override
    public void finish() {
        // Prepare data intent
        if (is != null) {
            is.close();
        }
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        CardManager.storeCard();
        setResult(RESULT_OK, data);
        super.finish();
    }

    //region Network and issuing

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
            ACRA.getErrorReporter().handleException(e);
            e.printStackTrace();
            return null;
        }
    }

    final protected Gson gson = new GsonBuilder()
            .registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer())
            .registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer())
            .registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter())
            .registerTypeAdapter(PassportDataMessage.class, new PassportDataMessageSerializer())
            .create();
    protected HttpClient client = null;
    protected EnrollmentStartMessage enrollSession = null;

    public void getEnrollmentSession(final Handler uiHandler) {
        final String serverUrl = BuildConfig.enrollServer;

        AsyncTask<Void, Void, EnrollmentStartResult> task = new AsyncTask<Void, Void, EnrollmentStartResult>() {
            @Override
            protected EnrollmentStartResult doInBackground(Void... params) {
                try {
                    EnrollmentStartMessage msg = client.doGet(EnrollmentStartMessage.class, serverUrl + "/start");
                    return new EnrollmentStartResult(msg);
                } catch (HttpClientException e) {
                    if (e.cause instanceof JsonSyntaxException) {
                        ACRA.getErrorReporter().handleException(e);
                        return new EnrollmentStartResult(e, R.string.error_enroll_invalidresponse);
                    }
                    else {
                        return new EnrollmentStartResult(e, R.string.error_enroll_cantconnect);
                    }
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

    //endregion
}
