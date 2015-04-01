package org.irmacard.cardemu.selfenrol.mno;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

public class MockupSubscriberDatabase implements SubscriberDatabase {
    private  Map<String, SubscriberInfo> subscriberDatabase = null;
    private SimpleDateFormat iso = null;

    public MockupSubscriberDatabase() {
        this.subscriberDatabase = new HashMap<String, SubscriberInfo>();
        SimpleDateFormat iso = new SimpleDateFormat ("yyyyMMddZ");

        /* Example */
        addSubscriber ("IMSI_01234567890abcdef", "19001231", "20251231", "PPNUMMER0");
    }

    private void addSubscriber (String subscriberIdentity, String dob, String exp, String pp) {
        try {
            subscriberDatabase.put(subscriberIdentity,
                    new SubscriberInfo(iso.parse(dob), iso.parse(exp), pp));
        } catch (ParseException e) {
            e.printStackTrace();
        }

    }

    @Override
    public SubscriberInfo getSubscriber(String subscriberIdentity) {
        return subscriberDatabase.get (subscriberIdentity);
    }

}