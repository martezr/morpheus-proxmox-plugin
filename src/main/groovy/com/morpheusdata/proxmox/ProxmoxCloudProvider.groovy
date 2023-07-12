package com.morpheusdata.proxmox

import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.*
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import com.morpheusdata.proxmox.sync.*

import java.security.MessageDigest

@Slf4j
class ProxmoxCloudProvider implements CloudProvider {
	ProxmoxPlugin plugin
	MorpheusContext morpheusContext

	ProxmoxCloudProvider(ProxmoxPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"proxmox-plugin.svg", darkPath: "proxmox-plugin.svg")
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"proxmox-circular.svg", darkPath: "proxmox-circular.svg")
	}

	@Override
	String getCode() {
		return 'proxmox-plugin'
	}

	@Override
	String getName() {
		return 'Proxmox'
	}

	@Override
	String getDescription() {
		return 'Proxmox Cloud'
	}

	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasBareMetal() {
		return false
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean hasFolders() {
		return true
	}

	@Override
	Boolean hasCloudInit() {
		return false
	}

	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		OptionType apiUrl = new OptionType(
				name: 'Api Url',
				code: 'proxmox-api-url',
				fieldName: 'serviceUrl',
				displayOrder: 0,
				fieldLabel: 'API URL',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
		)
		OptionType credentials = new OptionType(
				code: 'proxmox-credential',
				inputType: OptionType.InputType.CREDENTIAL,
				name: 'Credentials',
				fieldName: 'type',
				fieldLabel: 'Credentials',
				fieldContext: 'credential',
				required: true,
				defaultValue: 'local',
				displayOrder: 10,
				optionSource: 'credentials',
				config: '{"credentialTypes":["username-api-key"]}'
		)
		OptionType username = new OptionType(
				name: 'Username',
				code: 'proxmox-username',
				fieldName: 'serviceUsername',
				displayOrder: 20,
				fieldLabel: 'Username',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				localCredential: true
		)
		OptionType token = new OptionType(
				name: 'API Token',
				code: 'proxmox-token',
				fieldName: 'serviceToken',
				displayOrder: 30,
				fieldLabel: 'API Token',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'config',
				localCredential: true
		)
		OptionType inventoryInstances = new OptionType(
				name: 'Inventory Existing Instances',
				code: 'proxmox-import-existing',
				fieldName: 'importExisting',
				displayOrder: 40,
				fieldLabel: 'Inventory Existing Instances',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)
		return [apiUrl,credentials,username,token,inventoryInstances]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		ComputeServerType hypervisorType = new ComputeServerType()
		hypervisorType.name = 'Proxmox Hypervisor'
		hypervisorType.code = 'proxmox-hypervisor'
		hypervisorType.description = 'proxmox hypervisor'
		hypervisorType.vmHypervisor = true
		hypervisorType.controlPower = false
		hypervisorType.reconfigureSupported = false
		hypervisorType.externalDelete = false
		hypervisorType.hasAutomation = false
		hypervisorType.agentType = ComputeServerType.AgentType.none
		hypervisorType.platform = PlatformType.linux
		hypervisorType.managed = false
		hypervisorType.provisionTypeCode = 'proxmox-provision-provider'
		hypervisorType.nodeType = 'proxmox-node'

		ComputeServerType serverType = new ComputeServerType()
		serverType.name = 'Proxmox Server'
		serverType.code = 'proxmox-server'
		serverType.description = 'Proxmox Server'
		serverType.reconfigureSupported = false
		serverType.hasAutomation = false
		serverType.supportsConsoleKeymap = true
		serverType.platform = PlatformType.none
		serverType.managed = false
		serverType.provisionTypeCode = 'proxmox-provision-provider'
		serverType.nodeType = 'morpheus-vm-node'

		ComputeServerType proxmoxLinux = new ComputeServerType()
		proxmoxLinux.name = 'Proxmox Linux VM'
		proxmoxLinux.code = 'proxmox-linux-vm'
		proxmoxLinux.description = 'Proxmox Linux VM'
		proxmoxLinux.controlEjectCd = true
		proxmoxLinux.guestVm = true
		proxmoxLinux.controlSuspend = true
		proxmoxLinux.reconfigureSupported = true
		proxmoxLinux.hasAutomation = true
		proxmoxLinux.supportsConsoleKeymap = true
		proxmoxLinux.platform = PlatformType.linux
		proxmoxLinux.managed = true
		proxmoxLinux.provisionTypeCode = 'proxmox-provision-provider'
		proxmoxLinux.nodeType = 'morpheus-vm-node'

		ComputeServerType proxmoxWindows = new ComputeServerType()
		proxmoxWindows.name = 'Proxmox Windows VM'
		proxmoxWindows.code = 'proxmox-windows-vm'
		proxmoxWindows.description = 'Proxmox Windows VM'
		proxmoxWindows.reconfigureSupported = true
		proxmoxWindows.hasAutomation = true
		proxmoxWindows.supportsConsoleKeymap = true
		proxmoxWindows.controlEjectCd = true
		proxmoxWindows.guestVm = true
		proxmoxWindows.controlSuspend = true
		proxmoxWindows.platform = PlatformType.windows
		proxmoxWindows.managed = true
		proxmoxWindows.provisionTypeCode = 'proxmox-provision-provider'
		proxmoxWindows.nodeType = 'morpheus-vm-node'

		ComputeServerType unmanagedType = new ComputeServerType()
		unmanagedType.name = 'Proxmox Instance'
		unmanagedType.code = 'proxmox-unmanaged'
		unmanagedType.description = 'Proxmox Instance'
		unmanagedType.reconfigureSupported = true
		unmanagedType.hasAutomation = true
		unmanagedType.supportsConsoleKeymap = true
		unmanagedType.platform = PlatformType.linux
		unmanagedType.managed = false
		unmanagedType.provisionTypeCode = 'proxmox-provision-provider'
		unmanagedType.nodeType = 'unmanaged'
		unmanagedType.managedServerType = 'proxmox-vm'

		return [hypervisorType, serverType, proxmoxLinux, proxmoxWindows, unmanagedType]

	}

	@Override
	Collection<ProvisioningProvider> getAvailableProvisioningProviders() {
		return plugin.getProvidersByType(ProvisioningProvider) as Collection<ProvisioningProvider>
	}

	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return plugin.getProvidersByType(BackupProvider) as Collection<BackupProvider>
	}

	@Override
	ProvisioningProvider getProvisioningProvider(String providerCode) {
		return getAvailableProvisioningProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		NetworkType bridgeNetwork = new NetworkType([
				code              : 'proxmox-bridge-network',
				externalType      : 'bridge',
				cidrEditable      : true,
				dhcpServerEditable: true,
				dnsEditable       : true,
				gatewayEditable   : true,
				vlanIdEditable    : true,
				canAssignPool     : true,
				name              : 'Proxmox Bridge Network'
		])
		NetworkType bondNetwork = new NetworkType([
				code              : 'proxmox-bond-network',
				externalType      : 'bond',
				cidrEditable      : true,
				dhcpServerEditable: true,
				dnsEditable       : true,
				gatewayEditable   : true,
				vlanIdEditable    : true,
				canAssignPool     : true,
				name              : 'Proxmox Bond Network'
		])
		NetworkType vlanNetwork = new NetworkType([
				code              : 'proxmox-vlan-network',
				externalType      : 'vlan',
				cidrEditable      : true,
				dhcpServerEditable: true,
				dnsEditable       : true,
				gatewayEditable   : true,
				vlanIdEditable    : true,
				canAssignPool     : true,
				name              : 'Proxmox VLAN Network'
		])
		[bridgeNetwork, bondNetwork, vlanNetwork]
	}
	
	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		return null
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		return null
	}

	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		return null
	}

	@Override
	ServiceResponse validate(Cloud zoneInfo, ValidateCloudRequest validateCloudRequest) {
		return new ServiceResponse(success: true)
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloud) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		log.info "Initializing Cloud: ${cloud.code}"
		log.info "config: ${cloud.configMap}"

		HttpApiClient client

		try {
			NetworkProxy proxySettings = cloud.apiProxy
			client = new HttpApiClient()
			client.networkProxy = proxySettings
			//def apiHost = "10.0.0.192"
			//def apiPort = 8006
			//def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, proxySettings)
			//log.debug("proxmox online: {} - {}", apiHost, hostOnline)
			def hostOnline = true
			if(hostOnline) {
				// Evaluate whether to inventory existing virtual machines
				def doInventory = cloud.getConfigProperty('importExisting')
				Boolean createNew = false
				if(doInventory == 'on' || doInventory == 'true' || doInventory == true) {
					createNew = true
				}
				// Add a region code to the cloud
				ensureRegionCode(cloud)

				// Perform an initial resource sync
				// Sync hypervisor hosts
				(new HostsSync(this.plugin, cloud, client)).execute()
				// Sync hypervisor networks
				(new NetworksSync(this.plugin, cloud, client, getNetworkTypes())).execute()
				// Sync hypervisor datastores
				(new DatastoresSync(this.plugin, cloud, client)).execute()
				// Sync hypervisor virtual machines
				//(new VirtualMachinesSync(this.plugin, cloud, client, createNew)).execute()
				rtn = ServiceResponse.success()
			} else {
				rtn = ServiceResponse.error('Proxmox is not reachable', null, [status: Cloud.Status.offline])
			}
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}

		return rtn
	}

	@Override
	ServiceResponse refresh(Cloud cloud) {
		return ServiceResponse.success()
	}

	@Override
	void refreshDaily(Cloud cloudInfo) {
		log.debug "daily refresh run for ${cloudInfo.code}"
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return new ServiceResponse(success: true)
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		return new ServiceResponse(success: true)
	}

	KeyPair findOrUploadKeypair(String apiKey, String publicKey, String keyName) {
	}

	private ensureRegionCode(Cloud cloud) {
		def authConfig = plugin.getAuthConfig(cloud)
		def apiUrl = authConfig.apiUrl
		def regionString = "${apiUrl}"
		MessageDigest md = MessageDigest.getInstance("MD5")
		md.update(regionString.bytes)
		byte[] checksum = md.digest()
		def regionCode = checksum.encodeHex().toString()
		if (cloud.regionCode != regionCode) {
			cloud.regionCode = regionCode
			morpheusContext.cloud.save(cloud).blockingGet()
		}
	}
}