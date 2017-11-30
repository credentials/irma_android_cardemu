package org.irmacard.cardemu.irmaclient;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.google.gson.Gson;

import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.bluetooth.IrmaBluetoothTransportCommon;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

import javax.crypto.SecretKey;

/**
 * Created by neonlight on 20-11-17.
 */

public class IrmaBluetoothTransportClient implements IrmaTransport {
    private BluetoothDevice device;
    private BluetoothSocket socket;
    private InputStream is;
    private OutputStream os;
    private IrmaBluetoothTransportCommon common;
    private Gson gson;

    public IrmaBluetoothTransportClient(SecretKey key, String mac) {
        this.device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
        if(connect()) {
            this.common = new IrmaBluetoothTransportCommon(key, socket);
        }
        this.gson = GsonUtil.getGson();
        Log.d("TAG", "IrmaBluetoothTransportClient - Prover");
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
            is = socket.getInputStream();
            os = socket.getOutputStream();
            Log.d("TAG", "Socket Connected");
            return true;
        } catch (IOException e) {
            Log.e("TAG", "Socket could not connect", e);
            return false;
        }
    }

    @Override
    public <T> void post(Type type, String url, Object object, HttpResultHandler<T> handler) {
        Log.d("TAG", "POST::" + type + ":" + url +":"+object+":"+handler);
        byte[] buffer = new byte[2048];
        int nr_bytes;
        try {
            byte[] payload = (url + gson.toJson(object)).getBytes();
            os.write(common.encrypt(payload, 0));
            nr_bytes = is.read(buffer);
            String temp = new String(buffer, 0, nr_bytes);
            Log.d("TAG", "RECV: " + temp);
            T result = gson.fromJson(temp, type);
            common.close();
            handler.onSuccess(result);
        } catch (IOException e) {
            handler.onError(new HttpClientException(0, "Bluetooth Error"));
            common.close();
        }
    }

    @Override
    public <T> void get(Type type, String url, HttpResultHandler<T> handler) {
        Log.d("TAG", "GET::" + type + ":" + url +":"+handler);
        byte[] buffer = new byte[2048];
        int nr_bytes;
        try {
            os.write(common.encrypt(url.getBytes(), 0));
            nr_bytes = is.read(buffer);
            String temp = new String(buffer, 0, nr_bytes);
            Log.d("TAG", "RECV: " + temp);
            T result = gson.fromJson(temp, type);
            handler.onSuccess(result);
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
