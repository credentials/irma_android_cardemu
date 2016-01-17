package org.irmacard.cardemu.httpclient;

/**
 * A generic interface for handling the result of asynchronious POSTs or GETs from {@link HttpClient}.
 * @param <T> Type of the object that was returned by the request
 */
public interface HttpResultHandler<T> {
	/**
	 * Called when the request was successful.
	 * @param result The object that the server returned
	 */
	void onSuccess(T result);

	/**
	 * Called when the request failed.
	 * @param exception An exception containing failure information
	 */
	void onError(HttpClientException exception);
}
