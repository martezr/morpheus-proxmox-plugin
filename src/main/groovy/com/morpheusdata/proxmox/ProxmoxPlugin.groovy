package com.morpheusdata.proxmox

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class ProxmoxPlugin extends Plugin {

	@Override
	String getCode() {
		return 'proxmox-plugin'
	}

	@Override
	void initialize() {
		// Plugin name
		this.setName("Proxmox")

		// Cloud provider
		ProxmoxCloudProvider cloudProvider = new ProxmoxCloudProvider(this, morpheus)
        this.pluginProviders.put(cloudProvider.code, cloudProvider)

		// Provision provider
		ProxmoxProvisionProvider provisionProvider = new ProxmoxProvisionProvider(this, morpheus)
        this.pluginProviders.put(provisionProvider.code, provisionProvider)
	}

	@Override
	void onDestroy() {
	}

	MorpheusContext getMorpheusContext() {
		return morpheus
	}

	// Parse cloud credentials
	def getAuthConfig(Cloud cloud) {
		log.info "getAuthConfig: ${cloud}"
		def rtn = [
				apiUrl: cloud.configMap.serviceUrl,
				basePath: 'api2/json',
				username: null,
				token: null
		]

		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = this.morpheus.cloud.loadCredentials(cloud.id).blockingGet()
			} catch(e) {
				// If there is no credential on the cloud, then this will error
				// TODO: Change to using 'maybe' rather than 'blockingGet'?
			}
			cloud.accountCredentialLoaded = true
			cloud.accountCredentialData = accountCredential?.data
		}

		log.info "CRED INFO ${cloud.accountCredentialData}"
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
			rtn.username = cloud.accountCredentialData['username']
		} else {
			rtn.username = cloud.serviceUsername
		}

		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('token')) {
			log.info "FOUND TOKEN"
			rtn.token = cloud.accountCredentialData['token']
		}

		return rtn
	}

	static getApiUrl(String apiUrl) {
		if(apiUrl) {
			def rtn = apiUrl
			if(rtn.startsWith('http') == false)
				rtn = 'https://' + rtn

			if(rtn.endsWith('/') == false)
				rtn = rtn + '/'

			return rtn
		}
	}
}
