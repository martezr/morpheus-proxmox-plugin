package com.morpheusdata.proxmox.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.OsType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.proxmox.ProxmoxPlugin
import com.morpheusdata.proxmox.utils.ProxmoxComputeUtility
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.*
import com.morpheusdata.core.*
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.*



@Slf4j
class HostsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private ProxmoxPlugin plugin
	private HttpApiClient apiClient
	private Map authConfig

	public HostsSync(ProxmoxPlugin proxmoxPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = proxmoxPlugin
		this.cloud = cloud
		this.morpheusContext = proxmoxPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.info "syncing hosts for proxmox plugin: ${cloud}"

		try {
			// Fetch authentication configuration
			this.authConfig = plugin.getAuthConfig(cloud)

			def queryResults = [:]

			queryResults.serverType = new ComputeServerType(code: 'proxmox-hypervisor')
			queryResults.serverOs = new OsType(code: 'promox.7')

			def poolListProjections = []
			morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').filter { poolProjection ->
				return poolProjection.internalId != null && poolProjection.type == 'Cluster'
			}.blockingSubscribe { poolListProjections << it }
			queryResults.clusters = []
			morpheusContext.cloud.pool.listById(poolListProjections.collect { it.id }).blockingSubscribe { queryResults.clusters << it }

			def cloudItems = []
			def listResultSuccess = false
			// Fetch the lists of proxmox hypervisor hosts
			def listResults = ProxmoxComputeUtility.listHosts(apiClient, authConfig) // Need this one for the stats
			// Fetch the lists of proxmox hypervisor host networks
			def networkResults = ProxmoxComputeUtility.getHostNetworking(apiClient, authConfig) // Need this one for the stats
			log.info "HOST LISTS: ${listResults}"
			log.info "NETWORK RESTULTS: ${networkResults}"
			if (listResults.success) {
				listResultSuccess = true
				log.info "CLOUD ITEMS: ${listResults.data.data}"
				cloudItems = listResults.data.data
			}

			if (listResultSuccess) {
				Observable<ComputeServerIdentityProjection> domainRecords = morpheusContext.computeServer.listIdentityProjections(cloud.id,"").filter { ComputeServerIdentityProjection projection ->
					if (projection.category == "proxmox.host.${cloud.id}") {
						return true
					}
					false
				}

				//Observable<ComputeServerIdentityProjection> domainRecords = morpheusContext.computeServer.listSyncProjections(cloud.id)
				log.info "Domain Records: ${domainRecords}"
				log.info "Cloud Items: ${cloudItems}"

				SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudItems as Collection<Map>)
				syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.ref.toString()
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
						SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
						return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					addMissingHosts(cloud, queryResults.clusters, itemsToAdd)
				}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
					updateMatchedHosts(cloud, queryResults.clusters, updateItems)
				}.onDelete { removeItems ->
					removeMissingHosts(cloud, removeItems)
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
	}

	def removeMissingHosts(Cloud cloud, List removeList) {
		log.debug "removeMissingHosts: ${cloud} ${removeList.size()}"
		morpheusContext.computeServer.remove(removeList).blockingGet()
	}

	def updateMatchedHosts(Cloud cloud, List clusters, List updateList) {
		log.debug "updateMatchedHosts: ${cloud} ${updateList.size()}"

		def volumeType = new StorageVolumeType(code: 'vmware-plugin-datastore')

		List<ComputeZonePoolIdentityProjection> zoneClusters = []
		def clusterNames = updateList.collect{it.masterItem.cluster}.unique()
		morpheusContext.cloud.pool.listSyncProjections(cloud.id, null).filter {
			it.type == 'Cluster' && it.internalId in clusterNames
		}.blockingSubscribe { zoneClusters << it }

		def statsData = []
		for(update in updateList) {
			ComputeServer currentServer = update.existingItem
			def matchedServer = update.masterItem
			if(currentServer) {
				def save = false
				def clusterObj = zoneClusters?.find { pool -> pool.internalId == update.masterItem.cluster }
				if(currentServer.resourcePool?.id != clusterObj.id) {
					currentServer.resourcePool = new ComputeZonePool(id: clusterObj.id)
					save = true
				}
				def hostUuid = matchedServer.uuid
				if(hostUuid && currentServer.uniqueId != hostUuid) {
					currentServer.uniqueId = hostUuid
					save = true
				}
				if(save) {
					morpheusContext.computeServer.save([currentServer]).blockingGet()
				}
				syncHostDatastoreVolumes(currentServer, matchedServer, volumeType)
				updateHostStats(currentServer, matchedServer)
			}
		}
	}

    // Add Hypervisor Hosts
	def addMissingHosts(Cloud cloud, List clusters, List addList) {
		log.info "addMissingHosts: ${cloud} ${addList}"

        //def volumeType = new StorageVolumeType(code: 'vmware-plugin-datastore')
		def serverType = new ComputeServerType(code: 'proxmox-hypervisor')
		def serverOs = new OsType(code: 'proxmox.7')
		for(cloudItem in addList) {
			def clusterObj = clusters?.find{ pool -> pool.internalId == "test" }

			def serverConfig = [
							account:cloud.owner, 
							category:"proxmox.host.${cloud.id}", 
							cloud: cloud,
			                name: cloudItem.node, 
							resourcePool: clusterObj, 
							externalId:cloudItem.node, 
							uniqueId:cloudItem.node, 
							sshUsername:'root', 
							status:'provisioned',
			                provision:false, 
							serverType:'hypervisor', 
							computeServerType:serverType, 
							serverOs:serverOs,
			                osType:'linux', 
							hostname:cloudItem.node,
							externalIp: "10.0.0.192",
							platform: "7.3-3"
			]
			def newServer = new ComputeServer(serverConfig)
			newServer.maxCores = cloudItem.maxcpu
			newServer.maxStorage = 0
			newServer.capacityInfo = new ComputeCapacityInfo(maxMemory:cloudItem.maxmem,maxCores:cloudItem.maxcpu,maxStorage:cloudItem.maxdisk)
			if(!morpheusContext.computeServer.create([newServer]).blockingGet()){
				log.error "Error in creating host server ${newServer}"
			}

			//syncHostDatastoreVolumes(newServer, cloudItem, volumeType)
		}
	}

	private syncHostDatastoreVolumes(ComputeServer server, host, StorageVolumeType volumeType) {
		log.debug "syncHostDatastoreVolumes: ${server} ${host} ${volumeType}"
		def existingVolumes = server.volumes
		def hostDatastores = host.datastores.findAll{it.accessible}
		def datastores = []
		if(hostDatastores) {
			def uniqueExternalIds = host.datastores.collect{ds -> ds.ref}
			morpheusContext.cloud.datastore.listSyncProjections(cloud.id).filter {
				it.externalId in uniqueExternalIds
			}.blockingSubscribe { datastores << it }
		}
		def addList = []
		def updateList = []
		hostDatastores.each { ds ->
			def match = existingVolumes.find {it.externalId == ds.ref}
			if(match) {
				def save = false
				if(match.maxStorage != ds.summary.capacity) {
					match.maxStorage = ds.summary.capacity
					save = true
				}
				if(match.name != ds.name) {
					match.name = ds.name
					save = true
				}
				if(save) {
					updateList << match
				}
			} else {
				def newVolume = new StorageVolume(
						[
								type      : volumeType,
								maxStorage: ds.summary.capacity,
								externalId: ds.ref,
								name      : ds.name
						]
				)
				newVolume.datastore = datastores?.find{dsobj -> dsobj.externalId == ds.ref}

				addList << newVolume
			}
		}
		if(updateList?.size() > 0) {
			log.debug "Saving ${updateList.size()} storage volumes"
			morpheusContext.storageVolume.save(updateList).blockingGet()
		}

		def removeList = existingVolumes.findAll{vol -> !hostDatastores.find{ds -> ds.ref == vol.externalId}}
		if(removeList?.size() > 0) {
			log.debug "Removing ${removeList.size()} storage volumes"
			morpheusContext.storageVolume.remove(removeList, server, false).blockingGet()
		}

		if(addList?.size() > 0) {
			log.debug "Adding ${addList.size()} storage volumes"
			morpheusContext.storageVolume.create(addList, server).blockingGet()
		}
	}

	private updateHostMetrics(ComputeServer server, hostMap) {
		log.debug "updateHostStats for ${server}"
		try {
			//storage
			def host = hostMap //.entity
			def datastores = host.datastores
			def maxStorage = 0
			def maxUsedStorage = 0
			def maxFreeStorage = 0
			datastores?.each { datastore ->
				def summary = datastore.summary
				if(summary && summary.accessible) {
					maxStorage += (summary.capacity ?: 0)
					maxFreeStorage += (summary.freeSpace ?: 0)
					maxUsedStorage = maxStorage - maxFreeStorage
				}
			}
			def runtime = host.runtime
			//general
			def vendor = host.summary?.hardware?.vendor
			def nicCount = host.summary?.hardware?.nicCount
			//cpu
			def maxCpu = host.summary?.hardware?.cpuMhz
			def cpuModel = host.summary?.hardware?.cpuModel
			def cpuCount = host.summary?.hardware?.cpuCount
			def threadCount = host.summary?.hardware?.threadCount
			def cpuCores = host.hardware?.cpuInfo?.numCpuCores ?: 1
			def maxUsedCpu = host.summary.quickStats?.getOverallCpuUsage()
			def cpuPercent = 0
			//getSummary()?.getHardware()?.getCpuMhz()
			//memory
			def maxMemory = host.hardware.memorySize ?: 0
			def maxUsedMemory = (host.summary?.quickStats?.getOverallMemoryUsage() ?: 0) * ComputeUtility.ONE_MEGABYTE
			//power state
			def power = runtime.powerState
			def powerState = 'unknown'
			if(power == HostSystemPowerState.poweredOn)
				powerState = 'on'
			else if(power == HostSystemPowerState.poweredOff)
				powerState = 'off'
			else if(power == HostSystemPowerState.standBy)
				powerState = 'paused'
			//save it all
			def updates = false
			def capacityInfo = server.capacityInfo ?: new ComputeCapacityInfo(maxMemory:maxMemory, maxStorage:maxStorage)
			if(maxMemory > server.maxMemory) {
				server.maxMemory = maxMemory
				capacityInfo?.maxMemory = maxMemory
				updates = true
			}
			if(maxUsedMemory != capacityInfo.usedMemory) {
				capacityInfo.usedMemory = maxUsedMemory
				server.usedMemory = maxUsedMemory
				updates = true
			}
			if(maxStorage > server.maxStorage) {
				server.maxStorage = maxStorage
				capacityInfo?.maxStorage = maxStorage
				updates = true
			}
			if(cpuCores != null && (server.maxCores == null || cpuCores > server.maxCores)) {
				server.maxCores = cpuCores
				capacityInfo.maxCores = cpuCores
				updates = true
			}
			//settings some host detail info - will save if other updates happen
			if(cpuModel)
				server.setConfigProperty('cpuModel', cpuModel)
			if(threadCount)
				server.setConfigProperty('threadCount', threadCount)
			if(nicCount)
				server.setConfigProperty('nicCount', nicCount)
			if(vendor)
				server.setConfigProperty('hardwareVendor', vendor)
			if(maxCpu)
				server.setConfigProperty('cpuMhz', maxCpu)
			if(cpuCount)
				server.setConfigProperty('cpuCount', cpuCount)
			//storage updates
			if(maxUsedStorage != capacityInfo.usedStorage) {
				capacityInfo.usedStorage = maxUsedStorage
				server.usedStorage = maxUsedStorage
				updates = true
			}
			if(server.powerState != powerState) {
				server.powerState = powerState
				updates = true
			}
			if(maxCpu && maxUsedCpu) {
				if(maxCpu > 0 && maxUsedCpu > 0) {
					cpuPercent = maxUsedCpu.div((maxCpu * cpuCores)) * 100
					if(cpuPercent > 100.0)
						cpuPercent = 100.0
					server.usedCpu = cpuPercent
					updates = true
				}
			}
			if(hostMap.hostname && hostMap.hostname != server.hostname) {
				server.hostname = hostMap.hostname
				updates = true
			}
			if(updates == true) {
				server.capacityInfo = capacityInfo
				morpheusContext.computeServer.save([server]).blockingGet()
			}
		} catch(e) {
			log.warn("error updating host stats: ${e}", e)
		}
	}
}