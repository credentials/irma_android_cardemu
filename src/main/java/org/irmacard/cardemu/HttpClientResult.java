package org.irmacard.cardemu;

/**
 * Simple generic class to store the result from using the {@link HttpClient}: either an object
 * or a {@link HttpClientException}.
 * @param <T> Type of the object to store
 */
public class HttpClientResult<T> {
	private T object;
	private HttpClientException exception;

	public HttpClientResult(T object) {
		this.object = object;
	}

	public HttpClientResult(HttpClientException exception) {
		this.exception = exception;
	}

	public T getObject() {
		return object;
	}

	public HttpClientException getException() {
		return exception;
	}
}
