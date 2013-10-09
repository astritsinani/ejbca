/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/

package org.ejbca.core.protocol.cmp.authentication;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.cmp.CMPCertificate;
import org.bouncycastle.asn1.cmp.PKIMessage;
import org.cesecore.authentication.tokens.AuthenticationSubject;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.authorization.control.AccessControlSession;
import org.cesecore.authorization.control.StandardRules;
import org.cesecore.certificates.ca.CADoesntExistsException;
import org.cesecore.certificates.ca.CAInfo;
import org.cesecore.certificates.ca.CaSession;
import org.cesecore.certificates.certificate.CertificateConstants;
import org.cesecore.certificates.certificate.CertificateInfo;
import org.cesecore.certificates.certificate.CertificateStoreSession;
import org.cesecore.certificates.endentity.EndEntityInformation;
import org.cesecore.util.CertTools;
import org.ejbca.config.CmpConfiguration;
import org.ejbca.core.EjbcaException;
import org.ejbca.core.ejb.authentication.web.WebAuthenticationProviderSessionLocal;
import org.ejbca.core.ejb.ra.EndEntityAccessSession;
import org.ejbca.core.ejb.ra.EndEntityManagementSession;
import org.ejbca.core.ejb.ra.raadmin.EndEntityProfileSession;
import org.ejbca.core.model.SecConst;
import org.ejbca.core.model.approval.WaitingForApprovalException;
import org.ejbca.core.model.authorization.AccessRulesConstants;
import org.ejbca.core.model.ra.NotFoundException;
import org.ejbca.core.model.ra.raadmin.EndEntityProfileNotFoundException;
import org.ejbca.core.model.ra.raadmin.UserDoesntFullfillEndEntityProfile;
import org.ejbca.core.protocol.cmp.CmpMessageHelper;
import org.ejbca.core.protocol.cmp.CmpPKIBodyConstants;
import org.ejbca.util.passgen.IPasswordGenerator;
import org.ejbca.util.passgen.PasswordGeneratorFactory;

/**
 * Check the authentication of the PKIMessage by verifying the signature of the administrator who sent the message
 * 
 * @version $Id$
 *
 */
public class EndEntityCertificateAuthenticationModule implements ICMPAuthenticationModule {

    private static final Logger log = Logger.getLogger(EndEntityCertificateAuthenticationModule.class);
    
    private String authenticationparameter;
    private String password;
    private Certificate extraCert;
    private String confAlias;
    private CmpConfiguration cmpConfiguration;

    private AuthenticationToken admin;
    private CaSession caSession;
    private CertificateStoreSession certSession;
    private AccessControlSession authSession;
    private EndEntityProfileSession eeProfileSession;
    private EndEntityAccessSession eeAccessSession;
    private WebAuthenticationProviderSessionLocal authenticationProviderSession;
    private EndEntityManagementSession eeManagementSession;

    public EndEntityCertificateAuthenticationModule( final AuthenticationToken admin, String authparam, String confAlias, CmpConfiguration cmpConfig, 
            final CaSession caSession, final CertificateStoreSession certSession, final AccessControlSession authSession, 
            final EndEntityProfileSession eeprofSession, final EndEntityAccessSession eeaccessSession, 
            final WebAuthenticationProviderSessionLocal authProvSession, final EndEntityManagementSession endEntityManagementSession) {
        authenticationparameter = authparam;
        password = null;
        extraCert = null;
        this.confAlias = confAlias;
        this.cmpConfiguration = cmpConfig;
        
        this.admin = admin;
        this.caSession = caSession;
        this.certSession = certSession;
        this.authSession = authSession;
        this.eeProfileSession = eeprofSession;
        eeAccessSession = eeaccessSession;
        authenticationProviderSession = authProvSession;
        eeManagementSession = endEntityManagementSession;
    }
    
    /**
     * Returns the name of this authentication module as String
     * 
     * @return the name of this authentication module as String
     */
    public String getName() {
        return CmpConfiguration.AUTHMODULE_ENDENTITY_CERTIFICATE;
    }
    
    /**
     * Returns the password resulted from the verification process.
     * 
     * This password is set if verify() returns true.
     * 
     * @return The password as String. Null if the verification had failed.
     */
    public String getAuthenticationString() {
        return this.password;
    }
    
    /**
     * Get the certificate that was attached to the CMP request in it's extreCert filed.
     * 
     * @return The certificate that was attached to the CMP request in it's extreCert filed 
     */
    public Certificate getExtraCert() {
        return extraCert;
    }

    public Certificate getExtraCert(final PKIMessage msg) {
        final CMPCertificate[] extraCerts = msg.getExtraCerts();
        if ((extraCerts == null) || (extraCerts.length == 0)) {
            if(log.isDebugEnabled()) {
                log.debug("There is no certificate in the extraCert field in the PKIMessage");
            }
            return null;
        } else {
            if(log.isDebugEnabled()) {
                log.debug("A certificate is found in the extraCert field in the CMP message");
            }
        }
        
        //Read the extraCert
        CMPCertificate cmpcert = extraCerts[0];
        Certificate excert = null;
        try {
            excert = CertTools.getCertfromByteArray(cmpcert.getEncoded());
            if(log.isDebugEnabled()) {
                log.debug("Obtaning the certificate from extraCert field was done successfully");
            }
        } catch (CertificateException e) {
            if(log.isDebugEnabled()) {
                log.debug(e.getLocalizedMessage(), e);
            }
        } catch (IOException e) {
            if(log.isDebugEnabled()) {
                log.debug(e.getLocalizedMessage(), e);
            }
        }
        return excert;
    }
    
    /**
     * Verifies the signature of 'msg'. msg should be signed by an authorized administrator in EJBCA and 
     * the administrator's cerfificate should be attached in msg in the extraCert field.  
     * 
     * When successful, the password is set to the randomly generated 16-digit String.
     * When failed, the error message is set.
     * 
     * @param msg PKIMessage
     * @param username
     * @param authenticated
     * @return true if the message signature was verified successfully and false otherwise.
     * @throws CMPAuthenticationException 
     */
    public boolean verifyOrExtract(final PKIMessage msg, final String username, boolean authenticated) throws CMPAuthenticationException {
        
        //Check that msg is signed
        if(msg.getProtection() == null) {
            throw new CMPAuthenticationException("PKI Message is not athenticated properly. No PKI protection is found.");
        }
        
        // Read the extraCert and store it in a local variable
        extraCert = getExtraCert(msg);
        if(extraCert == null) {
            throw new CMPAuthenticationException("Error while reading the certificate in the extraCert field");
        }
        
        boolean vendormode = isVendorCertificateMode(msg.getBody().getType());
        boolean omitVerifications = cmpConfiguration.getOmitVerificationsInEEC(confAlias);
        boolean ramode = cmpConfiguration.getRAMode(confAlias);
        if(log.isDebugEnabled()) {
            log.debug("CMP is operating in RA mode: " + this.cmpConfiguration.getRAMode(this.confAlias));
            log.debug("CMP is operating in Vendor mode: " + vendormode);
            log.debug("CMP message already been authenticated: " + authenticated);
            log.debug("Omitting som verifications: " + omitVerifications);
        }    
        
        //----------------------------------------------------------------------------------------
        // Perform the different checks depending on the configuration and previous authentication
        //----------------------------------------------------------------------------------------

        // Unaccepted combinations. Throw an exception
        if(ramode && vendormode) {
            throw new CMPAuthenticationException("Vendor mode and RA mode cannot be combined");
        }
        if(omitVerifications && (!ramode || !authenticated)) {
            throw new CMPAuthenticationException("Omitting some verifications can only be accepted in RA mode and when the " +
            		                              "CMP request has already been authenticated, for example, through the use of NestedMessageContent");
        }
        
        // Accepted combinations
        if(omitVerifications && ramode && authenticated) {
            // Do nothing here
            if(log.isDebugEnabled()) {
                log.debug("Skipping some verification of the extraCert certificate in RA mode and an already authenticated CMP message, tex. through NestedMessageContent");
            }
        } else if(ramode) {
            
            // Get the CA to use for the authentication
            CAInfo cainfo = getCAInfo(authenticationparameter, false);
            
            // Check that extraCert is in the Database
            CertificateInfo certinfo = certSession.getCertificateInfo(CertTools.getFingerprintAsString(extraCert));
            if(certinfo == null) {
                throw new CMPAuthenticationException("The certificate attached to the PKIMessage in the extraCert field could not be found in the database.");
            }
            
            // More extraCert verifications
            checkExtraCertIssuedByCA(cainfo);
            checkExtraCertIsValid();
            checkExtraCertIsActive(certinfo);

            // Check that extraCert belong to an admin with sufficient access rights
            if(!isAuthorizedAdmin(certinfo, msg, cainfo.getCAId())){
                throw new CMPAuthenticationException("'" + CertTools.getSubjectDN(extraCert) + "' is not an authorized administrator.");
            }

        } else if(!ramode) { // client mode
            
            String extraCertUsername = null;
            if(vendormode) {

                // Check that extraCert is issued  by a configured VendorCA
                if(!checkExtraCertIssuedByVendorCA()) {
                    throw new CMPAuthenticationException("The certificate in extraCert field is not issued by any of the configured Vendor CAs: " + cmpConfiguration.getVendorCA(confAlias));
                }
                
                // Extract the username from extraCert to use for  further authentication
                String subjectDN = CertTools.getSubjectDN(extraCert);
                extraCertUsername = CertTools.getPartFromDN(subjectDN, this.cmpConfiguration.getExtractUsernameComponent(this.confAlias));
                if(log.isDebugEnabled()) {
                    log.debug("Username ("+extraCertUsername+") was extracted from the '" + this.cmpConfiguration.getExtractUsernameComponent(this.confAlias) + "' part of the subjectDN of the certificate in the 'extraCerts' field.");
                }
                
            } else {
                
                // Get the CA to use for the authentication
                CAInfo cainfo = getCAInfo(CertTools.getIssuerDN(extraCert), true);

                // Check that extraCert is in the Database
                CertificateInfo certinfo = certSession.getCertificateInfo(CertTools.getFingerprintAsString(extraCert));
                if(certinfo == null) {
                    throw new CMPAuthenticationException("The certificate attached to the PKIMessage in the extraCert field could not be found in the database.");
                }
                
                // More extraCert verifications
                checkExtraCertIssuedByCA(cainfo);
                checkExtraCertIsValid();
                checkExtraCertIsActive(certinfo);
                
                // Extract the username from extraCert to use for  further authentication
                extraCertUsername = certinfo.getUsername();
            }
            
            // Check if this certificate belongs to the user
            if ( (username != null) && (extraCertUsername != null) ) {
                if (!StringUtils.equals(username, extraCertUsername)) {
                    String errmsg = "The End Entity certificate attached to the PKIMessage in the extraCert field does not belong to user '"+username+"'";
                    if(log.isDebugEnabled()) {
                        // Use a different debug message, as not to reveal too much information
                        log.debug(errmsg + ", but to user '"+extraCertUsername+"'");
                    }
                    throw new CMPAuthenticationException(errmsg);
                }
                
                //set the password of the request to this user's password so it can later be used when issuing the certificate
                if (log.isDebugEnabled()) {
                    log.debug("The End Entity certificate attached to the PKIMessage in the extraCert field belongs to user '"+username+"'.");
                    log.debug("Extracting and setting password for user '"+username+"'.");
                }
                try {
                    EndEntityInformation user = eeAccessSession.findUser(admin, username);
                    password = user.getPassword();
                    if(password == null) {
                        password = genRandomPwd();
                        user.setPassword(password);
                        eeManagementSession.changeUser(admin, user, false);
                    }
                } catch (AuthorizationDeniedException e) {
                    if(log.isDebugEnabled()) {
                        log.debug(e.getLocalizedMessage());
                    }
                    throw new CMPAuthenticationException(e.getLocalizedMessage());
                } catch (CADoesntExistsException e) {
                    if(log.isDebugEnabled()) {
                        log.debug(e.getLocalizedMessage());
                    }
                    throw new CMPAuthenticationException(e.getLocalizedMessage());
                } catch (UserDoesntFullfillEndEntityProfile e) {
                    if(log.isDebugEnabled()) {
                        log.debug(e.getLocalizedMessage());
                    }
                    throw new CMPAuthenticationException(e.getLocalizedMessage());
                } catch (WaitingForApprovalException e) {
                    if(log.isDebugEnabled()) {
                        log.debug(e.getLocalizedMessage());
                    }
                    throw new CMPAuthenticationException(e.getLocalizedMessage());
                } catch (EjbcaException e) {
                    if(log.isDebugEnabled()) {
                        log.debug(e.getLocalizedMessage());
                    }
                    throw new CMPAuthenticationException(e.getLocalizedMessage());
                }
            }
        }
        
        //-------------------------------------------------------------
        //Begin the signature verification process.
        //Verify the signature of msg using the public key of extraCert
        //-------------------------------------------------------------
        try {
            final Signature sig = Signature.getInstance(msg.getHeader().getProtectionAlg().getAlgorithm().getId(), "BC");
            sig.initVerify(extraCert.getPublicKey());
            sig.update(CmpMessageHelper.getProtectedBytes(msg));
            if (sig.verify(msg.getProtection().getBytes())) {
                if (password == null) {
                    // If not set earlier
                    password = genRandomPwd();
                }
            } else {
                throw new CMPAuthenticationException("Failed to verify the signature in the PKIMessage");
            }
        } catch (InvalidKeyException e) {
            if(log.isDebugEnabled()) {
                log.debug(e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(e.getLocalizedMessage());
        } catch (NoSuchAlgorithmException e) {
            if(log.isDebugEnabled()) {
                log.debug(e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(e.getLocalizedMessage());
        } catch (NoSuchProviderException e) {
            if(log.isDebugEnabled()) {
                log.debug(e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(e.getLocalizedMessage());
        } catch (SignatureException e) {
            if(log.isDebugEnabled()) {
                log.debug(e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(e.getLocalizedMessage());
        }
        return true;
    }

    /**
     * Generated a random password of 16 digits.
     * 
     * @return a randomly generated password
     */
    private String genRandomPwd() {
        final IPasswordGenerator pwdgen = PasswordGeneratorFactory.getInstance(PasswordGeneratorFactory.PASSWORDTYPE_ALLPRINTABLE);
        return pwdgen.getNewPassword(12, 12);
    }
    
    /**
     * Checks if cert belongs to an administrator who is authorized to process the request.
     * 
     * @param cert
     * @param msg
     * @param caid
     * @return true if the administrator is authorized to process the request and false otherwise.
     * @throws NotFoundException
     * @throws CMPAuthenticationException 
     */
    private boolean isAuthorizedAdmin(final CertificateInfo certInfo, final PKIMessage msg, final int caid) throws CMPAuthenticationException {
        final String username = certInfo.getUsername();
        if(authenticationProviderSession == null) {
            String errMsg = "WebAuthenticationProviderSession is null";
            if(log.isDebugEnabled()) {
                log.debug(errMsg);
            }
            return false;
        }
        
        X509Certificate x509cert = (X509Certificate) extraCert;
        Set<X509Certificate> credentials = new HashSet<X509Certificate>();
        credentials.add(x509cert);
        
        AuthenticationSubject subject = new AuthenticationSubject(null, credentials);
        AuthenticationToken reqAuthToken = authenticationProviderSession.authenticate(subject);
        
        if(!authSession.isAuthorizedNoLogging(admin, StandardRules.CAACCESS.resource() + caid)) {
            if(log.isDebugEnabled()) {
                log.debug("Admin " + admin.toString() + " not authorized to resource " + StandardRules.CAACCESS.resource() + caid);
            }
            return false;
        }
        
        final int eeprofid = getUsedEndEntityProfileId((DEROctetString) msg.getHeader().getSenderKID());
        final int tagnr = msg.getBody().getType();
        if( (tagnr == CmpPKIBodyConstants.CERTIFICATAIONREQUEST) || (tagnr == CmpPKIBodyConstants.INITIALIZATIONREQUEST) || (tagnr == CmpPKIBodyConstants.KEYUPDATEREQUEST) ) {
        
            if (!authorizedToEndEntityProfile(reqAuthToken, eeprofid, AccessRulesConstants.CREATE_RIGHTS)) {
                if(log.isDebugEnabled()) {
                    log.debug("Admin " + admin.toString() + " was not authorized to resource " + StandardRules.ROLE_ROOT);
                }
                return false;
            }
            
            if(!authorizedToEndEntityProfile(reqAuthToken, eeprofid, AccessRulesConstants.EDIT_RIGHTS)) {
                if(log.isDebugEnabled()) {
                    log.debug("Admin " + admin.toString() + " was not authorized to resource " + StandardRules.ROLE_ROOT);
                }
                return false;
            }
            
            if(!authSession.isAuthorizedNoLogging(reqAuthToken, AccessRulesConstants.REGULAR_CREATECERTIFICATE)) {
                if(log.isDebugEnabled()) {
                    log.debug("Administrator " + username + " is not authorized to create certificates.");
                }
                return false;
            }
        } else if(tagnr == CmpPKIBodyConstants.REVOCATIONREQUEST) {
            
            if(!authorizedToEndEntityProfile(reqAuthToken, eeprofid, AccessRulesConstants.REVOKE_RIGHTS)) {
                if(log.isDebugEnabled()) {
                    log.debug("Administrator " + username + " is not authorized to revoke.");
                }
                return false;
            }
            
            if(!authSession.isAuthorizedNoLogging(reqAuthToken, AccessRulesConstants.REGULAR_REVOKEENDENTITY)) {
                if(log.isDebugEnabled()) {
                    log.debug("Administrator " + username + " is not authorized to revoke End Entities");
                }
                return false;
            }
            
        }
        return true;
    }
    
    /**
     * Checks whether admin is authorized to access the EndEntityProfile with the ID profileid
     * 
     * @param admin
     * @param profileid
     * @param rights
     * @return true if admin is authorized and false otherwise.
     */
    private boolean authorizedToEndEntityProfile(AuthenticationToken admin, int profileid, String rights) {
        if (profileid == SecConst.EMPTY_ENDENTITYPROFILE
                && (rights.equals(AccessRulesConstants.CREATE_RIGHTS) || rights.equals(AccessRulesConstants.EDIT_RIGHTS))) {

            return authSession.isAuthorizedNoLogging(admin, StandardRules.ROLE_ROOT.resource());
        } else {
            return authSession.isAuthorizedNoLogging(admin, AccessRulesConstants.ENDENTITYPROFILEPREFIX + profileid + rights)
                    && authSession.isAuthorizedNoLogging(admin, AccessRulesConstants.REGULAR_RAFUNCTIONALITY + rights);
        }
    }

    /**
     * Return the ID of EndEntityProfile that is used for CMP purposes. 
     * @param keyId
     * @return the ID of EndEntityProfile used for CMP purposes. 0 if no such EndEntityProfile exists. 
     * @throws NotFoundException
     * @throws CMPAuthenticationException 
     */
    private int getUsedEndEntityProfileId(final DEROctetString keyId) throws CMPAuthenticationException {
        String endEntityProfile = this.cmpConfiguration.getRAEEProfile(this.confAlias);
        if (StringUtils.equals(endEntityProfile, "KeyId") && (keyId != null)) {
            endEntityProfile = CmpMessageHelper.getStringFromOctets(keyId);
            if (log.isDebugEnabled()) {
                log.debug("Using End Entity Profile with same name as KeyId in request: "+endEntityProfile);
            }
        } 
        try {
            return eeProfileSession.getEndEntityProfileId(endEntityProfile);
        } catch (EndEntityProfileNotFoundException e) {
            String errmsg = "No end entity profile found with name: "+endEntityProfile;
            if(log.isDebugEnabled()) {
                log.debug(errmsg + " - " + e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(errmsg);
        }
    }
    
    private void checkExtraCertIsValid() throws CMPAuthenticationException {
        X509Certificate cert = (X509Certificate) extraCert;
        try {
            cert.checkValidity();
            if(log.isDebugEnabled()) {
                log.debug("The certificate in extraCert is valid");
            }
        } catch(Exception e) {
            String errmsg = "The certificate attached to the PKIMessage in the extraCert field in not valid.";
            if(log.isDebugEnabled()) {
                log.debug(errmsg + " SubjectDN=" + CertTools.getSubjectDN(cert) + " - " + e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(errmsg);
        }
    }

    private void checkExtraCertIsActive(final CertificateInfo certinfo) throws CMPAuthenticationException {
        if (certinfo.getStatus() != CertificateConstants.CERT_ACTIVE) {
            String errmsg = "The certificate attached to the PKIMessage in the extraCert field is not active.";
            if(log.isDebugEnabled()) {
                log.debug(errmsg + " Username="+certinfo.getUsername());
            }
            throw new CMPAuthenticationException(errmsg);
        }
        if(log.isDebugEnabled()) {
            log.debug("The certificate in extraCert is active");
        }
    }
    
    private void checkExtraCertIssuedByCA(CAInfo cainfo) throws CMPAuthenticationException {
        //Check that the extraCert is given by the right CA
        // Verify the signature of the client certificate as well, that it is really issued by this CA
        Certificate cacert = cainfo.getCertificateChain().iterator().next();
        try {
            extraCert.verify(cacert.getPublicKey(), "BC");
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                String errmsg = "The End Entity certificate attached to the PKIMessage is not issued by the CA '" + cainfo.getName() + "'";
                log.debug(errmsg + " - " + e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException("The End Entity certificate attached to the PKIMessage is issued by the wrong CA");
        }
    }
    
    private CAInfo getCAInfo(String caString, boolean issuer) throws CMPAuthenticationException {
        try {
            if(issuer)
                return caSession.getCAInfo(admin, caString.hashCode());
            else
                return caSession.getCAInfo(admin, caString);
        } catch (CADoesntExistsException e) {
            String errmsg = "CA '" + caString + "' does not exist";
            if(log.isDebugEnabled()) {
                log.debug(errmsg + " - " + e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(errmsg);
        } catch (AuthorizationDeniedException e) {
            String errmsg = "Authorization denied for CA: " + caString;
            if(log.isDebugEnabled()) {
                log.debug(errmsg + " - " + e.getLocalizedMessage());
            }
            throw new CMPAuthenticationException(errmsg);
        }
    }
    
    private boolean checkExtraCertIssuedByVendorCA() {
        String vendorCAsStr = this.cmpConfiguration.getVendorCA(this.confAlias);
        String[] vendorcas = vendorCAsStr.split(";");
        CAInfo cainfo = null;
        for(String vendorca : vendorcas) {
            if(log.isDebugEnabled()) {
                log.debug("Checking if extraCert is issued by the VendorCA: " + vendorca);
            }
            
            try {
                cainfo = caSession.getCAInfo(admin, vendorca.trim());
                checkExtraCertIssuedByCA(cainfo);
                return true;
            } catch (CMPAuthenticationException e) {
                if(log.isDebugEnabled()) {
                    log.debug(e.getLocalizedMessage());
                }
            } catch (CADoesntExistsException e) {
                if(log.isDebugEnabled()) {
                    log.debug("Cannot find CA: " + vendorca);
                }
            } catch (AuthorizationDeniedException e) {
                if(log.isDebugEnabled()) {
                    log.debug(e.getLocalizedMessage());
                }
            }
        }
        return false;
    }
    
    /**
     * Checks whether authentication by vendor-issued-certificate should be used. If authentication by vendor-issued-certificate is 
     * activated in the Cmp.properties file, it can be used only in client mode and with initialization/certification requests.
     *  
     * @param reqType
     * @return 'True' if authentication by vendor-issued-certificate is used. 'False' otherwise
     */
    private boolean isVendorCertificateMode(int reqType) {
        return !this.cmpConfiguration.getRAMode(this.confAlias) && this.cmpConfiguration.getVendorMode(this.confAlias) && (reqType == 0 || reqType == 2);
    }
 
}