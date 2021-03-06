

package org.bcia.javachain.ca.szca.common.bcca.core.ejb.ca.revoke;

import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.apache.log4j.Logger;
import org.cesecore.audit.enums.EventStatus;
import org.cesecore.audit.enums.ModuleTypes;
import org.cesecore.audit.enums.ServiceTypes;
import org.cesecore.audit.log.SecurityEventsLoggerSessionLocal;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.certificates.certificate.CertificateData;
import org.cesecore.certificates.certificate.CertificateDataWrapper;
import org.cesecore.certificates.certificate.CertificateRevokeException;
import org.cesecore.certificates.certificate.CertificateStoreSessionLocal;
import org.cesecore.certificates.crl.RevokedCertInfo;
import org.cesecore.config.CesecoreConfiguration;
import org.cesecore.jndi.JndiConstants;
import org.cesecore.util.CertTools;
import org.ejbca.core.ejb.audit.enums.EjbcaEventTypes;
import org.ejbca.core.ejb.ca.publisher.PublisherSessionLocal;
import org.ejbca.core.model.InternalEjbcaResources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;


@Repository
public class RevocationSessionBean implements RevocationSessionLocal, RevocationSessionRemote {

    private static final Logger log = Logger.getLogger(RevocationSessionBean.class);

    @PersistenceContext //(unitName = CesecoreConfiguration.PERSISTENCE_UNIT)
    private EntityManager entityManager;

    @Autowired
    private org.bcia.javachain.ca.szca.common.cesecore.audit.log.SecurityEventsLoggerSessionLocal auditSession;
    @Autowired
    private org.bcia.javachain.ca.szca.common.cesecore.certificates.certificate.CertificateStoreSessionLocal certificateStoreSession;
    @Autowired
    private org.bcia.javachain.ca.szca.common.core.ejb.ca.publisher.PublisherSessionLocal publisherSession;

    /** Internal localization of logs and errors */
    private static final InternalEjbcaResources intres = InternalEjbcaResources.getInstance();

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    @Override
    public void revokeCertificate(final AuthenticationToken admin, final Certificate cert, final Collection<Integer> publishers, final int reason, final String userDataDN) throws CertificateRevokeException, AuthorizationDeniedException {
        final Date revokedate = new Date();
        final CertificateDataWrapper cdw = certificateStoreSession.getCertificateData(CertTools.getFingerprintAsString(cert));
        if (cdw != null) { 
            revokeCertificate(admin, cdw, publishers, revokedate, reason, userDataDN);
        } else {
            final String msg = intres.getLocalizedMessage("store.errorfindcertserno", CertTools.getSerialNumberAsString(cert));              
            log.info(msg);
            throw new CertificateRevokeException(msg);
        }
    }
    
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    @Override
    public void revokeCertificate(final AuthenticationToken admin, final CertificateDataWrapper cdw, final Collection<Integer> publishers, Date revocationDate, final int reason, final String userDataDN) throws CertificateRevokeException, AuthorizationDeniedException {
    	final boolean waschanged = certificateStoreSession.setRevokeStatus(admin, cdw, revocationDate, reason);
    	// Publish the revocation if it was actually performed
    	if (waschanged) {
    	    // Since storeSession.findCertificateInfo uses a native query, it does not pick up changes made above
    	    // that is part if the transaction in the EntityManager, so we need to get the object from the EntityManager.
    	    final CertificateData certificateData = cdw.getCertificateData();
    	    final String username = certificateData.getUsername();
    	    final String password = null;
    		// Only publish the revocation if it was actually performed
    		if (reason==RevokedCertInfo.NOT_REVOKED || reason==RevokedCertInfo.REVOCATION_REASON_REMOVEFROMCRL) {
    			// unrevocation, -1L as revocationDate
    		    final boolean published = publisherSession.storeCertificate(admin, publishers, cdw, password, userDataDN, null);
        		if (published) {
        			log.info(intres.getLocalizedMessage("store.republishunrevokedcert", Integer.valueOf(reason)));
        		} else {
        		    final String serialNumber = certificateData.getSerialNumberHex();
            		// If it is not possible, only log error but continue the operation of not revoking the certificate
        			final String msg = "Unrevoked cert:" + serialNumber + " reason: " + reason + " Could not be republished.";
        			final Map<String, Object> details = new LinkedHashMap<String, Object>();
                	details.put("msg", msg);
                	final int caid = certificateData.getIssuerDN().hashCode();
                	auditSession.log(EjbcaEventTypes.REVOKE_UNREVOKEPUBLISH, EventStatus.FAILURE, ModuleTypes.CA, ServiceTypes.CORE, admin.toString(), String.valueOf(caid), serialNumber, username, details);
        		}    			
    		} else {
    			// revocation
                publisherSession.storeCertificate(admin, publishers, cdw, password, userDataDN, null);
    		}
    	}
    }
    
}
