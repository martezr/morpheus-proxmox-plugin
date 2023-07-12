package com.morpheusdata.proxmox.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.proxmox.ProxmoxPlugin
import com.morpheusdata.proxmox.utils.ProxmoxComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class DatastoresSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private ProxmoxPlugin plugin
	private HttpApiClient apiClient

	public DatastoresSync(ProxmoxPlugin proxmoxPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = proxmoxPlugin
		this.cloud = cloud
		this.morpheusContext = proxmoxPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute DatastoresSync: ${cloud.id}"

		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = ProxmoxComputeUtility.getHostStorage(apiClient, authConfig)
            def cloudItems = []
            cloudItems = listResults.data.data
			if(listResults.success == true) {
				Observable domainRecords = morpheusContext.cloud.datastore.listSyncProjections(cloud.id)
				SyncTask<DatastoreIdentityProjection, Map, ComputeZonePool> syncTask = new SyncTask<>(domainRecords, cloudItems as Collection<Map>)
				syncTask.addMatchFunction { DatastoreIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.storage
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<DatastoreIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<DatastoreIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.cloud.datastore.listById(updateItems?.collect { it.existingItem.id }).map { Datastore datastore ->
						SyncTask.UpdateItemDto<DatastoreIdentityProjection, Map> matchItem = updateItemMap[datastore.id]
						return new SyncTask.UpdateItem<Datastore, Map>(existingItem: datastore, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					def adds = []
					itemsToAdd?.each { cloudItem ->
                        def online = false
                        if (cloudItem.active == 1){
                            online = true
                        }
						def datastoreConfig = [
								owner       : new Account(id: cloud.owner.id),
								name        : cloudItem.storage,
								externalId  : cloudItem.storage,
								cloud       : cloud,
								storageSize : cloudItem.total.toLong(),
								freeSpace   : cloudItem.avail.toLong(),
								type        : cloudItem.type,
								category    : "proxmox-datastore.${cloud.id}",
								drsEnabled  : false,
								online      : online
						]
						Datastore add = new Datastore(datastoreConfig)
						//add.assignedZonePools = [new ComputeZonePool(id: cluster?.id)]
						adds << add

					}
					morpheusContext.cloud.datastore.create(adds).blockingGet()
				}.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
					for(item in updateItems) {
						def masterItem = item.masterItem
						Datastore existingItem = item.existingItem
						def save = false

						if(save) {
							morpheusContext.cloud.datastore.save([existingItem]).blockingGet()
						}
					}
				}.onDelete { removeItems ->
					if(removeItems) {
						def datastores = morpheusContext.cloud.datastore.listById(removeItems.collect { it.id }).toList().blockingGet()
						datastores.each { Datastore removeItem ->
							morpheusContext.cloud.datastore.remove([removeItem], removeItem.zonePool).blockingGet()
						}
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute of DatastoresSync: ${e}", e
		}
		log.debug "END: execute DatastoresSync: ${cloud.id}"
	}
}