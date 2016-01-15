package org.irmacard.cardemu;

/**
 * Simple generic class to store the result from using the {@link HttpClient}: either an object
 * or a {@link org.irmacard.cardemu.HttpClient.HttpClientException}.
 * @param <T> Type of the object to store
 */
public class HttpClientResult<T> {
	private T object;
	private HttpClient.HttpClientException exception;

	public HttpClientResult(T object) {
		this.object = object;
	}

	public HttpClientResult(HttpClient.HttpClientException exception) {
		this.exception = exception;
	}

	public T getObject() {
		return object;
	}

	public HttpClient.HttpClientException getException() {
		return exception;
	}
}
