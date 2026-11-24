package com.displayoff.test;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.io.*;
import java.math.BigInteger;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class LocalAdb extends AbsAdbConnectionManager {
    private final PrivateKey key;
    private final Certificate cert;
    LocalAdb(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setHostAddress("127.0.0.1");
        setTimeout(20, TimeUnit.SECONDS);
        File f = new File(context.getNoBackupFilesDir(), "adb-identity.bin");
        android.util.AtomicFile af = new android.util.AtomicFile(f);
        if (f.exists()) {
            try (DataInputStream in = new DataInputStream(af.openRead())) {
                int n = in.readInt();
                if (n < 1 || n > 8192) throw new IOException("Invalid key");
                byte[] k = new byte[n]; in.readFully(k);
                key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(k));
                cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair p = g.generateKeyPair();
            key = p.getPrivate();
            long t = System.currentTimeMillis();
            X500Name subject = new X500Name("CN=DisplayOffTest");
            byte[] der = new JcaX509v3CertificateBuilder(subject,
                    new BigInteger(120, new SecureRandom()).add(BigInteger.ONE),
                    new Date(t-86400000L), new Date(t+315360000000L),
                    subject, p.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(key)).getEncoded();
            cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            FileOutputStream out = af.startWrite();
            try {
                DataOutputStream data = new DataOutputStream(out);
                byte[] k = key.getEncoded();
                data.writeInt(k.length); data.write(k); data.write(der); data.flush();
                af.finishWrite(out);
            } catch (Exception e) { af.failWrite(out); throw e; }
        }
    }
    @Override protected PrivateKey getPrivateKey() { return key; }
    @Override protected Certificate getCertificate() { return cert; }
    @Override protected String getDeviceName() { return "DisplayOffTest"; }
}
