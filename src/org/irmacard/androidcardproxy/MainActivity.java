package org.irmacard.androidcardproxy;

import java.io.UnsupportedEncodingException;

import net.sourceforge.scuba.smartcards.CardServiceException;
import net.sourceforge.scuba.smartcards.IsoDepCardService;

import org.apache.http.entity.StringEntity;
import org.irmacard.androidcardproxy.ConfirmationDialogFragment.ConfirmationDialogListener;
import org.irmacard.androidcardproxy.EnterPINDialogFragment.PINDialogListener;
import org.json.JSONException;

import service.IdemixService;
import service.ProtocolCommand;
import service.ProtocolResponse;
import service.ProtocolResponses;
import android.app.Activity;
import android.app.DialogFragment;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.JsonHttpResponseHandler;


public class MainActivity extends Activity implements PINDialogListener, ConfirmationDialogListener {
	private String TAG = "CardProxyMainActivity";
	private NfcAdapter nfcA;
	private PendingIntent mPendingIntent;
	private IntentFilter[] mFilters;
	private String[][] mTechLists;

	
	
	// State variables
	private IsoDep lastTag = null;
	private ProtocolStep lastCommandSet = null;
	private boolean tagReadyForProcessing = false;
	private boolean confirmationGiven = false;
	private boolean pinSet = false;
	private String pinCode = null;

	private void resetState() {
		confirmationGiven = false;
		pinSet = false;
		pinCode = null;
	}
	
	
	private int activityState = STATE_WAITING;
	
	private static final int STATE_WAITING = 0;
	private static final int STATE_CHECKING = 1;
	private static final int STATE_RESULT_OK = 2;
	private static final int STATE_RESULT_MISSING = 3;
	private static final int STATE_RESULT_WARNING = 4;
	private static final int STATE_IDLE = 10;
	
	private CountDownTimer cdt = null;
	
	private static final int WAITTIME = 6000; // Time until the status jumps back to STATE_WAITING
	
	private void setState(int state) {
    	Log.i(TAG,"Set state: " + state);
    	activityState = state;
    	int imageResource = 0;
    	int statusTextResource = 0;
    	switch (activityState) {
    	case STATE_WAITING:
    		imageResource = R.drawable.irma_icon_place_card_520px;
    		statusTextResource = R.string.status_waiting;    		
    		break;
		case STATE_CHECKING:
			imageResource = R.drawable.irma_icon_card_found_520px;
			statusTextResource = R.string.status_checking;
			break;
		case STATE_RESULT_OK:
			imageResource = R.drawable.irma_icon_ok_520px;
			statusTextResource = R.string.status_ok;
			break;
		case STATE_RESULT_MISSING:
			imageResource = R.drawable.irma_icon_missing_520px;
			statusTextResource = R.string.status_missing;
			break;
		case STATE_RESULT_WARNING:
			imageResource = R.drawable.irma_icon_warning_520px;
			statusTextResource = R.string.status_warning;
			break;
		case STATE_IDLE:
			imageResource = R.drawable.irma_icon_place_card_520px;
			statusTextResource = R.string.status_idle;
			lastTag = null;
		default:
			break;
		}
    	
    	if (activityState == STATE_RESULT_OK ||
    			activityState == STATE_RESULT_MISSING || 
    			activityState == STATE_RESULT_WARNING) {
        	if (cdt != null) {
        		cdt.cancel();
        	}
        	cdt = new CountDownTimer(WAITTIME, 100) {

        	     public void onTick(long millisUntilFinished) {

        	     }

        	     public void onFinish() {
        	    	 if (activityState != STATE_CHECKING) {
        	    		 setState(STATE_IDLE);
        	    	 }
        	     }
        	  }.start();
    	}
    	
    	((TextView)findViewById(R.id.statustext)).setText(statusTextResource);
		((ImageView)findViewById(R.id.statusimage)).setImageResource(imageResource);
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
        // NFC stuff
        nfcA = NfcAdapter.getDefaultAdapter(getApplicationContext());
        mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        // Setup an intent filter for all TECH based dispatches
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        mFilters = new IntentFilter[] { tech };

        // Setup a tech list for all IsoDep cards
        mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };
		
	    setState(STATE_IDLE);

	}

	@Override
	protected void onPause() {
		super.onPause();
    	if (nfcA != null) {
    		nfcA.disableForegroundDispatch(this);
    	}
	}
	@Override
	protected void onResume() {
        super.onResume();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(getIntent().getAction())) {
            processIntent(getIntent());
        }        
        if (nfcA != null) {
        	nfcA.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
	}
	
	public void onMainTouch(View v) {
		// test code
//		askForPIN();
		if (activityState == STATE_IDLE) {
			lastTag = null;
			startQRScanner("Scan the QR image in the browser.");
		}
	}
	
    @Override
    public void onNewIntent(Intent intent) {
        tagReadyForProcessing = true;
        setIntent(intent);
    }
    
    public void processIntent(Intent intent) {
        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    	IsoDep tag = IsoDep.get(tagFromIntent);
    	if (tag != null) {
    		lastTag = tag;
    		tryNextStep();
    	}    	
    }
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		IntentResult scanResult = IntentIntegrator
				.parseActivityResult(requestCode, resultCode, data);

		// Process the results from the QR-scanning activity
		if (scanResult != null) {
			String contents = scanResult.getContents();
			if (contents != null) {
				doInitialRequest(contents);
			}
		}
	}
	
	public void doInitialRequest(String startURL) {

		AsyncHttpClient client = new AsyncHttpClient();
		try {
			client.post(this, startURL, new StringEntity("") , "application/json",  new AsyncHttpResponseHandler() {
			    @Override
			    public void onSuccess(String response) {
					Gson gson = new GsonBuilder().
							setPrettyPrinting().
							registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer()).
							create();
					lastCommandSet = gson.fromJson(response,ProtocolStep.class);
					setState(STATE_WAITING);
					tryNextStep();
			    }
			    @Override
			    public void onFailure(Throwable arg0, String arg1) {
			    	// TODO: feedback to user that command failed!
			    	Log.e(TAG, "Failure: " + arg1);
				}
			});
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

	}

	
	public void tryNextStep() {
		Log.i(TAG,"Try next step.");
		if (lastCommandSet != null && lastCommandSet.askConfirmation && !confirmationGiven) {
			askConfirmation(lastCommandSet.confirmationMessage);
		} else if (lastCommandSet != null && lastCommandSet.usePIN && !pinSet) {
			askForPIN();
		} else if (activityState == STATE_WAITING && lastTag != null && tagReadyForProcessing) {
			Log.i(TAG,"Trying to execute card commands.");
			setState(STATE_CHECKING);
			
			new ProcessCommandSet().execute(new SmartcardProxyInput(lastCommandSet, lastTag, pinCode));
		}
	}
	
	public void askForPIN() {
		DialogFragment newFragment = new EnterPINDialogFragment();
	    newFragment.show(getFragmentManager(), "pinentry");
	}
	
	public void askConfirmation(String message) {
		DialogFragment newFragment = ConfirmationDialogFragment.newInstance(message);
		newFragment.show(getFragmentManager(), "confirmation");
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}
	
	public void startQRScanner(String message) {
		IntentIntegrator integrator = new IntentIntegrator(this);
    	integrator.initiateScan(IntentIntegrator.QR_CODE_TYPES, message);
	}
	
	private class SmartcardProxyInput {
		public ProtocolStep cs;
		public IsoDep tag;
		public String pincode;
		SmartcardProxyInput(ProtocolStep cs, IsoDep tag, String pincode) {
			this.cs = cs;
			this.tag = tag;
			this.pincode = pincode;
		}
	}
	
	private class SmartcardProxyOutput {
		public ProtocolStep cs;
		public ProtocolResponses responses;
	}
	
	public static byte[] string2bytepin(String pincode) {
		byte[] result = new byte[pincode.length()];
		for (int i = 0; i < pincode.length(); i++) {
			result[i] = (byte)(pincode.charAt(i));
		}
		return result;
	}
	
	private class ProcessCommandSet extends AsyncTask<SmartcardProxyInput, Void, SmartcardProxyOutput> {

		@Override
		protected SmartcardProxyOutput doInBackground(SmartcardProxyInput... params) {
			IsoDep tag = params[0].tag;
			ProtocolStep cs = params[0].cs;
			String pincode = params[0].pincode;
			
			// Make sure time-out is long enough (10 seconds)
			tag.setTimeout(10000);
			
			IdemixService is = new IdemixService(new IsoDepCardService(tag));
			
			SmartcardProxyOutput smartcardOutput = new SmartcardProxyOutput();
			smartcardOutput.cs = cs;
			smartcardOutput.responses = new ProtocolResponses();
			try {
				if (!is.isOpen()) {
					is.open();
				}
				if (pincode != null) {
					is.sendPin(string2bytepin(pincode));
				}
				
				for (ProtocolCommand c : cs.commands) {
					smartcardOutput.responses.put(c.getKey(), 
							new ProtocolResponse(c.getKey(), is.transmit(c.getAPDU())));
				}
//				is.close(); 
			} catch (CardServiceException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			return smartcardOutput;
		}
		

		
		@Override
		protected void onPostExecute(SmartcardProxyOutput result) {
			// This is executed in the main UI thread
			Gson gson = new GsonBuilder().
					setPrettyPrinting().
					registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer()).
					create();
			
			String httpresult = gson.toJson(result.responses);
			Log.i(TAG,"Sending card responses to " + result.cs.responseurl);
			AsyncHttpClient client = new AsyncHttpClient();
			try {
				client.post(MainActivity.this, result.cs.responseurl, new StringEntity(httpresult) , "application/json",  new AsyncHttpResponseHandler() {
					@Override
					public void onSuccess(String response) {
						Log.i(TAG,response);
						Gson gson = new GsonBuilder().
								setPrettyPrinting().
								registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer()).
								create();
						lastCommandSet = gson.fromJson(response,ProtocolStep.class);
						if (lastCommandSet.protocolDone) {
							tagReadyForProcessing = false;
							if (lastCommandSet.result != null) {
								if (lastCommandSet.result.equals("valid")) {
									setState(STATE_RESULT_OK);
								} else {
									setState(STATE_RESULT_MISSING);
								}
							} else {
								setState(STATE_RESULT_OK);
							}
						} else {
							setState(STATE_WAITING);
							tryNextStep();
							resetState();
						}
						
					}
					
				});
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
	}



	@Override
	public void onPINEntry(String dialogPincode) {
		Log.i(TAG, "PIN entered: " + dialogPincode);
		pinSet = true;
		pinCode = dialogPincode;
		tryNextStep();
	}

	@Override
	public void onPINCancel() {
		Log.i(TAG, "PIN entry canceled!");
		setState(STATE_IDLE);
		resetState();
	}

	@Override
	public void onConfirmationPositive() {
		confirmationGiven = true;
		tryNextStep();
	}

	@Override
	public void onConfirmationNegative() {
		resetState();
		setState(STATE_IDLE);
	}
	
}
