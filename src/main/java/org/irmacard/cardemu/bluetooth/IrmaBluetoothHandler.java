package org.irmacard.cardemu.bluetooth;

import android.os.Message;

import com.noveogroup.android.log.Handler;

import org.irmacard.api.common.disclosure.DisclosureProofRequest;

/**
 * Created by neonlight on 16-11-17.
 */

public interface IrmaBluetoothHandler {
    enum State {
        IDLE,
        READY,
        CONNECTED,
        DISCONNECTED,
        SUCCESS,
        FAIL
    }

    void publish(State state);
    DisclosureProofRequest getDisclosureProofRequest();
}
