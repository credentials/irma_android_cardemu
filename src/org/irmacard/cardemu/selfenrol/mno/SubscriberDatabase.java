package org.irmacard.cardemu.selfenrol.mno;

import java.util.Map;

public interface SubscriberDatabase {
    public SubscriberInfo getSubscriber (String subscriberIdentity);
}
