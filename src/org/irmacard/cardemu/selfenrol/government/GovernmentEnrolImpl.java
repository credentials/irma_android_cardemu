package org.irmacard.cardemu.selfenrol.government;

import net.sourceforge.scuba.smartcards.CardServiceException;
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
import org.irmacard.idemix.util.CardVersion;

import java.math.BigInteger;
import java.util.Calendar;
import java.util.Date;

public class GovernmentEnrolImpl implements GovernmentEnrol {
    PersonalRecordDatabase personalRecordDatabase;

    public GovernmentEnrolImpl (PersonalRecordDatabase personalRecordDatabase) {
        this.personalRecordDatabase = personalRecordDatabase;
    }

    @Override
    public void enroll(String personalNumber, byte[] pin, IdemixService idemixService) {
        PersonalRecord personalRecord;

        personalRecord = personalRecordDatabase.getPersonalRecord (personalNumber);

        verifyMNOCredential (personalRecord, pin, idemixService);
        issueGovernmentCredentials(personalRecord, pin, idemixService);
    }

    private void verifyMNOCredential (PersonalRecord personalRecord,
                                      byte[] pin, IdemixService idemixService) {
        IdemixVerificationDescription vd =
                null;
        Attributes attributes = null;

        try {
            vd = new IdemixVerificationDescription("KPN", "kpnSelfEnrolDocNr");
        } catch (InfoException e) {
            e.printStackTrace();
        }
        IdemixCredentials ic = new IdemixCredentials(null);

        // Select applet and process version
        ProtocolResponse select_response = null;
        try {
            idemixService.open();
            select_response = idemixService.execute(
                    IdemixSmartcard.selectApplicationCommand);
            // Get prove commands, and send them to card
            // Generate a nonce (you need this for verification as well)
            BigInteger nonce = null;
            nonce = vd.generateNonce();

            ProtocolCommands commands = ic.requestProofCommands(vd, nonce);
            ProtocolResponses responses = idemixService.execute(commands);
            attributes = ic.verifyProofResponses(vd, nonce, responses);
        } catch (Exception e) {
            e.printStackTrace();
        }
        CardVersion cv = new CardVersion(select_response.getData());


        // Process the responses

        if (attributes == null) {
            System.out.println ("The proof does not verify");
        } else {
            System.out.println ("Proof verified");
        }
        idemixService.close();
    }

    private void issueGovernmentCredentials (PersonalRecord personalRecord,
                                            byte[] pin, IdemixService idemixService) {

        Attributes attributes = new Attributes();

        attributes.add ("root", personalRecord.personalNumber.getBytes());
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
            idemixService.sendPin (pin);
            ic.issue (cd, IdemixKeyStore.getInstance().getSecretKey(cd), attributes, expiryDate);
            idemixService.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    }
}
