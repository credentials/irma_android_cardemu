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

import android.view.WindowManager;
import com.google.gson.JsonSyntaxException;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.IsoDepCardService;

import org.acra.ACRA;
import org.irmacard.mno.common.util.GsonUtil;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.httpclient.HttpClient;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.CardManager;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.SecureSSLSocketFactory;
import org.irmacard.idemix.IdemixService;
import org.irmacard.mno.common.EnrollmentStartMessage;


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
    protected IRMACard card = null;
    protected IdemixService is = null;

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

        // Load the card and open the IdemixService
        card = CardManager.loadCard();
        is = new IdemixService(new SmartCardEmulatorService(card));
        try {
            is.open ();
        } catch (CardServiceException e) {
            e.printStackTrace();
        }

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

}
