package org.irmacard.cardemu;

/**
 * Exception class for HttpClient.
 */
public class HttpClientException extends Exception {
	private static final long serialVersionUID = -3138843561169048744L;

	public int status = 0;
	public Throwable cause;

	@Override
	public String toString() {
		return "HttpClientException{" +
				"status=" + status +
				", cause=" + cause +
				'}';
	}

	public HttpClientException(Throwable cause) {
		super(cause);
		this.cause = cause;
	}

	public HttpClientException(int status, String message) {
		super(message);
		this.status = status;
	}
}
