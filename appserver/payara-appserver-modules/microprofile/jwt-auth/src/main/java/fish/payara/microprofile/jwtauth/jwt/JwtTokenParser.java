/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2017-2021] Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.microprofile.jwtauth.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import static com.nimbusds.jose.JWEAlgorithm.RSA_OAEP;
import com.nimbusds.jose.JWSAlgorithm;
import static com.nimbusds.jose.JWSAlgorithm.ES256;
import static com.nimbusds.jose.JWSAlgorithm.RS256;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.SignedJWT;
import fish.payara.microprofile.jwtauth.eesecurity.JWTProcessingException;
import fish.payara.microprofile.jwtauth.eesecurity.JwtPrivateKeyStore;
import fish.payara.microprofile.jwtauth.eesecurity.JwtPublicKeyStore;
import java.io.StringReader;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import static java.util.Arrays.asList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.json.Json;
import static javax.json.Json.createObjectBuilder;
import javax.json.JsonNumber;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import org.eclipse.microprofile.jwt.Claims;
import static org.eclipse.microprofile.jwt.Claims.exp;
import static org.eclipse.microprofile.jwt.Claims.iat;
import static org.eclipse.microprofile.jwt.Claims.iss;
import static org.eclipse.microprofile.jwt.Claims.jti;
import static org.eclipse.microprofile.jwt.Claims.preferred_username;
import static org.eclipse.microprofile.jwt.Claims.raw_token;
import static org.eclipse.microprofile.jwt.Claims.sub;
import static org.eclipse.microprofile.jwt.Claims.upn;

/**
 * Parses a JWT bearer token and validates it according to the MP-JWT rules.
 *
 * @author Arjan Tijms
 */
public class JwtTokenParser {

    private final static String DEFAULT_NAMESPACE = "https://payara.fish/mp-jwt/";

    // Groups no longer required since 2.0
    private final List<Claims> requiredClaims = asList(iss, sub, exp, iat, jti);

    private final boolean enableNamespacedClaims;
    private final boolean disableTypeVerification;
    private final Optional<String> customNamespace;

    private String rawToken;
    private SignedJWT signedJWT;
    private EncryptedJWT encryptedJWT;

    public JwtTokenParser(Optional<Boolean> enableNamespacedClaims, Optional<String> customNamespace, Optional<Boolean> disableTypeVerification) {
        this.enableNamespacedClaims = enableNamespacedClaims.orElse(false);
        this.disableTypeVerification = disableTypeVerification.orElse(false);
        this.customNamespace = customNamespace;
    }

    public JwtTokenParser() {
        this(Optional.empty(), Optional.empty(), Optional.empty());
    }

    public JsonWebTokenImpl parse(String bearerToken, boolean encryptionRequired, JwtPublicKeyStore publicKeyStore,
            String acceptedIssuer, JwtPrivateKeyStore privateKeyStore) throws JWTProcessingException {
        JsonWebTokenImpl jsonWebToken;
        try {
            rawToken = bearerToken;
            String keyId;

            int parts = rawToken.split("\\.", 5).length; // not interested in parts above 5
            if (parts == 3) {
                // signed JWT has 3 parts
                signedJWT = SignedJWT.parse(rawToken);

                if (!checkIsSignedJWT(signedJWT)) {
                    throw new JWTProcessingException("Not signed JWT, typ must be 'JWT'");
                }
                keyId = signedJWT.getHeader().getKeyID();
            } else {
                // encrypted JWT has 5 parts
                encryptedJWT = EncryptedJWT.parse(rawToken);

                if (!checkIsEncryptedJWT(encryptedJWT)) {
                    throw new JWTProcessingException("Not encrypted JWT, cty must be 'JWT'");
                }
                keyId = encryptedJWT.getHeader().getKeyID();
            }

            PublicKey publicKey = publicKeyStore.getPublicKey(keyId);
            // first, parse the payload of the encrypting envelope, save signedJWT
            if (encryptedJWT == null) {
                if (encryptionRequired) {
                    // see JWT Auth 1.2, Requirements for accepting signed and encrypted tokens
                    throw new JWTProcessingException("JWT expected to be encrypted, mp.jwt.decrypt.key.location was defined!");
                }
                jsonWebToken = verifyAndParseSignedJWT(acceptedIssuer, publicKey);
            } else {
                jsonWebToken = verifyAndParseEncryptedJWT(acceptedIssuer, publicKey, privateKeyStore.getPrivateKey(keyId));
            }
        } catch (JWTProcessingException | ParseException ex) {
            throw new JWTProcessingException(ex);
        }
        return jsonWebToken;
    }

    private JsonWebTokenImpl verifyAndParseSignedJWT(String issuer, PublicKey publicKey) throws JWTProcessingException {
        if (signedJWT == null) {
            throw new IllegalStateException("No parsed SignedJWT.");
        }
        JWSAlgorithm signAlgorithmName = signedJWT.getHeader().getAlgorithm();

        // 1.0 4.1 alg + MP-JWT 1.0 6.1 1
        if (!signAlgorithmName.equals(RS256)
                && !signAlgorithmName.equals(ES256)) {
            throw new JWTProcessingException("Only RS256 or ES256 algorithms supported for JWT signing, used " + signAlgorithmName);
        }

        try (JsonReader reader = Json.createReader(new StringReader(signedJWT.getPayload().toString()))) {
            Map<String, JsonValue> rawClaims = new HashMap<>(reader.readObject());

            // Vendor - Process namespaced claims
            rawClaims = handleNamespacedClaims(rawClaims);

            // MP-JWT 1.0 4.1 Minimum MP-JWT Required Claims
            if (!checkRequiredClaimsPresent(rawClaims)) {
                throw new JWTProcessingException("Not all required claims present");
            }

            // MP-JWT 1.0 4.1 upn - has fallbacks
            String callerPrincipalName = getCallerPrincipalName(rawClaims);
            if (callerPrincipalName == null) {
                throw new JWTProcessingException("One of upn, preferred_username or sub is required to be non null");
            }

            // MP-JWT 1.0 6.1 2
            if (!checkIssuer(rawClaims, issuer)) {
                throw new JWTProcessingException("Bad issuer");
            }

            if (!checkNotExpired(rawClaims)) {
                throw new JWTProcessingException("JWT token expired");
            }

            // MP-JWT 1.0 6.1 2
            try {
                if (signAlgorithmName.equals(RS256)) {
                    if (!signedJWT.verify(new RSASSAVerifier((RSAPublicKey) publicKey))) {
                        throw new JWTProcessingException("Signature of the JWT token is invalid");
                    }
                } else {
                    if (!signedJWT.verify(new ECDSAVerifier((ECPublicKey) publicKey))) {
                        throw new JWTProcessingException("Signature of the JWT token is invalid");
                    }
                }
            } catch (JOSEException ex) {
                throw new JWTProcessingException("Exception during JWT signature validation", ex);
            }

            rawClaims.put(
                    raw_token.name(),
                    createObjectBuilder().add("token", rawToken).build().get("token"));

            return new JsonWebTokenImpl(callerPrincipalName, rawClaims);
        }
    }

    private JsonWebTokenImpl verifyAndParseEncryptedJWT(String issuer, PublicKey publicKey, PrivateKey privateKey) throws JWTProcessingException {
        if (encryptedJWT == null) {
            throw new IllegalStateException("EncryptedJWT not parsed");
        }

        String algName = encryptedJWT.getHeader().getAlgorithm().getName();
        if (!RSA_OAEP.getName().equals(algName)) {
            throw new JWTProcessingException("Only RSA-OAEP algorithm is supported for JWT encryption, used " + algName);
        }

        try {
            encryptedJWT.decrypt(new RSADecrypter(privateKey));
        } catch (JOSEException ex) {
            throw new JWTProcessingException("Exception during decrypting JWT token", ex);
        }

        signedJWT = encryptedJWT.getPayload().toSignedJWT();

        if (signedJWT == null) {
            throw new JWTProcessingException("Unable to parse signed JWT.");
        }

        return verifyAndParseSignedJWT(issuer, publicKey);
    }

    private Map<String, JsonValue> handleNamespacedClaims(Map<String, JsonValue> currentClaims) {
        if (this.enableNamespacedClaims) {
            final String namespace = customNamespace.orElse(DEFAULT_NAMESPACE);
            Map<String, JsonValue> processedClaims = new HashMap<>(currentClaims.size());
            for (Entry<String, JsonValue> entry : currentClaims.entrySet()) {
                String claimName = entry.getKey();
                JsonValue value = entry.getValue();
                if (claimName.startsWith(namespace)) {
                    claimName = claimName.substring(namespace.length());
                }
                processedClaims.put(claimName, value);
            }
            return processedClaims;
        } else {
            return currentClaims;
        }
    }

    private boolean checkRequiredClaimsPresent(Map<String, JsonValue> presentedClaims) {
        for (Claims requiredClaim : requiredClaims) {
            if (presentedClaims.get(requiredClaim.name()) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Will confirm the token has not expired if the current time and issue time
     * were both before the expiry time
     *
     * @param presentedClaims the claims from the JWT
     * @return if the JWT has expired
     */
    private boolean checkNotExpired(Map<String, JsonValue> presentedClaims) {
        final long currentTime = System.currentTimeMillis() / 1000;
        final long expiredTime = ((JsonNumber) presentedClaims.get(exp.name())).longValue();
        final long issueTime = ((JsonNumber) presentedClaims.get(iat.name())).longValue();

        return currentTime < expiredTime && issueTime < expiredTime;
    }

    private boolean checkIssuer(Map<String, JsonValue> presentedClaims, String acceptedIssuer) {
        if (!(presentedClaims.get(iss.name()) instanceof JsonString)) {
            return false;
        }

        String issuer = ((JsonString) presentedClaims.get(iss.name())).getString();

        // TODO: make acceptedIssuers (set)
        return acceptedIssuer.equals(issuer);
    }

    private boolean checkIsSignedJWT(SignedJWT jwt) {
        return disableTypeVerification || Optional.ofNullable(jwt.getHeader().getType())
                .map(JOSEObjectType::toString)
                .orElse("")
                .equals("JWT");
    }

    private boolean checkIsEncryptedJWT(EncryptedJWT jwt) {
        return disableTypeVerification || Optional.ofNullable(jwt.getHeader().getContentType())
                .orElse("")
                .equals("JWT");
    }

    private String getCallerPrincipalName(Map<String, JsonValue> rawClaims) {
        JsonString callerPrincipalClaim = (JsonString) rawClaims.get(upn.name());

        if (callerPrincipalClaim == null) {
            callerPrincipalClaim = (JsonString) rawClaims.get(preferred_username.name());
        }

        if (callerPrincipalClaim == null) {
            callerPrincipalClaim = (JsonString) rawClaims.get(sub.name());
        }

        if (callerPrincipalClaim == null) {
            return null;
        }

        return callerPrincipalClaim.getString();
    }

}
