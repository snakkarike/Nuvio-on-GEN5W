package org.nuvio.gen5w.player;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Modern TLS 1.2/1.3 and Mozilla CA root bundle provider for Android 4.4 KitKat.
 * Ensures HTTPS streams from modern CDNs (Torbox, RealDebrid, Cloudflare) can be validated
 * and decrypted without failing SSL handshakes or certificate verification.
 */
public final class VideoTls {

    private static final String TAG = "VideoTls";
    private static SSLSocketFactory cachedFactory;
    private static X509TrustManager cachedTrustManager;

    private VideoTls() {}

    /**
     * SSLSocketFactory that enforces TLSv1.2 on Android 4.4 (KitKat supports TLS 1.2
     * in the OS but disables it by default on client sockets).
     */
    public static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        public Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket configure(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                ArrayList<String> protocols = new ArrayList<>();
                for (String protocol : ssl.getSupportedProtocols()) {
                    if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) {
                        protocols.add(protocol);
                    }
                }
                if (!protocols.isEmpty()) {
                    ssl.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
                }
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return configure(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return configure(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    /**
     * Builds and caches an SSLSocketFactory initialized with Mozilla CA roots from assets/cacert.pem
     * and modern TLS 1.2.
     */
    public static synchronized SSLSocketFactory build(Context context) throws Exception {
        if (cachedFactory != null) {
            return cachedFactory;
        }

        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);

        InputStream in = context.getAssets().open("cacert.pem");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
                if (out.size() > 600000) {
                    throw new IOException("Certificate bundle too large");
                }
            }
        } finally {
            in.close();
        }

        String pem = new String(out.toByteArray(), "US-ASCII");
        String start = "-----BEGIN CERTIFICATE-----";
        String finish = "-----END CERTIFICATE-----";
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int index = 0;
        int count = 0;

        while (true) {
            int first = pem.indexOf(start, index);
            if (first < 0) break;
            int last = pem.indexOf(finish, first);
            if (last < 0) break;
            String body = pem.substring(first + start.length(), last).replaceAll("\\s+", "");
            byte[] der = Base64.decode(body, Base64.DEFAULT);
            Certificate certificate = cf.generateCertificate(new ByteArrayInputStream(der));
            roots.setCertificateEntry("mozilla-" + count, certificate);
            count++;
            index = last + finish.length();
        }

        if (count < 80) {
            throw new IOException("Too few trusted Mozilla certificates in cacert.pem (" + count + ")");
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(roots);

        X509TrustManager manager = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                manager = (X509TrustManager) tm;
                break;
            }
        }

        if (manager == null) {
            throw new IOException("X509 trust manager unavailable");
        }
        cachedTrustManager = manager;

        // Try Conscrypt if available on classpath (provides modern BoringSSL ciphers)
        try {
            Class<?> conscryptClass = Class.forName("org.conscrypt.Conscrypt");
            Method isAvailable = conscryptClass.getMethod("isAvailable");
            if (Boolean.TRUE.equals(isAvailable.invoke(null))) {
                Method newProvider = conscryptClass.getMethod("newProvider");
                Provider provider = (Provider) newProvider.invoke(null);
                SSLContext modern = SSLContext.getInstance("TLS", provider);
                modern.init(null, new TrustManager[]{manager}, new SecureRandom());
                cachedFactory = new Tls12SocketFactory(modern.getSocketFactory());
                Log.i(TAG, "Initialized Conscrypt TLS with " + count + " Mozilla roots");
                return cachedFactory;
            }
        } catch (Throwable t) {
            Log.d(TAG, "Conscrypt optional provider not used: " + t.getMessage());
        }

        // Platform Android TLS 1.2 fallback
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{manager}, new SecureRandom());
        cachedFactory = new Tls12SocketFactory(ssl.getSocketFactory());
        Log.i(TAG, "Initialized Platform TLS 1.2 with " + count + " Mozilla roots");
        return cachedFactory;
    }
}

