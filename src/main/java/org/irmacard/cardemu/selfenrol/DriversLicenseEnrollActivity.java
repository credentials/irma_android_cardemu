package org.irmacard.cardemu.selfenrol;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import net.sf.scuba.smartcards.*;
import net.sf.scuba.tlv.TLVInputStream;
import net.sf.scuba.tlv.TLVOutputStream;
import org.acra.ACRA;
import org.irmacard.cardemu.MetricsReporter;
import org.irmacard.cardemu.R;
import org.irmacard.mno.common.DocumentDataMessage;
import org.irmacard.mno.common.EDLDataMessage;
import org.irmacard.mno.common.EnrollmentStartMessage;
import org.jmrtd.PassportService;
import org.jmrtd.lds.icao.DG14File;
import org.jmrtd.lds.icao.DG15File;
import org.jmrtd.lds.SODFile;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

public class DriversLicenseEnrollActivity extends AbstractNFCEnrollActivity {
    // Configuration
    private static final String TAG = "DriversLicenseEnrollAct";
    public static final int DriversLicenseEnrollActivityCode = 400;

    // State variables
    //private EDLDataMessage eDLMsg = null;
    protected int tagReadAttempt = 0;

    @Override
    protected String getURLPath() {
        return "/dl";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Don't update the screen if super encountered a problem
        if (screen == SCREEN_ERROR)
            return;

        setNfcScreen(SCREEN_PASSPORT);

        // Update the UI
        setContentView(R.layout.enroll_activity_passport);
        screen = SCREEN_PASSPORT;
        updateProgressCounter();
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

            if (documentMsg == null) {
                documentMsg = new EDLDataMessage(message.getSessionToken(), "", message.getNonce());
            }
            readDriversLicense(passportService, documentMsg);
        } catch (CardServiceException e) {
            // TODO under what circumstances does this happen? Maybe handle it more intelligently?
            ACRA.getErrorReporter().handleException(e);
            showErrorScreen(getString(R.string.error_enroll_driverslicense_error),
                    getString(R.string.abort), 0,
                    getString(R.string.retry), SCREEN_PASSPORT);
        }
    }

    @SuppressWarnings("deprecation")
    public synchronized void doBAP(PassportService ps, String mrz) throws CardServiceException {
        if (mrz != null && mrz.length()>0) {
            try {
                String kdoc = mrz.substring(1, mrz.length() - 1);
                byte[] keySeed = computeKeySeedForBAP(kdoc);
                // Importing Util would create an unsuppressable lint deprecation warning
                SecretKey kEnc = org.jmrtd.Util.deriveKey(keySeed, org.jmrtd.Util.ENC_MODE);
                SecretKey kMac = org.jmrtd.Util.deriveKey(keySeed, org.jmrtd.Util.MAC_MODE);
                try {
                    //the eDL BAC is actually the same as the BAC for passports
                    ps.doBAC(kEnc,kMac);
                } catch (CardServiceException cse) {
                    Log.e(TAG,"BAP failed");
                    Log.e(TAG, cse.getMessage());
                    throw cse;
                }
            } catch (GeneralSecurityException gse) {
                gse.printStackTrace();
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
    private void readDriversLicense(PassportService ps, DocumentDataMessage eDLMessage) {
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
                        eDLMessage.setDg1File(eDLMessage.readFile(in,0x61));
                        Log.i(TAG, "Reading DG1");
                        publishProgress();
                    } if (eDLMessage.getSodFile() == null) {
                        eDLMessage.setSodFile(new SODFile(passportService.getInputStream((short) 0x001d)));
                        Log.i(TAG, "reading SOD");
                        publishProgress();
                    } if (eDLMessage.getSodFile() != null) { // We need the SOD file to check if DG14 exists
                        if (eDLMessage.getSodFile().getDataGroupHashes().get(14) != null) { // Checks if DG14 exists
                            if (eDLMessage.getEaFile() == null) {
                                eDLMessage.setEaFile(eDLMessage.readFile(passportService.getInputStream((short) 0x000e),0x6E));
                                Log.i(TAG, "reading DG14/ EA File");
                                publishProgress();
                            }
                        } else { // If DG14 does not exist, just advance the progress bar
                            Log.i(TAG, "reading DG14 not necessary, skipping");
                            publishProgress();
                        }
                    }
                    if (eDLMessage.getAaFile() == null) {
                        eDLMessage.setAaFile(eDLMessage.readFile(passportService.getInputStream((short) 0x000d),0x6F));
                        Log.i(TAG, "reading DG13/AA File");
                        publishProgress();
                    }
                    // The doAA() method does not use its first three arguments, it only passes the challenge
                    // on to another functio within JMRTD.
                    if (eDLMessage.getResponse() == null) {
                        eDLMessage.setResponse(passportService.doAA(null, null, null, eDLMessage.getChallenge()).getResponse());
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
                documentMsg = eDLMessage;

                Boolean done = eDLMessage != null && eDLMessage.isComplete();

                progressBar.setProgress(progressBar.getMax());

                stop = System.currentTimeMillis();
                MetricsReporter.getInstance().reportMeasurement("passport_data_attempts", tagReadAttempt, false);
                MetricsReporter.getInstance().reportMeasurement("passport_data_time", stop-start);

                Log.i(TAG, "PassportEnrollActivity: attempt " + tagReadAttempt + " finished, done: " + done);

                if (!bacError && !passportError) {
                    enroll(); // This also advances the screen
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

}
