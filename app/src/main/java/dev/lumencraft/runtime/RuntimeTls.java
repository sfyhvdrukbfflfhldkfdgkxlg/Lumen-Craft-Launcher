package dev.lumencraft.runtime;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

/** A per-connection CA fallback for legacy Android trust stores on two GitHub download origins. */
final class RuntimeTls {
    private RuntimeTls() {}
    static HttpsURLConnection open(Context c, URL url) throws IOException {
        HttpsURLConnection system = connection(url);
        try {
            system.getResponseCode();
            return system;
        } catch (SSLHandshakeException handshake) {
            system.disconnect();
            String[] roots;
            if (url.getPort()!=-1 && url.getPort()!=443) throw handshake;
            if (url.getHost().equalsIgnoreCase("github.com"))
                roots = new String[]{"SectigoPublicServerAuthenticationRootE46","SectigoPublicServerAuthenticationRootR46"};
            else if (url.getHost().equalsIgnoreCase("release-assets.githubusercontent.com"))
                roots = new String[]{"ISRGRootX1"};
            else throw handshake;
            HttpsURLConnection scoped = connection(url);
            try {
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(null,null);
                CertificateFactory certificates = CertificateFactory.getInstance("X.509");
                for (String name : roots) {
                    try (InputStream in=c.getAssets().open("tls/"+name+".pem")) {
                        X509Certificate anchor=(X509Certificate)certificates.generateCertificate(in);
                        anchor.checkValidity();
                        store.setCertificateEntry(name,anchor);
                    }
                }
                TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(store);
                SSLContext tls=SSLContext.getInstance("TLS");
                tls.init(null,tmf.getTrustManagers(),null);
                scoped.setSSLSocketFactory(tls.getSocketFactory());
                scoped.getResponseCode();
                return scoped;
            } catch (GeneralSecurityException e) { scoped.disconnect(); throw new IOException("无法验证运行时下载证书",e); }
            catch (IOException e) { scoped.disconnect(); throw e; }
        } catch (IOException e) { system.disconnect(); throw e; }
    }
    private static HttpsURLConnection connection(URL url) throws IOException {
        HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
        c.setRequestProperty("User-Agent","LumenCraft/Alpha1.0.0");
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000); c.setReadTimeout(45000);
        return c;
    }
}
