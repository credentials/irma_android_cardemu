package org.irmacard.cardemu.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.irmacard.api.common.JwtSessionRequest;
import org.irmacard.api.common.disclosure.DisclosureProofRequest;
import org.irmacard.api.common.disclosure.DisclosureProofResult;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.credentials.idemix.proofs.ProofD;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.info.AttributeIdentifier;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.KeyException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

/**
 * Created by neonlight on 24-11-17.
 */

public class IrmaBluetoothTransportServer extends Handler implements Runnable{
    private static final int CONNECTION_WAIT = 20000;              // Milliseconds to wait for the client to connect
    private static final int CONNECTION_TIMEOUT = 20000;           // Milliseconds after being connected; to drop the connection.
    private SecretKey key;
    private IrmaBluetoothHandler handler;
    private static IrmaBluetoothTransportServer instance;
    private IrmaBluetoothTransportCommon common;

    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);
        IrmaBluetoothHandler.State[] values = IrmaBluetoothHandler.State.values();
        handler.publish(values[msg.what]);
    }

    private IrmaBluetoothTransportServer(SecretKey key, IrmaBluetoothHandler handler) {
        this.key = key;
        this.handler = handler;
    }

    public static void start(@NonNull SecretKey key, @NonNull IrmaBluetoothHandler handler) {
        if(instance == null) {
            instance = new IrmaBluetoothTransportServer(key, handler);
            new Thread(instance).start();
        }
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        receiveConnection();
    }

    private void receiveConnection() {
        // Create the listening server socket
        BluetoothServerSocket serverSocket;
        BluetoothSocket socket;
        try {
            serverSocket = BluetoothAdapter.getDefaultAdapter()
                    .listenUsingInsecureRfcommWithServiceRecord("Irma", IrmaBluetoothTransportCommon.IRMA_UUID);
        } catch (IOException e) {
            Log.e("TAG", "ServerSocket failed", e);
            return;
        }

        // Serve any client connecting.
        try {
            Log.d("TAG", "Accepting connection");
            socket = serverSocket.accept(CONNECTION_WAIT);
            common = new IrmaBluetoothTransportCommon(key, socket);

            sendEmptyMessage(IrmaBluetoothHandler.State.CONNECTED.ordinal());

            long timeout = System.currentTimeMillis() + CONNECTION_TIMEOUT;

            DisclosureProofRequest disclosureProofRequest = handler.getDisclosureProofRequest();
            // TODO: Refactor the transportserver/handler? Does it need to verify?

            boolean done = false;
            while(common.connected() && System.currentTimeMillis() < timeout && !done) {
                Object obj = common.read();

                if(obj instanceof ProofList) {
                    ProofList proofList = (ProofList) obj; done = true;
                    try {
                        proofList.populatePublicKeyArray();
                        DisclosureProofResult result = disclosureProofRequest.verify(proofList);
                        if(result.getStatus() == DisclosureProofResult.Status.VALID) {
                            sendEmptyMessage(IrmaBluetoothHandler.State.SUCCESS.ordinal());
                        } else {
                            sendEmptyMessage(IrmaBluetoothHandler.State.FAIL.ordinal());
                        }
                        common.write(result.getStatus());
                    } catch (InfoException | KeyException | RuntimeException e) {
                        Log.e("TAG", "Proof exception", e);
                        sendEmptyMessage(IrmaBluetoothHandler.State.FAIL.ordinal());
                        common.write(DisclosureProofResult.Status.INVALID);
                    }
                } else if(obj instanceof RequestJwtSession) {
                    common.write(disclosureProofRequest);
                } else {
                    Log.d("TAG", "Unrecognized object: " + obj);
                }
            }
        } catch (IOException e) {
            Log.e("TAG", "Connection failed.", e);
            sendEmptyMessage(2);
        }

        instance = null;  // This server is a Singleton.
        common.close();
        try {
            serverSocket.close();
        } catch(IOException e) {
            Log.e("TAG", "Server close failed", e);
        }
    }
}
