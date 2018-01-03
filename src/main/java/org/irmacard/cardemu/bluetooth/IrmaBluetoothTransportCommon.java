package org.irmacard.cardemu.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.BufferOverflowException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

/**
 * Created by neonlight on 24-11-17.
 */

public class IrmaBluetoothTransportCommon {
    public static final UUID IRMA_UUID = UUID.fromString("c7986f0a-3154-4dc9-b19c-a5e713bb1737"); //TODO: choose UUID (this is random generated)
    private static final long TIMEOUT = 3000;                      // network I/O timeout in milliseconds.
    private static final int BUFFER_SIZE = 1024;                   // nr bytes the buffer should hold
    private static final int PACKET_SIZE = 4096;                   // maximum size of the received object
    private BluetoothSocket socket;
    private InputStream is;
    private OutputStream os;
    private SecretKey key;
    private Gson gson;

    public enum Type {
        POST_PROOFLIST,
        PROOF_RESULT_STATUS,
        GET_JWT,
        PROOFREQUEST
    }

    public IrmaBluetoothTransportCommon(@NonNull SecretKey key, @NonNull BluetoothSocket socket) {
        this.key = key;
        this.socket = socket;
        this.is = null;
        this.os = null;
        try {
            this.is = socket.getInputStream();
            this.os = socket.getOutputStream();
        } catch(IOException e) {
            Log.e("TAG", "I/O could not be opened", e);
        }
        this.gson = GsonUtil.getGson();
    }

    public boolean connected() {
        return this.socket.isConnected();
    }

    // TODO: possibly a One Time Pad? how to initialise AES?
    static public SecretKey generateSessionKey() {
        SecretKey secretKey = null;
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128); // for example
            secretKey = keyGen.generateKey();
        } catch(NoSuchAlgorithmException e) {
            Log.e("TAG", "NoSuchAlgorithm", e);
        }
        return secretKey;
    }

    private void encrypt(byte[] bytes, int start, int nr_bytes) {

    }

    private void decrypt(byte[] bytes, int start,  int nr_bytes) {

    }

    private boolean unbond(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * Close the socket, and UNBOND IT!
     */
    public void close() {
        BluetoothDevice tmp = null;
        try {
            tmp = socket.getRemoteDevice();
            socket.close();
        } catch( IOException | NullPointerException e) {
            Log.d("TAG", "Connection closed failed, socket already closed?");
        }
        Log.d("TAG", "Closed");
        if(unbond(tmp)) Log.d("TAG", "closing and unbonding succesfull");
    }

    /**
     * Keep Busy-Waiting on the stream for it to show up with data, then take that data.
     * Return it to be processed.
     * @param buffer the buffer in which to place the data.
     * @return the number of bytes that have been read.
     * @throws IOException in case the InputStream is unavailable, or broken.
     * @throws TimeoutException in case it took too long.
     */
    private int readPacket(byte[] buffer) throws IOException, TimeoutException{
        long time = System.currentTimeMillis() + TIMEOUT;
        while(System.currentTimeMillis() < time && is.available() < 1) ;
        if(is.available() > 0) {
            return is.read(buffer);
        }
        throw new TimeoutException("The reading failed");
    }

    /**
     * Keep reading packets until you can reconstruct it to an object.
     * The first two bytes received contain the length of the data.
     * @return the byte array containing the decrypted object
     * @throws TimeoutException in case it took to long to read the object
     * @throws IOException in case the channel is broken.
     */
    private byte[] readObject() throws TimeoutException, IOException{
        byte[] buffer = new byte[BUFFER_SIZE]; byte[] result = new byte[PACKET_SIZE];
        int nr_bytes = readPacket(buffer);
        if (nr_bytes < 4) { return null; }

        // Parse packet size field
        final int payload_length = ((buffer[0] & 0xff) << 8) | (buffer[1] & 0xff);
        int payload_size = nr_bytes - 4;

        // Start processing.
        System.arraycopy(buffer, 2, result, 0, nr_bytes - 2);
        boolean done = payload_size == payload_length;

        // Continue to readObject if packet is not yet complete.
        while (!done) {
            nr_bytes = readPacket(buffer);
            if (payload_size + nr_bytes > PACKET_SIZE) {
                throw new BufferOverflowException();
            }

            System.arraycopy(buffer, 0, result, payload_size + 2, nr_bytes);
            payload_size += nr_bytes;
            done = payload_size == payload_length;
        }
        decrypt(result, 2, payload_length);
        return Arrays.copyOfRange(result, 0, payload_length + 2);
    }

    /**
     * The IRMA object is to be returned:
     *  - DisclosureProofRequest
     *  - JwtSessionRequest
     *  - ProofList
     *  - DisclosureProofResult.Status
     *  etc. etc. etc.
     * @return an object that could be any of the above class.
     */
    public Object read() {
        try {
            byte[] bytes = null;
            while(bytes == null) {
                bytes = readObject();
            }

            Type irma_type = Type.values()[((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff)];
            byte[] result = Arrays.copyOfRange(bytes, 2, bytes.length);

            switch(irma_type) {
                case POST_PROOFLIST:
                    String jwt = new String(result);
                    Log.d("TAG", "RECV: POST_PROOFLIST: " + jwt);
                    List<ProofD> proofDS = gson.fromJson(jwt, new TypeToken<ArrayList<ProofD>>(){}.getType());
                    ProofList proofList = new ProofList();
                    proofList.addAll(proofDS);
                    return proofList;
                case PROOF_RESULT_STATUS:
                    Log.d("TAG", "RECV: PROOF_RESULT_STATUS: " + new String(result));
                    return DisclosureProofResult.Status.valueOf(new String(result));
                case GET_JWT:
                    Log.d("TAG", "RECV: GET_JWT ");
                    return new RequestJwtSession();
                case PROOFREQUEST:
                    Log.d("TAG", "RECV: PROOFREQUEST: " + new String(result));
                    //TODO: build the JwtSessionRequest at server side instead of replicating at client side
                    DisclosureProofRequest request = gson.fromJson(new String(result), new TypeToken<DisclosureProofRequest>(){}.getType());
                    JwtSessionRequest req = new JwtSessionRequest(
                            getDisclosureJwt(request),
                            request.getNonce(),
                            request.getContext()
                    );
                    return req;
                default:
                    Log.d("TAG", "RECV: UNKNOWN IRMA TYPE");
                    return null;
            }
        } catch (TimeoutException | IOException e) {
            Log.e("TAG", "Timeout readObject socket", e);
            return null;
        } catch (Exception e) {
            Log.e("TAG", "Unknown object mismatch?", e);
            return null;
        }
    }

    private String getDisclosureJwt(DisclosureProofRequest dpr) {
        // Translate the object to bytes
        String result = gson.toJson(dpr);

        //TODO: fix hacky string manipulation
        result = result.substring(1, result.length() - 1);
        JwtBuilder builder = Jwts.builder();
        String jwt = builder.setPayload(
                "{\"sub\": \"verification_request\"," +
                        "\"iss\": \"bluetooth\"," +
                        "\"iat\": "+ new Date().getTime() + "," +
                        "\"sprequest\": {"+
                            "\"validity\": 60, " +
                            "\"request\": {" +
                                result +
                            "}" +
                        "}" +
                "}").compact();

        return jwt;
    }

    /**
     * Public functions to write the objects to the other end.
     * As a developer you may want to transfer the object 'MyObject'
     * 1. implement 'public boolean write(MyObject object)'
     * 2. implement 'case ??' in the 'read' function above.
     *
     * @param dpr the DisclosureProofRequest
     * @return it succeeded.
     */
    public boolean write(DisclosureProofRequest dpr) {
        return write(gson.toJson(dpr), Type.PROOFREQUEST);
    }

    public boolean write(ProofList proofList) {
        return write(gson.toJson(proofList), Type.POST_PROOFLIST);
    }

    public boolean write(DisclosureProofResult.Status status) {
        return write(status.toString().getBytes(), Type.PROOF_RESULT_STATUS);
    }

    public boolean write(String url, Type type) {
        return write(url.getBytes(), type);
    }


    /**
     * The bare level write, it writes the bytes, adds a 'size' header field, and serialises the irma type.
     * @param object the bytes representing the object to send.
     * @param irma_type the 'type' of the IRMA packet, indicates to the receiver what it is receiving.
     *                   look into the enum Type at the top of this class declaration.
     * @return it succeeded.
     */
    private boolean write(byte[] object, Type irma_type) {
        // Attach header
        int length = object.length;
        int irma_int = irma_type.ordinal();
        byte[] packet = new byte[4 + length];
        packet[0] = (byte) (length >>> 8);
        packet[1] = (byte) (length);
        packet[2] = (byte) (irma_int >>> 8);
        packet[3] = (byte) (irma_int);

        System.arraycopy(object, 0, packet, 4, length);          // set payload
        encrypt(packet, 2, packet.length - 2);

        // Send it
        Log.d("TAG", "SEND: " + new String(object));
        try {
            this.os.write(packet);
        } catch(IOException e) {
            Log.e("TAG", "Write failed", e);
            return false;
        }
        return true;
    }
}
