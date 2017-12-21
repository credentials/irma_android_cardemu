package org.irmacard.cardemu.irmaclient;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.internal.Primitives;

import org.irmacard.api.common.JwtSessionRequest;
import org.irmacard.api.common.disclosure.DisclosureProofRequest;
import org.irmacard.api.common.disclosure.DisclosureProofResult;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.bluetooth.IrmaBluetoothTransportCommon;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.math.BigInteger;

import javax.crypto.SecretKey;

/**
 * Created by neonlight on 20-11-17.
 */

public class IrmaBluetoothTransportClient implements IrmaTransport {
    private BluetoothDevice device;
    private BluetoothSocket socket;
    private IrmaBluetoothTransportCommon common;
    private Gson gson;

    public IrmaBluetoothTransportClient(SecretKey key, String mac) {
        Log.d("TAG", "IrmaBluetoothTransportClient - Prover");
        this.gson = GsonUtil.getGson();
        this.device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
        if(connect()) {
            this.common = new IrmaBluetoothTransportCommon(key, socket);
        }
    }

    private boolean connect() {
        // Create socket to device
        Log.d("TAG", "Creating socket");
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(IrmaBluetoothTransportCommon.IRMA_UUID);
            Log.d("TAG", "Socket Created");
        } catch (IOException e) {
            Log.e("TAG", "Socket creation failed", e);
            return false;
        }

        // Connect to the device
        try {
            socket.connect();
            Log.d("TAG", "Socket Connected");
            return true;
        } catch (Exception e) {
            Log.e("TAG", "Socket could not connect", e);
            return false;
        }
    }

    @Override
    public <T> void post(Type type, String url, Object object, HttpResultHandler<T> handler) {
        Log.d("TAG", "POST::" + type + ":" + url +":"+object+":"+handler);
        try {
            common.write(gson.toJson(object), IrmaBluetoothTransportCommon.Type.POST_PROOFLIST);
            T result = Primitives.wrap((Class<T>) type).cast(common.read());
            if(result != null) {
                handler.onSuccess(result );
            } else {
                throw new IOException("Object is not correctly received.");
            }
        } catch (IOException e) {
            handler.onError(new HttpClientException(0, "Bluetooth Error"));
            common.close();
        }
    }

    @Override
    public <T> void get(Type type, String url, HttpResultHandler<T> handler) {
        Log.d("TAG", "GET::" + type + ":" + url +":"+handler);
        try {
            common.write("", IrmaBluetoothTransportCommon.Type.GET_JWT);
            T result = Primitives.wrap((Class<T>) type).cast(common.read());
            if(result != null) {
                handler.onSuccess(result );
            } else {
                throw new IOException("Object is not correctly received.");
            }
        } catch (IOException e) {
            handler.onError(new HttpClientException(0, "Bluetooth Error"));
            common.close();
        }
    }

    @Override
    public void delete() {
        Log.d("TAG", "DELETE");
    }
}
