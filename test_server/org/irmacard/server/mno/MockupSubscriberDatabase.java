package org.irmacard.server.mno;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

public class MockupSubscriberDatabase implements SubscriberDatabase {
    private  Map<String, SubscriberInfo> subscriberDatabase = new HashMap<String, SubscriberInfo>();
    private SimpleDateFormat iso = new SimpleDateFormat ("yyyyMMdd");

    public MockupSubscriberDatabase() {
        	/* Example */
//        addSubscriber ("IMSI_01234567890abcdef", "19001231", "20251231", "PPNUMMER0");
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
