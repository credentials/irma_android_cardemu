package org.irmacard.cardemu;

import android.util.Log;

import com.google.api.client.util.IOUtils;

import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.AttributeDisjunctionList;
import org.irmacard.api.common.JwtSessionRequest;
import org.irmacard.api.common.disclosure.DisclosureProofRequest;
import org.irmacard.cardemu.bluetooth.IrmaBluetoothTransportCommon;
import org.irmacard.credentials.info.AttributeIdentifier;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.SecretKey;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by neonlight on 3-1-18.
 */

public class IrmaBluetoothUnitTest {

    @Test
    public void encryptBeforeDecryptIsValid() {
        byte[] b1 = new byte[256];
        byte[] b2 = new byte[256];

        SecretKey key = IrmaBluetoothTransportCommon.generateSessionKey();
        IrmaBluetoothTransportCommon common = new IrmaBluetoothTransportCommon(key, null);

        new Random().nextBytes(b1);
        System.arraycopy(b1, 0, b2, 0, 128);
        b2 = common.encrypt(b1);
        b2 = common.decrypt(b2);

        assertTrue(Arrays.equals(b1,b2));
    }

    @Test
    public void twoDifferentKeys() {
        SecretKey key = IrmaBluetoothTransportCommon.generateSessionKey();
        SecretKey key2 = IrmaBluetoothTransportCommon.generateSessionKey();

        assertFalse(Arrays.equals(key.getEncoded(), key2.getEncoded()));
    }

    @Test
    public void multipleEncryptionsDecryptions() {
        byte[] b1 = new byte[256];
        byte[] b2 = new byte[256];
        boolean check = true;

        SecretKey key = IrmaBluetoothTransportCommon.generateSessionKey();
        IrmaBluetoothTransportCommon common = new IrmaBluetoothTransportCommon(key, null);

        for(int i = 0; i < 5; i++) {
            new Random().nextBytes(b1);
            System.arraycopy(b1, 0, b2, 0, 128);
            b2 = common.encrypt(b1);
            b2 = common.decrypt(b2);
            check = check && Arrays.equals(b1,b2);
        }
        assertTrue(check);
    }

    @Test
    public void writeDisclosureProofRequestTest() {
        // Create Object to send
        AttributeDisjunctionList list = new AttributeDisjunctionList();
        AttributeDisjunction disjunction = new AttributeDisjunction("over21");
        disjunction.add(new AttributeIdentifier("irma-demo.MijnOverheid.ageLower.over21"));
        list.add(disjunction);
        DisclosureProofRequest disclosureProofRequest = new DisclosureProofRequest(new BigInteger(128, new Random()),
                new BigInteger(128, new Random()), list);

        // Prepare Transport
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        IrmaBluetoothTransportCommon common = new IrmaBluetoothTransportCommon(IrmaBluetoothTransportCommon.generateSessionKey(), null);

        // Write object
        common.setInputOutputStreams(null, os);
        common.write(disclosureProofRequest);

        // Translate object to InputStream
        byte[] output = os.toByteArray();
        ByteArrayInputStream is = new ByteArrayInputStream(output);

        // Read object
        common.setInputOutputStreams(is, os);
        Object o = common.read();
        if(o instanceof JwtSessionRequest) {
            JwtSessionRequest jwtSessionRequest = (JwtSessionRequest) o;
            assertTrue(jwtSessionRequest.getNonce().equals(disclosureProofRequest.getNonce()) &&
                            jwtSessionRequest.getContext().equals(disclosureProofRequest.getContext()));
        } else {
            assertFalse(true);
        }
    }
}
