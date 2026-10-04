package salt

import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Local certificate authority. The CA lives in [dir]/ca.p12 (created on first use) and signs one
 * short-lived leaf per intercepted host. The device must trust [caPem] for HTTPS decryption to work.
 */
class Mitm(private val dir: File) {
    private val password = "salt".toCharArray() // protects nothing real: the file never leaves this machine
    private val p12 = File(dir, "ca.p12")
    val caPem = File(dir, "ca.pem")

    private val ca: Pair<KeyPair, X509Certificate> by lazy { loadOrCreateCa() }
    private val leafKeys: KeyPair by lazy { newKeyPair() } // one RSA key for all leaves: generating per host is slow
    private val contexts = ConcurrentHashMap<String, SSLContext>()

    /** Creates the CA (and ca.pem) if missing. */
    fun ensureCa() { ca }

    fun contextFor(host: String): SSLContext = contexts.computeIfAbsent(host) { h ->
        val (caKeys, caCert) = ca
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("leaf", leafKeys.private, password, arrayOf(leaf(h, caKeys, caCert), caCert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password) }
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun leaf(host: String, caKeys: KeyPair, caCert: X509Certificate): X509Certificate {
        val isIp = host.all { it.isDigit() || it == '.' } || ':' in host
        val san = GeneralNames(GeneralName(if (isIp) GeneralName.iPAddress else GeneralName.dNSName, host))
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            caCert, BigInteger(64, SecureRandom()), Date(now - DAY), Date(now + 365 * DAY), // Android rejects > 398 days
            X500Principal("CN=$host"), leafKeys.public,
        ).addExtension(Extension.subjectAlternativeName, false, san)
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder(SIG).build(caKeys.private)))
    }

    private fun loadOrCreateCa(): Pair<KeyPair, X509Certificate> {
        dir.mkdirs()
        if (p12.exists()) {
            val ks = KeyStore.getInstance("PKCS12").apply { p12.inputStream().use { load(it, password) } }
            val cert = ks.getCertificate("ca") as X509Certificate
            return KeyPair(cert.publicKey, ks.getKey("ca", password) as java.security.PrivateKey) to cert
        }
        val keys = newKeyPair()
        val name = X500Principal("CN=Salt Dev Proxy CA")
        val now = System.currentTimeMillis()
        val cert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - DAY), Date(now + 3650 * DAY), name, keys.public)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
                .build(JcaContentSignerBuilder(SIG).build(keys.private)),
        )
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("ca", keys.private, password, arrayOf(cert))
            p12.outputStream().use { store(it, password) }
        }
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        caPem.writeText("-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n")
        return keys to cert
    }

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    private companion object {
        const val SIG = "SHA256withRSA"
        const val DAY = 86_400_000L
    }
}
