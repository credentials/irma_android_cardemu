package org.irmacard.cardemu.selfenrol;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.ProtocolCommands;
import net.sf.scuba.smartcards.ProtocolResponses;

import org.acra.ACRA;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.CardManager;
import org.irmacard.cardemu.HttpClient;
import org.irmacard.cardemu.MetricsReporter;
import org.irmacard.cardemu.R;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.descriptions.IdemixVerificationDescription;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;
import org.irmacard.mno.common.BasicClientMessage;
import org.irmacard.mno.common.EnrollmentStartMessage;
import org.irmacard.mno.common.PassportDataMessage;
import org.irmacard.mno.common.PassportVerificationResult;
import org.irmacard.mno.common.PassportVerificationResultMessage;
import org.irmacard.mno.common.RequestFinishIssuanceMessage;
import org.irmacard.mno.common.RequestStartIssuanceMessage;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.Util;
import org.jmrtd.lds.DG14File;
import org.jmrtd.lds.DG15File;
import org.jmrtd.lds.DG1File;
import org.jmrtd.lds.SODFile;

import java.io.IOException;
import java.lang.reflect.Type;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.SecretKey;


public class DriversLicenseEnrollActivity extends AbstractNFCEnrollActivity {
    // Configuration
    private static final String TAG = "DriversLicenseEnrollAct";
    public static final int DriversLicenseEnrollActivityCode = 400;


    private static final int SCREEN_BAC = 2;
    private static final int SCREEN_PASSPORT = 3;
    private static final int SCREEN_ISSUE = 4;

    protected int tagReadAttempt = 0;

    // State variables
    private PassportDataMessage passportMsg = null;

    // Date stuff
    protected SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setNfcScreen(SCREEN_PASSPORT);

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

        // Update the UI
        setContentView(R.layout.enroll_activity_passport);
        screen = SCREEN_PASSPORT;
        updateProgressCounter();

        // The next advanceScreen() is called when the passport reading was successful (see onPostExecute() in
        // readPassport() above). Thus, if no passport arrives or we can't successfully read it, we have to
        // ensure here that we don't stay on the passport screen forever with this timeout.
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (screen == SCREEN_PASSPORT && (passportMsg == null || !passportMsg.isComplete())) {
                    showErrorScreen(getString(R.string.error_enroll_passporterror));
                }
            }
        }, MAX_TAG_READ_TIME);
    }

    @Override
    void handleNfcEvent(CardService service, EnrollmentStartMessage message) {
        TextView feedbackTextView = (TextView) findViewById(R.id.se_feedback_text);
        if (feedbackTextView != null) {
            feedbackTextView.setText(R.string.feedback_communicating_driverslicense);
        }

        try {
            service.open();
            PassportService passportService = new PassportService(service);
            passportService.sendSelectApplet(false);

            if (passportMsg == null) {
                passportMsg = new PassportDataMessage(message.getSessionToken(), imsi, message.getNonce());
            }
            readDriversLicense(passportService, passportMsg);
        } catch (CardServiceException e) {
            // TODO under what circumstances does this happen? Maybe handle it more intelligently?
            ACRA.getErrorReporter().handleException(e);
            showErrorScreen(getString(R.string.error_enroll_driverslicense_error),
                    getString(R.string.abort), 0,
                    getString(R.string.retry), SCREEN_PASSPORT);
        }
    }

    @Override
    protected void advanceScreen() {
        switch (screen) {
            case SCREEN_PASSPORT:
                setContentView(R.layout.enroll_activity_issue);
                screen = SCREEN_ISSUE;
                updateProgressCounter();

                // Save the card before messing with it so we can roll back if
                // something goes wrong
                CardManager.storeCard();

                // Do it!
                enroll(new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        if (msg.obj == null) {
                            // Success, save our new credentials
                            CardManager.storeCard();
                            enableContinueButton();
                            findViewById(R.id.se_done_text).setVisibility(View.VISIBLE);
                        } else {
                            // Rollback the card
                            card = CardManager.loadCard();
                            is = new IdemixService(new SmartCardEmulatorService(card));

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

    /**
     */
    public synchronized void doBAP(PassportService ps, String Kdoc) throws CardServiceException {
        String mrz = settings.getString("mrz", "");
        if (mrz != null && mrz.length()>0) {
            //String to byte array
            //redo computeKeySeedForBAC

//TODO
            /*
            try {
                byte[] keySeed = computeKeySeedForBAC(bacKey);
                SecretKey kEnc = Util.deriveKey(keySeed, Util.ENC_MODE);
                SecretKey kMac = Util.deriveKey(keySeed, Util.MAC_MODE);

                try {
                    ps.doBAC(kEnc, kMac);
                } catch (CardServiceException cse) {
                    Log.e(TAG,"BAC failed");
                    throw cse;
                }
            } catch (GeneralSecurityException gse) {
                throw new CardServiceException(gse.toString());
            }
        } else {
            //TODO error no valid mrz string
            */
        }
    }

    /**
     * Reads the datagroups 1, 14 and 15, and the SOD file and requests an active authentication from an e-passport
     * in a seperate thread.
     */
    private void readDriversLicense(PassportService ps, PassportDataMessage pdm) {
        new AsyncTask<Object,Void,PassportDataMessage>(){
            ProgressBar progressBar = (ProgressBar) findViewById(R.id.se_progress_bar);
            boolean passportError = false;
            boolean bacError = false;

            long start;
            long stop;

            @Override
            protected PassportDataMessage doInBackground(Object... params) {
                if (params.length <2) {
                    return null; //TODO appropriate error
                }
                PassportService ps = (PassportService) params[0];
                PassportDataMessage pdm = (PassportDataMessage) params[1];

                if (tagReadAttempt == 0) {
                    start = System.currentTimeMillis();
                }
                tagReadAttempt++;

                // Do the BAC separately from generating the pdm, so we can be specific in our error message if
                // necessary. (Note: the IllegalStateException should not happen, but if it does for some unforseen
                // reason there is no need to let it crash the app.)
                try {
                    //TODO
                    //doBAP(ps, );//ps.doBAC(getBAPKey());
                    Log.i(TAG, "doing BAP");
                } catch (/*CardServiceException | */IllegalStateException e) {
                    bacError = true;
                    Log.e(TAG, "doing BAP failed");
                    return null;
                }

                Exception ex = null;
                try {
                    Log.i(TAG, "PassportEnrollActivity: reading attempt " + tagReadAttempt);
                    generatePassportDataMessage(ps, pdm);
                } catch (IOException |CardServiceException e) {
                    Log.w(TAG, "PassportEnrollActivity: reading attempt " + tagReadAttempt + " failed, stack trace:");
                    Log.w(TAG, "          " + e.getMessage());
                    ex = e;
                }

                passportError = !pdm.isComplete();
                if (!pdm.isComplete() && tagReadAttempt == MAX_TAG_READ_ATTEMPTS && ex != null) {
                    // Build a fancy report saying which fields we did and which we did not manage to get
                    Log.e(TAG, "PassportEnrollActivity: too many attempts failed, aborting");
                    ACRA.getErrorReporter().reportBuilder()
                            .customData("sod", String.valueOf(pdm.getSodFile() == null))
                            .customData("dg1File", String.valueOf(pdm.getDg1File() == null))
                            .customData("dg14File", String.valueOf(pdm.getDg14File() == null))
                            .customData("dg15File", String.valueOf(pdm.getDg15File() == null))
                            .customData("response", String.valueOf(pdm.getResponse() == null))
                            .exception(ex)
                            .send();
                }

                return pdm;
            }

            @Override
            protected void onProgressUpdate(Void... values) {
                if (progressBar != null) { // progressBar can vanish if the progress goes wrong halfway through
                    progressBar.incrementProgressBy(1);
                }
            }

            /**
             * Do the AA protocol with the passport using the passportService, and put the response in a new
             * PassportDataMessage. Also read some data groups.
             */
            public void generatePassportDataMessage(PassportService passportService, PassportDataMessage pdm)
                    throws CardServiceException, IOException {
                publishProgress();

                try {
                    if (pdm.getDg1File() == null) {
                        pdm.setDg1File(new DG1File(passportService.getInputStream(PassportService.EF_DG1)));
                        Log.i(TAG, "PassportEnrollActivity: reading DG1");
                        publishProgress();
                    }
                    if (pdm.getSodFile() == null) {
                        pdm.setSodFile(new SODFile(passportService.getInputStream(PassportService.EF_SOD)));
                        Log.i(TAG, "PassportEnrollActivity: reading SOD");
                        publishProgress();
                    }
                    if (pdm.getSodFile() != null) { // We need the SOD file to check if DG14 exists
                        if (pdm.getSodFile().getDataGroupHashes().get(14) != null) { // Checks if DG14 exists
                            if (pdm.getDg14File() == null) {
                                pdm.setDg14File(new DG14File(passportService.getInputStream(PassportService.EF_DG14)));
                                Log.i(TAG, "PassportEnrollActivity: reading DG14");
                                publishProgress();
                            }
                        } else { // If DG14 does not exist, just advance the progress bar
                            Log.i(TAG, "PassportEnrollActivity: reading DG14 not necessary, skipping");
                            publishProgress();
                        }
                    }
                    if (pdm.getDg15File() == null) {
                        pdm.setDg15File(new DG15File(passportService.getInputStream(PassportService.EF_DG15)));
                        Log.i(TAG, "PassportEnrollActivity: reading DG15");
                        publishProgress();
                    }
                    // The doAA() method does not use its first three arguments, it only passes the challenge
                    // on to another functio within JMRTD.
                    if (pdm.getResponse() == null) {
                        pdm.setResponse(passportService.doAA(null, null, null, pdm.getChallenge()));
                        Log.i(TAG, "PassportEnrollActivity: doing AA");
                        publishProgress();
                    }
                } catch (NullPointerException e) {
                    // JMRTD sometimes throws a nullpointer exception if the passport communcation goes wrong
                    // (I've seen it happening if the passport is removed from the device halfway through)
                    throw new IOException("NullPointerException during passport communication", e);
                }
            }

            @Override
            protected void onPostExecute(PassportDataMessage pdm) {
                // First set the result, since it may be partially okay
                passportMsg = pdm;

                Boolean done = pdm != null && pdm.isComplete();

                Log.i(TAG, "PassportEnrollActivity: attempt " + tagReadAttempt + " finished, done: " + done);

                // If we're not yet done, we should not advance the screen but just wait for further attempts
                if (tagReadAttempt < MAX_TAG_READ_ATTEMPTS && !done) {
                    return;
                }

                stop = System.currentTimeMillis();
                MetricsReporter.getInstance().reportMeasurement("passport_data_attempts", tagReadAttempt, false);
                MetricsReporter.getInstance().reportMeasurement("passport_data_time", stop-start);

                // If we're here, we're done. Check for errors or failures, and advance the screen
                if (!bacError && !passportError) {
                    advanceScreen();
                }

                if (bacError) {
                    showErrorScreen(getString(R.string.error_enroll_bacfailed),
                            getString(R.string.abort), 0,
                            getString(R.string.retry), SCREEN_BAC);
                }

                if (passportError) {
                    showErrorScreen(R.string.error_enroll_passporterror);
                }
            }
        }.execute(ps,pdm);
    }

    /**
     * Do the enrolling and send a message to uiHandler when done. If something
     * went wrong, the .obj of the message sent to uiHandler will be an exception;
     * if everything went OK the .obj will be null.
     * TODO return our result properly using a class like EnrollmentStartResult above
     *
     * @param uiHandler The handler to message when done.
     */
    private void enroll(final Handler uiHandler) {
        final String serverUrl = BuildConfig.enrollServer;

        // Doing HTTP(S) stuff on the main thread is not allowed.
        new AsyncTask<PassportDataMessage, Void, Message>() {
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
                    ACRA.getErrorReporter().handleException(e);
                    //e.printStackTrace();
                    msg.obj = e;
                    msg.what = R.string.error_enroll_issuing_failed;
                } catch (HttpClient.HttpClientException e) {
                    msg.obj = e;
                    if (e.cause instanceof JsonSyntaxException) {
                        ACRA.getErrorReporter().handleException(e);
                        msg.what = R.string.error_enroll_invalidresponse;
                    }
                    else {
                        msg.what = R.string.error_enroll_cantconnect;
                    }
                }
                return msg;
            }

            private void issue(String credentialType, BasicClientMessage session)
                    throws HttpClient.HttpClientException, CardServiceException, InfoException, CredentialsException {
                // Get the first batch of commands for issuing
                RequestStartIssuanceMessage startMsg = new RequestStartIssuanceMessage(
                        session.getSessionToken(),
                        is.execute(IdemixSmartcard.selectApplicationCommand).getData()
                );
                ProtocolCommands issueCommands = client.doPost(ProtocolCommands.class,
                        serverUrl + "/issue/" + credentialType + "/start", startMsg);

                // Execute the retrieved commands
                is.sendCardPin("000000".getBytes());
                is.sendCredentialPin("0000".getBytes());
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

    @Override
    public void finish() {
        super.finish();

        //remove "old" passportdatamessage object
        passportMsg = null;
    }
}
