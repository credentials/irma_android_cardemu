package org.irmacard.cardemu.selfenrol;

import android.net.wifi.WifiConfiguration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import net.sf.scuba.smartcards.APDUWrapper;
import net.sf.scuba.smartcards.CardFileInputStream;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ISO7816;
import net.sf.scuba.smartcards.ProtocolCommands;
import net.sf.scuba.smartcards.ProtocolResponses;
import net.sf.scuba.smartcards.ResponseAPDU;
import net.sf.scuba.tlv.TLVInputStream;

import org.acra.ACRA;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.CredentialRequest;
import org.irmacard.api.common.IdentityProviderRequest;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.cardemu.CardManager;
import org.irmacard.cardemu.httpclient.HttpClient;
import org.irmacard.cardemu.MainActivity;
import org.irmacard.cardemu.MetricsReporter;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.protocols.Protocol;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.descriptions.IdemixVerificationDescription;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;
import org.irmacard.mno.common.BasicClientMessage;
import org.irmacard.mno.common.DriverDemographicInfo;
import org.irmacard.mno.common.EDLDataMessage;
import org.irmacard.mno.common.EDL_DG1File;
import org.irmacard.mno.common.EnrollmentStartMessage;
import org.irmacard.mno.common.PassportDataMessage;
import org.irmacard.mno.common.PassportVerificationResult;
import org.irmacard.mno.common.PassportVerificationResultMessage;
import org.irmacard.mno.common.RequestFinishIssuanceMessage;
import org.irmacard.mno.common.RequestStartIssuanceMessage;
import org.jmrtd.PassportService;
import org.jmrtd.SecureMessagingWrapper;
import org.jmrtd.Util;
import org.jmrtd.io.SplittableInputStream;
import org.jmrtd.lds.MRZInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

//import org.isodl.service.DrivingLicenseService;

public class DriversLicenseEnrollActivity extends AbstractNFCEnrollActivity {
    // Configuration
    private static final String TAG = "DriversLicenseEnrollAct";
    public static final int DriversLicenseEnrollActivityCode = 400;


    private static final int SCREEN_BAC = 2;
    private static final int SCREEN_PASSPORT = 3;
    private static final int SCREEN_ISSUE = 4;

    protected int tagReadAttempt = 0;

    // State variables
    private EDLDataMessage eDLMsg = null;

    // Date stuff
    protected SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd", Locale.US);

    /** The applet we select when we start a session. */
    protected static final byte[] APPLET_AID = { (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x48, (byte) 0x02, (byte) 0x00};

    /** Copied from JMRTD.. to be removed when functional */
    private transient Cipher cipher;
    private transient Mac mac;
    private static final IvParameterSpec ZERO_IV_PARAM_SPEC = new IvParameterSpec(new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 });
    protected Random random;


    protected SecureMessagingWrapper wrapper;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* TODO remove this when ported back to JMRTD*/
        random = new SecureRandom();
        try {
            cipher = Cipher.getInstance("DESede/CBC/NoPadding");
            mac = Mac.getInstance("ISO9797Alg3Mac", new org.spongycastle.jce.provider.BouncyCastleProvider());
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, e.getStackTrace().toString());
        } catch (NoSuchPaddingException e) {
            Log.e(TAG, e.getStackTrace().toString());
        }
        /* until here */

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
           // DrivingLicenseService eDLService = new DrivingLicenseService(service)
            PassportService passportService = new PassportService(service);
            //passportService.sendSelectApplet(null,APPLET_AID);

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


    private void issue() {
        DriverDemographicInfo driverInfo = eDLMsg.getDriverInfo();
        HashMap<String, String> attributes = new HashMap<>(4);
        attributes.put("firstnames", driverInfo.getGivenNames());
        attributes.put("firstname", driverInfo.getGivenNames());
        attributes.put("familyname", driverInfo.getFamilyName());
        attributes.put("prefix", "");
        CredentialRequest cred = new CredentialRequest(1483228800, "MijnOverheid.fullName", attributes);

        HashMap<String, String> attributesAge = new HashMap<>(4);
        String dob = driverInfo.getDob();
        int date = Integer.parseInt(dob.substring(4));
        if (date <= 2004) {
            attributesAge.put("over12", "True");
        } else {
            attributesAge.put("over12", "False");
        }
        if (date <= 2000) {
            attributesAge.put("over16", "True");
        } else {
            attributesAge.put("over16", "False");
        }if (date <= 1998) {
            attributesAge.put("over18", "True");
        } else {
            attributesAge.put("over18", "False");
        }if (date <= 1995) {
            attributesAge.put("over21", "True");
        } else {
            attributesAge.put("over21", "False");
        }
        CredentialRequest credAge = new CredentialRequest(1483228800, "MijnOverheid.ageLower", attributes);

        ArrayList<CredentialRequest> credentials = new ArrayList<>();
        credentials.add(cred);
        credentials.add(credAge);

        IssuingRequest request = new IssuingRequest(null, null, credentials);
        IdentityProviderRequest ipRequest = new IdentityProviderRequest("foo", request, 6);

        // Manually create JWT
        String header = Base64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"none\"}".getBytes(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        Map<String, Object> jwtBody = new HashMap<>(4);
        jwtBody.put("iss", "testip");
        jwtBody.put("sub", "issue_request");
        jwtBody.put("iat", System.currentTimeMillis() / 1000);
        jwtBody.put("iprequest", ipRequest);
        String json = GsonUtil.getGson().toJson(jwtBody);
        String jwt = header + "." + Base64.encodeToString(json.getBytes(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING) + ".";

        // Post JWT to the API server
        final String server = "https://demo.irmacard.org/tomcat/irma_api_server/api/v2/issue/";
        new org.irmacard.cardemu.httpclient.HttpClient(GsonUtil.getGson()).post(ClientQr.class, server, jwt, new org.irmacard.cardemu.httpclient.HttpResultHandler<ClientQr>() {
                    @Override
                    public void onSuccess(ClientQr result) {
                        ClientQr qr = new ClientQr(result.getVersion(), server + result.getUrl());
                        Protocol.NewSession(GsonUtil.getGson().toJson(qr), false);
                    }

                    @Override
                    public void onError(HttpClientException exception) {
                        Log.e(TAG,"stuff");
                        //setFeedback("Selfenroll failed!", "failure");
                    }
                }
        );
    }


    /***********************************************************************************************
     * FROM HERE MIGHT BE MOVED BACK TO JMRTD                                                         *
     **********************************************************************************************/
    public synchronized void doBAP(PassportService ps) throws CardServiceException {
        String mrz = settings.getString("mrz", "");
        if (mrz != null && mrz.length()>0) {
            //String to byte array
            //redo computeKeySeedForBAC
            Log.e(TAG, "de mrz is: "+ mrz);

//TODO
            try {
                String kdoc = mrz.substring(1, mrz.length() - 1);
                Log.e(TAG, "de mrz waar we mee rekenenen is eigenlijk: "+ kdoc);
                byte[] keySeed = computeKeySeedForBAP2(kdoc);
                //byte[] keySeed = computeKeySeedForBAP(kdoc);
                //byte[] keySeed = getBytes(mrz);
                SecretKey kEnc = Util.deriveKey(keySeed, Util.ENC_MODE);
                SecretKey kMac = Util.deriveKey(keySeed, Util.MAC_MODE);
                Log.e(TAG, "kEnc = "+ toHexString(kEnc.getEncoded()));
                Log.e(TAG, "kMac = "+ toHexString(kMac.getEncoded()));
                try {
                    //doBAC(ps, kEnc, kMac);
                    ps.doBAC(kEnc,kMac);
                } catch (CardServiceException cse) {
                    Log.e(TAG,"BAC failed");
                    Log.e(TAG, cse.getMessage().toString());
                    throw cse;
                }
            } catch (GeneralSecurityException gse) {
                Log.e(TAG,gse.getStackTrace().toString());
                throw new CardServiceException(gse.toString());
            }
        } else {
            //TODO error no valid mrz string

        }
    }


/* included and changed to test BAP algo, should be removed and possibly ported back to JMRTD*/
    public synchronized void doBAC(PassportService ps, SecretKey kEnc, SecretKey kMac) throws CardServiceException, GeneralSecurityException {
        byte[] rndICC = ps.sendGetChallenge();
        Log.e(TAG, "gotten challenge: " + toHexString(rndICC));
        byte[] rndIFD = new byte[8];
        random.nextBytes(rndIFD);
        byte[] kIFD = new byte[16];
        random.nextBytes(kIFD);
        byte[] response = sendMutualAuth(rndIFD, rndICC, kIFD, kEnc, kMac, ps);
        Log.e(TAG,"sent the mutualAuth, gotten response: " + toHexString(response));
        byte[] kICC = new byte[16];
        System.arraycopy(response, 16, kICC, 0, 16);
        byte[] keySeed = new byte[16];
        for (int i = 0; i < 16; i++) {
            keySeed[i] = (byte) ((kIFD[i] & 0xFF) ^ (kICC[i] & 0xFF));
        }
        SecretKey ksEnc = Util.deriveKey(keySeed, Util.ENC_MODE);
        SecretKey ksMac = Util.deriveKey(keySeed, Util.MAC_MODE);
        long ssc = Util.computeSendSequenceCounter(rndICC, rndIFD);
        //wrapper = new DESedeSecureMessagingWrapper(ksEnc, ksMac, ssc);
        //wrapper = new VeiligeMessagingWrapper(ksEnc,ksMac,ssc);
    }

    /**
     * Sends an <code>EXTERNAL AUTHENTICATE</code> command to the passport.
     * This is part of BAC.
     * The resulting byte array has length 32 and contains <code>rndICC</code>
     * (first 8 bytes), <code>rndIFD</code> (next 8 bytes), their key material "
     * <code>kICC</code>" (last 16 bytes).
     *
     * @param rndIFD our challenge
     * @param rndICC their challenge
     * @param kIFD our key material
     * @param kEnc the static encryption key
     * @param kMac the static mac key
     *
     * @return a byte array of length 32 containing the response that was sent
     *         by the passport, decrypted (using <code>kEnc</code>) and verified
     *         (using <code>kMac</code>)
     *
     * @throws CardServiceException on tranceive error
     */
    public synchronized byte[] sendMutualAuth(byte[] rndIFD, byte[] rndICC, byte[] kIFD, SecretKey kEnc, SecretKey kMac, PassportService ps) throws CardServiceException {
        Log.e(TAG, "started mutual auth with:");
        Log.e(TAG, "RND.IFD = " + toHexString(rndIFD));
        Log.e(TAG, "RND.ICC = " + toHexString(rndICC));
        Log.e(TAG, "K.IFD = " + toHexString(kIFD));

        try {
            if (rndIFD == null || rndIFD.length != 8) { throw new IllegalArgumentException("rndIFD wrong length"); }
            if (rndICC == null || rndICC.length != 8) { rndICC = new byte[8]; Log.e(TAG,"serious problem");}
            if (kIFD == null || kIFD.length != 16) { throw new IllegalArgumentException("kIFD wrong length"); }
            if (kEnc == null) { throw new IllegalArgumentException("kEnc == null"); }
            if (kMac == null) { throw new IllegalArgumentException("kMac == null"); }

            cipher.init(Cipher.ENCRYPT_MODE, kEnc, ZERO_IV_PARAM_SPEC);
            Log.e(TAG, "initiatlized cipher");
			/*
			 * cipher.update(rndIFD); cipher.update(rndICC); cipher.update(kIFD); //
			 * This doesn't work, apparently we need to create plaintext array. //
			 * Probably has something to do with ZERO_IV_PARAM_SPEC.
			 */
            byte[] plaintext = new byte[32];
            System.arraycopy(rndIFD, 0, plaintext, 0, 8);
            System.arraycopy(rndICC, 0, plaintext, 8, 8);
            System.arraycopy(kIFD, 0, plaintext, 16, 16);
            byte[] ciphertext = cipher.doFinal(plaintext);
            if (ciphertext.length != 32) {
                throw new IllegalStateException("Cryptogram wrong length " + ciphertext.length);
            }
            Log.e(TAG, "Computed response");
            mac.init(kMac);
            Log.e(TAG, "initialized MAC");
            byte[] paddedinput = Util.padWithMRZ(ciphertext);
            Log.e(TAG,"ciphertext: " + toHexString(ciphertext));
            Log.e(TAG,"padded ciphertext: " + toHexString(paddedinput));
            byte[] mactext = mac.doFinal(paddedinput);
            if (mactext.length != 8) {
                Log.e(TAG, "apparantly MAC is of wrong length");
                throw new IllegalStateException("MAC wrong length");
            }

            byte p1 = (byte) 0x00;
            byte p2 = (byte) 0x00;

            byte[] data = new byte[32 + 8];
            System.arraycopy(ciphertext, 0, data, 0, 32);
            System.arraycopy(mactext, 0, data, 32, 8);
            Log.e(TAG,"cmd_data = " + toHexString(data));
            int le = 40; /* 40 means max ne is 40 (0x28). */
            CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_EXTERNAL_AUTHENTICATE, p1, p2, data, le);
            Log.e(TAG,"about to send command APDU: " + toHexString(capdu.getBytes()));
            ResponseAPDU rapdu = ps.transmit(capdu);

            byte[] rapduBytes = rapdu.getBytes();
            short sw = (short)rapdu.getSW();
            Log.e(TAG,"received response " + toHexString(rapduBytes));
            if (rapduBytes == null) {
                throw new CardServiceException("Mutual authentication failed", sw);
            }

			/* Some MRTDs apparently don't support 40 here, try again with 0. See R2-p1_v2_sIII_0035 (and other issues). */
            if (sw != ISO7816.SW_NO_ERROR) {
                Log.e(TAG,"we get here???" + sw);
                le = 0; /* 0 means ne is max 256 (0xFF). */
                capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_EXTERNAL_AUTHENTICATE, p1, p2, data, le);
                Log.e(TAG,"about to send command APDU: " + toHexString(capdu.getBytes()));
                rapdu = ps.transmit(capdu);
                rapduBytes = rapdu.getBytes();
                Log.e(TAG,"received response " + toHexString(rapduBytes));
                sw = (short)rapdu.getSW();
            }

            if (rapduBytes.length != 42) {
                throw new CardServiceException("Mutual authentication failed: expected length: 40 + 2, actual length: " + rapduBytes.length, sw);
            }

			/*
			 * byte[] eICC = new byte[32]; System.arraycopy(rapdu, 0, eICC, 0, 32);
			 *
			 * byte[] mICC = new byte[8]; System.arraycopy(rapdu, 32, mICC, 0, 8);
			 */

			/* Decrypt the response. */
            cipher.init(Cipher.DECRYPT_MODE, kEnc, ZERO_IV_PARAM_SPEC);
            byte[] result = cipher.doFinal(rapduBytes, 0, rapduBytes.length - 8 - 2);
            if (result.length != 32) {
                throw new IllegalStateException("Cryptogram wrong length " + result.length);
            }
            return result;
        } catch (GeneralSecurityException gse) {
            throw new CardServiceException(gse.toString());
        }
    }


    private static byte[] computeKeySeedForBAP(String kdoc) throws GeneralSecurityException {
        if (kdoc == null || kdoc.length() < 6) {
            throw new IllegalArgumentException("Wrong document key for drivers license, found " + kdoc);
        }

        byte[] keySeed = computeKeySeed(kdoc, "SHA-1", true);

        return keySeed;
    }

    private static byte[] computeKeySeedForBAP2(String kdoc) throws GeneralSecurityException {
        if (kdoc == null || kdoc.length() < 6) {
            throw new IllegalArgumentException("Wrong document key for drivers license, found " + kdoc);
        }

        //byte[] keySeed = computeKeySeed(kdoc, "SHA-1", true);
        MessageDigest shaDigest = MessageDigest.getInstance("SHA-1");
        shaDigest.update(getBytes(kdoc));
        byte[] hash = shaDigest.digest();

        //truncate
        byte[] keySeed = new byte[16];
        System.arraycopy(hash, 0, keySeed, 0, 16);
        return keySeed;
    }

    public static byte[] computeKeySeed(String kdoc, String digestAlg, boolean doTruncate) throws GeneralSecurityException {

		/* Check digits... */
        byte[] documentNumberCheckDigit = { (byte) MRZInfo.checkDigit(kdoc) };

        MessageDigest shaDigest = MessageDigest.getInstance(digestAlg);

        shaDigest.update(getBytes(kdoc));
        shaDigest.update(documentNumberCheckDigit);

        byte[] hash = shaDigest.digest();

        if (doTruncate) {
			/* FIXME: truncate to 16 byte only for BAC with 3DES. Also for PACE and/or AES? -- MO */
            byte[] keySeed = new byte[16];
            System.arraycopy(hash, 0, keySeed, 0, 16);
            return keySeed;
        } else {
            return hash;
        }
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
    /***********************************************************************************************
     * UNTIL HERE SHOULD BE MOVED TO JMRTD                                                         *
     **********************************************************************************************/

    /*
    * debug code for hex string from SCUBA
    * can pribably be removed after we have working code
    * */
    public static String byteToHexString(byte b) {
        int n = b & 0x000000FF;
        String result = (n < 0x00000010 ? "0" : "") + Integer.toHexString(n);
        return result.toUpperCase();
    }
    public static String bytesToHexString(byte[] text, int offset, int length, int numRow) {
        if(text == null) return "NULL";
        StringBuffer result = new StringBuffer();
        for (int i = 0; i < length; i++) {
            if(i != 0 && i % numRow == 0) result.append("\n");
            result.append(byteToHexString(text[offset + i]));
        }
        return result.toString();
    }
    public static String toHexString(byte[] text) {
        return bytesToHexString(text,0,text.length, 1000);
    }
    /* end debug code*/

    /**
     * Reads the datagroups 1, 14 and 15, and the SOD file and requests an active authentication from an e-passport
     * in a seperate thread.
     */
    private void readDriversLicense(PassportService ps, EDLDataMessage eDLMessage) {
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
                try {
                    //TODO
                    doBAP(ps);
                    Log.i(TAG, "doing BAP");
                } catch (CardServiceException | IllegalStateException e) {
                    bacError = true;
                    Log.e(TAG, "doing BAP failed");
                    return null;
                }

                Log.e(TAG,"TADA!!! BAP SUCCEEDED!!!");

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
                    //        .customData("sod", String.valueOf(eDLMessage.getSodFile() == null))
                    //        .customData("dg1File", String.valueOf(eDLMessage.getDg1File() == null))
                    //        .customData("dg14File", String.valueOf(eDLMessage.getDg14File() == null))
                    //        .customData("dg15File", String.valueOf(eDLMessage.getDg15File() == null))
                    //        .customData("response", String.valueOf(eDLMessage.getResponse() == null))
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

            private void parseDG1(InputStream in) {
                try {
                    int t = in.read();
                    while ( t !=-1){
                        if (t == 95 /*0x5F start of tag*/){
                            readData(in);
                        }
                        t = in.read();
                    }

                } catch (IOException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
            }

            private void readData(InputStream in) throws IOException {
                int t2 = in.read();
                int length = in.read();
                byte[] contents = new byte[length];
                if (t2 != -1 || length !=-1){
                    switch (t2) {
                        case 01:
                            in.skip(length);/*unsure what this field represents*/
                            break;
                        case 02://unclear why, but this field contains no length...
                            break;
                        case 03: //country of issuance
                            in.skip(length);
                            break;
                        case 04://last name
                            in.read(contents,0,length);
                            driverInfo.setFamilyName(new String(contents));
                            break;
                        case 05: //first name
                            in.read(contents,0,length);
                            driverInfo.setGivenNames(new String(contents));
                            break;
                        case 06: //birth date
                            in.read(contents,0,length);
                            driverInfo.setDob(toHexString(contents));
                            break;
                        case 07: // birth place
                            in.read(contents,0,length);
                            driverInfo.setPlaceOfBirth(new String(contents));
                            break;
                        default:
                            in.skip(length); //we don't care about the rest of the fields for now.
                    }
                }
            }

            private int dataGroupTag = 0x61;
            private static final short DEMOGRAPHIC_INFO_TAG = 0x5F1F;
            private DriverDemographicInfo driverInfo = new DriverDemographicInfo();
            protected void readObject(InputStream inputStream) throws IOException {
                TLVInputStream tlvIn = inputStream instanceof TLVInputStream ? (TLVInputStream)inputStream : new TLVInputStream(inputStream);
                int tag = tlvIn.readTag();
                if (tag != dataGroupTag) {
                    throw new IllegalArgumentException("Was expecting tag " + Integer.toHexString(dataGroupTag) + ", found " + Integer.toHexString(tag));
                }
                int dataGroupLength = tlvIn.readLength();
                byte [] contents = tlvIn.readValue();
                Log.e(TAG, "reading contents: " + toHexString(contents));
                parseDG1(new ByteArrayInputStream(contents));
                //inputStream = new SplittableInputStream(inputStream, dataGroupLength);
            }


            /**
             * Do the AA protocol with the passport using the passportService, and put the response in a new
             * PassportDataMessage. Also read some data groups.
             */
            public void generateEDLDataMessage(PassportService passportService, EDLDataMessage eDLMessage)
                    throws CardServiceException, IOException {
                publishProgress();

                //CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816,ISO7816.INS_SELECT_FILE, (byte) 0x02, (byte) 0x0C, new byte[]{(byte)0x00,(byte)0x01});
                //ResponseAPDU rapdu = transmitWrappedAPDU(passportService,capdu);
                //Log.e(TAG, "received rapdu: "+ toHexString(rapdu.getBytes()));
                //passportService.sendSelectApplet(passportService.getWrapper(),APPLET_AID);
                try {
                    if (eDLMessage.getDriverInfo() == null) {
                       // eDLMessage.setDg1File(new DG1File(passportService.getInputStream(PassportService.EF_DG1)));
                        CardFileInputStream in = passportService.getInputStream((short) 1);
                        readObject(in);
                        if (driverInfo.getFamilyName()!=null/*TODO it read somethin*/){
                            eDLMessage.setDriverInfo(driverInfo);
                        }
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
        issue();
    }
        /*
        final String serverUrl = BuildConfig.enrollServer;

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
                    Type t = new TypeToken<HashMap<String, Map<String, String>>>() {}.getType();
                    HashMap<String, Map<String, String>> credentialList =
                            client.doPost(t, serverUrl + "/issue/credential-list", bcm);

                    // Get them all!
                    for (String credentialType : credentialList.keySet()) {
                        issue(credentialType, eDLMsg);
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
        }.execute(eDLMsg);
    }*/

    @Override
    public void finish() {
        super.finish();

        //remove "old" eDLdatamessage object
        eDLMsg = null;
    }
}
