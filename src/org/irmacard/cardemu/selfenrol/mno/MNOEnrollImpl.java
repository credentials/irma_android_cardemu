package org.irmacard.cardemu.selfenrol.mno;

import android.os.AsyncTask;
import android.util.Log;

import net.sf.scuba.smartcards.CardServiceException;

import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.idemix.IdemixService;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.Security;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;



public class MNOEnrollImpl implements MNOEnrol {

   // private SubscriberDatabase subscribers;
    private SimpleDateFormat shortDate;
    private String TAG = "MNOImpl";

    public MNOEnrollImpl (SubscriberDatabase subscribers) {
    //    this.subscribers = subscribers;

        this.shortDate = new SimpleDateFormat ("yyMMdd");
    }

    public MNOEnrolResult enroll(String subscriberID, SubscriberInfo subscriberInfo, byte[] pin, PassportService passportService, IdemixService idemixService) {
        MNOEnrolResult result = MNOEnrolResult.SUCCESS;
        //SubscriberInfo subscriberInfo = null;

       // subscriberInfo = subscribers.getSubscriber (subscriberID);


        if (subscriberInfo == null) {
            result = MNOEnrolResult.UNKNOWN_SUBSCRIBER;
            Log.d(TAG,"MNO Impl, did not find a matching subscriber");
        }
        if (result != MNOEnrolResult.SUCCESS)
            return result;

        result = checkPassport (subscriberInfo, passportService);
        //result = checkPassport(subscriberID,passportService);
        if (result != MNOEnrolResult.SUCCESS)
            return result;

        result = issueMNOCredential (subscriberInfo, pin, idemixService);
        if (result != MNOEnrolResult.SUCCESS)
            return result;

        return result;
    }

    //TEST FUNCTION WHICH retieves data again from it's own db.
    private MNOEnrolResult checkPassport (String subscriberId, PassportService passportService) {
        MNOEnrolResult result = MNOEnrolResult.SUCCESS;
        SubscriberInfo subscriberInfo = new MockupSubscriberDatabase().getSubscriber(subscriberId);

        String passportNumber = subscriberInfo.passportNumber;
        String dateOfBirth = shortDate.format (subscriberInfo.dateOfBirth);
        String expiryDate = shortDate.format(subscriberInfo.passportExpiryDate);

        BACKey bacKey = new BACKey (passportNumber, dateOfBirth, expiryDate);

        try {
            passportService.sendSelectApplet (false);
            passportService.doBAC (bacKey);
        } catch (CardServiceException e) {
            /* BAC failed */
            e.printStackTrace();
            result = MNOEnrolResult.PASSPORT_CHECK_FAILED;
        }

        return result;
    }

    private MNOEnrolResult checkPassport (SubscriberInfo subscriberInfo, PassportService passportService) {
        MNOEnrolResult result = MNOEnrolResult.SUCCESS;

        String passportNumber = subscriberInfo.passportNumber;
        String dateOfBirth = shortDate.format (subscriberInfo.dateOfBirth);
        String expiryDate = shortDate.format(subscriberInfo.passportExpiryDate);

        BACKey bacKey = new BACKey (passportNumber, dateOfBirth, expiryDate);

        try {
            // Spongycastle provides the MAC ISO9797Alg3Mac, which JMRTD uses
            // in the doBAC method below (at DESedeSecureMessagingWrapper.java,
            // line 115)
            // TODO examine if Android's BouncyCastle version causes other problems;
            // perhaps we should use SpongyCastle over all projects.
            Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());
            passportService.sendSelectApplet(false);
            passportService.doBAC (bacKey);
        } catch (CardServiceException e) {
            /* BAC failed */
            e.printStackTrace();
            result = MNOEnrolResult.PASSPORT_CHECK_FAILED;
        }

        return result;
    }

    private MNOEnrolResult issueMNOCredential (SubscriberInfo subscriberInfo, byte[] pin,
                                     IdemixService idemixService) {
        MNOEnrolResult result = MNOEnrolResult.SUCCESS;

        Attributes attributes = new Attributes();

        attributes.add ("docNr", subscriberInfo.passportNumber.getBytes());
        String issuer = "KPN";
        String credential = "kpnSelfEnrolDocNr";

        CredentialDescription cd = null;
        try {
            Date expiryDate = new Date ();

            Calendar c = Calendar.getInstance();
            c.setTime (expiryDate);
            c.add (Calendar.DATE, 1);
            expiryDate = c.getTime();

            cd = DescriptionStore.getInstance ().getCredentialDescriptionByName (issuer, credential);
            IdemixCredentials ic = new IdemixCredentials (idemixService);
            ic.connect();
            idemixService.sendPin (pin);
            ic.issue (cd, IdemixKeyStore.getInstance().getSecretKey(cd), attributes, expiryDate);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();

            result = MNOEnrolResult.ISSUANCE_FAILED;
        }
        return result;
    }


}
