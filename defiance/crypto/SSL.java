package defiance.crypto;

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.RFC4519Style;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.bc.BcX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.Date;

public class SSL
{
    public static final String AUTH = "RSA";
    public static final int RSA_KEY_SIZE = 4096;

    public static KeyStore getKeyStore(char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException, OperatorCreationException
    {
        // never store certificates to disk, if program is restarted, we generate a new certificate and rejoin network
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, password);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
        kpg.initialize(RSA_KEY_SIZE);
        KeyPair keypair = kpg.generateKeyPair();
        PrivateKey myPrivateKey = keypair.getPrivate();
        java.security.PublicKey myPublicKey = keypair.getPublic();

//        X509CertInfo info = new X509CertInfo();
        Date from = new Date();
        Date to = new Date(from.getTime() + 365 * 86400000l);
//        CertificateValidity interval = new CertificateValidity(from, to);
//        info.set(X509CertInfo.VALIDITY, interval);
        BigInteger sn = new BigInteger(64, new SecureRandom());
//        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));
//
//        X500Name owner = new X500Name("EMAIL=hello.NSA.GCHQ.ASIO@goodluck.com");
//        info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
//        info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
//        info.set(X509CertInfo.KEY, new CertificateX509Key(keypair.getPublic()));
//        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
//        AlgorithmId algo = new AlgorithmId(AlgorithmId.SHA512_oid);
//        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));

        X500NameBuilder builder = new X500NameBuilder(RFC4519Style.INSTANCE);
        builder.addRDN(RFC4519Style.c, "AU");
        builder.addRDN(RFC4519Style.o, "Peergos");
        builder.addRDN(RFC4519Style.l, "Melbourne");
        builder.addRDN(RFC4519Style.st, "Victoria");
        builder.addRDN(PKCSObjectIdentifiers.pkcs_9_at_emailAddress, "hello.NSA.GCHQ.ASIO@goodluck.com");

        ContentSigner sigGen = new JcaContentSignerBuilder("SHA256withRSA").build(myPrivateKey);
        X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(builder.build(), sn, from, to, builder.build(), myPublicKey);

        X509CertificateHolder certHolder = certGen.build(sigGen);

        // Sign the cert to identify the algorithm that's used.
//        X509CertImpl cert = new X509CertImpl(info);
//        cert.sign(myPrivateKey, "MD5WithRSA");

        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        Certificate cert = converter.getCertificate(certHolder);

        ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
        ks.store(new FileOutputStream("sslkeystore"), password);
        return ks;
    }
}