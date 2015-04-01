package org.irmacard.cardemu.selfenrol.mno;

import net.sourceforge.scuba.smartcards.CardServiceException;

import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.idemix.IdemixService;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;

import java.text.SimpleDateFormat;

public class MNOEnrollImpl implements MNOEnrol {
    private SubscriberDatabase subscribers;
    private SimpleDateFormat shortDate;

    public MNOEnrollImpl (SubscriberDatabase subscribers) {
        this.subscribers = subscribers;

        this.shortDate = new SimpleDateFormat ("yyMMdd");
    }

    public void enroll(String subscriberID, byte[] pin, PassportService passportService, IdemixService idemixService) {
        SubscriberInfo subscriberInfo;

        subscriberInfo = subscribers.getSubscriber (subscriberID);

        checkPassport (subscriberInfo, passportService);
        issueMNOCredential (subscriberInfo, pin, idemixService);
    }

    private void checkPassport (SubscriberInfo subscriberInfo, PassportService passportService) {
        String passportNumber = subscriberInfo.passportNumber;
        String dateOfBirth = shortDate.format (subscriberInfo.dateOfBirth);
        String expiryDate = shortDate.format(subscriberInfo.passportExpiryDate);

        BACKey bacKey = new BACKey (passportNumber, dateOfBirth, expiryDate);

        try {
            passportService.doBAC (bacKey);
        } catch (CardServiceException e) {
            e.printStackTrace();
        }
    }

    private void issueMNOCredential (SubscriberInfo subscriberInfo, byte[] pin,
                                     IdemixService idemixService) {
        Attributes attributes = new Attributes();

        attributes.add("userID", "s1234567@student.ru.nl".getBytes());
        attributes.add("securityHash", "DEADBABE".getBytes());
        String issuer = "Surfnet";
        String credential = "root";

        CredentialDescription cd = null;
        try {
            cd = DescriptionStore.getInstance ().getCredentialDescriptionByName (issuer, credential);
            IdemixCredentials ic = new IdemixCredentials (idemixService);
            ic.connect();
            idemixService.sendPin (pin);
            ic.issue(cd, IdemixKeyStore.getInstance().getSecretKey(cd), attributes, null);
            idemixService.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
