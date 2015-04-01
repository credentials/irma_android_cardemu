package org.irmacard.cardemu.selfenrol.government;

import net.sourceforge.scuba.smartcards.ProtocolCommands;
import net.sourceforge.scuba.smartcards.ProtocolResponse;
import net.sourceforge.scuba.smartcards.ProtocolResponses;

import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.descriptions.IdemixVerificationDescription;
import org.irmacard.credentials.idemix.info.IdemixKeyStore;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.DescriptionStore;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class GovernmentEnrolImpl implements GovernmentEnrol {
    PersonalRecordDatabase personalRecordDatabase;

    public GovernmentEnrolImpl (PersonalRecordDatabase personalRecordDatabase) {
        this.personalRecordDatabase = personalRecordDatabase;
    }

    @Override
    public GovernmentEnrolResult enroll(byte[] pin, IdemixService idemixService) {
        GovernmentEnrolResult result = GovernmentEnrolResult.SUCCESS;

        String passportNumber;
        PersonalRecord personalRecord;

        passportNumber = verifyMNOCredential (pin, idemixService);
        if (passportNumber == null)
            result = GovernmentEnrolResult.NO_VALID_MNO_CREDENTIAL;
        if (result != GovernmentEnrolResult.SUCCESS)
            return result;

        personalRecord = personalRecordDatabase.getPersonalRecord(passportNumber);
        result = GovernmentEnrolResult.PASSPORT_NUMBER_NOT_FOUND;
        if (result != GovernmentEnrolResult.SUCCESS)
            return result;

        result = issueGovernmentCredentials (personalRecord, pin, idemixService);

        return result;
    }

    private String verifyMNOCredential (byte[] pin, IdemixService idemixService) {
        IdemixVerificationDescription vd =
                null;
        Attributes attributes = null;
        String passportNumber = null;

        IdemixCredentials ic = new IdemixCredentials(idemixService);

        /* FIXME
            for unclear reasons the verification does not work yet, we fake it by using
            the admin interface to obtain the passport number from the MNO credential
         */
        HashMap<CredentialDescription,Attributes> credentialAttributes = new HashMap<CredentialDescription,Attributes>();
        try {
            ic.connect();
            idemixService.sendCardPin("000000".getBytes());
            List<CredentialDescription> credentialDescriptions = ic.getCredentials();
            for(CredentialDescription cd : credentialDescriptions) {
                credentialAttributes.put(cd, ic.getAttributes(cd));
                String issuerID = cd.getIssuerID();
                if (issuerID.equals ("KPN")) {
                    String credentialID = cd.getCredentialID();
                    if (credentialID.equals("kpnSelfEnrolDocNr")) {
                        attributes = ic.getAttributes(cd);
                        byte[] docNrBytes = credentialAttributes.get(cd).get("docNr");
                        int nLeadingZeroBytes = 0;
                        while (nLeadingZeroBytes < docNrBytes.length && docNrBytes [nLeadingZeroBytes] == 0)
                            nLeadingZeroBytes++;
                        docNrBytes = Arrays.copyOfRange(docNrBytes, nLeadingZeroBytes, docNrBytes.length);
                        passportNumber = new String(docNrBytes);
                    }
                }
            }

        } catch (/* Info */Exception e) {
            e.printStackTrace();
        }
/*
        // Doesn't work yet
        try {
            vd = new IdemixVerificationDescription("MijnOverheid", "kpnSelfEnrolDocNr");
            ic.connect();
            attributes = ic.verify(vd);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Process the responses
        if (attributes != null) {
            passportNumber = new String (attributes.get("docNr"));
        }
*/

        return passportNumber;
    }

    private GovernmentEnrolResult issueGovernmentCredentials (PersonalRecord personalRecord,
                                            byte[] pin, IdemixService idemixService) {

        GovernmentEnrolResult result = GovernmentEnrolResult.SUCCESS;

        Attributes attributes = new Attributes();

        attributes.add ("BSN", personalRecord.personalNumber.getBytes());
        String issuer = "MijnOverheid";
        String credential = "root";

        CredentialDescription cd = null;
        try {
            Date expiryDate = new Date ();

            Calendar c = Calendar.getInstance();
            c.setTime (expiryDate);
            c.add (Calendar.DATE, 365);
            expiryDate = c.getTime();

            cd = DescriptionStore.getInstance().getCredentialDescriptionByName (issuer, credential);
            IdemixCredentials ic = new IdemixCredentials (idemixService);
            ic.connect();
            idemixService.sendPin(pin);
            ic.issue(cd, IdemixKeyStore.getInstance().getSecretKey(cd), attributes, expiryDate);
        } catch (Exception e) {
            e.printStackTrace();
            result = GovernmentEnrolResult.ISSUANCE_FAILED;
        }
        return result;
    }
}
