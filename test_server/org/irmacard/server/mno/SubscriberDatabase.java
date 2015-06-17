package org.irmacard.server.mno;

import java.util.Map;

public interface SubscriberDatabase {
    public SubscriberInfo getSubscriber (String subscriberIdentity);
}
