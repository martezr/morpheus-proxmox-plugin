package com.morpheusdata.proxmox.utils

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.RestApiUtil
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.entity.ContentType


@Slf4j
class ProxmoxComputeUtility {
	static testConnection(HttpApiClient client, Map authConfig) {
		def rtn = [success:false, invalidLogin:false]
		try {
			def listResults = listHosts(client, authConfig)
			rtn.success = listResults.success
		} catch(e) {
			log.error("testConnection to ${authConfig.apiUrl}: ${e}")
		}
		return rtn
	}

	static ServiceResponse listHosts(HttpApiClient client, Map authConfig) {
		log.info("listHosts")
		log.info("AUTH CONFIG: ${authConfig}")
		def rtn = new ServiceResponse(success: false)
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/nodes", "", "",
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json',"Authorization":"PVEAPIToken=${authConfig.username}=${authConfig.token}"], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		log.info("CALL JSON API RESPONSE: ${results}")
		/*
		{success=true, msg=null, errors={}, data={data=[{id=node/grtproxmox01, uptime=7151, mem=1370411008, disk=2809053184, maxdisk=22736429056, maxcpu=4, level=, ssl_fingerprint=1B:28:97:57:CE:81:37:60:AB:CF:13:D3:9B:7F:86:15:27:1D:14:79:29:7A:2F:B2:A1:AA:F3:C0:17:32:24:81, node=grtproxmox01, type=node, maxmem=25174564864, status=online, cpu=0.00570032573289902}]}}
		*/
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error listing snapshots for cluster", null, results.data)
		}
	}

	static ServiceResponse getHostNetworking(HttpApiClient client, Map authConfig) {
		log.info("AUTH CONFIG: ${authConfig}")
		def rtn = new ServiceResponse(success: false)
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/nodes", "", "",
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json',"Authorization":"PVEAPIToken=${authConfig.username}=${authConfig.token}"], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		log.info("CALL JSON API RESPONSE: ${results}")
		if(results?.success) {
			log.info("HOST NETWORKING: ${results?.data.data[0].node}")
			def networkResults = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/nodes/${results?.data.data[0].node}/network", "", "",
			    new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json',"Authorization":"PVEAPIToken=${authConfig.username}=${authConfig.token}"], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
			log.info("CALL JSON API RESPONSE: ${networkResults}")
			return ServiceResponse.success(networkResults?.data)
		} else {
			return ServiceResponse.error("Error listing snapshots for cluster", null, results.data)
		}
	}

	static ServiceResponse getHostStorage(HttpApiClient client, Map authConfig) {
		log.info("AUTH CONFIG: ${authConfig}")
		def rtn = new ServiceResponse(success: false)
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/nodes", "", "",
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json',"Authorization":"PVEAPIToken=${authConfig.username}=${authConfig.token}"], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		log.info("CALL JSON API RESPONSE: ${results}")
		if(results?.success) {
			def storageResults = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/nodes/${results?.data.data[0].node}/storage", "", "",
			    new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json',"Authorization":"PVEAPIToken=${authConfig.username}=${authConfig.token}"], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
			log.info("CALL JSON API RESPONSE: ${storageResults}")
			return ServiceResponse.success(storageResults?.data)
		} else {
			return ServiceResponse.error("Error listing snapshots for cluster", null, results.data)
		}
	}

	static ServiceResponse getHostVirtualMachines(HttpApiClient client, Map authConfig) {
		log.info("AUTH CONFIG: ${authConfig}")
		def rtn = new ServiceResponse(success: false)
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/nodes", "", "",
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json',"Authorization":"PVEAPIToken=${authConfig.username}=${authConfig.token}"], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		log.info("CALL JSON API RESPONSE: ${results}")
		if(results?.success) {
			def vmResults = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/nodes/${results?.data.data[0].node}/qemu", "", "",
			    new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json',"Authorization":"PVEAPIToken=${authConfig.username}=${authConfig.token}"], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
			log.info("CALL JSON API RESPONSE: ${vmResults}")
			return ServiceResponse.success(vmResults?.data)
		} else {
			return ServiceResponse.error("Error listing snapshots for cluster", null, results.data)
		}
	}
}