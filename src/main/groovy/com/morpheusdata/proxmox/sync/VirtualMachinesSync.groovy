package com.morpheusdata.proxmox.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.WorkloadIdentityProjection
import com.morpheusdata.proxmox.ProxmoxPlugin
import com.morpheusdata.proxmox.utils.ProxmoxComputeUtility
import com.morpheusdata.core.util.SyncUtils
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachinesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private ProxmoxPlugin plugin
	private HttpApiClient apiClient
	private Boolean createNew
	private Map authConfig
	private Collection<ComputeServerInterfaceType> netTypes

	public VirtualMachinesSync(ProxmoxPlugin proxmoxPlugin, Cloud cloud, HttpApiClient apiClient, Boolean createNew) {
		this.plugin = proxmoxPlugin
		this.cloud = cloud
		this.morpheusContext = proxmoxPlugin.morpheusContext
		this.apiClient = apiClient
		this.createNew = createNew
		this.netTypes = proxmoxPlugin.getCloudProvider().getProvisioningProvider('proxmox-provision-provider').getComputeServerInterfaceTypes()
	}

	def execute() {
		log.debug "BEGIN: execute VirtualMachinesSync: ${cloud.id} ${createNew}"
		def startTime = new Date().time
		try {
			this.authConfig = plugin.getAuthConfig(cloud)

			def listResults = ProxmoxComputeUtility.getHostVirtualMachines(apiClient, authConfig)
			if(listResults.success) {
				def domainRecords = morpheusContext.computeServer.listSyncProjections(cloud.id).filter { ComputeServerIdentityProjection projection ->
					projection.computeServerTypeCode != 'proxmox-hypervisor'
				}
				def blackListedNames = domainRecords.filter {it.status == 'provisioning'}.map {it.name}.toList().blockingGet()

				// To be used throughout the sync
				def defaultServerType = new ComputeServerType(code: 'proxmox-unmanaged')
				//Map hosts = getAllHosts()
				//Map resourcePools = getAllResourcePools()
				//Map networks = getAllNetworks()
				//Map osTypes = getAllOsTypes()
				//List plans = getAllServicePlans()

				def usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]

				SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.data)
				syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem.vmid
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
						SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
						return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					if (createNew) {
						addMissingVirtualMachines(cloud, itemsToAdd, defaultServerType, blackListedNames, usageLists)
					}
				}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
					//updateMatchedVirtualMachines(cloud, plans, hosts, resourcePools, networks, osTypes, updateItems, usageLists)
				}.onDelete { removeItems ->
					removeMissingVirtualMachines(cloud, removeItems, blackListedNames)
				}.observe().blockingSubscribe { completed ->
					log.debug "sending usage start/stop/restarts: ${usageLists}"
					morpheusContext.usage.startServerUsage(usageLists.startUsageIds).blockingGet()
					morpheusContext.usage.stopServerUsage(usageLists.stopUsageIds).blockingGet()
					morpheusContext.usage.restartServerUsage(usageLists.restartUsageIds).blockingGet()
					morpheusContext.usage.restartSnapshotUsage(usageLists.updatedSnapshotIds).blockingGet()
				}
			} else {
				log.warn("Error in getting VMs: ${listResults}")
			}
		} catch(e) {
			log.error("VirtualMachinesSync error: ${e}", e)
		}
		def endTime = new Date().time
		log.debug "END: execute VirtualMachinesSync: ${cloud.id} ${createNew} in ${endTime - startTime} ms"
	}

	def addMissingVirtualMachines(Cloud cloud, List addList, ComputeServerType defaultServerType, List blackListedNames, Map usageLists) {
		//log.debug "addMissingVirtualMachines ${cloud} ${plans?.size()} ${addList?.size()} ${defaultServerType} ${blackListedNames}"

		if (!createNew)
			return

		//def metricsResult = ProxmoxComputeUtility.listVMMetrics(apiClient, authConfig, addList?.collect{ it.metadata.uuid } )
		ServicePlan fallbackPlan = new ServicePlan(code: 'proxmox-internal-custom')

		for(cloudItem in addList) {
			try {
				//ComputeZonePool resourcePool = resourcePools[cloudItem.status?.cluster_reference?.uuid]
				//def doCreate = resourcePool.inventory != false && !blackListedNames?.contains(cloudItem.status.name)
				if(doCreate) {
					def vmConfig = buildVmConfig(cloudItem, resourcePools, hosts)
					vmConfig.plan = SyncUtils.findServicePlanBySizing(plans, vmConfig.maxMemory, vmConfig.maxCores, null, fallbackPlan, null, cloud.account)
					ComputeServer add = new ComputeServer(vmConfig)
					add.computeServerType = defaultServerType
					ComputeServer savedServer = morpheusContext.computeServer.create(add).blockingGet()
					if (!savedServer) {
						log.error "Error in creating server ${add}"
					} else {
						performPostSaveSync(savedServer, cloudItem, networks, metricsResult)
					}

					if (vmConfig.powerState == ComputeServer.PowerState.on) {
						usageLists.startUsageIds << savedServer.id
					} else {
						usageLists.stopUsageIds << savedServer.id
					}
				}

			} catch(e) {
				log.error "Error in adding VM ${e}", e
			}
		}
	}

	protected updateMatchedVirtualMachines(Cloud cloud, List plans, Map hosts, Map resourcePools, Map networks, Map osTypes, List updateList, Map usageLists) {
		log.debug "updateMatchedVirtualMachines: ${cloud} ${updateList?.size()}"

		ServicePlan fallbackPlan = new ServicePlan(code: 'nutanix-prism-internal-custom')
		List<ComputeServer> servers = updateList.collect { it.existingItem }

		// Gather up all the Workloads that may pertain to the servers we are sync'ing
		def managedServerIds = servers?.findAll{it.computeServerType?.managed }?.collect{it.id}
		Map<Long, WorkloadIdentityProjection> tmpWorkloads = morpheusContext.cloud.listCloudWorkloadProjections(cloud.id).filter {it.serverId in (managedServerIds ?: []) }.toMap {it.serverId}.blockingGet()
		def statsData = []
		def metricsResult = NutanixPrismComputeUtility.listVMMetrics(apiClient, authConfig, updateList?.collect{ it.masterItem.metadata.uuid } )
		for(update in updateList) {
			try {
				ComputeServer currentServer = update.existingItem
				def cloudItem = update.masterItem
				if (currentServer.status != 'provisioning') {
					try {
						def vmConfig = buildVmConfig(cloudItem, resourcePools, hosts)

						def save = false
						def planInfoChanged = false
						if(currentServer.name != vmConfig.name) {
							currentServer.name = vmConfig.name
							save = true
						}

						if(currentServer.externalIp != vmConfig.externalIp) {
							currentServer.externalIp = vmConfig.externalIp
							currentServer.internalIp = vmConfig.externalIp
							currentServer.sshHost = vmConfig.externalIp
							save = true
						}

						if(currentServer.resourcePool?.id != vmConfig.resourcePool?.id) {
							currentServer.resourcePool = vmConfig.resourcePool
							save = true
						}

						if(currentServer.maxMemory != vmConfig.maxMemory) {
							currentServer.maxMemory = vmConfig.maxMemory
							planInfoChanged = true
							save = true
						}

						if(currentServer.maxCores != vmConfig.maxCores) {
							currentServer.maxCores = vmConfig.maxCores
							planInfoChanged = true
							save = true
						}

						if(currentServer.coresPerSocket != vmConfig.coresPerSocket) {
							currentServer.coresPerSocket = vmConfig.coresPerSocket
							planInfoChanged = true
							save = true
						}

						if(currentServer.parentServer?.id != vmConfig.parentServer?.id) {
							currentServer.parentServer = vmConfig.parentServer
							save = true
						}

						ServicePlan plan = SyncUtils.findServicePlanBySizing(plans, currentServer.maxMemory, currentServer.maxCores, null, fallbackPlan, currentServer.plan, currentServer.account)
						if(currentServer.plan?.code != plan?.code) {
							currentServer.plan = plan
							planInfoChanged = true
							save = true
						}

						if(save) {
							currentServer = saveAndGet(currentServer)
						}

						def changes = performPostSaveSync(currentServer, cloudItem, networks, metricsResult)
						if(changes || save) {
							currentServer = morpheusContext.computeServer.get(currentServer.id).blockingGet()
							planInfoChanged = true
						}

						if(planInfoChanged && currentServer.computeServerType?.guestVm) {
							updateServerContainersAndInstances(currentServer, null)
						}

						if(currentServer.powerState != vmConfig.powerState) {
							currentServer.powerState = vmConfig.powerState
							if (currentServer.computeServerType?.guestVm) {
								morpheusContext.computeServer.updatePowerState(currentServer.id, currentServer.powerState).blockingGet()
							}
						}

						//check for restart usage records
						if (planInfoChanged ) {
							if (!usageLists.stopUsageIds.contains(currentServer.id) && !usageLists.startUsageIds.contains(currentServer.id))
								usageLists.restartUsageIds << currentServer.id
						}

						if ((currentServer.agentInstalled == false || currentServer.powerState == ComputeServer.PowerState.off || currentServer.powerState == ComputeServer.PowerState.paused) && currentServer.status != 'provisioning') {
							// Simulate stats update
							statsData += updateVirtualMachineStats(currentServer, tmpWorkloads)
							save = true
						}

						if (save) {
							morpheusContext.computeServer.save([currentServer]).blockingGet()
						}

					} catch (ex) {
						log.warn("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${ex}", ex)
					}
				}
			} catch(e) {
				log.error "Error in updating server: $e", e
			}
		}
		if(statsData) {
			for(statData in statsData) {
				morpheusContext.stats.updateWorkloadStats(new WorkloadIdentityProjection(id: statData.workload.id), statData.maxMemory, statData.maxUsedMemory, statData.maxStorage, statData.maxUsedStorage, statData.cpuPercent, statData.running)
			}
		}
	}

	protected removeMissingVirtualMachines(Cloud cloud, List removeList, List blackListedNames) {
		log.debug "removeMissingVirtualMachines: ${cloud} ${removeList.size()}"
		for(ComputeServerIdentityProjection removeItem in removeList) {
			try {
				def doDelete = true
				if(blackListedNames?.contains(removeItem.name))
					doDelete = false
				if(doDelete) {
					log.info("remove vm: ${removeItem}")
					morpheusContext.computeServer.remove([removeItem]).blockingGet()
				}
			} catch(e) {
				log.error "Error removing virtual machine: ${e}", e
				log.warn("Unable to remove Server from inventory, Perhaps it is associated with an instance currently... ${removeItem.name} - ID: ${removeItem.id}")
			}
		}
	}


	private Map getAllHosts() {
		log.debug "getAllHosts: ${cloud}"
		def hostIdentitiesMap = morpheusContext.computeServer.listSyncProjections(cloud.id).filter {
			it.computeServerTypeCode == 'proxmox-hypervisor'
		}.toMap {it.externalId }.blockingGet()
		hostIdentitiesMap
	}

	private Map getAllResourcePools() {
		log.debug "getAllResourcePools: ${cloud}"
		def resourcePoolProjectionIds = morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').map{it.id}.toList().blockingGet()
		def resourcePoolsMap = morpheusContext.cloud.pool.listById(resourcePoolProjectionIds).toMap { it.externalId }.blockingGet()
		resourcePoolsMap
	}

	private Map getAllNetworks() {
		log.debug "getAllNetworks: ${cloud}"
		def networkProjectionsMap = morpheusContext.cloud.network.listSyncProjections(cloud.id).toMap {it.externalId }.blockingGet()
		networkProjectionsMap
	}

	private Map getAllOsTypes() {
		log.debug "getAllOsTypes: ${cloud}"
		Map osTypes = morpheusContext.osType.listAll().toMap {it.code}.blockingGet()
		osTypes
	}

	private List getAllServicePlans() {
		log.debug "getAllServicePlans: ${cloud}"
		def provisionType = new ProvisionType(code: 'nutanix-prism-provision-provider')
		def servicePlanProjections = morpheusContext.servicePlan.listSyncProjections(provisionType).toList().blockingGet()
		def plans = morpheusContext.servicePlan.listById(servicePlanProjections.collect { it.id }).filter {it.active && it.deleted != true}.toList().blockingGet()
		plans
	}

	private def updateVirtualMachineStats(ComputeServer server, Map<Long, WorkloadIdentityProjection> workloads = [:]) {
		def statsData = []
		try {
			def maxUsedStorage = 0
			if (server.agentInstalled && server.usedStorage) {
				maxUsedStorage = server.usedStorage
			}
			
			def workload = workloads[server.id]
			if (workload) {
				statsData << [
						workload      : workload,
//						maxUsedMemory : maxUsedMemory,
						maxMemory     : server.maxMemory,
						maxStorage    : server.maxStorage,
						maxUsedStorage: maxUsedStorage,
						cpuPercent    : server.usedCpu,
						running       : server.powerState == ComputeServer.PowerState.on
				]
			}
		} catch (e) {
			log.warn("error updating vm stats: ${e}", e)
			return []
		}
		return statsData
	}

	private updateServerContainersAndInstances(ComputeServer currentServer, ServicePlan plan) {
		log.debug "updateServerContainersAndInstances: ${currentServer}"
		try {
			// Save the workloads
			def instanceIds = []
			def workloads = getWorkloadsForServer(currentServer)
			for(Workload workload in workloads) {
				workload.plan = plan
				workload.maxCores = currentServer.maxCores
				workload.maxMemory = currentServer.maxMemory
				workload.coresPerSocket = currentServer.coresPerSocket
				workload.maxStorage = currentServer.maxStorage
				def instanceId = workload.instance?.id
				morpheusContext.cloud.saveWorkload(workload).blockingGet()

				if(instanceId) {
					instanceIds << instanceId
				}
			}

			if(instanceIds) {
				def instancesToSave = []
				def instances = morpheusContext.instance.listById(instanceIds).toList().blockingGet()
				instances.each { Instance instance ->
					if(plan) {
						if (instance.containers.every { cnt -> (cnt.plan.id == currentServer.plan.id && cnt.maxMemory == currentServer.maxMemory && cnt.maxCores == currentServer.maxCores && cnt.coresPerSocket == currentServer.coresPerSocket) || cnt.server.id == currentServer.id }) {
							log.debug("Changing Instance Plan To : ${plan.name} - memory: ${currentServer.maxMemory} for ${instance.name} - ${instance.id}")
							instance.plan = plan
							instance.maxCores = currentServer.maxCores
							instance.maxMemory = currentServer.maxMemory
							instance.maxStorage = currentServer.maxStorage
							instance.coresPerSocket = currentServer.coresPerSocket
							instancesToSave << instance
						}
					}
				}
				if(instancesToSave.size() > 0) {
					morpheusContext.instance.save(instancesToSave).blockingGet()
				}
			}
		} catch(e) {
			log.error "Error in updateServerContainersAndInstances: ${e}", e
		}
	}

	private getWorkloadsForServer(ComputeServer currentServer) {
		def workloads = []
		def projections = morpheusContext.cloud.listCloudWorkloadProjections(cloud.id).filter { it.serverId == currentServer.id }.toList().blockingGet()
		for(proj in projections) {
			workloads << morpheusContext.cloud.getWorkloadById(proj.id).blockingGet()
		}
		workloads
	}

	private buildVmConfig(Map cloudItem, Map resourcePools, Map hosts) {
		ComputeZonePool resourcePool = resourcePools[cloudItem.status?.cluster_reference?.uuid]
		def ipAddress = cloudItem.status.resources.nic_list?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
		def vmConfig = [
				account          : cloud.account,
				externalId       : cloudItem.vmid,
				name             : cloudItem.name,
				externalIp       : ipAddress,
				internalIp       : ipAddress,
				sshHost          : ipAddress,
				sshUsername      : 'root',
				provision        : false,
				cloud            : cloud,
				lvmEnabled       : false,
				managed          : false,
				serverType       : 'vm',
				status           : 'provisioned',
				resourcePool     : resourcePool,
				uniqueId         : cloudItem.vmid,
				internalId       : cloudItem.vmid,
				powerState       : cloudItem.status == 'ON' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
				maxMemory        : cloudItem.maxmem * ComputeUtility.ONE_MEGABYTE,
				maxCores         : (cloudItem.status.resources.num_vcpus_per_socket?.toLong() ?: 0) * (cloudItem.status.resources.num_sockets?.toLong() ?: 0),
				coresPerSocket   : cloudItem.status.resources.num_vcpus_per_socket?.toLong(),
				parentServer     : hosts[cloudItem.status.cluster_reference.uuid],
				osType           :'unknown',
				serverOs         : new OsType(code: 'unknown')
		]
		vmConfig
	}

	private Boolean performPostSaveSync(ComputeServer server, Map cloudItem, Map networks, metricsResult) {
		log.debug "performPostSaveSync: ${server?.id}"
		def changes = false
		// Disks and metrics
		if(server.status != 'resizing') {
			def syncResults = NutanixPrismSyncUtils.syncVolumes(server, cloudItem.status.resources.disk_list?.findAll { it.device_properties.device_type == 'DISK' }, cloud, morpheusContext)
			if(!server.computeCapacityInfo) {
				server.capacityInfo = new ComputeCapacityInfo(maxCores: server.maxCores, maxMemory: server.maxMemory, maxStorage: syncResults.maxStorage)
				changes = true
			} else if(syncResults.changed) {
				server.maxStorage = syncResults.maxStorage
				server.capacityInfo.maxCores = server.maxCores
				server.capacityInfo.maxMemory = server.maxMemory
				server.capacityInfo.maxStorage = server.maxStorage
				changes = true
			}

		}
		// TODO : how to get used storage?
		def metricChanges = NutanixPrismSyncUtils.updateMetrics(server, 'memory_usage_ppm', 'hypervisor_cpu_usage_ppm', metricsResult)
		if(metricChanges || changes) {
			saveAndGet(server)
		}

		// Networks
		if(server.status != 'resizing') {
			def interfaceChanges = NutanixPrismSyncUtils.syncInterfaces(server, cloudItem.status.resources.nic_list, networks, netTypes, morpheusContext)
			if (interfaceChanges) {
				changes = true
			}
		}
		return changes
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheusContext.computeServer.save([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return morpheusContext.computeServer.get(server.id).blockingGet()
	}
}