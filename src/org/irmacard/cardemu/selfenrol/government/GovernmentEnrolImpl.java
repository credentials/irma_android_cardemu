package org.irmacard.cardemu.selfenrol.government;

import org.irmacard.idemix.IdemixService;

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
        issueGovernmentCredentials (personalRecord, pin, idemixService);
    }

    private void verifyMNOCredential (PersonalRecord personalRecord,
                                      byte[] pin, IdemixService idemixService) {

    }

    private void issueGovernmentCredentials (PersonalRecord personalRecord,
                                            byte[] pin, IdemixService idemixService) {

    }
}
