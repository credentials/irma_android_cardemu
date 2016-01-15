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
import android.util.Log;
import android.view.*;
import android.widget.Button;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import org.acra.ACRA;

import net.sf.scuba.smartcards.*;
import org.irmacard.cardemu.*;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.messages.ProtocolCommandDeserializer;
import org.irmacard.cardemu.messages.ProtocolResponseSerializer;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.cardemu.HttpClientException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.mno.common.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Abstract base class for enrolling, coontaining generic UI, NFC and networking logic. Things handled by this class
 * include:
 * <ul>
 *     <li>The progress counter in the top right of the screen, see {@link #updateProgressCounter(int)}</li>
 *     <li>The error screen, see {@link #showErrorScreen(int)} and its friends</li>
 *     <li>Fetching an EnrollmentStartMessage, see {@link #getEnrollmentSession(Handler)}</li>
 *     <li>Catching the NFC intent and dispatching a tag to inheritors that is ready to be read</li>
 * </ul>
 * Inheriting classes are expected to handle NFC events that we dispatch to it (see
 * {@link #handleNfcEvent(CardService, EnrollmentStartMessage)}), define and handle screens (see
 * {@link #advanceScreen()}), take care of its own UI, and do the actual enrolling.
 */
abstract public class EnrollActivity extends Activity {
    // Configuration
    private static final String TAG = "cardemu.EnrollActivity";
    protected static final String SETTINGS = "cardemu";
    protected static final int SCREEN_START = 1;
    protected static final int SCREEN_ERROR = -1;
    protected final static int MAX_TAG_READ_ATTEMPTS = 5;
    protected final static int MAX_TAG_READ_TIME = 15 * 1000;

    // Settings
    protected SharedPreferences settings;

    // NFC stuff
    private NfcAdapter nfcA;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private String[][] mTechLists;
    private Handler handler = new Handler();
    private int SCREEN_NFC_EVENT = -2;

    // State variables
    protected IRMACard card = null;
    protected IdemixService is = null;
    protected int screen;
    protected int tagReadAttempt = 0;

    // Date stuff
    protected SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd", Locale.US);
    protected DateFormat hrDateFormat = DateFormat.getDateInstance();

    // Enrolling variables
    protected HttpClient client = null;
    private EnrollmentStartMessage enrollSession = null;
    protected final Gson gson = new GsonBuilder()
            .registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer())
            .registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer())
            .registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter())
            .registerTypeAdapter(PassportDataMessage.class, new PassportDataMessageSerializer())
            .create();

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
        client = new HttpClient(gson, new SecureSSLSocketFactory(this, "ca"));

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

    /**
     * Called when the user presses the continue button
     */
    abstract protected void advanceScreen();

    /**
     * Called when an NFC tag and enroll session are ready to be handled. Overriding methods should expect to be
     * called multiple times.
     *
     * @param service Card service representing the tag
     * @param message The enrollment session
     */
    abstract protected void handleNfcEvent(CardService service, EnrollmentStartMessage message);

    /**
     * Sets the screen on which we should handle incoming NFC events.
     */
    protected void setNfcScreen(int screen) {
        this.SCREEN_NFC_EVENT = screen;
    }

    /**
     * Process NFC intents. If we're on the screen on which we expect NFC events, and there's an enroll session
     * containing a nonce, this method calls {@link #handleNfcEvent(CardService, EnrollmentStartMessage)} of the
     * inheriting class.
     * <p>
     * As the ACTION_TECH_DISCOVERED intent can occur multiple times, this method can be called multiple times, so
     * {@link #handleNfcEvent(CardService, EnrollmentStartMessage)} may also be called multiple times.
     */
    private void processIntent(final Intent intent) {
        // Only handle this event if we expect it
        if (screen != SCREEN_NFC_EVENT)
            return;

        if (enrollSession == null) {
            // We need to have an enroll session before we can do AA, because the enroll session contains the nonce.
            // So retry later. This will not cause an endless loop if the server is unreachable, because after a timeout
            // (5 sec) the getEnrollmentSession method will go to the error screen, so the if-clause above will trigger.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    processIntent(intent);
                }
            }, 250);
            return;
        }

        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        assert (tagFromIntent != null);

        IsoDep tag = IsoDep.get(tagFromIntent);
        // Prevents "Tag is lost" messages (at least on a Nexus 5)
        // TODO how about on other devices?
        tag.setTimeout(1500);
        CardService cs = new IsoDepCardService(tag);

        handleNfcEvent(cs, enrollSession);
    }

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

    protected void enableContinueButton() {
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
        updateStepLabels(screen - 1, R.color.irmared);
        screen = SCREEN_ERROR;
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

    /**
     * Fetch an enrollment session. When done, the specified handler will be called with the result in its .obj.
     */
    protected void getEnrollmentSession(final Handler uiHandler) {
        final String serverUrl = BuildConfig.enrollServer;

        new AsyncTask<Void, Void, EnrollmentStartResult>() {
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
                enrollSession = result.msg;
                uiHandler.sendMessage(msg);
            }
        }.execute();
    }
}
