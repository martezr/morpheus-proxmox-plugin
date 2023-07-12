package com.morpheusdata.proxmox.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Network
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.proxmox.ProxmoxPlugin
import com.morpheusdata.proxmox.utils.ProxmoxComputeUtility
import com.morpheusdata.model.NetworkType
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class NetworksSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private ProxmoxPlugin plugin
	private HttpApiClient apiClient
	private Collection<NetworkType> networkTypes


	public NetworksSync(ProxmoxPlugin proxmoxPlugin, Cloud cloud, HttpApiClient apiClient, Collection<NetworkType> networkTypes) {
		this.plugin = proxmoxPlugin
		this.cloud = cloud
		this.morpheusContext = proxmoxPlugin.morpheusContext
		this.apiClient = apiClient
		this.networkTypes = networkTypes
	}

	def execute() {
		log.debug "BEGIN: execute NetworksSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = ProxmoxComputeUtility.getHostNetworking(apiClient, authConfig)
			def cloudItems = []
			cloudItems = listResults.data.data
			if (listResults.success) {
				def domainRecords = morpheusContext.cloud.network.listSyncProjections(cloud.id)
				SyncTask<NetworkIdentityProjection, Map, ComputeZonePool> syncTask = new SyncTask<>(domainRecords, cloudItems as Collection<Map>)
				syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.iface
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.cloud.network.listById(updateItems?.collect { it.existingItem.id }).map { Network network ->
						SyncTask.UpdateItemDto<NetworkIdentityProjection, Map> matchItem = updateItemMap[network.id]
						return new SyncTask.UpdateItem<NetworkIdentityProjection, Map>(existingItem: network, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					def networkAdds = []
					itemsToAdd?.each { cloudItem ->
						log.info "Network Type: ${cloudItem.type}"
						log.info "Network ENTRY: ${cloudItem}"
						// Skip eth type
						if (cloudItem.type == "eth") {
							return
						}
						def networkTypeString = cloudItem.type
						def networkType = networkTypes?.find { it.externalType == networkTypeString }
						def networkConfig = [
								owner       : new Account(id: cloud.owner.id),
								category    : "proxmox.network.${cloud.id}",
								name        : cloudItem.iface,
								displayName : cloudItem.iface,
								code        : "proxmox.network.${cloud.id}.${cloudItem.iface}",
								uniqueId    : cloudItem.iface,
								externalId  : cloudItem.iface,
								dhcpServer  : true,
								externalType: networkTypeString,
								type        : networkType,
								refType     : 'ComputeZone',
								refId       : cloud.id,
								//zonePoolId  : vpcId ?: clusterId,
								active      : true
						]

						Network networkAdd = new Network(networkConfig)
						//if(clusterId) {
						//	networkConfig.assignedZonePools = [new ComputeZonePool(id: clusterId)]
						//}
						networkAdds << networkAdd
					}
					//create networks
					morpheusContext.cloud.network.create(networkAdds).blockingGet()
				}.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
					List<Network> itemsToUpdate = []
					for (item in updateItems) {
						def masterItem = item.masterItem
						Network existingItem = item.existingItem
						//def cluster = clusters?.find { it.externalId == masterItem.status?.cluster_reference?.uuid}
						//def clusterId = cluster?.id
						def save = false
						if (existingItem) {
							//if (existingItem.zonePoolId != clusterId) {
							//	existingItem.zonePoolId = clusterId
							//	save = true
							//}
							def name = masterItem.status.name
							if (existingItem.name != name) {
								existingItem.name = name
								//if(clusterId) {
								//	existingItem.displayName = "${name} ${cluster.name}"
								//} else {
								//	existingItem.displayName = name
								//}
								save = true
							}
							def networkType = masterItem.status.resources.subnet_type
							if (existingItem.externalType != networkType) {
								existingItem.externalType = networkType
								save = true
							}
							if (!existingItem.type) {
								existingItem.type = networkTypes?.find { it.externalType == networkType }
								save = true
							}
							//if (clusterId && !existingItem.assignedZonePools?.find { it.id == clusterId }) {
							//	existingItem.assignedZonePools += new ComputeZonePool(id: clusterId)
							//	save = true
							//}
							if (save) {
								itemsToUpdate << existingItem
							}
						}
					}
					if (itemsToUpdate.size() > 0) {
						morpheusContext.cloud.network.save(itemsToUpdate).blockingGet()
					}

				}.onDelete { removeItems ->
					morpheusContext.cloud.network.remove(removeItems).blockingGet()
				}.start()

			}

		} catch(e) {
			log.error "Error in execute of NetworksSync: ${e}", e
		}
		log.debug "END: execute NetworksSync: ${cloud.id}"
	}
}