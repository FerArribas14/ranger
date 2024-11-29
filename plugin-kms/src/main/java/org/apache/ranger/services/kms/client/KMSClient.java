/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.services.kms.client;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.HadoopKerberosName;
import org.apache.hadoop.security.ProviderUtils;
import org.apache.hadoop.security.SecureClientLogin;
import org.apache.ranger.plugin.client.BaseClient;
import org.apache.ranger.plugin.util.PasswordUtils;
import org.apache.ranger.plugin.client.HadoopException;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class KMSClient {

	private static final Logger LOG = LoggerFactory.getLogger(KMSClient.class);

	private static final String EXPECTED_MIME_TYPE = "application/json";

	private static final String KMS_LIST_API_ENDPOINT = "v1/keys/names"; // GET

	private static final String errMessage = " You can still save the repository and start creating "
			+ "policies, but you would not be able to use autocomplete for "
			+ "resource names. Check ranger_admin.log for more info.";
	
	private static final String AUTH_TYPE_KERBEROS = "kerberos";

	String provider;
	String username;
	String password;
	String rangerPrincipal;
	String rangerKeytab;
	String nameRules;
	String authType;

	public KMSClient(String provider, String username, String password, String rangerPrincipal, String rangerKeytab, String nameRules, String authType) {
		this.provider = provider;
		this.username = username;
		this.password = password;
		this.rangerPrincipal = rangerPrincipal;
		this.rangerKeytab = rangerKeytab;
		this.nameRules = nameRules;
		this.authType = authType;
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("Kms Client is build with url [" + provider + "] user: ["
					+ username + "]");
		}
	}

	private String[] createProvider(String uri) throws IOException,
			URISyntaxException {
		URI providerUri = new URI(uri);
		URL origUrl = new URL(extractKMSPath(providerUri).toString());
		String authority = origUrl.getAuthority();
		// check for ';' which delimits the backup hosts
		if (StringUtils.isEmpty(authority)) {
			throw new IOException("No valid authority in kms uri [" + origUrl
					+ "]");
		}
		// Check if port is present in authority
		// In the current scheme, all hosts have to run on the same port
		int port = -1;
		String hostsPart = authority;
		if (authority.contains(":")) {
			String[] t = authority.split(":");
			try {
				port = Integer.parseInt(t[1]);
			} catch (Exception e) {
				throw new IOException("Could not parse port in kms uri ["
						+ origUrl + "]");
			}
			hostsPart = t[0];
		}
		return createProvider(origUrl, port, hostsPart);
	}

	private static Path extractKMSPath(URI uri) throws MalformedURLException,
			IOException {
		return ProviderUtils.unnestUri(uri);
	}

	private String[] createProvider(URL origUrl, int port,
			String hostsPart) throws IOException {
		String[] hosts = hostsPart.split(";");
		String[] providers = new String[hosts.length];
		if (hosts.length == 1) {
			providers[0] = origUrl.toString();
		} else {
			for (int i = 0; i < hosts.length; i++) {
				try {
					String url = origUrl.getProtocol() + "://" + hosts[i] + ":"
							+ port + origUrl.getPath();
					providers[i] = new URI(url).toString();
				} catch (URISyntaxException e) {
					throw new IOException("Could not Prase KMS URL..", e);
				}
			}
		}
		return providers;
	}

	public List<String> getKeyList(final String keyNameMatching,
			final List<String> existingKeyList) {
		
		String providers[] = null;
		try {
			providers = createProvider(provider);
		} catch (IOException | URISyntaxException e) {
			return null;
		}
		final String errMsg = errMessage;
		List<String> lret = null;
		for (int i = 0; i < providers.length; i++) {
			lret  = new ArrayList<String>();
			if (LOG.isDebugEnabled()) {
				LOG.debug("Getting Kms Key list for keyNameMatching : " + keyNameMatching);
			}
			String uri = providers[i] + (providers[i].endsWith("/") ? KMS_LIST_API_ENDPOINT : ("/" + KMS_LIST_API_ENDPOINT));
			Client client = null;
			Response response = null;
			boolean isKerberos = false;
			try {
				client = ClientBuilder.newBuilder()
						.property(ClientProperties.FOLLOW_REDIRECTS, true)
						.build();
							
				if(authType != null && authType.equalsIgnoreCase(AUTH_TYPE_KERBEROS)){
					isKerberos = true;
				}
				
				Subject sub = new Subject();
				if(!isKerberos){
					uri = uri.concat("?user.name="+username);
					WebTarget webResource = client.target(uri);
					response = webResource.request(EXPECTED_MIME_TYPE).get(Response.class);
					LOG.info("Init Login: security not enabled, using username");
					sub = SecureClientLogin.login(username);					
				}else{										
					if(!StringUtils.isEmpty(rangerPrincipal) && !StringUtils.isEmpty(rangerKeytab)){
						LOG.info("Init Lookup Login: security enabled, using rangerPrincipal/rangerKeytab");
						if(StringUtils.isEmpty(nameRules)){
							nameRules = "DEFAULT";
						}
						String shortName = new HadoopKerberosName(rangerPrincipal).getShortName();
						uri = uri.concat("?doAs="+shortName);						
						sub = SecureClientLogin.loginUserFromKeytab(rangerPrincipal, rangerKeytab, nameRules);
					}
					else{
						LOG.info("Init Login: using username/password");
						String shortName = new HadoopKerberosName(username).getShortName();
						uri = uri.concat("?doAs="+shortName);
						String decryptedPwd = PasswordUtils.decryptPassword(password);
						sub = SecureClientLogin.loginUserWithPassword(username, decryptedPwd);						
					}
				}
				final WebTarget webResource = client.target(uri);
				response = Subject.doAs(sub, new PrivilegedAction<Response>() {
					@Override
					public Response run() {
						return webResource.request(EXPECTED_MIME_TYPE).get(Response.class);
					}
				});
				
				if (LOG.isDebugEnabled()) {
					LOG.debug("getKeyList():calling " + uri);
				}
				if (response != null) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("getKeyList():response.getStatus()= "
								+ response.getStatus());
					}
					if (response.getStatus() == 200) {
						String jsonString = response.readEntity(String.class);
						Gson gson = new GsonBuilder().setPrettyPrinting()
								.create();
						@SuppressWarnings("unchecked")
						List<String> keys = gson.fromJson(jsonString,
								List.class);
						if (keys != null) {
							for (String key : keys) {
								if (existingKeyList != null
										&& existingKeyList.contains(key)) {
									continue;
								}
								if (keyNameMatching == null
										|| keyNameMatching.isEmpty()
										|| key.startsWith(keyNameMatching)) {
									if (LOG.isDebugEnabled()) {
										LOG.debug("getKeyList():Adding kmsKey "
												+ key);
									}
									lret.add(key);
								}
							}
							return lret;
						}						
					} else if (response.getStatus() == 401) {
						LOG.info("getKeyList():response.getStatus()= "
								+ response.getStatus() + " for URL " + uri
								+ ", so returning null list");
						String msgDesc = response.readEntity(String.class);
						HadoopException hdpException = new HadoopException(msgDesc);
						hdpException.generateResponseDataMap(false, msgDesc,
								msgDesc + errMsg, null, null);
						lret = null;
						throw hdpException;
					} else if (response.getStatus() == 403) {
						LOG.info("getKeyList():response.getStatus()= "
								+ response.getStatus() + " for URL " + uri
								+ ", so returning null list");
						String msgDesc = response.readEntity(String.class);
						HadoopException hdpException = new HadoopException(msgDesc);
						hdpException.generateResponseDataMap(false, msgDesc,
								msgDesc + errMsg, null, null);
						lret = null;
						throw hdpException;
					} else {
						LOG.info("getKeyList():response.getStatus()= "
								+ response.getStatus() + " for URL " + uri
								+ ", so returning null list");
						String jsonString = response.readEntity(String.class);
						LOG.info(jsonString);
						lret = null;
					}
				} else {
					String msgDesc = "Unable to get a valid response for "
							+ "expected mime type : [" + EXPECTED_MIME_TYPE
							+ "] URL : " + uri + " - got null response.";
					LOG.error(msgDesc);
					HadoopException hdpException = new HadoopException(msgDesc);
					hdpException.generateResponseDataMap(false, msgDesc,
							msgDesc + errMsg, null, null);
					lret = null;
					throw hdpException;
				}
			} catch (HadoopException he) {
				lret = null;
				throw he;
			} catch (Throwable t) {
				String msgDesc = "Exception while getting Kms Key List. URL : "
						+ uri;
				HadoopException hdpException = new HadoopException(msgDesc, t);
				LOG.error(msgDesc, t);
				hdpException.generateResponseDataMap(false,
						BaseClient.getMessage(t), msgDesc + errMsg, null, null);
				lret = null;
				throw hdpException;
			} finally {
				if (response != null) {
					response.close();
				}

				if (client != null) {
					client.close();
				}
				
				if(lret == null){
					if (i != providers.length - 1)
						continue;					
				}
			}			
		}
		return lret;
	}

	public static Map<String, Object> testConnection(String serviceName, //NOPMD
			Map<String, String> configs) {

		List<String> strList = new ArrayList<String>();
		String errMsg = errMessage;
		boolean connectivityStatus = false;
		Map<String, Object> responseData = new HashMap<String, Object>();

		KMSClient kmsClient = getKmsClient(serviceName, configs);
		strList = getKmsKey(kmsClient, "", null);
		if (strList != null) {
			connectivityStatus = true;
		}
		if (connectivityStatus) {
			String successMsg = "TestConnection Successful";
			BaseClient.generateResponseDataMap(connectivityStatus, successMsg,
					successMsg, null, null, responseData);
		} else {
			String failureMsg = "Unable to retrieve any Kms Key using given URL.";
			BaseClient.generateResponseDataMap(connectivityStatus, failureMsg,
					failureMsg + errMsg, null, null, responseData);
		}

		return responseData;
	}

	public static KMSClient getKmsClient(String serviceName,
			Map<String, String> configs) {
		KMSClient kmsClient = null;
		if (LOG.isDebugEnabled()) {
			LOG.debug("Getting KmsClient for datasource: " + serviceName);
			LOG.debug("configMap: " + configs);
		}
		String errMsg = errMessage;
		if (configs == null || configs.isEmpty()) {
			String msgDesc = "Could not connect as Connection ConfigMap is empty.";
			LOG.error(msgDesc);
			HadoopException hdpException = new HadoopException(msgDesc);
			hdpException.generateResponseDataMap(false, msgDesc, msgDesc
					+ errMsg, null, null);
			throw hdpException;
		} else {
			String kmsUrl = configs.get("provider");
			String kmsUserName = configs.get("username");
			String kmsPassWord = configs.get("password");
			String rangerPrincipal = configs.get("rangerprincipal");
			String rangerKeytab = configs.get("rangerkeytab");
			String nameRules = configs.get("namerules");
			String authType = configs.get("authtype");
			
			kmsClient = new KMSClient(kmsUrl, kmsUserName, kmsPassWord, rangerPrincipal, rangerKeytab, nameRules, authType);

		}
		return kmsClient;
	}

	public static List<String> getKmsKey(final KMSClient kmsClient,
			String keyName, List<String> existingKeyName) {

		List<String> resultList = new ArrayList<String>();
		String errMsg = errMessage;

		try {
			if (kmsClient == null) {
				String msgDesc = "Unable to get Kms Key : KmsClient is null.";
				LOG.error(msgDesc);
				HadoopException hdpException = new HadoopException(msgDesc);
				hdpException.generateResponseDataMap(false, msgDesc, msgDesc
						+ errMsg, null, null);
				throw hdpException;
			}

			if (keyName != null) {
				String finalkmsKeyName = keyName.trim();
				resultList = kmsClient.getKeyList(finalkmsKeyName,
						existingKeyName);
				if (resultList != null) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("Returning list of " + resultList.size()
								+ " Kms Keys");
					}
				}
			}
		} catch (HadoopException he) {
			resultList = null;
			throw he;
		} catch (Exception e) {
			String msgDesc = "Unable to get a valid response from the provider : "+e.getMessage();
			LOG.error(msgDesc, e);
			HadoopException hdpException = new HadoopException(msgDesc);
			hdpException.generateResponseDataMap(false, msgDesc, msgDesc
					+ errMsg, null, null);
			resultList = null;
			throw hdpException;
		}
		return resultList;
	}
}
