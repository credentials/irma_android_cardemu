package org.irmacard.cardemu.selfenrol;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import com.google.gson.JsonSyntaxException;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.IsoDepCardService;
import org.acra.ACRA;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.SecureSSLSocketFactory;
import org.irmacard.cardemu.httpclient.HttpClient;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.protocols.Protocol;
import org.irmacard.cardemu.protocols.ProtocolHandler;
import org.irmacard.mno.common.DocumentDataMessage;
import org.irmacard.mno.common.EnrollmentStartMessage;
import org.irmacard.mno.common.PassportVerificationResult;
import org.irmacard.mno.common.PassportVerificationResultMessage;
import org.irmacard.mno.common.util.GsonUtil;

import java.security.Security;


/**
 * Abstract base class for enrolling, coontaining generic UI, NFC and networking logic. Things handled by this class
 * include:
 * <ul>
 *     <li>Fetching an EnrollmentStartMessage, see {@link #getEnrollmentSession(Handler)}</li>
 *     <li>Catching the NFC intent and dispatching a tag to inheritors that is ready to be read</li>
 * </ul>
 * Inheriting classes are expected to handle NFC events that we dispatch to it (see
 * {@link #handleNfcEvent(CardService, EnrollmentStartMessage)}), define and handle screens (see
 * {@link #advanceScreen()}), take care of its own UI, and do the actual enrolling.
 */
public abstract class AbstractNFCEnrollActivity extends AbstractGUIEnrollActivity{
    private static final String TAG = "cardemu.AbsGUIEnrollAct";

    //state variables
    protected DocumentDataMessage documentMsg;

    // NFC stuff
    private NfcAdapter nfcA;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private String[][] mTechLists;
    private Handler handler = new Handler();
    private int SCREEN_NFC_EVENT = -2;
    protected final static int MAX_TAG_READ_ATTEMPTS = 5;
    protected final static int MAX_TAG_READ_TIME = 40 * 1000;

    // Enrolling variables
    protected HttpClient client = null;
    private EnrollmentStartMessage enrollSession = null;
    private Handler uiHandler;
    private Message msg;

    protected abstract String getURLPath();

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

        client = new HttpClient(GsonUtil.getGson(), new SecureSSLSocketFactory(this, "ca"));

        if (nfcA == null) {
            showErrorScreen(R.string.error_nfc_notsupported);
            return;
        }
        if (!nfcA.isEnabled()) {
            showErrorScreen(R.string.error_nfc_disabled);
        }

        // Get the BasicClientMessage containing our nonce to send to the passport.
        getEnrollmentSession(new Handler() {
            @Override
            public void handleMessage(Message msg) {
                EnrollmentStartResult result = (EnrollmentStartResult) msg.obj;

                if (result.exception != null) { // Something went wrong
                    showErrorScreen(result.errorId);
                } else {
                    TextView connectedTextView = (TextView) findViewById(R.id.se_connected);
                    connectedTextView.setTextColor(getResources().getColor(R.color.irmagreen));
                    connectedTextView.setText(R.string.se_connected_mno);

                    findViewById(R.id.se_feedback_text).setVisibility(View.VISIBLE);
                    findViewById(R.id.se_progress_bar).setVisibility(View.VISIBLE);
                }
            }
        });

        // Spongycastle provides the MAC ISO9797Alg3Mac, which JMRTD usesin the doBAC method below (at
        // DESedeSecureMessagingWrapper.java, line 115)
        Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());

        // The next advanceScreen() is called when the passport reading was successful (see onPostExecute() in
        // readPassport() above). Thus, if no passport arrives or we can't successfully read it, we have to
        // ensure here that we don't stay on the passport screen forever with this timeout.
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (screen == SCREEN_NFC_EVENT && (documentMsg == null || !documentMsg.isComplete())) {
                    showErrorScreen(getString(R.string.error_enroll_edlerror));
                }
            }
        }, MAX_TAG_READ_TIME);
    }

    private ProtocolHandler protocolHandler = new ProtocolHandler(this) {
        @Override public void onStatusUpdate(Action action, Status status) {} // Not interested
        @Override public void onCancelled(Action action) {
            finish();
        }
        @Override public void onSuccess(Action action) {
            done();
        }
        @Override public void onFailure(Action action, String message, ApiErrorMessage error) {
            if (error != null)
                fail(error);
            else
                fail(R.string.error_enroll_issuing_failed);
        }
    };

    private void fail(int resource, Exception e) {
        if (e != null)
            msg.obj = e;
        else
            msg.obj = new Exception();
        msg.what = resource;
        uiHandler.sendMessage(msg);
    }

    private void fail(int resource) {
        fail(resource, null);
    }

    private void fail(ApiErrorMessage msg) {
        fail(msg.getError().ordinal(), null); // TODO improve
    }

    private void fail(Exception e) {
        fail(R.string.error_enroll_cantconnect, e); // TODO improve
    }

    private void done() {
        uiHandler.sendMessage(msg);
    }

    protected void doEnroll() {
        enroll(new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (msg.obj == null) { // Success
                    enableContinueButton();
                    findViewById(R.id.se_done_text).setVisibility(View.VISIBLE);
                } else {
                    if (msg.what != 0) // .what may contain a string identifier saying what went wrong
                        showErrorScreen(msg.what);
                    else
                        showErrorScreen(R.string.unknown_error);
                }
            }
        });
    }


    protected void enroll(final Handler uiHandler) {
        final String serverUrl = getEnrollmentServer();
        this.uiHandler = uiHandler;
        this.msg = Message.obtain();

        // Send our passport message to the enroll server; if it accepts, perform an issuing
        // session with the issuing API server that the enroll server returns
        client.post(PassportVerificationResultMessage.class, serverUrl + getURLPath() + "/verify-document",
                documentMsg, new JsonResultHandler<PassportVerificationResultMessage>() {
                    @Override public void onSuccess(PassportVerificationResultMessage result) {
                        if (result.getResult() != PassportVerificationResult.SUCCESS) {
                            fail(R.string.error_enroll_passportrejected);
                            return;
                        }

                        ClientQr qr = result.getIssueQr();
                        if (qr == null || qr.getVersion() == null || qr.getVersion().length() == 0
                                || qr.getUrl() == null || qr.getUrl().length() == 0) {
                            fail(R.string.error_enroll_invalidresponse);
                            return;
                        }

                        advanceScreen();
                        Protocol.NewSession(result.getIssueQr(), protocolHandler);
                    }
                });
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
    public void onPause() {
        super.onPause();

        Log.i(TAG, "onPause() called");

        if (nfcA != null) {
            nfcA.disableForegroundDispatch(this);
        }
    }

    /**
     * Called when an NFC tag and enroll session are ready to be handled. Overriding methods should expect to be
     * called multiple times.
     *
     * @param service Card service representing the tag
     * @param message The enrollment session
     */
    abstract void handleNfcEvent(CardService service, EnrollmentStartMessage message);

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
        tag.setTimeout(4000);
        CardService cs = new IsoDepCardService(tag);

        handleNfcEvent(cs, enrollSession);
    }

    /**
     * Fetch an enrollment session. When done, the specified handler will be called with the result in its .obj.
     */
    protected void getEnrollmentSession(final Handler uiHandler) {
        final String serverUrl = getEnrollmentServer();

        new AsyncTask<Void, Void, EnrollmentStartResult>() {
            @Override
            protected EnrollmentStartResult doInBackground(Void... params) {
                try {
                    EnrollmentStartMessage msg = client.doGet(EnrollmentStartMessage.class, serverUrl + getURLPath() + "/start");
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

    private abstract class JsonResultHandler<T> implements HttpResultHandler<T> {
        @Override
        public void onError(HttpClientException exception) {
            try {
                ApiErrorMessage msg = org.irmacard.api.common.util.GsonUtil.getGson().fromJson(exception.getMessage(), ApiErrorMessage.class);
                fail(msg);
            } catch (Exception e) {
                fail(exception);
            }
        }
    }


    @Override
    public void finish() {
        // Prepare data intent
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        setResult(RESULT_OK, data);
        documentMsg = null;
        super.finish();
    }

}
