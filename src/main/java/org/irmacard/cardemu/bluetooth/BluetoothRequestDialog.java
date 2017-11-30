package org.irmacard.cardemu.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import com.google.gson.Gson;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import org.irmacard.api.common.AttributeDisjunction;
import org.irmacard.api.common.AttributeDisjunctionList;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.disclosure.DisclosureProofRequest;
import org.irmacard.cardemu.R;
import org.irmacard.credentials.info.AttributeIdentifier;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.UUID;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;

public class BluetoothRequestDialog extends Dialog implements IrmaBluetoothHandler {
    private BluetoothAdapter mBluetoothAdapter;
    private static final int REQUEST_COARSE_LOCATION = 3;
    public static final UUID IRMA_UUID = UUID.fromString("c7986f0a-3154-4dc9-b19c-a5e713bb1737");
    private ImageView qrImageView;
    private DisclosureProofRequest request;

    public BluetoothRequestDialog(@NonNull Activity context) {
        super(context);
        this.setOwnerActivity(context);
        setTitle("Attribute Request");
    }

    private void requestPermission() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED ) {
            ActivityCompat.requestPermissions(getOwnerActivity(),
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_COARSE_LOCATION);
        }
    }

    private void bluetoothEnabled() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            getContext().startActivity(enableBtIntent);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth);
        Button requestAttributeBtn = (Button) findViewById(R.id.request18Attribute);
        Button request21Btn = (Button) findViewById(R.id.request21Attribute);
        qrImageView = (ImageView) findViewById(R.id.qrImageView);
        // TODO: Let the user construct DisclosureProofRequest
        // TODO: Keep a list of last used DisclosureProofRequest's for ease of use.

        requestAttributeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AttributeDisjunctionList list = new AttributeDisjunctionList();
                AttributeDisjunction disjunction = new AttributeDisjunction("over18");
                disjunction.add(new AttributeIdentifier("irma-demo.MijnOverheid.ageLower.over18"));
                list.add(disjunction);
                DisclosureProofRequest proofRequest = new DisclosureProofRequest(
                        new BigInteger(64, new Random()), new BigInteger(64, new Random()), list);
                startRequest("disclosing", proofRequest);
            }
        });

        request21Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AttributeDisjunctionList list = new AttributeDisjunctionList();
                AttributeDisjunction disjunction = new AttributeDisjunction("over21");
                disjunction.add(new AttributeIdentifier("irma-demo.MijnOverheid.ageLower.over21"));
                list.add(disjunction);
                DisclosureProofRequest proofRequest = new DisclosureProofRequest(
                        new BigInteger(64, new Random()), new BigInteger(64, new Random()), list);
                startRequest("disclosing", proofRequest);
            }
        });

        bluetoothEnabled();
        requestPermission();
    }

    private SecretKey generateSessionKey() {
        SecretKey secretKey = null;
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128); // for example
            secretKey = keyGen.generateKey();
            secretKey.getEncoded();
        } catch(NoSuchAlgorithmException e) {
            Log.e("TAG", "NoSuchAlgorithm", e);
        }
        return secretKey;
    }

    private void startRequest(String sessiontype, DisclosureProofRequest request) {
        // Generate AES session key
        SecretKey secretKey;
        if((secretKey = generateSessionKey()) == null) {
            return;
        }
        String session_key = Base64.encodeToString(secretKey.getEncoded(), Base64.DEFAULT);
        Log.d("TAG", "SessionKey: " + session_key);

        // Create IrmaQr session
        String mac_address = mBluetoothAdapter.getAddress();
        String url = "irma-bluetooth://" + mac_address + "/" + session_key;
        String json = new Gson().toJson(new ClientQr("2.0", "2.2", url, sessiontype));

        // Show Qr code and Hide it when it has received connection.
        // Start the server asynchronously to wait for connection.
        this.request = request;
        IrmaBluetoothTransportServer.start(secretKey, this);
        Log.d("TAG", "Good to go, show QR");
        qrImageView.setImageBitmap(generateQrBitmap(json));
    }

    private Bitmap generateQrBitmap(String string) {
        try {
            return encodeAsBitmap(string);
        } catch(Exception e) {
            Log.d("TAG", "Writer exception");
            e.printStackTrace();
            return null;
        }
    }

    private Bitmap encodeAsBitmap(String str) throws WriterException {
        BitMatrix result;
        try {
            result = new MultiFormatWriter().encode(str,
                    BarcodeFormat.QR_CODE, 200, 200, null);
        } catch (IllegalArgumentException iae) {
            // Unsupported format
            return null;
        }
        int w = result.getWidth();
        int h = result.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                pixels[offset + x] = result.get(x, y) ? BLACK : WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, 200, 0, 0, w, h);
        return bitmap;
    }
    @Override
    public void publish(State state) {
        Log.d("TAG", "Publishing: " + state);
        switch(state) {
            case IDLE:
                break;
            case READY:
                break;
            case CONNECTED:
                qrImageView.setImageDrawable(null);
                break;
            case SUCCESS:
                qrImageView.setImageResource(android.R.drawable.btn_star_big_on);
                break;
            case DISCONNECTED:
            case FAIL:
                qrImageView.setImageResource(android.R.drawable.ic_menu_delete);
                break;
            default:
                break;
        }
    }

    @Override
    public DisclosureProofRequest getDisclosureProofRequest() {
        return this.request;
    }
}
