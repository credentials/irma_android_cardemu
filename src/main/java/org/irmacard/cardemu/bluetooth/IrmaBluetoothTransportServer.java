package org.irmacard.cardemu.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
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
    BluetoothServerSocket serverSocket;
    BluetoothSocket socket;
    SecretKey key;
    IrmaBluetoothHandler handler;
    private InputStream is;
    private OutputStream os;
    static IrmaBluetoothTransportServer instance;
    private IrmaBluetoothTransportCommon common;
    private Gson gson;

    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);
        IrmaBluetoothHandler.State[] values = IrmaBluetoothHandler.State.values();
        handler.publish(values[msg.what]);
    }

    private IrmaBluetoothTransportServer(SecretKey key, IrmaBluetoothHandler handler) {
        this.key = key;
        this.handler = handler;
        this.gson = GsonUtil.getGson();
    }

    public static void start(SecretKey key, IrmaBluetoothHandler handler) {
        if(instance == null) {
            instance = new IrmaBluetoothTransportServer(key, handler);
            new Thread(instance).start();
        }
        throw new UnsupportedOperationException("IrmaBluetoothTransportServer already started.");
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        receiveConnection();
    }

    private void receiveConnection() {
        // Create ServerSocket listener
        Log.d("TAG", "Receiving connection");
        try {
            serverSocket = BluetoothAdapter.getDefaultAdapter()
                    .listenUsingInsecureRfcommWithServiceRecord("Irma", IrmaBluetoothTransportCommon.IRMA_UUID);
        } catch (IOException e) {
            Log.e("TAG", "ServerSocket failed", e);
            return;
        }

        // Wait 20 seconds for connection
        try {
            Log.d("TAG", "Accepting connection");
            socket = serverSocket.accept(20000);
            common = new IrmaBluetoothTransportCommon(key, socket);
            sendEmptyMessage(1);
            is = socket.getInputStream();
            os = socket.getOutputStream();
            Log.d("TAG", "Socket connected");
            int nr_bytes;
            byte[] read_buffer = new byte[4096];
            byte[] request_buffer = new byte[1024];
            byte[] response_buffer = new byte[1024];

            //
            DisclosureProofRequest drp = handler.getDisclosureProofRequest();
            String label = drp.getContent().get(0).getLabel();
            //TODO : GsonUtil.getGson().toJson(drp);
            JwtBuilder builder = Jwts.builder();
            // TODO: generate JWT dynamically from DisclosureProofRequest
            String jwt = builder.setPayload(
                    "{\"sub\": \"verification_request\"," +
                            "\"iss\": \"bluetooth\"," +
                            "\"iat\": "+ new Date().getTime() + "," +
                            "\"sprequest\": {"+
                            "\"validity\": 60, " +
                            "\"request\": {" +
                            "\"content\": [" +
                            "{" +
                            "\"label\": \""+ label +"\", " +
                            "\"attributes\": [ " +
                            "\"irma-demo.MijnOverheid.ageLower." + label +"\"" +
                            "]" +
                            "}" +
                            "]" +
                            "}" +
                            "}" +
                            "}").compact();
            Log.d("TAG", "Requesting: " + label + " :: irma-demo.MijnOverheid.ageLower." + label);

            JwtSessionRequest jsr = new JwtSessionRequest(jwt, drp.getNonce(), drp.getContext());
            String rq = new Gson().toJson(jsr);
            byte[] jwt_buffer = rq.getBytes();

            // Act as a HTTP REST web server for as long as the connection is alive (or timeout)
            try {
                while(socket.isConnected()) {
                    // Get Decrypted data.
                    String request;
                    nr_bytes = is.read(read_buffer);
                    request_buffer = common.decrypt(read_buffer, nr_bytes);
                    request = new String(request_buffer, 0, nr_bytes);
                    Log.d("TAG", "RECV(" + nr_bytes +"): " + request);
                    if(request.startsWith("proofs")) {
                        Log.d("TAG", "Responding to POST");
                        request = request.substring(6);
                        /**
                         * ReConstruct packet.
                         */
                        while(!request.endsWith("]")) {
                            nr_bytes = is.read(read_buffer);
                            request_buffer = common.decrypt(read_buffer, nr_bytes);
                            request = request.concat(new String(request_buffer, 0, nr_bytes));
                            Log.d("TAG", "RECV(" + nr_bytes +"): " + request);
                        }
                        /**
                         * Verify Proof and Send Answer.
                         */
                        try {
                            List<ProofD> proofDS = gson.fromJson(request, new TypeToken<ArrayList<ProofD>>(){}.getType());
                            ProofList proofList = new ProofList();
                            proofList.addAll(proofDS);
                            proofList.populatePublicKeyArray();
                            Log.d("TAG", "ProofList: " + proofList);
                            DisclosureProofResult result = drp.verify(proofList);
                            response_buffer = result.getStatus().toString().getBytes();
                            Log.d("TAG", "ProofResult: " + result.getStatus().toString());
                            if(result.getStatus() == DisclosureProofResult.Status.VALID &&
                                    result.getAttributes().get(new AttributeIdentifier("irma-demo.MijnOverheid.ageLower." + label)).equals("yes")) {
                                sendEmptyMessage(IrmaBluetoothHandler.State.SUCCESS.ordinal());
                            } else {
                                sendEmptyMessage(IrmaBluetoothHandler.State.FAIL.ordinal());
                            }
                            os.write(common.encrypt(response_buffer, response_buffer.length));
                        } catch (InfoException | KeyException | RuntimeException e) {
                            Log.e("TAG", "Proof exception", e);
                            response_buffer = "INVALID".getBytes();
                            sendEmptyMessage(4);
                            os.write(common.encrypt(response_buffer, response_buffer.length));
                        }
                    }
                    else if (request.startsWith("jwt")) {
                        response_buffer = jwt_buffer;
                        Log.d("TAG", "Responding to GETS");
                        os.write(common.encrypt(response_buffer, response_buffer.length));
                    }
                }
            } catch(IOException e) {
                Log.d("TAG", "Bluetooth Read/Write error, or socket is closed.");
            }
        } catch (IOException e) {
            Log.e("TAG", "Connection failed.", e);
            sendEmptyMessage(2);
        }

        // Close ServerSocket listener
        instance = null; // New Server can be created.
        Log.d("TAG", "Closing ServerSocket");
        try {
            serverSocket.close();
        } catch(IOException e) {
            Log.e("TAG", "server close failed");
        }
        common.close();
    }
}
