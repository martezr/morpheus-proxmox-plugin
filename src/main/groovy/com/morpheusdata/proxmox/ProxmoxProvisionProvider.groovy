package com.morpheusdata.proxmox

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.ContainerType
import com.morpheusdata.model.HostType
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.UserConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.model.provisioning.UsersConfiguration
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.HostResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity

@Slf4j
class ProxmoxProvisionProvider extends AbstractProvisionProvider {
	ProxmoxPlugin plugin
	MorpheusContext context
	//ProxmoxApiService apiService


	ProxmoxProvisionProvider(ProxmoxPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.context = context
		//apiService = new ProxmoxApiService()
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	Collection<VirtualImage> getVirtualImages() {
		VirtualImage virtualImage = new VirtualImage(
				code: UBUNTU_VIRTUAL_IMAGE_CODE,
				category:'proxmoxplugin.image.os.proxmox-plugin',
				name:'Ubuntu 18.04 LTS (Proxmox Marketplace)',
				imageType: ImageType.qcow2,
				systemImage:true,
				isCloudInit:true,
				externalId:'ubuntu-18-04-x64',
				osType: new OsType(code: 'ubuntu.18.04.64')
		)
		[virtualImage]
	}

	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {}

	@Override
	public Collection<OptionType> getNodeOptionTypes() {
		return new ArrayList<OptionType>()
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		//image OptionType
		OptionType managedDatabaseLabelOption = new OptionType()
		managedDatabaseLabelOption.name = 'proxmoxDatabaseLabel'
		managedDatabaseLabelOption.code = 'proxmox-databaseLabel'
		managedDatabaseLabelOption.fieldName = 'proxmoxDatabaseLabel'
		managedDatabaseLabelOption.fieldContext = 'config'
		managedDatabaseLabelOption.fieldLabel = 'Label'
		managedDatabaseLabelOption.inputType = OptionType.InputType.TEXT
		managedDatabaseLabelOption.displayOrder = 110
		managedDatabaseLabelOption.required = false

		[managedDatabaseLabelOption]
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		def servicePlans = []
		servicePlans << new ServicePlan([code:'proxmox-vm-512', name:'1 vCPU, 512MB Memory', description:'1 vCPU, 512MB Memory', sortOrder:0,
				maxStorage:10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-vm-1024', name:'1 vCPU, 1GB Memory', description:'1 vCPU, 1GB Memory', sortOrder:1,
				maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-vm-2048', name:'1 vCPU, 2GB Memory', description:'1 vCPU, 2GB Memory', sortOrder:2,
				maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-vm-4096', name:'1 vCPU, 4GB Memory', description:'1 vCPU, 4GB Memory', sortOrder:3,
				maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:4,
				maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-vm-16384', name:'2 vCPU, 16GB Memory', description:'2 vCPU, 16GB Memory', sortOrder:5,
				maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCores:2,
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-vm-24576', name:'4 vCPU, 24GB Memory', description:'4 vCPU, 24GB Memory', sortOrder:6,
				maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCores:4, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-vm-32768', name:'4 vCPU, 32GB Memory', description:'4 vCPU, 32GB Memory', sortOrder:7,
				maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCores:4, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'proxmox-internal-custom', editable:false, name:'Proxmox Custom', description:'Proxmox Custom', sortOrder:0,
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
				maxStorage:0l, maxMemory: 0l,  maxCpu:0])
		servicePlans
	}

	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	private getStorageVolumeTypes() {
		def volumeTypes = []

		volumeTypes << new StorageVolumeType([
			code: 'proxmox-disk-scsi',
			externalId: 'scsi',
			name: 'scsi',
			displayOrder: 0
		])

		volumeTypes << new StorageVolumeType([
			code: 'proxmox-disk-sata',
			externalId: 'sata',
			name: 'sata',
			displayOrder: 1
		])

		volumeTypes << new StorageVolumeType([
			code: 'proxmox-disk-virtio',
			externalId: 'virtio',
			name: 'virtio',
			displayOrder: 2
		])

		volumeTypes << new StorageVolumeType([
			code: 'proxmox-disk-ide',
			externalId: 'ide',
			name: 'ide',
			displayOrder: 3
		])

		volumeTypes
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		return []
	}

	@Override
	String getCode() {
		return 'proxmox'
	}

	@Override
	String getName() {
		return 'Proxmox'
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasPlanTagMatch() {
		false
	}

	@Override
	Integer getMaxNetworks() {
		return 2
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug "validateWorkload: ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateInstance(Instance instance, Map opts) {
		log.debug "validateInstance: ${instance} ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateDockerHost(ComputeServer server, Map opts) {
		log.debug "validateDockerHost: ${server} ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse.success()
	}

	@Override
	ServiceResponse<WorkloadResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.info "Proxmox Provision Provider: runWorkload ${workload.configs} ${opts}"
		def containerConfig = new groovy.json.JsonSlurper().parseText(workload.configs ?: '{}')
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def rtn = [success: false, msg: null]
		try {
			VirtualImage virtualImage
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.containerType) {
					ContainerType containerType = morpheus.containerType.get(computeTypeSet.containerType.id).blockingGet()
					virtualImage = containerType.virtualImage
				}
			}
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error "${rtn.msg}, ${e}", e

		}
		new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse<HostResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "runHost: ${server} ${hostRequest} ${opts}"
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse<HostResponse> waitForHost(ComputeServer server) {
		log.debug "waitForHost: ${server}"
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		log.debug "finalizeHost: ${server} "
		return ServiceResponse.success();
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeServer: ${server} ${resizeRequest} ${opts}"
		internalResizeServer(server, resizeRequest)
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeWorkload: ${instance} ${workload} ${resizeRequest} ${opts}"
        return ServiceResponse.success()
	}

	private ServiceResponse internalResizeServer(ComputeServer server, ResizeRequest resizeRequest) {
		log.debug "internalResizeServer: ${server} ${resizeRequest}"
		ServiceResponse rtn = ServiceResponse.success()
		rtn
	}

	@Override
	ServiceResponse<WorkloadResponse> stopWorkload(Workload workload) {
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse<WorkloadResponse> startWorkload(Workload workload) {
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.debug 'restartWorkload'
        return ServiceResponse.success()
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
        return ServiceResponse.success()
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.context
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	ServiceResponse<WorkloadResponse> getServerDetails(ComputeServer server) {
		log.debug "getServerDetails"
		ServiceResponse resp = new ServiceResponse(success: false)
		Boolean pending = true
		Integer attempts = 0
		while (pending) {
			log.debug "attempt $attempts"
			sleep(1000l * 20l)
			resp = serverStatus(server)
			if (resp.success || resp.msg == 'failed') {
				pending = false
			}
			attempts++
			if (attempts > 15) {
				pending = false
			}
		}
		resp
	}

	ServiceResponse<WorkloadResponse> serverStatus(ComputeServer server) {
		log.debug "check server status for server ${server.externalId}"
        return ServiceResponse.success()
	}

	ServiceResponse<WorkloadResponse> powerOffServer(String apiKey, String dropletId) {
		log.debug "power off server"
        return ServiceResponse.success()
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		morpheus.computeServer.save([server]).blockingGet()
		return morpheus.computeServer.get(server.id).blockingGet()
	}

	protected cleanInstanceName(name) {
		def rtn = name.replaceAll(/[^a-zA-Z0-9\.\-]/,'')
		return rtn
	}
}