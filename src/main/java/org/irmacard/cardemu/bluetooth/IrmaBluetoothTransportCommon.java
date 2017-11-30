package org.irmacard.cardemu.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

import javax.crypto.SecretKey;

/**
 * Created by neonlight on 24-11-17.
 */

public class IrmaBluetoothTransportCommon {
    public static final UUID IRMA_UUID = UUID.fromString("c7986f0a-3154-4dc9-b19c-a5e713bb1737");
    public static final long TIMEOUT = 1000;                      // I/O timeout in milliseconds.
    private BluetoothSocket socket;
    private InputStream is;
    private OutputStream os;
    private SecretKey key;

    public IrmaBluetoothTransportCommon(SecretKey key, BluetoothSocket socket) {
        this.key = key;
        this.socket = socket;
    }

    public byte[] encrypt(byte[] bytes, int nr_bytes) {
        return bytes;
    }

    public byte[] decrypt(byte[] bytes, int nr_bytes) {
        return bytes;
    }

    private boolean unpair(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private Object read() {
        return null;
    }

    private boolean write() {
        return false;
    }

    public void close() {
        BluetoothDevice tmp = null;
        try {
            tmp = socket.getRemoteDevice();
            socket.close();
        } catch( IOException | NullPointerException e) {
            Log.d("TAG", "Connection closed failed, socket already closed?");
        }
        Log.d("TAG", "Closed");
        if(unpair(tmp)) Log.d("TAG", "closing and unpairing succesfull");
    }
}
