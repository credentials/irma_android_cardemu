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
import net.sf.scuba.smartcards.*;
import net.sf.scuba.tlv.TLVInputStream;
import net.sf.scuba.tlv.TLVOutputStream;

import org.acra.ACRA;
import org.irmacard.cardemu.CardManager;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.descriptions.IdemixVerificationDescription;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialIdentifier;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;
import org.irmacard.mno.common.*;
import org.jmrtd.PassportService;
import org.jmrtd.Util;
import org.jmrtd.lds.DG14File;
import org.jmrtd.lds.DG15File;
import org.jmrtd.lds.SODFile;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.security.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class DriversLicenseEnrollActivity extends AbstractNFCEnrollActivity {
    // Configuration
    private static final String TAG = "DriversLicenseEnrollAct";
    public static final int DriversLicenseEnrollActivityCode = 400;


    private static final int SCREEN_BAC = 2;
    private static final int SCREEN_PASSPORT = 3;
    private static final int SCREEN_ISSUE = 4;

    protected Random random;

    // State variables
    private EDLDataMessage eDLMsg = null;
    protected int tagReadAttempt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* TODO remove this when ported back to JMRTD*/
        random = new SecureRandom();

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
                if (screen == SCREEN_PASSPORT && (eDLMsg == null || !eDLMsg.isComplete())) {
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

            if (eDLMsg == null) {
                eDLMsg = new EDLDataMessage(message.getSessionToken(), imsi, message.getNonce());
            }
            readDriversLicense(passportService, eDLMsg);
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


    public synchronized void doBAP(PassportService ps, String mrz) throws CardServiceException {
        if (mrz != null && mrz.length()>0) {
            try {
                String kdoc = mrz.substring(1, mrz.length() - 1);
                byte[] keySeed = computeKeySeedForBAP(kdoc);
                SecretKey kEnc = Util.deriveKey(keySeed, Util.ENC_MODE);
                SecretKey kMac = Util.deriveKey(keySeed, Util.MAC_MODE);
                try {
                    //the eDL BAC is actually the same as the BAC for passports
                    ps.doBAC(kEnc,kMac);
                } catch (CardServiceException cse) {
                    Log.e(TAG,"BAP failed");
                    Log.e(TAG, cse.getMessage().toString());
                    throw cse;
                }
            } catch (GeneralSecurityException gse) {
                Log.e(TAG,gse.getStackTrace().toString());
                throw new CardServiceException(gse.toString());
            }
        } else {
            Log.e(TAG,"no valid MRZ found");
            //TODO error no valid mrz string

        }
    }

    private static byte[] computeKeySeedForBAP(String kdoc) throws GeneralSecurityException {
        if (kdoc == null || kdoc.length() < 6) {
            throw new IllegalArgumentException("Wrong document key for drivers license, found " + kdoc);
        }
        MessageDigest shaDigest = MessageDigest.getInstance("SHA-1");
        shaDigest.update(getBytes(kdoc));
        byte[] hash = shaDigest.digest();

        //truncate
        byte[] keySeed = new byte[16];
        System.arraycopy(hash, 0, keySeed, 0, 16);
        return keySeed;
    }

    //helper function from JMRTD Util. Unchanged.
    private static byte[] getBytes(String str) {
        byte[] bytes = str.getBytes();
        try {
            bytes = str.getBytes("UTF-8");
        } catch (UnsupportedEncodingException use) {
			/* NOTE: unlikely. */
            Log.e(TAG,"Exception: " + use.getMessage());
        }
        return bytes;
    }


    /**
     * Reads the datagroups 1, 14 and 15, and the SOD file and requests an active authentication from an e-passport
     * in a seperate thread.
     */
    private void readDriversLicense(PassportService ps, final EDLDataMessage eDLMessage) {
        new AsyncTask<Object,Void,EDLDataMessage>(){
            ProgressBar progressBar = (ProgressBar) findViewById(R.id.se_progress_bar);
            boolean passportError = false;
            boolean bacError = false;

            long start;
            long stop;

            @Override
            protected EDLDataMessage doInBackground(Object... params) {
                if (params.length <2) {
                    return null; //TODO appropriate error
                }
                PassportService ps = (PassportService) params[0];
                EDLDataMessage eDLMessage = (EDLDataMessage) params[1];

                if (tagReadAttempt == 0) {
                    start = System.currentTimeMillis();
                }
                tagReadAttempt++;

                // Do the BAC separately from generating the eDLMessage, so we can be specific in our error message if
                // necessary. (Note: the IllegalStateException should not happen, but if it does for some unforseen
                // reason there is no need to let it crash the app.)
                String mrz = settings.getString("mrz", "");
                try {
                    doBAP(ps, mrz);
                    Log.i(TAG, "BAP Succeeded");
                } catch (CardServiceException | IllegalStateException e) {
                    bacError = true;
                    Log.e(TAG, "doing BAP failed");
                    return null;
                }
                //If we get here, the BAP succeeded. Which means the MRZ was correct, so we can trust the documentNumber
                if (eDLMessage.getDocumentNr() == null){
                    eDLMessage.setDocumentNr(mrz.substring(5,15));
                }
                Exception ex = null;
                try {
                    Log.i(TAG, "PassportEnrollActivity: reading attempt " + tagReadAttempt);
                    generateEDLDataMessage(ps, eDLMessage);
                } catch (IOException |CardServiceException e) {
                    Log.w(TAG, "PassportEnrollActivity: reading attempt " + tagReadAttempt + " failed, stack trace:");
                    Log.w(TAG, "          " + e.getMessage());
                    ex = e;
                }

                passportError = !eDLMessage.isComplete();
                if (!eDLMessage.isComplete() && tagReadAttempt == MAX_TAG_READ_ATTEMPTS && ex != null) {
                    // Build a fancy report saying which fields we did and which we did not manage to get
                    Log.e(TAG, "PassportEnrollActivity: too many attempts failed, aborting");
                    ACRA.getErrorReporter().reportBuilder()
                            .customData("sod", String.valueOf(eDLMessage.getSodFile() == null))
                            .customData("dg1File", String.valueOf(eDLMessage.getDg1File() == null))
                            .customData("dg14File", String.valueOf(eDLMessage.getDg14File() == null))
                            .customData("dg13File", String.valueOf(eDLMessage.getDg13File() == null))
                            .customData("response", String.valueOf(eDLMessage.getResponse() == null))
                            .exception(ex)
                            .send();
                }
                publishProgress();

                return eDLMessage;
            }

            @Override
            protected void onProgressUpdate(Void... values) {
                if (progressBar != null) { // progressBar can vanish if the progress goes wrong halfway through
                    progressBar.incrementProgressBy(1);
                }
            }

            /* we need this method for now to be able to send secured APDUs to cards */
            private ResponseAPDU transmitWrappedAPDU (PassportService ps, CommandAPDU capdu) throws CardServiceException {
                APDUWrapper wrapper = ps.getWrapper();
                if (wrapper == null){
                    throw new NullPointerException("No wrapper was set for secure messaging");
                }
                CommandAPDU wcapdu = wrapper.wrap(capdu);
                ResponseAPDU wrapdu = ps.transmit(wcapdu);
                return wrapper.unwrap(wrapdu, wrapdu.getBytes().length);

            }

            protected byte[] readDg1File(InputStream inputStream) throws IOException {
                int dataGroupTag = 0x61;
                TLVInputStream tlvIn = inputStream instanceof TLVInputStream ? (TLVInputStream)inputStream : new TLVInputStream(inputStream);
                int tag = tlvIn.readTag();
                if (tag != dataGroupTag) {
                    throw new IllegalArgumentException("Was expecting tag " + Integer.toHexString(dataGroupTag) + ", found " + Integer.toHexString(tag));
                }
                int dataGroupLength = tlvIn.readLength();
                byte[] value = tlvIn.readValue();

                ByteArrayOutputStream bOut = new ByteArrayOutputStream();
                TLVOutputStream tlvOut = new TLVOutputStream(bOut);
                tlvOut.writeTag(tag);
                tlvOut.writeValue(value);
                byte[] contents = bOut.toByteArray();
                return contents;
            }


            /**
             * Do the AA protocol with the passport using the passportService, and put the response in a new
             * PassportDataMessage. Also read some data groups.
             */
            public void generateEDLDataMessage(PassportService passportService, EDLDataMessage eDLMessage)
                    throws CardServiceException, IOException {
                publishProgress();

                try {
                    if (eDLMessage.getDg1File() == null) {
                        CardFileInputStream in = passportService.getInputStream((short) 0x0001);
                        eDLMessage.setDg1File(readDg1File(in));
                        Log.i(TAG,"Reading DG1");
                        publishProgress();
                    } if (eDLMessage.getSodFile() == null) {
                        eDLMessage.setSodFile(new SODFile(passportService.getInputStream((short) 0x001d)));
                        Log.i(TAG, "reading SOD");
                        publishProgress();
                    } if (eDLMessage.getSodFile() != null) { // We need the SOD file to check if DG14 exists
                        if (eDLMessage.getSodFile().getDataGroupHashes().get(14) != null) { // Checks if DG14 exists
                            if (eDLMessage.getDg14File() == null) {
                                eDLMessage.setDg14File(new DG14File(passportService.getInputStream((short) 0x000e)));
                                Log.i(TAG, "reading DG14");
                                publishProgress();
                            }
                        } else { // If DG14 does not exist, just advance the progress bar
                            Log.i(TAG, "reading DG14 not necessary, skipping");
                            publishProgress();
                        }
                    }
                    if (eDLMessage.getDg13File() == null) {
                        eDLMessage.setDg13File(new DG15File(passportService.getInputStream((short) 0x000d)));
                        Log.i(TAG, "reading DG13");
                        publishProgress();
                    }
                    // The doAA() method does not use its first three arguments, it only passes the challenge
                    // on to another functio within JMRTD.
                    if (eDLMessage.getResponse() == null) {
                        eDLMessage.setResponse(passportService.doAA(null, null, null, eDLMessage.getChallenge()));
                        Log.i(TAG, "doing AA");
                        publishProgress();
                    }
                } catch (NullPointerException e) {
                    // JMRTD sometimes throws a nullpointer exception if the passport communcation goes wrong
                    // (I've seen it happening if the passport is removed from the device halfway through)
                    throw new IOException("NullPointerException during passport communication", e);
                }
            }

            @Override
            protected void onPostExecute(EDLDataMessage eDLMessage) {
                // First set the result, since it may be partially okay
                eDLMsg = eDLMessage;

                Boolean done = eDLMessage != null && eDLMessage.isComplete();

                progressBar.setProgress(progressBar.getMax());

                Log.i(TAG, "PassportEnrollActivity: attempt " + tagReadAttempt + " finished, done: " + done);

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
        }.execute(ps,eDLMessage);
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
        final String serverUrl = getEnrollmentServer();

        // Doing HTTP(S) stuff on the main thread is not allowed.
        new AsyncTask<EDLDataMessage, Void, Message>() {
            @Override
            protected Message doInBackground(EDLDataMessage... params) {
                Message msg = Message.obtain();
                try {
                    // Get a eDLMsg token
                    EDLDataMessage eDLMsg = params[0];

                    // Send eDL response and let server check it
                    PassportVerificationResultMessage result = client.doPost(
                            PassportVerificationResultMessage.class,
                            serverUrl + "/verify-passport",
                            eDLMsg
                    );

                    if (result.getResult() != PassportVerificationResult.SUCCESS) {
                        throw new CardServiceException("Server rejected passport proof");
                    }

                    // Get a list of credential that the client can issue
                    BasicClientMessage bcm = new BasicClientMessage(eDLMsg.getSessionToken());
                    Type t = new TypeToken<HashMap<CredentialIdentifier, Map<String, String>>>() {}.getType();
                    HashMap<CredentialIdentifier, Map<String, String>> credentialList =
                            client.doPost(t, serverUrl + "/issue/credential-list", bcm);

                    // Get them all!
                    for (CredentialIdentifier credentialType : credentialList.keySet()) {
                        issue(credentialType, eDLMsg);
                    }
                } catch (CardServiceException // Issuing the credential to the card failed
                        |InfoException // VerificationDescription not found in configurarion
                        |CredentialsException e) { // Verification went wrong
                    ACRA.getErrorReporter().handleException(e);
                    //e.printStackTrace();
                    msg.obj = e;
                    msg.what = R.string.error_enroll_issuing_failed;
                } catch (HttpClientException e) {
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

            private void issue(CredentialIdentifier credentialType, BasicClientMessage session)
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
                        credentialType.getIssuerIdentifier(), credentialType.getCredentialName() + "All");
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
        }.execute(eDLMsg);
    }

    @Override
    public void finish() {
        super.finish();

        //remove "old" eDLdatamessage object
        eDLMsg = null;
    }
}
