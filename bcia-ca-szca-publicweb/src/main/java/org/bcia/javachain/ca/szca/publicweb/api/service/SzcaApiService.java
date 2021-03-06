/*
 * Copyright ? 2018  深圳市电子商务安全证书管理有限公司(SZCA,深圳CA) 版权所有
 * Copyright ? 2018  SZCA. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bcia.javachain.ca.szca.publicweb.api.service;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.ejb.FinderException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.bcia.javachain.ca.szca.publicweb.api.ApiAdminUserData;
import org.bcia.javachain.ca.szca.publicweb.api.CallConfigData;
import org.bcia.javachain.ca.szca.publicweb.api.CallLogData;
import org.bcia.javachain.ca.szca.publicweb.controller.EnrollCertForm;
import org.bcia.javachain.ca.szca.publicweb.entity.CertProcessData;
import org.bcia.javachain.ca.szca.publicweb.entity.EndEntityApprovalData;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.certificates.ca.CAData;
import org.cesecore.certificates.ca.CAInfo;
import org.cesecore.certificates.certificate.CertificateConstants;
import org.cesecore.certificates.certificate.CertificateData;
import org.cesecore.certificates.certificate.CertificateDataWrapper;
import org.cesecore.certificates.certificateprofile.CertificateProfileData;
import org.cesecore.certificates.endentity.EndEntityConstants;
import org.cesecore.certificates.endentity.EndEntityInformation;
import org.cesecore.certificates.endentity.EndEntityType;
import org.cesecore.certificates.endentity.EndEntityTypes;
import org.cesecore.util.Base64;
import org.cesecore.util.CertTools;
import org.ejbca.core.ejb.ra.UserData;
import org.ejbca.core.ejb.ra.raadmin.EndEntityProfileData;
import org.ejbca.core.model.approval.ApprovalException;
import org.ejbca.core.model.approval.WaitingForApprovalException;
import org.ejbca.core.model.ra.AlreadyRevokedException;
import org.ejbca.core.model.ra.userdatasource.UserDataSourceConnectionException;
import org.ejbca.ui.web.CertificateView;
import org.ejbca.util.crypto.BCrypt;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.JsonObject;

import org.bcia.javachain.ca.szca.common.bcca.core.ejb.ra.EndEntityManagementSession;
import org.bcia.javachain.ca.szca.common.bcca.core.ejb.ra.EndEntityManagementSessionLocal;
import org.bcia.javachain.ca.szca.common.bcca.core.model.SecConst;
import org.bcia.javachain.ca.szca.common.cesecore.certificates.certificate.CertificateStoreSession;

@Repository
public class SzcaApiService implements ISzcaApiService {
	private static final org.slf4j.Logger log = LoggerFactory.getLogger(SzcaApiService.class);
	@Autowired
    private CertificateStoreSession certificatesession;
	@PersistenceContext
	private EntityManager entityManager;
	@Autowired
	EndEntityManagementSession endEntityManagementSession;
	@Autowired
	private EndEntityManagementSessionLocal endEntityManagementSessionBean;
	@Autowired
	CertificateStoreSession certificateStoreSessionBean;
	// @Autowired
	// EndEntityManagementSessionLocal endEntityManagementSession;

	public String getDefaultCaName() {

		String hql = "SELECT a FROM CAData a ";
		Query query = entityManager.createQuery(hql);
		List caList = query.getResultList();
		if (caList != null) {
			CAData caData = (CAData) caList.get(0);

			return caData.getCA().getName();// .toArray(new Certificate[1]);
		} else
			return null;

	}

	public CAData getCaByName(String caName) {

		String hql = "SELECT a FROM CAData a WHERE a.name=:name";
		Query query = entityManager.createQuery(hql);
		query.setParameter("name", caName);
		List caList = query.getResultList();
		if (caList != null) {
			CAData caData = (CAData) caList.get(0);

			return caData;// .toArray(new Certificate[1]);
		} else
			return null;

	}

	public String getCertificateChain4Base64(String caName, String format) throws Exception {

		Collection<Certificate> chain = this.getCertificateChain(caName);
		byte[] outbytes = new byte[0];
		// Encode and send back
		if ((format == null) || StringUtils.equalsIgnoreCase(format, "pem")) {
			outbytes = getPemFromCertificateChain(chain);
			// outbytes = getBase64FromCertificateChain(chain);
			// outbytes
			// ="MIIFZTCCA02gAwIBAgIIAwIJrv8NcegwDQYJKoZIhvcNAQELBQAwQDENMAsGA1UECgwEQkNJQTESMBAGA1UECwwJamF2YWNoYWluMRswGQYDVQQDDBJCQ0lBIE1hbmFnZW1lbnQgQ0EwHhcNMTgwNTAxMTA1NjUzWhcNMjgwNDI4MTA1NjUzWjBAMQ0wCwYDVQQKDARCQ0lBMRIwEAYDVQQLDAlqYXZhY2hhaW4xGzAZBgNVBAMMEkJDSUEgTWFuYWdlbWVudCBDQTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBALZxM6GO0UNAJdyXwsTyO5d22RA4qreX3w8wGMXwCDPli1GixzReYYu4CrmKYaz6aE3gpZYrOG2B0vOftgeI5/6f7Q8Deg7+lr4Fqc/MUz2aFQVLk/ogItoYMbVkaQZscPRsN8EPuPx7U7zWs9GML4HjRRQdeZ3O0mjbbpiIInnsmkT1x1ogZWKxpLAJP9/XbZR8szJGGgRNeB2CZN0uOQ5WqUXTDPBNh3ys1YhijYZeYL/SjGbTnw21RIidhbINzQLfie5rtY2hJebGN1USFKRbhPkP5vxWo3UBNjfoNdGs9j5hVUUec+IrgfnP4cv7Iq4Di0+0oBBhPbhFrzxhLh/kgf6bCx1ElXh5FEzUSr29f+CdEHj3o3ZAGE3QTb1yXs+29m4SlZI7HD1AQltK3BUPrvqSESytThLm0LOsFT8aTPzhim8vOl/q/Pz1kNe3EwP4M1M6rXSVUIoQso0PN6CiuweauhDhz5q/wUBmfbKVTEnMxCBON1tR4zKVJA79gcPn0vpdhWIqZf/DiQwignzJSTRf6Tc6wk76mC9SVoUeoBcymdswBgiwddfh1+w/lSAkd1PXeLZTsIEVmZNISwtmbUFyhfwxYuf1rp4ukSlpKDiTuPNbcAdDSBWfNmTbfJ7HMeQpUhGKJ/B7hUUHxuJmbSFDWt+1J6OZkJnwQd9jAgMBAAGjYzBhMA8GA1UdEwEB/wQFMAMBAf8wHwYDVR0jBBgwFoAU0NOmFKV7cbfLqeByHqfykiageaEwHQYDVR0OBBYEFNDTphSle3G3y6ngch6n8pImoHmhMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsFAAOCAgEAWFfS+2k61AWyFQ3VHG2yvw0Ze6HZJhJCh3qKt+FII0g99rapCmDB0nKeQJ7mCbaqOjv6GdTIs0zavNzuI8fWSx6Zf+4fHneAwthGCNqFUZmPcPjPcBC62cXr2dOCM2W+4jbZYsLQpeiSy+WLC6HCOg3ZD1V2cQrpLjH3TwHRVzcOomZlC86lfcnj4/gWr9EJsFSI6t88lLImr0nlN+EmN4NnXHOtbN9NuKHe9OYk2n72/5E3ry/rGsd43e0oeIzI3NyQNvQhDhME9gtBF8DLVnVvNx+muSIsWGwkAwFC6c9xIjAfovWbIv1BJmsz6Kv/qs8Wkfo5Nz+ti0zhsdOEgs3OKG+KtNzphSNDl76mBg/eJTMy+TkNeS/5FOGvPjCSvT41xOBqvpt7nTQ8qVudOEtW1FQEHOqMSNqZ681tnGR6azrMOiHdfehiOoqNpsZEVJXktFicJ+wJp8xoqLkBnO7hNSK2+MjdsASkLhA7P8hwmwdZ189SyBsG/Frnx294nxnHWuzaQJ+d4Wz3xC/bGuMCUmNT91RiEgsicLHtCCQ8BVRzNZR9LwjOP+MDUmxRbe8bYpn5fwZwT7+JHt94M/C7DYPUMR5lRSWY9mn/grQmgMxSMbpPslvd8N7YCeRq1V+J6J9C/MVnXNNOi0IEDk0LvQ0AOeN8vN8Q00Kqi/s=".getBytes();
		} else {
			// Create a JKS truststore with the CA certificates in
			final KeyStore store = KeyStore.getInstance("JKS");
			store.load(null, null);
			Certificate[] chainCerts = (Certificate[]) chain.toArray(new Certificate[1]);
			for (int i = 0; i < chainCerts.length; i++) {
				String cadn = CertTools.getSubjectDN(chainCerts[i]);
				String alias = CertTools.getPartFromDN(cadn, "CN");
				if (alias == null) {
					alias = CertTools.getPartFromDN(cadn, "O");
				}
				if (alias == null) {
					alias = "cacert" + i;
				}
				alias = StringUtils.replaceChars(alias, ' ', '_');
				alias = StringUtils.substring(alias, 0, 15);
				store.setCertificateEntry(alias, chainCerts[i]);
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				store.store(out, "changeit".toCharArray());
				out.close();
				outbytes = out.toByteArray();
			}
		}
		return new String(outbytes);
	}

	public byte[] getPemFromCertificateChain(Collection<Certificate> certs) throws CertificateEncodingException {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try (final PrintStream printStream = new PrintStream(baos)) {
			for (final Certificate certificate : certs) {
				// printStream.println("Subject: " + CertTools.getSubjectDN(certificate));
				// printStream.println("Issuer: " + CertTools.getIssuerDN(certificate));
				printStream.println("-----BEGIN CERTIFICATE-----");
				printStream.println(new String(Base64.encode(certificate.getEncoded())));
				printStream.println("-----END CERTIFICATE-----");
			}
		}

		// return baos.toByteArray();
		return org.apache.commons.codec.binary.Base64.encodeBase64(baos.toByteArray());
	}

	public byte[] getBase64FromCertificateChain___(Collection<Certificate> certs) throws CertificateEncodingException {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();

		for (final Certificate certificate : certs) {
			try {
				// 使用org.cesecore.util.Base64会自动格式化，即每64个字符自动回车换行\r\n
				// baos.write(org.cesecore.util.Base64.encode(certificate.getEncoded()));
				// 使用org.apache.commons.codec.binary.Base64不会自动格式化
				// baos.write(org.apache.commons.codec.binary.Base64.encodeBase64(certificate.getEncoded()));
				baos.write(org.apache.commons.codec.binary.Base64
						.encodeBase64(Base64.encode(certificate.getEncoded())));
			} catch (Exception e) {
				new CertificateEncodingException(e.getMessage());
			}
		}
		byte[] base64certs = baos.toByteArray();
		System.out.println(new String(base64certs));
		return base64certs;
	}

	private Collection<Certificate> getCertificateChain(String caName) {

		Collection<Certificate> chain = null;
		String cname;
		if (caName == null || "".equals(caName.trim()))
			cname = this.getDefaultCaName();
		else
			cname = caName;
		String hql = "SELECT a FROM CAData a WHERE a.name=:name";
		Query query = entityManager.createQuery(hql);
		query.setParameter("name", cname);
		CAData caData = (CAData) query.getSingleResult();

		if (caData != null)
			chain = caData.getCA().getCertificateChain();// .toArray(new Certificate[1]);

		return chain;
	}

	@Override
	@Transactional
	public List<String> revoke(AuthenticationToken admin, String caName, String serno, String certSubjectDn, int reason,
			String entityUserName, String aki) throws Exception {
		// TODO Auto-generated method stub
		List<String> snList = new ArrayList<String>();
		Collection<Certificate> chain = this.getCertificateChain(caName);
		Certificate[] certs = (Certificate[]) chain.toArray(new Certificate[1]);
		String issuerDN = ((X509Certificate) certs[0]).getIssuerDN().getName();
		
		 MessageDigest messageDigest = MessageDigest.getInstance("SHA1");  
         messageDigest.update(certs[0].getEncoded());  
         String caFingerprint =  getFormattedText(messageDigest.digest());
        // System.out.println("FingerPrint:"+ss);
         
		BigInteger certserno = null;
		try {
			certserno = new BigInteger(serno);
		} catch (Exception e1) {
			try {
				certserno = new BigInteger(serno, 16);
			} catch (Exception e2) {
			}
		}

	 
		//构造HQL,因为issuerDN项的排序可能不同，所有不能用issuerDN=...，而是用caFingerprint
		//String hql = "SELECT a FROM CertificateData  a WHERE a.revocationReason=-1 AND a.issuerDN=:issuerDN ";
		String hql = "SELECT a FROM CertificateData  a WHERE a.revocationReason=-1 AND a.caFingerprint=:caFingerprint ";
		if (certserno != null) {
			hql += " AND a.serialNumber=:serialNumber";
		}
		if (entityUserName != null && !"".equals(entityUserName.trim())) {
			hql += " AND a.username=:username";
		}
		if (certSubjectDn != null && !"".equals(certSubjectDn.trim())) {
			hql += " AND a.subjectDN=:subjectDN";
		}
		
		Query query = entityManager.createQuery(hql);
		//query.setParameter("issuerDN", issuerDN);
		query.setParameter("caFingerprint", caFingerprint);
		
		//配置参数
		if (certserno != null) {
			query.setParameter("serialNumber", certserno.toString(10));
		}
		if (entityUserName != null && !"".equals(entityUserName.trim())) {
			query.setParameter("username", entityUserName);
		}
		if (certSubjectDn != null && !"".equals(certSubjectDn.trim())) {
			query.setParameter("subjectDN", certSubjectDn);
		}

		List<CertificateData> certList = query.getResultList();

		if (certList == null || certList.isEmpty())
			throw new Exception("无法根据指定的参数定位有效的证书");

		for (CertificateData cert : certList) {
			BigInteger sn = null;
			try {
				sn = new BigInteger(cert.getSerialNumber());
			} catch (Exception e1) {
				try {
					sn = new BigInteger(cert.getSerialNumber(), 16);
				} catch (Exception e2) {
				}
			}
 
			endEntityManagementSession.revokeCert(admin, sn, issuerDN, reason);
			if (sn != null)
				snList.add(sn.toString(16));
		}
		// endEntityManagementSession.revokeCert(admin, certserno, issuerdn, reason);
		// if(certserno!=null)
		// snList.add(certserno.toString(10));
		return snList;
	}

	@Override
	@Transactional
	public String addEndEntity(AuthenticationToken admin, EndEntityInformation entity) throws Exception {
		// TODO Auto-generated method stub
		String passwd = this.randomPwd(12);
		entity.setPassword(passwd);
		endEntityManagementSession.addUser(admin, entity, false);

		return passwd;
	}
	
	@Override
	@Transactional
	public void addEndEntityWithPassword(AuthenticationToken admin,EndEntityApprovalData endEntityApprovalData, EndEntityInformation entity) throws Exception {
		if(endEntityApprovalData != null && endEntityApprovalData.getUserName() != null && endEntityApprovalData.getUserName().length() > 0)
			entityManager.persist(endEntityApprovalData);
		endEntityManagementSession.addUser(admin, entity, false);
	}
	
	@Override
	@Transactional
	public void updateEndEntityWithPassword(AuthenticationToken admin,EndEntityApprovalData endEntityApprovalData, EndEntityInformation entity) throws Exception {
		if(endEntityApprovalData != null && endEntityApprovalData.getUserName() != null && endEntityApprovalData.getUserName().length() > 0)
			entityManager.persist(endEntityApprovalData);
		endEntityManagementSession.changeUser(admin, entity, false);
	}

	@Override
	public String randomPwd(int length) {
		String s = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";// +"!@#$%&*()<>?";
		char[] sarray = s.toCharArray();
		StringBuffer buff = new StringBuffer();
		for (int i = 0; i < length; i++) {
			int idx = (int) (Math.random() * s.length());
			buff.append(sarray[idx]);
		}
		return buff.toString();
	}

	@Override
	public List<CAData> getCaList() {

		String hql = "SELECT a FROM CAData a WHERE a.status=1 ";
		Query query = entityManager.createQuery(hql);

		List<CAData> caList = query.getResultList();
		return caList;
	}

	@Override
	@Transactional
	public void apiCallLog(CallLogData callLog) {
		// TODO Auto-generated method stub

		callLog.setLogTime(System.currentTimeMillis());
		entityManager.persist(callLog);
	}

	@Override
	public CAData getCaByUser(String username) {
		// TODO Auto-generated method stub
		String hql = "SELECT a FROM UserData a WHERE a.username=:username ";
		Query query = entityManager.createQuery(hql);
		query.setParameter("username", username);
		List<UserData> userList = query.getResultList();
		int caId = 0;
		if (userList != null && !userList.isEmpty()) {
			UserData userData = userList.get(0);
			caId = userData.getCaId();
		}
		CAData caData = null;
		if (caId != 0) {
			hql = "SELECT a FROM CAData a WHERE a.caId=:caId ";
			query = entityManager.createQuery(hql);
			query.setParameter("caId", caId);
			// caData = (CAData)query.getSingleResult();
			List<CAData> caList = query.getResultList();
			if (caList != null && !caList.isEmpty()) {
				caData = caList.get(0);
			}
		}

		return caData;
	}

	@Override
	public CallConfigData getCallConfigByIP(String ip) {
		String hql = "SELECT a FROM CallConfigData a WHERE a.ip=:ip ";
		Query query = entityManager.createQuery(hql);
		query.setParameter("ip", ip);
		List<CallConfigData> userList = query.getResultList();
		int caId = 0;
		CallConfigData callConfigData = null;
		if (userList != null && !userList.isEmpty()) {
			callConfigData = userList.get(0);
		}
		return callConfigData;
	}

	@Override
	public List<Certificate> findTCert(AuthenticationToken authenticationToken, String caName, int certCount,
			int validityPeriod, boolean isEncrypt, String attr_names) {
		return null;

	}

	@Override
	public ApiAdminUserData getApiAdminUserData(String adminName) {
		String hql = "SELECT a FROM ApiAdminUserData a WHERE a.username=:username ";
		Query query = entityManager.createQuery(hql);
		query.setParameter("username", adminName);
		List<ApiAdminUserData> userList = query.getResultList();

		ApiAdminUserData admin = null;
		if (userList != null && !userList.isEmpty()) {
			admin = userList.get(0);
		}
		return admin;
	}
	private static String getFormattedText(byte[] bytes) { 
		 char[] HEX_DIGITS = {'0', '1', '2', '3', '4', '5',  
                 '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};  
        int len = bytes.length;  
        StringBuilder buf = new StringBuilder(len * 2);  
        // 把密文转换成十六进制的字符串形式  
        for (int j = 0; j < len; j++) {  
            buf.append(HEX_DIGITS[(bytes[j] >> 4) & 0x0f]);  
            buf.append(HEX_DIGITS[bytes[j] & 0x0f]);  
        }  
        return buf.toString();  
    }

	@Override
	public EndEntityInformation createEndEntityInformation(JsonObject jsonObject,EnrollCertForm form, EndEntityApprovalData endEntityApprovalData) throws Exception {
		String userName = jsonObject.get("userName") == null ? null : jsonObject.get("userName").getAsString();
		String password = jsonObject.get("password") == null ? null : jsonObject.get("password").getAsString();
		String cn = jsonObject.get("CN") == null ? null : jsonObject.get("CN").getAsString();
		String o = jsonObject.get("O") == null ? null : jsonObject.get("O").getAsString();
		String ou = jsonObject.get("OU") == null ? null : jsonObject.get("OU").getAsString();
		String l = jsonObject.get("L") == null ? null : jsonObject.get("L").getAsString();
		String s = jsonObject.get("S") == null ? null : jsonObject.get("S").getAsString();
		String c = jsonObject.get("C") == null ? null : jsonObject.get("C").getAsString();
		String e = jsonObject.get("E") == null ? null : jsonObject.get("E").getAsString();
		long processId = jsonObject.get("processId") == null ? 0 : jsonObject.get("processId").getAsLong();
		int certType = jsonObject.get("certType") == null ? 0 : jsonObject.get("certType").getAsInt();
		int reqType = jsonObject.get("reqType") == null ? 0 : jsonObject.get("reqType").getAsInt();
		int keyType = jsonObject.get("keyType") == null ? 0 : jsonObject.get("keyType").getAsInt();
		String csr = jsonObject.get("CSR") == null ? null : jsonObject.get("CSR").getAsString();
		if(userName == null || password == null || cn == null || certType == 0 || reqType == 0) throw new Exception(BciaRequestResult.RESULT_CODE_EE_INFO_ERROR);
		if(processId == 0) throw new Exception(BciaRequestResult.RESULT_CODE_CERT_PROCESS_ERROR);
		CertProcessData certProcess = CertProcessData.getCertProccess(entityManager, processId);
		EndEntityProfileData epd = EndEntityProfileData.findByProfileName(entityManager, certProcess.getEndEntityProfileName());
		CertificateProfileData cpd = CertificateProfileData.findByProfileName(entityManager, certProcess.getCertProfileName());
		EndEntityInformation entity = new EndEntityInformation();
		entity.setEndEntityProfileId(epd.getId());
		entity.setCertificateProfileId(cpd.getId());
		entity.setDN(getSubjectDn(cn, o, ou, l, s, c, e));
		entity.setStatus(EndEntityConstants.STATUS_NEW);
		//entity.setSubjectAltName(getEntitySubject(ENTITY_SUBJECT_SAN_ORDER, request, profileMap));
		entity.setEmail(e);
		entity.setPassword(password);
		CAData caData = CAData.findByName(entityManager, certProcess.getCaName());
		entity.setCAId(caData.getCaId());
		entity.setUsername(userName);
		entity.setTokenType(certType);
		entity.setType(new EndEntityType(EndEntityTypes.ENDUSER));
		form.setUsername(userName);
		form.setPassword(password);
		form.setCsr(csr);
		if(keyType == 2) {
			form.setKeyAlg("ECDSA");
			form.setKeyLength("sm2p256v1");
		}
		form.setTokenType(certType);
		form.setCertProfile(cpd.getCertificateProfileName());
		return entity;
	} 
	
	public JsonObject checkEndEntityInfo(JsonObject jsonObject){
		String userName = jsonObject.get("userName") == null ? null : jsonObject.get("userName").getAsString();
		String password = jsonObject.get("password") == null ? null : jsonObject.get("password").getAsString();
		String cn = jsonObject.get("CN") == null ? null : jsonObject.get("CN").getAsString();
		long processId = jsonObject.get("processId") == null ? 0 : jsonObject.get("processId").getAsLong();
		int certType = jsonObject.get("certType") == null ? 0 : jsonObject.get("certType").getAsInt();
		int reqType = jsonObject.get("reqType") == null ? 0 : jsonObject.get("reqType").getAsInt();
		String csr = jsonObject.get("CSR") == null ? null : jsonObject.get("CSR").getAsString();
		JsonObject result = new JsonObject();
		if(userName == null || password == null || cn == null || certType == 0 || reqType == 0) {
			result.addProperty("resultCode", BciaRequestResult.RESULT_CODE_EE_INFO_ERROR);
			return result;
		}
		if(processId == 0 || CertProcessData.getCertProccess(entityManager, processId) == null) {
			result.addProperty("resultCode", BciaRequestResult.RESULT_CODE_CERT_PROCESS_ERROR);
			return result;
		}
		if(certType == SecConst.TOKEN_SOFT_BROWSERGEN && csr == null) {
			result.addProperty("resultCode", BciaRequestResult.RESULT_CODE_CSR_ERROR);
			return result;
		}
		if(reqType == 1 && UserData.findByUsername(entityManager, userName) != null) {
			result.addProperty("resultCode", BciaRequestResult.RESULT_CODE_USERNAME_EXISTS_ERROR);
			return result;
		}
		if(reqType == 2) {
			UserData user = UserData.findByUsername(entityManager, userName);
			if(user == null) {
				result.addProperty("resultCode", BciaRequestResult.RESULT_CODE_USER_NO_EXISTS_ERROR);
				return result;
			}
			/*try {
				if(user != null && !comparePassword(password, user.getPasswordHash())) {
					result.addProperty("resultCode", BciaRequestResult.RESULT_CODE_USERNAME_PASSWORD_ERROR);
					return result;
				}
			} catch (NoSuchAlgorithmException e) {
				result.addProperty("resultCode", BciaRequestResult.RESULT_CODE_SYSTEM_ERROR);
				result.addProperty("errorMsg", "Check Password NoSuchAlgorithm ERROR:" + e.getMessage());
				return result;
			}*/
		}
		
		return result;
	}
	
	@Transactional 
	public BciaRequestResult revokeCert(AuthenticationToken administrator, JsonObject jsonObject) {
		if(jsonObject.get("userName") == null || jsonObject.get("serialNo") == null) 
			return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_REVOKE_PARAM_ERROR, "Error Request Paramters:null", 0, null);
		String userName = jsonObject.get("userName").getAsString();
		String serialNo = jsonObject.get("serialNo").getAsString();
		int reqType = jsonObject.get("reqType").getAsInt();
		int reason = jsonObject.get("revokeReason") == null ? 0 : jsonObject.get("revokeReason").getAsInt();
 		if(BciaRequestResult.REQ_TYPE_REVOKE_USER == reqType) {
 			boolean isExistsUser = endEntityManagementSessionBean.existsUser(userName);
 			if(!isExistsUser)
 				return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_USER_NO_EXISTS_ERROR, null, 0, null);
 		}
 		
		List<Integer> excludeStatus = new ArrayList<Integer>();
		excludeStatus.add(CertificateConstants.CERT_REVOKED);
		List<CertificateDataWrapper> list=certificatesession.getCertificateDataByUsername(userName, false, excludeStatus);
		if(list.size()<=0) {
			return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_REVOKED, null, 0, null);
		}else{
			CertificateView  certificatedata = null;
			for(int i=0;i<list.size();i++) {
				CertificateView  c = new CertificateView(list.get(i));
				if(c.getSerialNumber().equals(serialNo.toUpperCase())) {
					certificatedata = c;
					break;
				}
			}
			if(certificatedata == null) 
				return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_REVOKED, null, 0, null);
			try {
				endEntityManagementSessionBean.revokeCert(administrator, certificatedata.getSerialNumberBigInt(), certificatedata.getIssuerDN(), reason);
				return new BciaRequestResult(true, BciaRequestResult.RESULT_CODE_SUCCESS, null, 0, null);
			} catch (Exception e) {
				e.printStackTrace();
				return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_REVOKE_FAILURE, e.getMessage(), 0, null);
			}
  		}
	}
	
    @Transactional 
	public BciaRequestResult revokeUser(AuthenticationToken administrator, JsonObject jsonObject) {
    	if(jsonObject.get("userName") == null || jsonObject.get("reqType") == null) 
			return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_REVOKE_PARAM_ERROR, "Error Request Paramters:null", 0, null);
		String userName = jsonObject.get("userName").getAsString();
		int reqType = jsonObject.get("reqType").getAsInt();
		int reason = jsonObject.get("revokeReason") == null ? 0 : jsonObject.get("revokeReason").getAsInt();
 		if(BciaRequestResult.REQ_TYPE_REVOKE_USER == reqType) {
 			boolean isExistsUser = endEntityManagementSessionBean.existsUser(userName);
 			if(!isExistsUser)
 				return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_USER_NO_EXISTS_ERROR, null, 0, null);
 		}
 		UserData userData = UserData.findByUsername(entityManager, userName);
		List<Integer> excludeStatus = new ArrayList<Integer>();
		excludeStatus.add(CertificateConstants.CERT_REVOKED);
		List<CertificateDataWrapper> list = certificatesession.getCertificateDataByUsername(userName, false, excludeStatus);
		if(list.size()<=0 || userData.getStatus() == EndEntityConstants.STATUS_REVOKED) {
			return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_REVOKED, null, 0, null);
		}else{
			try {
				endEntityManagementSessionBean.revokeUser(administrator, userName, reason);
				return new BciaRequestResult(true, BciaRequestResult.RESULT_CODE_SUCCESS, null, 0, null);
			} catch (Exception e) {
				e.printStackTrace();
				return new BciaRequestResult(false, BciaRequestResult.RESULT_CODE_REVOKE_FAILURE, e.getMessage(), 0, null);
			}
  		}
	}
	
	private boolean comparePassword(String password,String passwordHash) throws NoSuchAlgorithmException {
        boolean ret = false;
        if (password != null) {
        	String hash = passwordHash;
            ret = BCrypt.checkpw(password, hash);        		
        }
        return ret;
    }
	
	private  String getSubjectDn(String cn, String o, String ou, String l, String s, String c, String email){
		StringBuilder sb = new StringBuilder();
		if(!StringUtils.isEmpty(cn))
			sb.append("CN=").append(cn).append(",");
		if(!StringUtils.isEmpty(o))
			sb.append("O=").append(o).append(",");
		if(!StringUtils.isEmpty(ou))
			sb.append("OU=").append(ou).append(",");
		if(!StringUtils.isEmpty(l))
			sb.append("L=").append(l).append(",");
		if(!StringUtils.isEmpty(s))
			sb.append("ST=").append(s).append(",");
		if(!StringUtils.isEmpty(c))
			sb.append("C=").append(c).append(",");
		if(!StringUtils.isEmpty(email))
			sb.append("E=").append(email).append(",");
		if(sb.toString().length() > 0){
			return sb.toString().substring(0,sb.toString().length() - 1);
		}else{
			return null;
		}
	}
}
