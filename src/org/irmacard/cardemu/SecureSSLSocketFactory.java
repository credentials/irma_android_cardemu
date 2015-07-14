package org.irmacard.cardemu;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.*;

/**
 * Socket factory for configuring the cipher list
 * Loosely based on http://stackoverflow.com/a/16714084
 * and http://stackoverflow.com/a/23365536
 */
public class SecureSSLSocketFactory extends SSLSocketFactory
{
	private SSLSocketFactory factory;

	private String[] ciphers;
	private String[] protocols;

	public SecureSSLSocketFactory(SSLSocketFactory delegate) {
		this.factory = delegate;
		protocols = GetProtocolList();
		ciphers = GetCipherList();
	}

	@Override
	public String[] getDefaultCipherSuites() {
		return ciphers;
	}

	@Override
	public String[] getSupportedCipherSuites() {
		return ciphers;
	}

	public String[] getDefaultProtocols() {
		return protocols;
	}

	public String[] getSupportedProtocols() {
		return protocols;
	}

	@Override
	public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
		SSLSocket ss = (SSLSocket)factory.createSocket(s, host, port, autoClose);

		ss.setEnabledProtocols(protocols);
		ss.setEnabledCipherSuites(ciphers);

		return ss;
	}

	@Override
	public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
		SSLSocket s = (SSLSocket)factory.createSocket(address, port, localAddress, localPort);

		s.setEnabledProtocols(protocols);
		s.setEnabledCipherSuites(ciphers);

		return s;
	}

	@Override
	public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
		SSLSocket s = (SSLSocket)factory.createSocket(host, port, localHost, localPort);

		s.setEnabledProtocols(protocols);
		s.setEnabledCipherSuites(ciphers);

		return s;
	}

	@Override
	public Socket createSocket(InetAddress host, int port) throws IOException {
		SSLSocket s = (SSLSocket)factory.createSocket(host, port);

		s.setEnabledProtocols(protocols);
		s.setEnabledCipherSuites(ciphers);

		return s;
	}

	@Override
	public Socket createSocket(String host, int port) throws IOException {
		SSLSocket s = (SSLSocket)factory.createSocket(host, port);

		s.setEnabledProtocols(protocols);
		s.setEnabledCipherSuites(ciphers);

		return s;
	}

	protected String[] GetProtocolList() {
		List<String> preferredProtocols = Arrays.asList("TLSv1", "TLSv1.1", "TLSv1.2");
		List<String> availableProtocols = null;

		SSLSocket socket = null;

		try {
			socket = (SSLSocket)factory.createSocket();
			availableProtocols = Arrays.asList(socket.getSupportedProtocols());
			Collections.sort(availableProtocols);
		} catch(IOException e) {
			return new String[]{ "TLSv1" };
		} finally {
			try {
				if (socket != null)
					socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		List<String> aa = new ArrayList<String>();
		for (String protocol: preferredProtocols)
			if (availableProtocols.contains(protocol))
				aa.add(protocol);

		return aa.toArray(new String[aa.size()]);
	}

	protected String[] GetCipherList() {
		List<String> preferredCiphers = Arrays.asList(
				// TLS v1.2 and below
				"TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
				"TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
				"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
				"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",

				"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
				"TLS_DHE_DSS_WITH_AES_256_GCM_SHA384",
				"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
				"TLS_DHE_DSS_WITH_AES_128_GCM_SHA256",

				// TLS v1.0 (with some SSLv3 interop)
				"TLS_DHE_RSA_WITH_AES_256_CBC_SHA384",
				"TLS_DHE_DSS_WITH_AES_256_CBC_SHA256",
				"TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
				"TLS_DHE_DSS_WITH_AES_128_CBC_SHA",

				"TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA",
				"TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA",
				"SSL_DH_RSA_WITH_3DES_EDE_CBC_SHA",
				"SSL_DH_DSS_WITH_3DES_EDE_CBC_SHA"
		);

		List<String> availableCiphers = Arrays.asList(factory.getSupportedCipherSuites());
		Collections.sort(availableCiphers);

		List<String> aa = new ArrayList<String>();
		for (String cipher: preferredCiphers)
			if (availableCiphers.contains(cipher))
				aa.add(cipher);

		aa.add("TLS_EMPTY_RENEGOTIATION_INFO_SCSV");

		return aa.toArray(new String[aa.size()]);
	}
}
