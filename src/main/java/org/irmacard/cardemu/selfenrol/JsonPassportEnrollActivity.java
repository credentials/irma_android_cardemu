package org.irmacard.cardemu.selfenrol;

import android.os.Handler;
import android.os.Message;
import org.irmacard.api.common.ClientQr;
import org.irmacard.api.common.exceptions.ApiErrorMessage;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.cardemu.R;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.cardemu.protocols.Protocol;
import org.irmacard.cardemu.protocols.ProtocolHandler;
import org.irmacard.mno.common.PassportVerificationResult;
import org.irmacard.mno.common.PassportVerificationResultMessage;

public class JsonPassportEnrollActivity extends PassportEnrollActivity {
	private Handler uiHandler;
	private Message msg;

	private ProtocolHandler protocolHandler = new ProtocolHandler(this) {
		@Override public void onStatusUpdate(Action action, Status status) {} // Not interested
		@Override public void onCancelled(Action action) {
			finish();
		}
		@Override public void onSuccess(Action action) {
			done();
		}
		@Override public void onFailure(Action action, String message, ApiErrorMessage error) {
			if (error != null)
				fail(error);
			else
				fail(R.string.error_enroll_issuing_failed);
		}
	};

	private void fail(int resource, Exception e) {
		if (e != null)
			msg.obj = e;
		else
			msg.obj = new Exception();
		msg.what = resource;
		uiHandler.sendMessage(msg);
	}

	private void fail(int resource) {
		fail(resource, null);
	}

	private void fail(ApiErrorMessage msg) {
		fail(msg.getError().ordinal(), null); // TODO improve
	}

	private void fail(Exception e) {
		fail(R.string.error_enroll_cantconnect, e); // TODO improve
	}

	private void done() {
		uiHandler.sendMessage(msg);
	}

	@Override
	protected void enroll(final Handler uiHandler) {
		final String serverUrl = getEnrollmentServer();
		this.uiHandler = uiHandler;
		this.msg = Message.obtain();

		// Send our passport message to the enroll server; if it accepts, perform an issuing
		// session with the issuing API server that the enroll server returns
		client.post(PassportVerificationResultMessage.class, serverUrl + "/verify-passport",
				passportMsg, new JsonResultHandler<PassportVerificationResultMessage>() {
			@Override public void onSuccess(PassportVerificationResultMessage result) {
				if (result.getResult() != PassportVerificationResult.SUCCESS) {
					fail(R.string.error_enroll_passportrejected);
					return;
				}

				ClientQr qr = result.getIssueQr();
				if (qr == null || qr.getVersion() == null || qr.getVersion().length() == 0
						|| qr.getUrl() == null || qr.getUrl().length() == 0) {
					fail(R.string.error_enroll_invalidresponse);
					return;
				}

				Protocol.NewSession(result.getIssueQr(), null, protocolHandler);
			}
		});
	}

	// TODO reuse from JsonProtocol.java
	private abstract class JsonResultHandler<T> implements HttpResultHandler<T> {
		@Override
		public void onError(HttpClientException exception) {
			try {
				ApiErrorMessage msg = GsonUtil.getGson().fromJson(exception.getMessage(), ApiErrorMessage.class);
				fail(msg);
			} catch (Exception e) {
				fail(exception);
			}
		}
	}
}
